package com.xgy.lansms;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal native client for the account-sync API at /api/v1. */
public final class AccountApi {
    public static final String DEFAULT_API_URL = "https://msgdock.dpdns.org";
    private static final String API_PREFIX = "/api/v1";
    private static final String CLIENT_HEADER = "X-MsgDock-Client";
    private static final String NATIVE_CLIENT = "native";
    private static final Object RETRY_LOCK = new Object();
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "msgdock-account-retry");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final AtomicBoolean ACCOUNT_FLUSHING = new AtomicBoolean(false);
    private static ScheduledFuture<?> retryFuture;
    private static long retryDueAt = -1L;

    private AccountApi() {}

    public interface Callback {
        void completed(boolean success, String message);
    }

    public static void register(Context context, String username, String email, String password,
                                Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            try {
                require(username, "用户名");
                require(email, "邮箱");
                require(password, "密码");
                JSONObject body = new JSONObject().put("username", username.trim())
                        .put("email", email.trim()).put("password", password);
                HttpResult result = request("POST", endpoint("/auth/register"), body, null, true);
                JSONObject response = successObject(result, "注册失败");
                saveAuthentication(app, response);
                if (!ensureDeviceSync(app)) throw new IllegalStateException("设备注册失败");
                CloudSyncJobService.schedule(app);
                scheduleOutboxFlush(app);
                complete(callback, true, "注册并登录成功，账号同步已启用");
            } catch (Exception e) {
                scheduleAccountRetry(app);
                complete(callback, false, errorMessage(e));
            }
        });
    }

    public static void login(Context context, String identifier, String password, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            try {
                require(identifier, "用户名或邮箱");
                require(password, "密码");
                JSONObject body = new JSONObject().put("identifier", identifier.trim())
                        .put("password", password);
                HttpResult result = request("POST", endpoint("/auth/login"), body, null, true);
                JSONObject response = successObject(result, "登录失败");
                saveAuthentication(app, response);
                if (!ensureDeviceSync(app)) throw new IllegalStateException("设备注册失败");
                CloudSyncJobService.schedule(app);
                scheduleOutboxFlush(app);
                complete(callback, true, "登录成功，账号同步已启用");
            } catch (Exception e) {
                scheduleAccountRetry(app);
                complete(callback, false, errorMessage(e));
            }
        });
    }

    public static void logout(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        CloudRelay.executor().execute(() -> {
            String message = "已退出账号";
            try {
                String token = AccountStore.sessionToken(app);
                if (!token.isEmpty()) {
                    HttpResult result = request("POST", endpoint("/auth/logout"), null, token, true);
                    if (result.status < 200 || result.status >= 300) message = "本地已退出账号（服务器响应 HTTP " + result.status + ")";
                }
            } catch (Exception e) {
                message = "本地已退出账号（服务器暂时不可达）";
            } finally {
                AccountOutboxStore.clear(app);
                AccountStore.clear(app);
            }
            complete(callback, true, message);
        });
    }

    /** Ensures a session-authenticated account has a separate device token. */
    public static boolean ensureDeviceSync(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String session = AccountStore.sessionToken(app);
        if (session.isEmpty()) return false;
        JSONObject body = new JSONObject().put("id", AccountStore.ensureDeviceId(app))
                .put("name", AccountStore.deviceName(app)).put("type", "android");
        HttpResult result = request("POST", endpoint("/devices"), body, session, false);
        if (result.status == 401 || result.status == 403) {
            AccountStore.clearDeviceToken(app);
            throw new IllegalStateException("账号会话已失效，请重新登录");
        }
        JSONObject response = successObject(result, "设备注册失败");
        JSONObject device = response.optJSONObject("device");
        String token = response.optString("device_token", "").trim();
        if (token.isEmpty()) throw new IllegalStateException("设备注册响应缺少 device_token");
        String name = device == null ? AccountStore.deviceName(app) : device.optString("name", AccountStore.deviceName(app));
        AccountStore.saveDeviceToken(app, token, name);
        return true;
    }

    /** Uploads due account messages. The caller supplies the JobScheduler worker thread. */
    public static boolean flushOutbox(Context context) {
        Context app = context.getApplicationContext();
        int initial = AccountOutboxStore.count(app);
        if (initial < 0) return false;
        if (!AccountStore.hasAccount(app)) return initial == 0;
        if (!ACCOUNT_FLUSHING.compareAndSet(false, true)) return true;
        try {
            try {
                if (!AccountStore.hasDeviceToken(app) && !ensureDeviceSync(app)) {
                    if (initial > 0) markDueFailures(app, "账号设备注册失败");
                    return false;
                }
            } catch (Exception e) {
                android.util.Log.w("MsgDock", "账号设备注册失败", e);
                if (initial > 0) markDueFailures(app, errorMessage(e));
                return false;
            }
            if (initial == 0) return true;
            List<AccountOutboxStore.Entry> entries = AccountOutboxStore.due(app, System.currentTimeMillis(), 20);
            for (AccountOutboxStore.Entry entry : entries) {
                try {
                    JSONObject body = AccountOutboxStore.payload(entry.clientMessageId, entry.sender,
                            entry.body, entry.receivedAt);
                    HttpResult result = request("POST", endpoint("/messages"), body,
                            AccountStore.deviceToken(app), false);
                    if (result.status >= 200 && result.status < 300) {
                        AccountOutboxStore.remove(app, entry.clientMessageId);
                    } else {
                        if (result.status == 401 || result.status == 403) AccountStore.clearDeviceToken(app);
                        AccountOutboxStore.markFailure(app, entry.clientMessageId, httpError(result));
                    }
                } catch (Exception e) {
                    AccountOutboxStore.markFailure(app, entry.clientMessageId, errorMessage(e));
                }
            }
            return AccountOutboxStore.count(app) == 0;
        } finally {
            ACCOUNT_FLUSHING.set(false);
            scheduleOutboxFlush(app);
        }
    }

    /**
     * Schedules one in-process retry for the earliest persisted account outbox deadline.
     * JobScheduler remains the durable fallback when this process is killed or suspended.
     */
    public static void scheduleOutboxFlush(Context context) {
        Context app = context.getApplicationContext();
        if (!AccountStore.hasAccount(app)) return;
        if (AccountOutboxStore.count(app) <= 0) return;
        long next = AccountOutboxStore.nextAttemptAt(app);
        if (next < 0L) return;
        long now = System.currentTimeMillis();
        long dueAt = Math.max(now, next);
        synchronized (RETRY_LOCK) {
            if (retryFuture != null && retryFuture.isDone()) {
                retryFuture = null;
                retryDueAt = -1L;
            }
            if (retryFuture != null && !shouldReplaceRetryTimer(retryDueAt, dueAt)) return;
            if (retryFuture != null) retryFuture.cancel(false);
            retryDueAt = dueAt;
            retryFuture = RETRY_EXECUTOR.schedule(() -> runScheduledFlush(app, dueAt),
                    Math.max(0L, dueAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
    }

    static boolean shouldReplaceRetryTimer(long currentDueAt, long requestedDueAt) {
        return currentDueAt < 0L || requestedDueAt < currentDueAt;
    }

    private static void runScheduledFlush(Context app, long scheduledDueAt) {
        synchronized (RETRY_LOCK) {
            if (retryDueAt != scheduledDueAt) return;
            retryFuture = null;
            retryDueAt = -1L;
        }
        flushOutbox(app);
    }

    private static void markDueFailures(Context context, String error) {
        long now = System.currentTimeMillis();
        for (AccountOutboxStore.Entry entry : AccountOutboxStore.due(context, now, 1000)) {
            AccountOutboxStore.markFailure(context, entry.clientMessageId, error);
        }
    }

    private static void scheduleAccountRetry(Context context) {
        if (!AccountStore.hasAccount(context)) return;
        CloudSyncJobService.schedule(context);
        scheduleOutboxFlush(context);
    }

    public static String statusText(Context context) {
        return AccountStore.statusText(context);
    }

    private static void saveAuthentication(Context context, JSONObject response) {
        JSONObject user = response.optJSONObject("user");
        String session = response.optString("session_token", "").trim();
        if (session.isEmpty()) throw new IllegalStateException("账号响应缺少 session_token");
        String userId = user == null ? "" : user.optString("id", "");
        // Never upload messages captured while another account was active to the
        // newly authenticated account.  Same-account re-login keeps its queue.
        String previousUserId = AccountStore.userId(context);
        if (!previousUserId.isEmpty() && !previousUserId.equals(userId)) {
            AccountOutboxStore.clear(context);
        }
        AccountStore.saveAuthentication(context,
                userId,
                user == null ? "" : user.optString("username", ""),
                user == null ? "" : user.optString("email", ""),
                session, response.optLong("expires_at", 0L));
    }

    private static JSONObject successObject(HttpResult result, String prefix) throws Exception {
        if (result.status < 200 || result.status >= 300) {
            throw new IllegalStateException(prefix + " HTTP " + result.status
                    + (result.body.isEmpty() ? "" : "：" + apiError(result.body)));
        }
        return result.body.trim().isEmpty() ? new JSONObject() : new JSONObject(result.body);
    }

    private static HttpResult request(String method, String urlString, JSONObject body,
                                      String bearer, boolean nativeAuth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            if (nativeAuth) connection.setRequestProperty(CLIENT_HEADER, NATIVE_CLIENT);
            if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(data); }
            }
            int status = connection.getResponseCode();
            return new HttpResult(status, readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String endpoint(String path) {
        return DEFAULT_API_URL + API_PREFIX + (path.startsWith("/") ? path : "/" + path);
    }

    private static String apiError(String body) {
        try {
            JSONObject value = new JSONObject(body);
            String error = value.optString("error", "");
            if (!error.isEmpty()) return error;
            String message = value.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) { }
        return body.length() > 256 ? body.substring(0, 256) : body;
    }

    private static String httpError(HttpResult result) {
        return "账号消息上传 HTTP " + result.status
                + (result.body.isEmpty() ? "" : "：" + apiError(result.body));
    }

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n, total = 0;
            while ((n = input.read(buffer)) >= 0) {
                total += n;
                if (total > 2 * 1024 * 1024) break;
                output.write(buffer, 0, n);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void require(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + "不能为空");
    }

    private static void complete(Callback callback, boolean success, String message) {
        if (callback == null) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.completed(success, message));
        } catch (RuntimeException ignored) {
            callback.completed(success, message);
        }
    }

    private static String errorMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isEmpty() ? e.getClass().getSimpleName() : value;
    }

    private static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
