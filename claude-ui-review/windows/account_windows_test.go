//go:build windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAccountLoginDeviceRegistrationAndCursor(t *testing.T) {
	var messageRequests int
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-MsgDock-Client") != "native" {
			t.Errorf("native client header = %q", r.Header.Get("X-MsgDock-Client"))
		}
		switch r.URL.Path {
		case "/api/v1/auth/login":
			if got := r.Header.Get("Authorization"); got != "" {
				t.Errorf("login authorization = %q", got)
			}
			var payload map[string]string
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Errorf("decode login payload: %v", err)
				http.Error(w, "bad request", http.StatusBadRequest)
				return
			}
			if payload["identifier"] != "demo@example.com" || payload["password"] != "secret" {
				t.Errorf("login payload = %#v", payload)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user":          map[string]string{"id": "user-1", "username": "demo", "email": "demo@example.com"},
				"session_token": "session-1",
				"expires_at":    1787400000000,
			})
		case "/api/v1/devices":
			if r.Method == http.MethodPost {
				if got := r.Header.Get("Authorization"); got != "Bearer session-1" {
					t.Errorf("device registration authorization = %q", got)
				}
				var payload map[string]string
				if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
					t.Errorf("decode device payload: %v", err)
					http.Error(w, "bad request", http.StatusBadRequest)
					return
				}
				if payload["type"] != "windows" || payload["name"] == "" {
					t.Errorf("device registration payload = %#v", payload)
				}
				_ = json.NewEncoder(w).Encode(map[string]any{
					"device":       map[string]any{"id": "device-1", "name": "Dorm-PC", "type": "windows", "last_seen_at": 1787400000000},
					"device_token": "device-token-1",
				})
				return
			}
			if r.Method == http.MethodGet {
				if got := r.Header.Get("Authorization"); got != "Bearer session-1" {
					t.Errorf("device list authorization = %q", got)
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"devices": []any{
					map[string]any{"id": "device-1", "name": "Dorm-PC", "type": "windows", "last_seen_at": 1787400000000},
				}})
				return
			}
		case "/api/v1/messages":
			messageRequests++
			if got := r.Header.Get("Authorization"); got != "Bearer device-token-1" {
				t.Errorf("message authorization = %q", got)
			}
			if messageRequests == 1 && r.URL.Query().Get("after") != "0" {
				t.Errorf("first after cursor = %q", r.URL.Query().Get("after"))
			}
			if messageRequests > 1 && r.URL.Query().Get("after") != "42" {
				t.Errorf("second after cursor = %q", r.URL.Query().Get("after"))
			}
			messages := []any(nil)
			if messageRequests == 1 {
				messages = []any{map[string]any{
					"seq":               42,
					"client_message_id": "00000000-0000-4000-8000-000000000042",
					"sender":            "10086",
					"body":              "验证码 583921",
					"received_at":       1787400001000,
					"created_at":        1787400001000,
					"source_device":     map[string]string{"id": "phone-1", "name": "K70 Ultra", "type": "android"},
				}}
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"messages": messages, "next_seq": 42})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	app := &App{dir: t.TempDir(), cfg: Config{PairCode: "246810", RelayURL: defaultRelayURL, Account: AccountCredentials{APIURL: server.URL}}, seenIDs: make(map[string]struct{})}
	client := newAccountClient(app)
	client.httpClient = server.Client()
	pushed := 0
	installTestNotificationWorker(t, app, func(SMS) error {
		pushed++
		return nil
	})

	if err := client.login(context.Background(), "demo@example.com", "secret"); err != nil {
		t.Fatal(err)
	}
	credentials := app.accountCredentials()
	if credentials.UserID != "user-1" || credentials.SessionToken != "session-1" || credentials.DeviceID != "device-1" || credentials.DeviceToken != "device-token-1" {
		t.Fatalf("account credentials = %#v", credentials)
	}
	if err := client.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := app.accountCredentials().LastSeq; got != 42 {
		t.Fatalf("last_seq = %d, want 42", got)
	}
	if pushed != 1 || len(app.recentSnapshot()) != 1 {
		t.Fatalf("push/history = %d/%d, want 1/1", pushed, len(app.recentSnapshot()))
	}
	if err := client.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if pushed != 1 || len(app.recentSnapshot()) != 1 {
		t.Fatalf("duplicate push/history = %d/%d, want 1/1", pushed, len(app.recentSnapshot()))
	}
	configBytes, err := os.ReadFile(filepath.Join(app.dir, "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(configBytes), `"last_seq": 42`) {
		t.Fatalf("config did not persist account cursor: %s", configBytes)
	}
}

