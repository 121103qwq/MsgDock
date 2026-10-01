package com.xgy.lansms;

import android.content.Context;

/**
 * Remembers when any sync path last worked, for the home-screen status only.
 * Writes are throttled to one per minute so a 3-second poll loop never turns
 * into a 3-second disk write; the UI shows minute granularity anyway.
 */
final class SyncClock {
    static final String KEY_LAST_OK = "last_sync_ok_at";
    static final String KEY_LAN_OK = "lan_last_ok_at";
    static final String KEY_LAN_FAIL = "lan_last_fail_at";
    static final String KEY_LAN_FAIL_TARGET = "lan_last_fail_target";
    static final long WRITE_INTERVAL_MS = 60_000L;
    private static volatile long lastWritten;
    private static volatile long lastLanOkWritten;

    private SyncClock() {}

    static void markSuccess(Context context) {
        long now = System.currentTimeMillis();
        if (!shouldWrite(lastWritten, now)) return;
        lastWritten = now;
        TargetStore.prefs(context.getApplicationContext()).edit().putLong(KEY_LAST_OK, now).apply();
    }

    static void markLan(Context context, boolean ok, String target) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (ok) {
            markSuccess(app);
            // Only a recovery after a failure needs an immediate write; otherwise throttle.
            boolean recovering = TargetStore.prefs(app).getLong(KEY_LAN_FAIL, 0L)
                    > TargetStore.prefs(app).getLong(KEY_LAN_OK, 0L);
            if (!recovering && !shouldWrite(lastLanOkWritten, now)) return;
            lastLanOkWritten = now;
            TargetStore.prefs(app).edit().putLong(KEY_LAN_OK, now).apply();
        } else {
            TargetStore.prefs(app).edit().putLong(KEY_LAN_FAIL, now)
                    .putString(KEY_LAN_FAIL_TARGET, target == null ? "" : target).apply();
        }
    }

    static boolean shouldWrite(long previous, long now) {
        return previous <= 0L || now - previous >= WRITE_INTERVAL_MS || now < previous;
    }

    static long lastSuccess(Context context) {
        return Math.max(lastWritten, TargetStore.prefs(context.getApplicationContext()).getLong(KEY_LAST_OK, 0L));
    }

    /** Name of the LAN receiver whose latest attempt failed, or empty when LAN is fine. */
    static String lanProblem(Context context) {
        android.content.SharedPreferences p = TargetStore.prefs(context.getApplicationContext());
        long fail = p.getLong(KEY_LAN_FAIL, 0L);
        if (fail <= 0L || fail <= p.getLong(KEY_LAN_OK, 0L)) return "";
        String target = p.getString(KEY_LAN_FAIL_TARGET, "");
        return target.isEmpty() ? "局域网接收端" : target;
    }
}
