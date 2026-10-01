package com.xgy.lansms;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Durable inbox and the shared LAN/cloud UUID de-duplication ledger. */
public final class CloudInboxStore {
    private static final Object LOCK = new Object();
    private static final String INBOX = "cloud-inbox.jsonl";
    private static final String SEEN = "cloud-seen-ids.jsonl";
    private static final long SEEN_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final Set<String> DELIVERY_CLAIMS = new HashSet<>();
    private CloudInboxStore() {}

    /** Account history is scoped by user+seq; deliveryId keeps LAN/cloud notification de-duplication. */
    static boolean acceptAccount(File dir, JSONObject sms) {
        synchronized (LOCK) {
            String id = sms.optString("id", "");
            if (id.isEmpty() || sms.optString("accountUserId", "").isEmpty()) return false;
            File file = new File(dir, INBOX);
            if (file.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try { if (id.equals(new JSONObject(line).optString("id"))) return true; }
                        catch (org.json.JSONException ignored) { }
                    }
                } catch (Exception e) { return false; }
            }
            return appendLine(file, sms.toString());
        }
    }

    /** Local LAN/paired-cloud history plus the current account, newest arrival first. */
    static List<JSONObject> localHistory(File dir, String user, int limit) throws Exception {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            if (limit <= 0) return out;
            File file = new File(dir, INBOX);
            if (!file.isFile()) return out;
            LinkedHashMap<String, JSONObject> recent = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject record = new JSONObject(line);
                        // Filter before de-duplication: another account must not hide or replace a visible row.
                        if (!accountVisible(record, user, true)) continue;
                        String id = deliveryId(record);
                        if (id.isEmpty()) continue;
                        recent.remove(id);
                        recent.put(id, record);
                        if (recent.size() > limit) recent.remove(recent.keySet().iterator().next());
                    } catch (org.json.JSONException ignored) { }
                }
            }
            out.addAll(recent.values());
            java.util.Collections.reverse(out);
            return out;
        }
    }

    static boolean accountVisible(JSONObject sms, String user, boolean enabled) {
        String owner = sms.optString("accountUserId", "");
        return owner.isEmpty() || (enabled && !user.isEmpty() && owner.equals(user));
    }

    public static String deliveryId(JSONObject sms) {
        return sms.optString("deliveryId", sms.optString("id", ""));
    }

    /**
     * Persists a LAN message before notification. A pending record is returned
     * as accepted again so a retry can recover when notification was disabled.
     * Empty IDs remain compatible with v1 LAN senders and are not persisted.
     */
    public static boolean acceptLan(Context context, JSONObject sms) {
        String id = sms == null ? "" : sms.optString("id", "");
        if (id.isEmpty()) return true;
        synchronized (LOCK) {
            if (seenLocked(context, id) || deliveredLocked(context, id)) return false;
            if (pendingLocked(context, id) != null) return true;
            try {
                JSONObject record = new JSONObject(sms.toString());
                record.put("id", id);
                record.put("source", "lan");
                return appendLine(new File(context.getFilesDir(), INBOX), record.toString());
            } catch (Exception ignored) { return false; }
        }
    }

    /** Persists decrypted cloud plaintext before its notification is emitted. */
    public static boolean acceptCloud(Context context, String id, JSONObject plaintext) {
        if (id == null || id.isEmpty() || plaintext == null) return false;
        synchronized (LOCK) {
            if (seenLocked(context, id) || deliveredLocked(context, id)) return false;
            // A crash after the inbox append must not append the same plaintext again.
            if (pendingLocked(context, id) != null) return true;
            try {
                JSONObject record = new JSONObject(plaintext.toString());
                record.put("id", id);
                record.put("source", "cloud");
                return appendLine(new File(context.getFilesDir(), INBOX), record.toString());
            } catch (Exception ignored) { return false; }
        }
    }

    /** Returns a previously persisted, not-yet-seen message, or null. */
    public static JSONObject pending(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (LOCK) { return pendingLocked(context, id); }
    }

    /** Returns all persisted records that still need a successful notification. */
    public static List<JSONObject> pending(Context context) {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            File file = new File(context.getFilesDir(), INBOX);
            if (!file.isFile()) return out;
            Set<String> seen = seenIdsLocked(context);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject record = new JSONObject(line);
                        if (!accountVisible(record, AccountStore.userId(context), AccountStore.receiveEnabled(context))) continue;
                        String id = record.optString("id", "");
                        boolean delivered = record.optLong("deliveredAt", 0L) > 0L
                                || record.optLong("notifiedAt", 0L) > 0L;
                        if (!id.isEmpty() && !delivered && !seen.contains(id)) out.add(record);
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            return out;
        }
    }

    public static boolean isSeen(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) { return seenLocked(context, id); }
    }

    /** Claims one ID for the notification transaction within this process. */
    public static boolean claim(String id) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) {
            if (DELIVERY_CLAIMS.contains(id)) return false;
            DELIVERY_CLAIMS.add(id);
            return true;
        }
    }

    /** Releases a claim from a finally block, including failed notifications. */
    public static void release(String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (LOCK) { DELIVERY_CLAIMS.remove(id); }
    }

    public static boolean isDelivered(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) { return deliveredLocked(context, id); }
    }

    /** Durably records that the notification was emitted before seen/ACK. */
    public static boolean markDelivered(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), INBOX);
            if (!file.isFile()) return false;
            List<String> lines = new ArrayList<>();
            boolean found = false;
            boolean changed = false;
            long now = System.currentTimeMillis();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject record = new JSONObject(line);
                        if (id.equals(record.optString("id", ""))) {
                            found = true;
                            if (record.optLong("deliveredAt", 0L) <= 0L || record.optLong("notifiedAt", 0L) <= 0L) {
                                record.put("deliveredAt", now);
                                record.put("notifiedAt", now);
                                line = record.toString();
                                changed = true;
                            }
                        }
                    } catch (Exception ignored) { }
                    lines.add(line);
                }
            } catch (Exception ignored) { return false; }
            if (!found) return false;
            return !changed || rewriteLines(file, lines);
        }
    }

    /** Marks a record as delivered only after notification succeeded. */
    public static boolean markSeen(Context context, String id, String source) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) {
            if (seenLocked(context, id)) return true;
            return appendSeenLocked(context, id, source == null || source.isEmpty() ? "unknown" : source);
        }
    }

    private static boolean seenLocked(Context context, String id) {
        return seenIdsLocked(context).contains(id);
    }

    private static Set<String> seenIdsLocked(Context context) {
        Set<String> ids = new HashSet<>();
        File file = new File(context.getFilesDir(), SEEN);
        if (!file.isFile()) return ids;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String value = new JSONObject(line).optString("id", "");
                    if (!value.isEmpty()) ids.add(value);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return ids;
    }

    private static JSONObject pendingLocked(Context context, String id) {
        File file = new File(context.getFilesDir(), INBOX);
        if (!file.isFile()) return null;
        if (seenIdsLocked(context).contains(id) || deliveredLocked(context, id)) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject record = new JSONObject(line);
                    if (id.equals(record.optString("id", ""))) return record;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean deliveredLocked(Context context, String id) {
        File file = new File(context.getFilesDir(), INBOX);
        if (!file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject record = new JSONObject(line);
                    if ((id.equals(record.optString("id", ""))
                            || (id.equals(record.optString("deliveryId", ""))
                                && accountVisible(record, AccountStore.userId(context), true)))
                            && (record.optLong("deliveredAt", 0L) > 0L || record.optLong("notifiedAt", 0L) > 0L)) return true;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean appendSeenLocked(Context context, String id, String source) {
        try {
            if (!deliveredLocked(context, id)) return false;
            if (!pruneSeenLocked(context)) return false;
            JSONObject record = new JSONObject();
            record.put("id", id);
            record.put("source", source);
            record.put("seenAt", System.currentTimeMillis());
            return appendLine(new File(context.getFilesDir(), SEEN), record.toString());
        } catch (Exception ignored) { return false; }
    }

    /**
     * Rewrites old JSONL entries at most once per delivered record, adding
     * timestamps to legacy entries and removing records older than 30 days.
     * Invalid lines are retained so a damaged ledger is never silently erased.
     */
    private static boolean pruneSeenLocked(Context context) {
        File file = new File(context.getFilesDir(), SEEN);
        if (!file.isFile()) return true;
        List<String> kept = new ArrayList<>();
        Set<String> deliveredIds = deliveredIdsLocked(context);
        boolean changed = false;
        long now = System.currentTimeMillis();
        long cutoff = now - SEEN_RETENTION_MS;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject value = new JSONObject(line);
                    String id = value.optString("id", "");
                    if (id.isEmpty()) { kept.add(line); continue; }
                    long seenAt = value.optLong("seenAt", 0L);
                    if (seenAt > 0L && seenAt < cutoff && deliveredIds.contains(id)) { changed = true; continue; }
                    if (seenAt <= 0L) { value.put("seenAt", now); kept.add(value.toString()); changed = true; }
                    else kept.add(line);
                } catch (Exception ignored) { kept.add(line); }
            }
        } catch (Exception ignored) { return false; }
        return !changed || rewriteLines(file, kept);
    }

    private static boolean rewriteLines(File file, List<String> lines) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            for (String line : lines) output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Exception ignored) { return false; }
        if (temp.renameTo(file)) return true;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            for (String line : lines) output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            temp.delete();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static Set<String> deliveredIdsLocked(Context context) {
        Set<String> ids = new HashSet<>();
        File file = new File(context.getFilesDir(), INBOX);
        if (!file.isFile()) return ids;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject record = new JSONObject(line);
                    if ((record.optLong("deliveredAt", 0L) > 0L || record.optLong("notifiedAt", 0L) > 0L)) {
                        String id = record.optString("id", "");
                        if (!id.isEmpty()) ids.add(id);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return ids;
    }

    private static boolean appendLine(File file, String line) {
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file, "rw")) {
            long length = output.length();
            if (length > 0) {
                output.seek(length - 1);
                if (output.read() != '\n') output.write('\n'); // Keep a crash-truncated tail from swallowing the retry.
            }
            output.seek(output.length());
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            return true;
        } catch (Exception ignored) { return false; }
    }
}