func TestAccountCursorStaysWhenToastFails(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/messages" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": []any{map[string]any{
			"seq":               7,
			"client_message_id": "message-7",
			"sender":            "95555",
			"body":              "快递已到达",
			"received_at":       1787400001000,
		}}, "next_seq": 7})
	}))
	defer server.Close()
	app := &App{dir: t.TempDir(), cfg: Config{Account: AccountCredentials{APIURL: server.URL, UserID: "user-1", DeviceID: "device-1", DeviceToken: "device-token-1"}}, seenIDs: make(map[string]struct{})}
	client := newAccountClient(app)
	client.httpClient = server.Client()
	installTestNotificationWorker(t, app, func(SMS) error { return errors.New("toast unavailable") })
	if err := client.pollOnce(context.Background()); err == nil {
		t.Fatal("toast failure unexpectedly advanced account cursor")
	}
	if got := app.accountCredentials().LastSeq; got != 0 {
		t.Fatalf("last_seq after toast failure = %d, want 0", got)
	}
	pending, err := app.pendingNotificationsSnapshot()
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 || pending[0].NotifiedAt != 0 {
		t.Fatalf("pending after toast failure = %#v", pending)
	}
	if !app.hasHistoryID("message-7") {
		t.Fatal("message was not persisted before toast failure")
	}
}

func TestAccountUnauthorizedDoesNotTouchLegacyCloudOrLAN(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "expired", http.StatusUnauthorized)
	}))
	defer server.Close()
	oldCloud := CloudCredentials{RoomID: "legacy-room", DeviceID: "legacy-device", Token: "legacy-token"}
	app := &App{dir: t.TempDir(), cfg: Config{PairCode: "135790", Cloud: oldCloud, Account: AccountCredentials{APIURL: server.URL, UserID: "user-1", DeviceID: "device-1", DeviceToken: "expired-token", LastSeq: 9}}, seenIDs: make(map[string]struct{})}
	client := newAccountClient(app)
	client.httpClient = server.Client()
	if err := client.pollOnce(context.Background()); !isAccountUnauthorized(err) {
		t.Fatalf("unauthorized error = %v", err)
	}
	got := app.accountCredentials()
	if got.DeviceToken != "" || got.LastSeq != 9 {
		t.Fatalf("account state after 401 = %#v", got)
	}
	if app.pairCode() != "135790" || app.cloudCredentials() != oldCloud {
		t.Fatalf("legacy state changed after account 401: pair=%q cloud=%#v", app.pairCode(), app.cloudCredentials())
	}
	if snapshot := client.snapshot(); snapshot.State != "需要重新登录" {
		t.Fatalf("401 status = %#v", snapshot)
	}
}

