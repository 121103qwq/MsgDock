package com.xgy.lansms;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encrypted, device-derived cloud backup and device/link management. */
public final class DeviceBackupManager {
    private static final int SCHEMA = 1;
    private static final String PREF_REVISION = "device_backup_revision";
    private static final String PREF_STATUS = "device_backup_status";
    private static final String PREF_ERROR = "device_backup_error";
    private static final String PREF_DELETED = "device_backup_deleted";
    private static final String PREF_LAST_SYNC = "device_backup_last_sync";
    private static final String PREF_MACHINE_CODE = "device_machine_code";
    private static final String PREF_BACKUP_ID = "device_backup_id";
    private static final Object TASK_LOCK = new Object();
    private static final AtomicBoolean STARTUP_STARTED = new AtomicBoolean(false);
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceBackupManager() {}

    public interface Callback { void completed(boolean success, String message); }

    /** Called from MainActivity; all derivation and network work remains off the UI thread. */
    public static void onAppStarted(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        if (!STARTUP_STARTED.compareAndSet(false, true)) return;
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    DeviceIdentity identity = DeviceIdentity.from(app);
                    if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) {
                        setStatus(app, "云机器记录已删除", "");
                        complete(callback, false, "本机云机器记录已删除，请在设备管理中确认“重新登记本机”");
                    } else {
                        SyncOutcome outcome = reconcileRemote(app, identity, true);
                        complete(callback, outcome.success, outcome.message);
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("设备编号不可用")) {
                        clearIdentityCache(app);
                    }
                    recordError(app, errorMessage(e));
                    complete(callback, false, errorMessage(e));
                }
            }
        });
    }

    public static void scheduleSync(Context context) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) return;
                    DeviceIdentity identity = DeviceIdentity.from(app);
                    long previousRevision = TargetStore.prefs(app).getLong(PREF_REVISION, 0L);
                    SyncOutcome outcome = reconcileRemote(app, identity, true);
                    if (outcome.registered && previousRevision == 0L) {
                        notifyRegistered(app);
                    }
                } catch (Exception e) { recordError(app, errorMessage(e)); }
            }
        });
    }

    /**
     * Retries automatic device-link recovery from the persisted JobScheduler job.
     * This deliberately bypasses STARTUP_STARTED so a failed first launch can be
     * recovered later in the same process when the network comes back.
     *
     * @return true when the job should finish successfully; false requests the
     *         platform's existing exponential backoff.
     */
    public static boolean retryFromJob(Context context) {
        Context app = context.getApplicationContext();
        synchronized (TASK_LOCK) {
            if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) return true;
            try {
                DeviceIdentity identity = DeviceIdentity.from(app);
                SyncOutcome outcome = reconcileRemote(app, identity, true);
                boolean deleted = TargetStore.prefs(app).getBoolean(PREF_DELETED, false);
                return !jobShouldRetry(outcome.success, deleted);
            } catch (Exception e) {
                recordError(app, errorMessage(e));
                return false;
            }
        }
    }

    /** Pure retry policy: deleted or terminal success must not be rescheduled. */
    static boolean jobShouldRetry(boolean reconcileSuccess, boolean deleted) {
        return !reconcileSuccess && !deleted;
    }

    public static void syncOrRecover(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) {
                        complete(callback, false, "云机器记录已删除，请先重新登记本机");
                    } else {
                        DeviceIdentity identity = DeviceIdentity.from(app);
                        SyncOutcome outcome = reconcileRemote(app, identity, false);
                        complete(callback, outcome.success, outcome.message);
                    }
                } catch (Exception e) {
                    recordError(app, errorMessage(e));
                    complete(callback, false, errorMessage(e));
                }
            }
        });
    }

    /** Explicit user action after a DELETE: revive, then create revision 1. */
    public static void revive(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    DeviceIdentity identity = DeviceIdentity.from(app);
                    JSONObject body = new JSONObject().put("backupId", identity.backupId).put("schema", SCHEMA);
                    HttpResult result = request("POST", relay(app) + "/v1/device/backup/revive", body, identity.backupToken);
                    if (result.status == 404) {
                        clearIdentityCache(app);
                        TargetStore.prefs(app).edit().putBoolean(PREF_DELETED, false).putLong(PREF_REVISION, 0L)
                                .putString(PREF_STATUS, "已重新启用，等待云配对").remove(PREF_ERROR).apply();
                        complete(callback, true, "本机已重新启用；完成云配对后会自动创建设备备份");
                        return;
                    }
                    if (result.status < 200 || result.status >= 300) throw new IllegalStateException(httpError("重新登记失败", result));
                    TargetStore.prefs(app).edit().putBoolean(PREF_DELETED, false).putLong(PREF_REVISION, 0L)
                            .remove(PREF_ERROR).apply();
                    boolean hasLinks = !CloudConfigStore.loadLinks(app).isEmpty();
                    if (hasLinks && !syncInternal(app, identity)) {
                        complete(callback, false, statusText(app));
                        return;
                    }
                    if (hasLinks) cacheIdentity(app, identity); else clearIdentityCache(app);
                    setStatus(app, hasLinks ? "已重新登记" : "已重新启用，等待云配对", "");
                    complete(callback, true, hasLinks ? "本机已重新登记，设备备份已创建" : "本机已重新启用；完成云配对后会自动创建设备备份");
                } catch (Exception e) { recordError(app, errorMessage(e)); complete(callback, false, errorMessage(e)); }
            }
        });
    }

    /** Removes a single link only after the relay accepts its revoke request. */
    public static void removeLink(Context context, CloudConfigStore.CloudLink link, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    if (link == null) throw new IllegalArgumentException("云链路为空");
                    JSONObject body = new JSONObject().put("roomId", link.roomId).put("deviceId", link.deviceId);
                    HttpResult revoke = request("POST", relayFor(link) + "/v1/device/revoke", body, link.token);
                    if (!((revoke.status >= 200 && revoke.status < 300) || revoke.status == 401)) {
                        throw new IllegalStateException(httpError("撤销云链路失败", revoke));
                    }
                    CloudOutboxStore.moveForLinkToDeadLetter(app, link, "云链路已删除");
                    if (!CloudConfigStore.removeLink(app, link)) throw new IllegalStateException("本地云链路删除失败");
                    if (!TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) {
                        DeviceIdentity identity = DeviceIdentity.from(app); cacheIdentity(app, identity);
                        if (!syncInternal(app, identity)) {
                            complete(callback, false, "云链路已删除，但设备备份同步未完成：" + statusText(app));
                            return;
                        }
                    }
                    if (CloudConfigStore.receiverLinks(app).isEmpty()) stopReceiver(app);
                    complete(callback, true, "云链路已删除");
                } catch (Exception e) { recordError(app, errorMessage(e)); complete(callback, false, errorMessage(e)); }
            }
        });
    }

    /** Revokes all links, deletes the encrypted device record, then clears local state. */
    public static void deleteDevice(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            synchronized (TASK_LOCK) {
                try {
                    DeviceIdentity identity = DeviceIdentity.from(app);
                    cacheIdentity(app, identity);
                    List<CloudConfigStore.CloudLink> links = CloudConfigStore.loadLinks(app);
                    String revokeErrors = "";
                    for (CloudConfigStore.CloudLink link : links) {
                        try {
                            JSONObject body = new JSONObject().put("roomId", link.roomId).put("deviceId", link.deviceId);
                            HttpResult result = request("POST", relayFor(link) + "/v1/device/revoke", body, link.token);
                            if (!((result.status >= 200 && result.status < 300) || result.status == 401)) revokeErrors += result.status + " ";
                        } catch (Exception e) { revokeErrors += e.getClass().getSimpleName() + " "; }
                    }
                    if (!revokeErrors.isEmpty()) {
                        throw new IllegalStateException("部分云链路撤销失败，未删除云机器记录：" + revokeErrors.trim());
                    }
                    HttpResult deleted = request("DELETE", relay(app) + "/v1/device/backup?backupId=" + enc(identity.backupId),
                            new JSONObject().put("backupId", identity.backupId), identity.backupToken);
                    if (!(deleted.status >= 200 && deleted.status < 300) && deleted.status != 404 && deleted.status != 410) {
                        throw new IllegalStateException(httpError("删除云机器记录失败", deleted));
                    }
                    if (!CloudConfigStore.clearLinks(app)) throw new IllegalStateException("本地云链路清空失败");
                    for (CloudConfigStore.CloudLink link : links) CloudOutboxStore.moveForLinkToDeadLetter(app, link, "云机器记录已删除");
                    stopReceiver(app);
                    TargetStore.prefs(app).edit().putLong(PREF_REVISION, 0L).putBoolean(PREF_DELETED, true)
                            .putString(PREF_STATUS, "云机器记录已删除").remove(PREF_ERROR).apply();
                    complete(callback, true, "云机器记录已删除");
                } catch (Exception e) { recordError(app, errorMessage(e)); complete(callback, false, errorMessage(e)); }
            }
        });
    }

    public static String machineCode(Context context) {
        return TargetStore.prefs(context.getApplicationContext()).getString(PREF_MACHINE_CODE, "未登记");
    }

    public static String backupId(Context context) {
        return TargetStore.prefs(context.getApplicationContext()).getString(PREF_BACKUP_ID, "");
    }

    private static void cacheIdentity(Context context, DeviceIdentity identity) {
        TargetStore.prefs(context.getApplicationContext()).edit().putString(PREF_MACHINE_CODE, identity.machineCode)
                .putString(PREF_BACKUP_ID, identity.backupId).apply();
    }

    private static void clearIdentityCache(Context context) {
        TargetStore.prefs(context.getApplicationContext()).edit()
                .remove(PREF_MACHINE_CODE).remove(PREF_BACKUP_ID).apply();
    }

    public static String statusText(Context context) {
        android.content.SharedPreferences p = TargetStore.prefs(context.getApplicationContext());
        String status = p.getString(PREF_STATUS, "未登记");
        String error = p.getString(PREF_ERROR, "");
        long revision = p.getLong(PREF_REVISION, 0L);
        long synced = p.getLong(PREF_LAST_SYNC, 0L);
        StringBuilder out = new StringBuilder(status).append(" · revision ").append(revision);
        if (synced > 0) out.append("\n最近同步：").append(android.text.format.DateFormat.format("MM-dd HH:mm:ss", synced));
        if (!error.isEmpty()) out.append("\n错误：").append(error);
        return out.toString();
    }

    /**
     * Always reads the device backup first, then merges the cloud links with the local
     * links.  A local endpoint wins when both sides contain it; local-only endpoints
     * are retained only while their relay status is still active.
     */
    private static SyncOutcome reconcileRemote(Context app, DeviceIdentity identity, boolean automatic) throws Exception {
        if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) {
            return new SyncOutcome(false, "云机器记录已删除，请先重新登记本机", 0, false);
        }
        List<CloudConfigStore.CloudLink> localBefore = deduplicate(CloudConfigStore.loadLinks(app));
        HttpResult result = request("GET", relay(app) + "/v1/device/backup?backupId=" + enc(identity.backupId), null, identity.backupToken);
        if (result.status == 404) {
            // Do not expose a derived machine code before a real registration succeeds.
            if (localBefore.isEmpty()) {
                clearIdentityCache(app);
                TargetStore.prefs(app).edit().putLong(PREF_REVISION, 0L).putBoolean(PREF_DELETED, false).apply();
                setStatus(app, "未登记", "");
                return new SyncOutcome(true, "未登记", 0, false);
            }
            // A missing backup is not permission to resurrect a revoked local
            // credential. Validate all local endpoints before creating revision 1.
            List<CloudConfigStore.CloudLink> activeLocal = validateLinks(app, localBefore);
            List<CloudConfigStore.CloudLink> removedLocal = linksRemovedByMerge(localBefore, activeLocal);
            if (activeLocal.isEmpty()) {
                if (!CloudConfigStore.clearLinks(app)) throw new IllegalStateException("清理失效本地云链路失败");
                if (!removedLocal.isEmpty()) moveRemovedLocalOutbox(app, removedLocal, "本地云链路已失效，停止重试");
                clearIdentityCache(app);
                TargetStore.prefs(app).edit().putLong(PREF_REVISION, 0L).putBoolean(PREF_DELETED, false).apply();
                setStatus(app, "未登记", "");
                return new SyncOutcome(true, "未登记", 0, false);
            }
            if (!CloudConfigStore.replaceLinks(app, activeLocal)) throw new IllegalStateException("设备登记时本地云链路保存失败");
            if (!removedLocal.isEmpty()) moveRemovedLocalOutbox(app, removedLocal, "本地云链路已失效，停止重试");
            TargetStore.prefs(app).edit().putLong(PREF_REVISION, 0L).putBoolean(PREF_DELETED, false).apply();
            if (!syncInternal(app, identity)) return new SyncOutcome(false, statusText(app), 0, false);
            cacheIdentity(app, identity);
            setStatus(app, "已登记", "");
            ensureReceiverStarted(app);
            return new SyncOutcome(true, "设备已登记", 0, true);
        }
        if (result.status == 410) {
            setDeleted(app, "云机器记录已删除");
            return new SyncOutcome(false, "本机云机器记录已删除，请在设备管理中确认“重新登记本机”", 0, false);
        }
        if (result.status < 200 || result.status >= 300) throw new IllegalStateException(httpError("读取设备备份失败", result));

        JSONObject backup = new JSONObject(result.body);
        if (backup.has("backup") && backup.optJSONObject("backup") != null) backup = backup.getJSONObject("backup");
        List<CloudConfigStore.CloudLink> remote = deduplicate(decryptLinks(identity, backup));
        // Successful authenticated decryption is the point at which the machine code
        // becomes a real, user-visible registration identity.
        cacheIdentity(app, identity);
        long remoteRevision = backup.optLong("revision", 0L);
        TargetStore.prefs(app).edit().putLong(PREF_REVISION, Math.max(0L, remoteRevision)).putBoolean(PREF_DELETED, false).apply();

        // Validate both sides before merging.  Never let an invalid local token
        // shadow a valid remote token (or vice versa).
        List<CloudConfigStore.CloudLink> activeLocal = validateLinks(app, localBefore);
        List<CloudConfigStore.CloudLink> activeRemote = validateLinks(app, remote);
        Map<String, CloudConfigStore.CloudLink> localForMerge = index(activeLocal);
        List<CloudConfigStore.CloudLink> mergedWithLocalPriority = mergeWithLocalPriority(localForMerge, activeRemote, activeLocal);
        int autoAdded = countRemoteOnly(activeLocal, activeRemote);
        List<CloudConfigStore.CloudLink> removedLocal = linksRemovedByMerge(localBefore, mergedWithLocalPriority);
        boolean localChanged = !sameLinkSets(localBefore, mergedWithLocalPriority);
        boolean localSaved = !localChanged || (mergedWithLocalPriority.isEmpty()
                ? CloudConfigStore.clearLinks(app)
                : CloudConfigStore.replaceLinks(app, mergedWithLocalPriority));
        if (!localSaved) {
            throw new IllegalStateException("云链路合并后本地保存失败");
        }
        if (!removedLocal.isEmpty()) moveRemovedLocalOutbox(app, removedLocal, "本地云链路已失效或已被远端替换，停止重试");
        boolean remoteChanged = !sameLinkSets(remote, mergedWithLocalPriority);
        if (remoteChanged) {
            if (!syncAtRevision(app, identity, mergedWithLocalPriority, remoteRevision)) {
                return new SyncOutcome(false, statusText(app), autoAdded, false);
            }
        } else {
            setStatus(app, mergedWithLocalPriority.isEmpty() ? "备份已读取，但没有有效云链路" : "已同步", "");
        }
        if (!mergedWithLocalPriority.isEmpty()) ensureReceiverStarted(app);
        if (autoAdded > 0 && automatic) notifyAutoAdded(app, autoAdded);
        String message = autoAdded > 0
                ? "已按设备编号自动添加 " + autoAdded + " 条云链路"
                : (remoteChanged ? "设备备份同步完成" : "设备链路已是最新");
        return new SyncOutcome(true, message, autoAdded, false);
    }

    private static List<CloudConfigStore.CloudLink> mergeWithLocalPriority(
            Map<String, CloudConfigStore.CloudLink> localByEndpoint,
            List<CloudConfigStore.CloudLink> activeRemote,
            List<CloudConfigStore.CloudLink> localOnlyActive) {
        LinkedHashMap<String, CloudConfigStore.CloudLink> merged = new LinkedHashMap<>();
        for (CloudConfigStore.CloudLink remote : activeRemote) {
            String key = endpointKey(remote);
            CloudConfigStore.CloudLink local = localByEndpoint.get(key);
            merged.put(key, local == null ? remote : local);
        }
        for (CloudConfigStore.CloudLink local : localOnlyActive) merged.putIfAbsent(endpointKey(local), local);
        return new ArrayList<>(merged.values());
    }

    /** Pure merge helper used by unit tests and kept independent from Android I/O. */
    static List<CloudConfigStore.CloudLink> mergeLinks(List<CloudConfigStore.CloudLink> local,
                                                        List<CloudConfigStore.CloudLink> remoteActive) {
        Map<String, CloudConfigStore.CloudLink> localByEndpoint = index(deduplicate(local));
        return mergeWithLocalPriority(localByEndpoint, deduplicate(remoteActive), deduplicate(local));
    }

    static int countRemoteOnly(List<CloudConfigStore.CloudLink> local,
                               List<CloudConfigStore.CloudLink> remoteActive) {
        Set<String> localKeys = endpointKeys(local);
        int count = 0;
        for (CloudConfigStore.CloudLink remote : deduplicate(remoteActive)) {
            if (!localKeys.contains(endpointKey(remote))) count++;
        }
        return count;
    }

    static boolean sameLinkSets(List<CloudConfigStore.CloudLink> first,
                                List<CloudConfigStore.CloudLink> second) {
        Map<String, CloudConfigStore.CloudLink> a = index(deduplicate(first));
        Map<String, CloudConfigStore.CloudLink> b = index(deduplicate(second));
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, CloudConfigStore.CloudLink> entry : a.entrySet()) {
            if (!sameLink(entry.getValue(), b.get(entry.getKey()))) return false;
        }
        return true;
    }

    /** Returns local credentials that no longer survive the validated merge. */
    static List<CloudConfigStore.CloudLink> linksRemovedByMerge(List<CloudConfigStore.CloudLink> before,
                                                                 List<CloudConfigStore.CloudLink> after) {
        List<CloudConfigStore.CloudLink> removed = new ArrayList<>();
        List<CloudConfigStore.CloudLink> uniqueBefore = deduplicate(before);
        List<CloudConfigStore.CloudLink> uniqueAfter = deduplicate(after);
        for (CloudConfigStore.CloudLink link : uniqueBefore) {
            boolean retained = false;
            for (CloudConfigStore.CloudLink candidate : uniqueAfter) {
                if (sameLink(link, candidate)) { retained = true; break; }
            }
            if (!retained) removed.add(link);
        }
        return removed;
    }

    private static void moveRemovedLocalOutbox(Context app,
                                                List<CloudConfigStore.CloudLink> removed,
                                                String reason) {
        for (CloudConfigStore.CloudLink link : removed) {
            CloudOutboxStore.moveForLinkToDeadLetter(app, link, reason);
        }
    }

    private static boolean sameLink(CloudConfigStore.CloudLink a, CloudConfigStore.CloudLink b) {
        return b != null && Objects.equals(a.relayUrl, b.relayUrl) && Objects.equals(a.roomId, b.roomId)
                && Objects.equals(a.deviceId, b.deviceId) && Objects.equals(a.token, b.token)
                && Objects.equals(a.peerDeviceId, b.peerDeviceId) && Objects.equals(a.peerPublicKey, b.peerPublicKey)
                && Objects.equals(a.privateKey, b.privateKey) && Objects.equals(a.role, b.role)
                && Objects.equals(a.peerName, b.peerName);
    }

    private static List<CloudConfigStore.CloudLink> deduplicate(List<CloudConfigStore.CloudLink> links) {
        LinkedHashMap<String, CloudConfigStore.CloudLink> unique = new LinkedHashMap<>();
        if (links != null) for (CloudConfigStore.CloudLink link : links) {
            if (link != null && CloudConfigStore.isConfigured(link)) unique.put(endpointKey(link), link);
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<String, CloudConfigStore.CloudLink> index(List<CloudConfigStore.CloudLink> links) {
        LinkedHashMap<String, CloudConfigStore.CloudLink> indexed = new LinkedHashMap<>();
        if (links != null) for (CloudConfigStore.CloudLink link : links) {
            if (link != null && CloudConfigStore.isConfigured(link)) indexed.put(endpointKey(link), link);
        }
        return indexed;
    }

    private static Set<String> endpointKeys(List<CloudConfigStore.CloudLink> links) {
        return new HashSet<>(index(links).keySet());
    }

    private static String endpointKey(CloudConfigStore.CloudLink link) {
        if (link == null) return "";
        return link.role + "\n" + link.roomId + "\n" + link.deviceId;
    }

    private static StatusResult linkStatus(Context app, CloudConfigStore.CloudLink link) throws Exception {
        HttpResult state = request("GET", relayFor(link) + "/v1/device/status?roomId=" + enc(link.roomId)
                + "&deviceId=" + enc(link.deviceId), null, link.token);
        if (state.status >= 200 && state.status < 300) {
            return new StatusResult(statusActive(state.body, link.peerDeviceId), false);
        }
        if (state.status == 401 || state.status == 403 || state.status == 404 || state.status == 410) {
            return new StatusResult(false, true);
        }
        throw new IllegalStateException(httpError("校验云链路状态失败", state));
    }

    private static List<CloudConfigStore.CloudLink> validateLinks(Context app,
                                                                    List<CloudConfigStore.CloudLink> links) throws Exception {
        List<CloudConfigStore.CloudLink> active = new ArrayList<>();
        for (CloudConfigStore.CloudLink link : deduplicate(links)) {
            if (linkStatus(app, link).active) active.add(link);
        }
        return active;
    }

    private static void ensureReceiverStarted(Context app) {
        if (CloudConfigStore.receiverLinks(app).isEmpty()) return;
        TargetStore.prefs(app).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, true).commit();
        ReceiverService.ensureStarted(app);
    }

    private static void notifyAutoAdded(Context app, int count) {
        String message = "已按设备编号自动添加 " + count + " 条云链路";
        Notifications.showDeviceNotice(app, "MsgDock", message);
        postMain(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    private static void notifyRegistered(Context app) {
        String message = "设备已登记";
        Notifications.showDeviceNotice(app, "MsgDock", message);
        postMain(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    private static final class StatusResult {
        final boolean active;
        final boolean permanentInactive;
        StatusResult(boolean active, boolean permanentInactive) {
            this.active = active;
            this.permanentInactive = permanentInactive;
        }
    }

    private static final class SyncOutcome {
        final boolean success;
        final String message;
        final int autoAdded;
        final boolean registered;
        SyncOutcome(boolean success, String message, int autoAdded, boolean registered) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.autoAdded = autoAdded;
            this.registered = registered;
        }
    }

    private static boolean syncInternal(Context app, DeviceIdentity identity) throws Exception {
        if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) return false;
        return syncAtRevision(app, identity, deduplicate(CloudConfigStore.loadLinks(app)),
                TargetStore.prefs(app).getLong(PREF_REVISION, 0L));
    }

    private static boolean syncAtRevision(Context app, DeviceIdentity identity,
                                          List<CloudConfigStore.CloudLink> links,
                                          long previous) throws Exception {
        if (TargetStore.prefs(app).getBoolean(PREF_DELETED, false)) return false;
        long revision = Math.max(1L, previous + 1L);
        JSONObject plain = new JSONObject(); plain.put("v", SCHEMA); plain.put("relayUrl", CloudRelay.DEFAULT_RELAY_URL);
        plain.put("savedAt", System.currentTimeMillis());
        JSONArray array = new JSONArray();
        for (CloudConfigStore.CloudLink link : links) array.put(link.toJson());
        plain.put("links", array);
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        byte[] key = identity.deriveBackupKey(salt);
        String saltEncoded = CloudCrypto.b64(salt);
        CloudCrypto.Encrypted encrypted = CloudCrypto.encrypt(key, plain.toString().getBytes(StandardCharsets.UTF_8),
                aad(identity.backupId, revision, saltEncoded).getBytes(StandardCharsets.UTF_8));
        JSONObject body = new JSONObject().put("schema", SCHEMA).put("backupId", identity.backupId).put("revision", revision)
                .put("salt", saltEncoded).put("nonce", CloudCrypto.b64(encrypted.nonce)).put("ciphertext", CloudCrypto.b64(encrypted.ciphertext));
        HttpResult result = request("PUT", relay(app) + "/v1/device/backup", body, identity.backupToken);
        if (result.status == 409) { setStatus(app, "备份版本冲突，未覆盖云端", "revision conflict"); return false; }
        if (result.status == 410) { setDeleted(app, "云机器记录已删除"); return false; }
        if (result.status < 200 || result.status >= 300) throw new IllegalStateException(httpError("同步设备备份失败", result));
        JSONObject response = parseObject(result.body);
        long savedRevision = response.optLong("revision", revision);
        TargetStore.prefs(app).edit().putLong(PREF_REVISION, savedRevision).putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply();
        setStatus(app, "已同步", "");
        return true;
    }

    private static List<CloudConfigStore.CloudLink> decryptLinks(DeviceIdentity identity, JSONObject backup) throws Exception {
        if (!identity.backupId.equals(backup.optString("backupId", ""))) throw new IllegalStateException("设备备份机器码不匹配");
        if (!backup.has("schema") || backup.getInt("schema") != SCHEMA) throw new IllegalStateException("不支持的设备备份版本");
        long revision = backup.optLong("revision", 0L); if (revision < 1L) throw new IllegalStateException("设备备份 revision 无效");
        String saltEncoded = required(backup, "salt"), nonceEncoded = required(backup, "nonce"), ciphertextEncoded = required(backup, "ciphertext");
        byte[] salt = CloudCrypto.unb64(saltEncoded); if (salt.length != 16) throw new IllegalStateException("设备备份 salt 无效");
        byte[] plain = CloudCrypto.decrypt(identity.deriveBackupKey(salt), CloudCrypto.unb64(nonceEncoded), CloudCrypto.unb64(ciphertextEncoded),
                aad(identity.backupId, revision, saltEncoded).getBytes(StandardCharsets.UTF_8));
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        if (root.optInt("v", 0) != SCHEMA) throw new IllegalStateException("设备备份正文版本无效");
        String defaultRelay = root.optString("relayUrl", CloudRelay.DEFAULT_RELAY_URL);
        JSONArray array = root.optJSONArray("links");
        if (array == null) throw new IllegalStateException("设备备份缺少云链路");
        List<CloudConfigStore.CloudLink> links = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i); CloudConfigStore.CloudLink link = CloudConfigStore.CloudLink.fromJson(value);
            if (link == null) continue;
            if (link.relayUrl.isEmpty()) link = new CloudConfigStore.CloudLink(defaultRelay, link.roomId, link.deviceId, link.token,
                    link.peerDeviceId, link.peerPublicKey, link.privateKey, link.role, link.peerName);
            if (!CloudConfigStore.isConfigured(link)) throw new IllegalStateException("设备备份包含无效云链路");
            links.add(link);
        }
        return links;
    }

    private static boolean statusActive(String body, String expectedPeerDeviceId) throws Exception {
        if (body == null || body.trim().isEmpty()) throw new IllegalStateException("云链路状态响应为空");
        JSONObject value = new JSONObject(body);
        if (!value.has("active") || !value.has("peerActive") || !value.has("peerDeviceId")
                || !(value.get("active") instanceof Boolean) || !(value.get("peerActive") instanceof Boolean)) {
            throw new IllegalStateException("云链路状态缺少 active/peerActive/peerDeviceId");
        }
        String peerDeviceId = value.getString("peerDeviceId");
        String state = value.optString("status", "").toLowerCase(java.util.Locale.ROOT);
        return value.getBoolean("active") && value.getBoolean("peerActive")
                && expectedPeerDeviceId != null && expectedPeerDeviceId.equals(peerDeviceId)
                && !value.optBoolean("revoked", false)
                && !"revoked".equals(state) && !"inactive".equals(state);
    }

    private static void stopReceiver(Context app) {
        TargetStore.prefs(app).edit().putBoolean(ReceiverService.PREF_RECEIVER_ENABLED, false).commit();
        BootReceiver.cancelReceiverRestart(app);
        try { app.stopService(new Intent(app, ReceiverService.class)); } catch (Exception ignored) { }
    }

    private static void setDeleted(Context app, String message) {
        TargetStore.prefs(app).edit().putBoolean(PREF_DELETED, true).putString(PREF_STATUS, message)
                .putString(PREF_ERROR, "").apply();
    }

    private static void setStatus(Context app, String status, String error) {
        TargetStore.prefs(app).edit().putString(PREF_STATUS, status == null ? "" : status)
                .putString(PREF_ERROR, error == null ? "" : error).apply();
    }

    private static void recordError(Context app, String error) {
        TargetStore.prefs(app).edit().putString(PREF_STATUS, "同步失败")
                .putString(PREF_ERROR, error == null ? "unknown error" : error).apply();
    }

    private static void complete(Callback callback, boolean success, String message) {
        if (callback == null) return;
        postMain(() -> callback.completed(success, message == null ? "" : message));
    }

    private static void postMain(Runnable runnable) {
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(runnable);
        } catch (RuntimeException ignored) {
            // Plain JVM unit tests do not provide Android's main Looper.
            runnable.run();
        }
    }

    private static JSONObject parseObject(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        return new JSONObject(body);
    }

    public static String aad(String backupId, long revision, String salt) {
        return "xgy-device-backup-v1\n" + backupId + "\n" + SCHEMA + "\n" + revision + "\n" + salt;
    }

    private static String relay(Context context) { return CloudRelay.DEFAULT_RELAY_URL; }

    private static String relayFor(CloudConfigStore.CloudLink link) {
        String value = link == null ? "" : link.relayUrl;
        if (!CloudConfigStore.isSecureRelayUrl(value)) return CloudRelay.DEFAULT_RELAY_URL;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static HttpResult request(String method, String urlString, JSONObject body, String token) throws Exception {
        RelayHttp.Result result = RelayHttp.request(method, urlString, body, token, false);
        return new HttpResult(result.status, result.body);
    }


    private static String required(JSONObject object, String field) {
        String value = object.optString(field, "").trim();
        if (value.isEmpty()) throw new IllegalStateException("设备备份缺少 " + field);
        return value;
    }

    private static String enc(String value) throws Exception { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }

    private static String httpError(String prefix, HttpResult result) {
        return prefix + " HTTP " + result.status + (result.body.isEmpty() ? "" : ": " + result.body);
    }

    private static String errorMessage(Exception e) {
        String value = e.getMessage(); return value == null || value.isEmpty() ? e.getClass().getSimpleName() : value;
    }

    static final class HttpResult {
        final int status; final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body == null ? "" : body; }
    }
}
