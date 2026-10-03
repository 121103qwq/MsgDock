package com.xgy.lansms;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RelayHttpTest {
    @Test public void onlyUnambiguousConnectionFailuresPermitWriteReplay() {
        assertTrue(RelayHttp.definitelyNotSent(new java.net.UnknownHostException()));
        assertTrue(RelayHttp.definitelyNotSent(new java.net.ConnectException()));
        assertTrue(RelayHttp.definitelyNotSent(new java.net.NoRouteToHostException()));
        assertTrue(RelayHttp.definitelyNotSent(new javax.net.ssl.SSLHandshakeException("TLS")));
        assertFalse(RelayHttp.definitelyNotSent(new SocketTimeoutException()));
        assertFalse(RelayHttp.definitelyNotSent(new IOException()));
    }

    @Test public void oldSavedUrlUsesPrimaryWithoutChangingPathOrQuery() {
        assertArrayEquals(new String[]{RelayHttp.PRIMARY + "/v1/messages?roomId=a%2Fb&limit=20",
                RelayHttp.BACKUP + "/v1/messages?roomId=a%2Fb&limit=20"},
                RelayHttp.candidates(RelayHttp.BACKUP + "/v1/messages?roomId=a%2Fb&limit=20"));
    }

    @Test public void successfulPrimaryNeverCallsBackup() throws Exception {
        List<String> calls = new ArrayList<>();
        RelayHttp.Result result = RelayHttp.execute("GET", RelayHttp.BACKUP + "/v1/messages", url -> {
            calls.add(url); return new RelayHttp.Result(200, "ok");
        });
        assertEquals(200, result.status);
        assertEquals(List.of(RelayHttp.PRIMARY + "/v1/messages"), calls);
    }

    @Test public void readTimeoutFallsBackAndNextRequestStartsAtPrimaryAgain() throws Exception {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) RelayHttp.execute("GET", RelayHttp.PRIMARY + "/api/v1/messages?after=42", url -> {
            calls.add(url);
            if (url.startsWith(RelayHttp.PRIMARY)) throw new SocketTimeoutException();
            return new RelayHttp.Result(200, "ok");
        });
        assertEquals(4, calls.size());
        assertEquals(calls.get(0), calls.get(2));
        assertEquals(calls.get(1), calls.get(3));
        assertTrue(calls.get(1).startsWith(RelayHttp.BACKUP));
    }

    @Test public void deduplicatedSmsUploadCanFailOverAfterDispatch() throws Exception {
        for (String path : List.of("/v1/messages", "/api/v1/messages", "/v1/ack")) {
            List<String> calls = new ArrayList<>();
            RelayHttp.Result result = RelayHttp.execute("POST", RelayHttp.PRIMARY + path, url -> {
                calls.add(url);
                if (calls.size() == 1) throw new SocketTimeoutException();
                return new RelayHttp.Result(201, "saved");
            });
            assertEquals(201, result.status);
            assertEquals(2, calls.size());
        }
    }

    @Test public void transientServerFailureFallsBackForReads() throws Exception {
        for (int status : new int[]{408, 500, 502, 503, 504}) {
            List<String> calls = new ArrayList<>();
            RelayHttp.Result result = RelayHttp.execute("GET", RelayHttp.PRIMARY + "/v1/device/backup", url -> {
                calls.add(url); return new RelayHttp.Result(calls.size() == 1 ? status : 200, "");
            });
            assertEquals(200, result.status);
            assertEquals(2, calls.size());
        }
    }

    @Test public void authRateLimitConflictAndRedirectAreNotRetried() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 400, 401, 403, 404, 409, 429}) {
            List<String> calls = new ArrayList<>();
            assertEquals(status, RelayHttp.execute("GET", RelayHttp.PRIMARY + "/api/v1/messages", url -> {
                calls.add(url); return new RelayHttp.Result(status, "");
            }).status);
            assertEquals(1, calls.size());
        }
    }

    @Test public void accountAndPairingCanFailOverBeforeSending() throws Exception {
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/devices", "/v1/pair/start")) {
            List<String> calls = new ArrayList<>();
            RelayHttp.execute("POST", RelayHttp.PRIMARY + path, url -> {
                calls.add(url);
                if (calls.size() == 1) throw new RelayHttp.BeforeSendException(new IOException("DNS"));
                return new RelayHttp.Result(200, "ok");
            });
            assertEquals(2, calls.size());
        }
    }

    @Test public void uncertainNonIdempotentWritesAreNotReplayed() throws Exception {
        for (String path : List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/devices", "/v1/pair/start", "/v1/pair/finish")) {
            List<String> calls = new ArrayList<>();
            try {
                RelayHttp.execute("POST", RelayHttp.PRIMARY + path, url -> {
                    calls.add(url); throw new SocketTimeoutException("response lost");
                });
                fail("Must preserve uncertain result");
            } catch (SocketTimeoutException expected) { assertEquals(1, calls.size()); }
        }
    }

    @Test public void serverErrorDoesNotReplayRegistration() throws Exception {
        List<String> calls = new ArrayList<>();
        assertEquals(502, RelayHttp.execute("POST", RelayHttp.PRIMARY + "/api/v1/auth/register", url -> {
            calls.add(url); return new RelayHttp.Result(502, "");
        }).status);
        assertEquals(1, calls.size());
    }

    @Test public void customHostsNeverReceiveOfficialFallback() throws Exception {
        for (String url : List.of("https://custom.example/v1/messages", RelayHttp.PRIMARY + ".evil.example/v1/messages",
                "https://msgdock.dpdns.org:8443/v1/messages")) {
            assertArrayEquals(new String[]{url}, RelayHttp.candidates(url));
            List<String> calls = new ArrayList<>();
            assertEquals(503, RelayHttp.execute("GET", url, candidate -> {
                calls.add(candidate); return new RelayHttp.Result(503, "");
            }).status);
            assertEquals(1, calls.size());
        }
    }

    @Test public void invalidAndInsecureOriginsRejectedBeforeSending() {
        for (String url : List.of("http://msgdock.dpdns.org/v1/messages", "https://user:pass@msgdock.dpdns.org/", RelayHttp.PRIMARY + "/#fragment")) {
            try { RelayHttp.candidates(url); fail(url); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void twoFailuresStopAfterBackup() throws Exception {
        List<String> calls = new ArrayList<>();
        try {
            RelayHttp.execute("GET", RelayHttp.PRIMARY + "/api/v1/messages", url -> {
                calls.add(url); throw new IOException("offline");
            });
            fail("must return to existing retry queue");
        } catch (IOException expected) { assertEquals(2, calls.size()); }
    }
}