func TestAccountMessageSharesUUIDDeduplicationWithLAN(t *testing.T) {
	const messageID = "00000000-0000-4000-8000-000000000099"
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/messages" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": []any{map[string]any{
			"seq":               1,
			"client_message_id": messageID,
			"sender":            "10086",
			"body":              "验证码 123456",
			"received_at":       1787400001000,
		}}, "next_seq": 1})
	}))
	defer server.Close()
	app := &App{dir: t.TempDir(), cfg: Config{PairCode: "123456", Account: AccountCredentials{APIURL: server.URL, UserID: "user-1", DeviceID: "device-1", DeviceToken: "device-token-1"}}, seenIDs: make(map[string]struct{})}
	pushed := 0
	installTestNotificationWorker(t, app, func(SMS) error {
		pushed++
		return nil
	})
	lan := SMS{ID: messageID, From: "10086", Text: "验证码 123456", ReceivedAt: 1787400001000, Device: "LAN phone"}
	if err := app.processLANMessage(lan); err != nil {
		t.Fatal(err)
	}
	client := newAccountClient(app)
	client.httpClient = server.Client()
	if err := client.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if pushed != 1 || len(app.recentSnapshot()) != 1 || app.accountCredentials().LastSeq != 1 {
		t.Fatalf("same UUID account delivery = pushes:%d history:%d last_seq:%d", pushed, len(app.recentSnapshot()), app.accountCredentials().LastSeq)
	}
	if pending, err := app.pendingNotificationsSnapshot(); err != nil {
		t.Fatal(err)
	} else if len(pending) != 1 || pending[0].Source != "lan" {
		t.Fatalf("LAN pending ledger was removed or changed: %#v", pending)
	}
}

func TestAccountDeviceRemovalClearsOnlyLocalDeviceToken(t *testing.T) {
	var removed bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/devices/device-1" && r.Method == http.MethodDelete {
			if r.Header.Get("Authorization") != "Bearer session-1" {
				t.Errorf("device removal authorization = %q", r.Header.Get("Authorization"))
			}
			removed = true
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if r.URL.Path == "/api/v1/devices" && r.Method == http.MethodGet {
			_ = json.NewEncoder(w).Encode(map[string]any{"devices": []any{}})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()
	app := &App{dir: t.TempDir(), cfg: Config{PairCode: "123456", Cloud: CloudCredentials{RoomID: "legacy-room"}, Account: AccountCredentials{APIURL: server.URL, UserID: "user-1", SessionToken: "session-1", DeviceID: "device-1", DeviceToken: "device-token-1", LastSeq: 8}}, seenIDs: make(map[string]struct{})}
	client := newAccountClient(app)
	client.httpClient = server.Client()
	if err := client.removeDevice(context.Background(), "device-1"); err != nil {
		t.Fatal(err)
	}
	if !removed {
		t.Fatal("device removal request was not sent")
	}
	credentials := app.accountCredentials()
	if credentials.SessionToken != "session-1" || credentials.DeviceID != "" || credentials.DeviceToken != "" || credentials.LastSeq != 8 {
		t.Fatalf("credentials after device removal = %#v", credentials)
	}
	if app.cloudCredentials().RoomID != "legacy-room" {
		t.Fatal("legacy cloud credentials changed during device removal")
	}
}

func TestAccountAPIURLRejectsUnsafeForms(t *testing.T) {
	for _, raw := range []string{"http://example.test", "https://user:pass@example.test", "https://example.test/api", "https://example.test/?token=secret"} {
		if _, err := parseAccountAPIURL(raw); err == nil {
			t.Fatalf("parseAccountAPIURL(%q) unexpectedly succeeded", raw)
		}
	}
	if parsed, err := parseAccountAPIURL(defaultAccountAPIURL); err != nil || parsed.String() != defaultAccountAPIURL {
		t.Fatalf("default API URL = %v / %v", parsed, err)
	}
}

func TestAccountErrorBackoffIsBounded(t *testing.T) {
	if got := nextAccountErrorDelay(accountPollInterval); got != 2*accountPollInterval {
		t.Fatalf("first account backoff = %s, want %s", got, 2*accountPollInterval)
	}
	if got := nextAccountErrorDelay(accountMaxBackoff); got != accountMaxBackoff {
		t.Fatalf("bounded account backoff = %s, want %s", got, accountMaxBackoff)
	}
	if got := nextAccountErrorDelay(time.Second); got != accountPollInterval {
		t.Fatalf("short account backoff = %s, want %s", got, accountPollInterval)
	}
}
