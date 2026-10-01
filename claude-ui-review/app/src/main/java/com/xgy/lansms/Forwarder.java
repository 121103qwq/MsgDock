package com.xgy.lansms;

import android.content.Context;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Forwarder {
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private Forwarder() {}

    public static void forward(Context c, String from, String text, long receivedAt, int sim) {
        forward(c, UUID.randomUUID().toString(), from, text, receivedAt, sim);
    }

    /** UI/test entry point: LAN I/O, encryption, and outbox fsync stay off the main thread. */
    public static void forwardAsync(Context c, String id, String from, String text, long receivedAt, int sim) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> forward(app, id, from, text, receivedAt, sim));
    }

    /** One id is shared by LAN and cloud so the receiver can de-duplicate both paths. */
    public static void forward(Context c, String id, String from, String text, long receivedAt, int sim) {
        Context app = c.getApplicationContext();
        String messageId = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        String device = android.os.Build.MODEL;

        // The account path is independent from legacy /v1 pairing. Persist it
        // before starting any network work so a process kill cannot lose a
        // message that was meant for the MsgDock history.
        boolean accountQueued = false;
        if (AccountStore.hasAccount(app)) {
            try {
                AccountOutboxStore.enqueue(app, messageId, from, text, receivedAt);
                accountQueued = true;
            } catch (Exception e) {
                android.util.Log.w("MsgDock", "账号 outbox 写入失败；继续 LAN/旧云端转发", e);
            }
        }

        List<TargetStore.Target> targets = TargetStore.load(app);
        for (TargetStore.Target t : targets) {
            EXEC.execute(() -> send(app, t, messageId, from, text, receivedAt, sim, device));
        }
        if (accountQueued) {
            // Start the current account path before touching the legacy relay;
            // the two network paths must not serialize one another.
            try {
                CloudSyncJobService.schedule(app);
            } catch (RuntimeException e) {
                android.util.Log.w("MsgDock", "账号 JobScheduler 安排失败；进程内重试仍继续", e);
            }
            AccountApi.scheduleOutboxFlush(app);
            EXEC.execute(() -> AccountApi.flushOutbox(app));
        }
        // Keep the old E2EE path intact. It has its own durable encrypted outbox.
        // A malformed legacy link must not prevent the independent account queue
        // from being flushed.
        try {
            CloudRelay.enqueueSms(app, messageId, from, text, receivedAt, sim, device);
        } catch (RuntimeException e) {
            android.util.Log.w("XgyLanSms", "旧云端入队失败；账号云端仍继续", e);
        }
    }

    public static void send(Context c, TargetStore.Target t, String from, String text, long receivedAt, int sim, String device) {
        send(c, t, UUID.randomUUID().toString(), from, text, receivedAt, sim, device);
    }

    public static void send(Context c, TargetStore.Target t, String id, String from, String text, long receivedAt, int sim, String device) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("id", id == null || id.isEmpty() ? UUID.randomUUID().toString() : id);
            body.put("from", from == null ? "Unknown" : from);
            body.put("text", text == null ? "" : text);
            body.put("receivedAt", receivedAt);
            body.put("sim", sim);
            body.put("device", device == null ? "Android" : device);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("http", t.host, t.port, "/sms");
            conn = (HttpURLConnection) LanNet.open(c, url);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Xgy-Key", t.code);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                android.util.Log.w("XgyLanSms", "Receiver returned HTTP " + status + " for " + t.label());
            }
        } catch (Exception e) {
            android.util.Log.w("XgyLanSms", "Forward failed: " + t.label(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
