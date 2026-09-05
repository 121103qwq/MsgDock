package com.xgy.lansms;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Durable account-message queue. It is independent from the legacy E2EE outbox. */
public final class AccountOutboxStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "msgdock-account-outbox.json";
    private static final long[] RETRY_DELAYS_MS = {
            2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 5L * 60L * 1_000L
    };

    private AccountOutboxStore() {}

    /** Enqueues exactly one logical SMS. Duplicate client IDs are ignored. */
    public static void enqueue(Context context, String clientMessageId, String sender,
                               String body, long receivedAt) throws Exception {
        if (clientMessageId == null || clientMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("client_message_id 不能为空");
        }
        synchronized (LOCK) {
            List<Entry> entries = readLocked(context);
            if (containsId(entries, clientMessageId)) return;
            entries.add(new Entry(clientMessageId.trim(), value(sender), value(body), receivedAt,
                    0, 0L, ""));
            if (!writeLocked(context, entries)) throw new IOException("账号 outbox 写入失败");
        }
    }

    public static List<Entry> due(Context context, long now, int limit) {
        synchronized (LOCK) {
            List<Entry> result = new ArrayList<>();
            try {
                for (Entry entry : readLocked(context)) {
                    if (entry.nextAttemptAt <= now && result.size() < Math.max(0, limit)) result.add(entry);
                }
            } catch (OutboxCorruptException ignored) {
                // Keep a damaged file intact; count() below will request retry.
            }
            return result;
        }
    }

    public static void remove(Context context, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                List<Entry> entries = readLocked(context);
                boolean changed = entries.removeIf(entry -> clientMessageId.equals(entry.clientMessageId));
                if (changed && !writeLocked(context, entries)) {
                    throw new IOException("账号 outbox 更新失败");
                }
            } catch (OutboxCorruptException ignored) {
                // Never replace a damaged queue with an empty one.
            } catch (Exception e) {
                android.util.Log.w("MsgDock", "账号 outbox 删除失败", e);
            }
        }
    }

    public static void markFailure(Context context, String clientMessageId, String error) {
        if (clientMessageId == null || clientMessageId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                List<Entry> entries = readLocked(context);
                for (Entry entry : entries) {
                    if (clientMessageId.equals(entry.clientMessageId)) {
                        entry.attempts++;
                        entry.nextAttemptAt = System.currentTimeMillis() + retryDelayMs(entry.attempts);
                        entry.lastError = error == null || error.isEmpty() ? "unknown error" : error;
                        break;
                    }
                }
                if (!writeLocked(context, entries)) throw new IOException("账号 outbox 更新失败");
            } catch (OutboxCorruptException ignored) {
                // Keep the damaged queue for later/manual recovery.
            } catch (Exception e) {
                android.util.Log.w("MsgDock", "账号 outbox 重试状态保存失败", e);
            }
        }
    }

    /** Exact backoff requested by the account-sync contract, capped at 5 minutes. */
    static long retryDelayMs(int attempts) {
        if (attempts <= 0) return 0L;
        return RETRY_DELAYS_MS[Math.min(attempts, RETRY_DELAYS_MS.length) - 1];
    }

    static JSONObject payload(String clientMessageId, String sender, String body, long receivedAt)
            throws Exception {
        return new JSONObject()
                .put("client_message_id", value(clientMessageId))
                .put("sender", value(sender))
                .put("body", value(body))
                .put("received_at", receivedAt);
    }

    static boolean containsId(List<Entry> entries, String clientMessageId) {
        if (entries == null || clientMessageId == null) return false;
        for (Entry entry : entries) {
            if (entry != null && clientMessageId.equals(entry.clientMessageId)) return true;
        }
        return false;
    }

    public static int count(Context context) {
        synchronized (LOCK) {
            try {
                return readLocked(context).size();
            } catch (OutboxCorruptException ignored) {
                return -1;
            }
        }
    }

    /** Returns the earliest persisted retry deadline, or -1 when unavailable/empty. */
    static long nextAttemptAt(Context context) {
        synchronized (LOCK) {
            try {
                List<Entry> entries = readLocked(context);
                if (entries.isEmpty()) return -1L;
                long next = Long.MAX_VALUE;
                for (Entry entry : entries) next = Math.min(next, entry.nextAttemptAt);
                return next == Long.MAX_VALUE ? -1L : next;
            } catch (OutboxCorruptException ignored) {
                return -1L;
            }
        }
    }

    /** Explicit logout may clear messages that were waiting for the old account. */
    public static void clear(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            if (!file.isFile()) return;
            if (!file.delete()) android.util.Log.w("MsgDock", "账号 outbox 清理失败");
        }
    }

    private static List<Entry> readLocked(Context context) {
        List<Entry> result = new ArrayList<>();
        File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!file.isFile()) return result;
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
            JSONArray array = new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) throw new IOException("第 " + i + " 项不是对象");
                String id = item.optString("client_message_id", "").trim();
                if (id.isEmpty()) throw new IOException("第 " + i + " 项缺少 client_message_id");
                int attempts = item.optInt("attempts", 0);
                long nextAttemptAt = item.optLong("next_attempt_at", 0L);
                if (attempts < 0 || nextAttemptAt < 0L) throw new IOException("账号 outbox 重试状态无效");
                result.add(new Entry(id, item.optString("sender", ""), item.optString("body", ""),
                        item.optLong("received_at", 0L), attempts, nextAttemptAt,
                        item.optString("last_error", "")));
            }
        } catch (Exception e) {
            throw new OutboxCorruptException("账号 outbox 读取失败，原文件已保留", e);
        }
        return result;
    }

    private static boolean writeLocked(Context context, List<Entry> entries) {
        File dir = context.getApplicationContext().getFilesDir();
        File file = new File(dir, FILE_NAME);
        File temp = new File(dir, FILE_NAME + ".tmp");
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(payload(entry.clientMessageId, entry.sender, entry.body, entry.receivedAt)
                        .put("attempts", entry.attempts)
                        .put("next_attempt_at", entry.nextAttemptAt)
                        .put("last_error", value(entry.lastError)));
            }
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(array.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
                temp.delete();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public static final class Entry {
        public final String clientMessageId;
        public final String sender;
        public final String body;
        public final long receivedAt;
        public int attempts;
        public long nextAttemptAt;
        public String lastError;

        Entry(String clientMessageId, String sender, String body, long receivedAt, int attempts,
              long nextAttemptAt, String lastError) {
            this.clientMessageId = clientMessageId;
            this.sender = sender;
            this.body = body;
            this.receivedAt = receivedAt;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.lastError = lastError;
        }
    }

    public static final class OutboxCorruptException extends RuntimeException {
        OutboxCorruptException(String message, Throwable cause) { super(message, cause); }
    }
}
