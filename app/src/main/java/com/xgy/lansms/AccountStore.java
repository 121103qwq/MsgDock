package com.xgy.lansms;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Account-sync credentials are deliberately separate from the legacy /v1
 * pairing credentials in {@link CloudConfigStore}.
 *
 * The server stores hashes of these tokens.  This class only keeps the native
 * client's own session/device credentials and the account message cursor.
 */
public final class AccountStore {
    private static final String PREFS = "msgdock_account";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_SESSION_TOKEN = "session_token";
    private static final String KEY_SESSION_EXPIRES_AT = "session_expires_at";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_LAST_SEQ = "last_seq";
    private static final Object LOCK = new Object();

    private AccountStore() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasAccount(Context context) {
        SharedPreferences p = prefs(context);
        return !p.getString(KEY_SESSION_TOKEN, "").isEmpty()
                || !p.getString(KEY_DEVICE_TOKEN, "").isEmpty();
    }

    public static boolean hasSession(Context context) {
        return !prefs(context).getString(KEY_SESSION_TOKEN, "").isEmpty();
    }

    public static boolean hasDeviceToken(Context context) {
        return !prefs(context).getString(KEY_DEVICE_TOKEN, "").isEmpty();
    }

    public static String sessionToken(Context context) {
        return prefs(context).getString(KEY_SESSION_TOKEN, "");
    }

    public static String deviceToken(Context context) {
        return prefs(context).getString(KEY_DEVICE_TOKEN, "");
    }

    public static String userId(Context context) {
        return prefs(context).getString(KEY_USER_ID, "");
    }

    public static String username(Context context) {
        return prefs(context).getString(KEY_USERNAME, "");
    }

    public static String email(Context context) {
        return prefs(context).getString(KEY_EMAIL, "");
    }

    public static String deviceId(Context context) {
        return prefs(context).getString(KEY_DEVICE_ID, "");
    }

    /**
     * The account device id is independent from the old machine-code/backup
     * identity.  It is stable for this installed account profile.
     */
    public static String ensureDeviceId(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            String existing = p.getString(KEY_DEVICE_ID, "").trim();
            if (!existing.isEmpty()) return existing;
            String value = "android_" + UUID.randomUUID();
            p.edit().putString(KEY_DEVICE_ID, value).commit();
            return value;
        }
    }

    public static String deviceName(Context context) {
        String value = prefs(context).getString(KEY_DEVICE_NAME, "").trim();
        return value.isEmpty() ? android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL : value;
    }

    public static long lastSeq(Context context) {
        return Math.max(0L, prefs(context).getLong(KEY_LAST_SEQ, 0L));
    }

    public static void saveAuthentication(Context context, String userId, String username, String email,
                                          String sessionToken, long expiresAt) {
        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            throw new IllegalArgumentException("账号登录响应缺少 session_token");
        }
        SharedPreferences p = prefs(context);
        boolean accountChanged = !p.getString(KEY_USER_ID, "").isEmpty()
                && !p.getString(KEY_USER_ID, "").equals(value(userId));
        SharedPreferences.Editor editor = p.edit()
                .putString(KEY_USER_ID, value(userId))
                .putString(KEY_USERNAME, value(username))
                .putString(KEY_EMAIL, value(email))
                .putString(KEY_SESSION_TOKEN, sessionToken.trim())
                .putLong(KEY_SESSION_EXPIRES_AT, Math.max(0L, expiresAt))
                .remove(KEY_DEVICE_TOKEN)
                .putString(KEY_DEVICE_NAME, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                ;
        if (accountChanged) editor.remove(KEY_DEVICE_ID);
        editor.commit();
    }

    public static void saveDeviceToken(Context context, String deviceToken, String deviceName) {
        if (deviceToken == null || deviceToken.trim().isEmpty()) {
            throw new IllegalArgumentException("设备注册响应缺少 device_token");
        }
        prefs(context).edit()
                .putString(KEY_DEVICE_TOKEN, deviceToken.trim())
                .putString(KEY_DEVICE_NAME, value(deviceName))
                .commit();
    }

    public static void clearDeviceToken(Context context) {
        prefs(context).edit().remove(KEY_DEVICE_TOKEN).commit();
    }

    public static void saveLastSeq(Context context, long seq) {
        if (seq < 0L) return;
        prefs(context).edit().putLong(KEY_LAST_SEQ, seq).apply();
    }

    /** Clears credentials, cursor and account-device identity for a clean logout. */
    public static void clear(Context context) {
        prefs(context).edit()
                .remove(KEY_USER_ID).remove(KEY_USERNAME).remove(KEY_EMAIL)
                .remove(KEY_SESSION_TOKEN).remove(KEY_SESSION_EXPIRES_AT)
                .remove(KEY_DEVICE_TOKEN).remove(KEY_DEVICE_NAME).remove(KEY_DEVICE_ID).remove(KEY_LAST_SEQ)
                .commit();
    }

    public static String statusText(Context context) {
        SharedPreferences p = prefs(context);
        String session = p.getString(KEY_SESSION_TOKEN, "");
        String device = p.getString(KEY_DEVICE_TOKEN, "");
        if (session.isEmpty() && device.isEmpty()) return "账号：未登录";
        String name = p.getString(KEY_USERNAME, "");
        String mail = p.getString(KEY_EMAIL, "");
        String identity = !name.isEmpty() ? name : mail;
        if (identity.isEmpty()) identity = "已登录账号";
        return "账号：" + identity + "\n"
                + "设备同步：" + (device.isEmpty() ? "等待设备注册" : "已启用")
                + "\n最近游标：" + lastSeq(context)
                + "\n待上传：" + AccountOutboxStore.count(context);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
