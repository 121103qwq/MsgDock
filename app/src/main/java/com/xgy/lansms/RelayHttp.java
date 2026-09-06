package com.xgy.lansms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import javax.net.ssl.SSLHandshakeException;

/** Shared HTTPS transport for the account API, encrypted relay and device backup. */
final class RelayHttp {
    static final String PRIMARY = "https://msgdock.dpdns.org";
    static final String BACKUP = "https://xgy-sms-relay.xgy2021sh.workers.dev";

    static final class Result {
        final int status;
        final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
    }

    // Package-private seam for deterministic failure tests; no runtime adapter state.
    interface Attempt { Result send(String url) throws IOException; }

    static Result request(String method, String url, JSONObject body, String token,
                          boolean nativeAuth) throws IOException {
        byte[] data = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return execute(method, url, candidate -> send(method, candidate, data, token, nativeAuth));
    }

    static Result execute(String method, String url, Attempt attempt) throws IOException {
        String[] endpoints = candidates(url);
        boolean replaySafe = replaySafe(method, URI.create(url).getPath());
        try {
            Result first = attempt.send(endpoints[0]);
            if (endpoints.length == 1 || !replaySafe || !transientStatus(first.status)) return first;
        } catch (IOException failure) {
            if (endpoints.length == 1 || (!replaySafe && !(failure instanceof BeforeSendException))) throw failure;
        }
        return attempt.send(endpoints[1]);
    }

    static String[] candidates(String url) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Relay URL 必须是无凭据的 HTTPS 地址");
        }
        boolean official = (uri.getPort() == -1 || uri.getPort() == 443)
                && ("msgdock.dpdns.org".equalsIgnoreCase(uri.getHost())
                || "xgy-sms-relay.xgy2021sh.workers.dev".equalsIgnoreCase(uri.getHost()));
        // Historical custom relays retain their own credentials and destination.
        if (!official) return new String[]{url};
        String suffix = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return new String[]{PRIMARY + suffix, BACKUP + suffix};
    }

    static boolean replaySafe(String method, String path) {
        return "GET".equals(method) || "HEAD".equals(method)
                || ("POST".equals(method) && ("/api/v1/messages".equals(path)
                || "/v1/messages".equals(path) || "/v1/ack".equals(path)));
    }

    private static boolean transientStatus(int status) {
        return status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static final class BeforeSendException extends IOException {
        BeforeSendException(IOException cause) { super("Relay 连接失败", cause); }
    }

    static boolean definitelyNotSent(IOException failure) {
        // A generic timeout from connect() is not proof: implementations may send headers there.
        return failure instanceof UnknownHostException || failure instanceof ConnectException
                || failure instanceof NoRouteToHostException || failure instanceof SSLHandshakeException;
    }

    private static Result send(String method, String url, byte[] data, String token,
                               boolean nativeAuth) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false); // Never forward credentials to a redirect target.
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            if (nativeAuth) connection.setRequestProperty("X-MsgDock-Client", "native");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (data != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
            }
            // Only unambiguous DNS/TCP/TLS setup failures permit replay of account/pair creation.
            try { connection.connect(); }
            catch (IOException failure) {
                if (definitelyNotSent(failure)) throw new BeforeSendException(failure);
                throw failure;
            }
            if (data != null) {
                try (OutputStream output = connection.getOutputStream()) { output.write(data); }
            }
            int status = connection.getResponseCode();
            return new Result(status, readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream()));
        } finally { connection.disconnect(); }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 2 * 1024 * 1024) throw new IOException("Relay 响应超过大小限制");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
