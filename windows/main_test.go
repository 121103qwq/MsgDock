//go:build windows

package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestExtractVerificationCode(t *testing.T) {
	tests := []struct {
		name string
		text string
		want string
	}{
		{name: "six digits", text: "【示例】验证码 583921，5 分钟内有效", want: "583921"},
		{name: "prefer six digits", text: "订单 1234，验证码 654321", want: "654321"},
		{name: "four digits", text: "动态码 9021", want: "9021"},
		{name: "ignore phone number", text: "请联系 13800138000", want: ""},
		{name: "none", text: "没有验证码", want: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := extractVerificationCode(test.text); got != test.want {
				t.Fatalf("extractVerificationCode() = %q, want %q", got, test.want)
			}
		})
	}
}

func TestToastValueRoundTrip(t *testing.T) {
	want := "【银行】验证码 583921\n请勿泄露"
	got, err := decodeToastValue(encodeToastValue(want))
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("round trip = %q, want %q", got, want)
	}
}

func TestSMSNotificationUsesNormalQuietScenario(t *testing.T) {
	xml := buildSMSNotificationXML(SMS{From: "10086", Text: "验证码 919191"})
	if strings.Contains(xml, `scenario=`) || strings.Contains(xml, `duration="long"`) || !strings.Contains(xml, `silent="true"`) {
		t.Fatalf("notification should be ordinary, short and quiet: %s", xml)
	}
	if !strings.Contains(xml, "复制验证码") || !strings.Contains(xml, "复制全文") {
		t.Fatalf("notification actions missing: %s", xml)
	}
}

func TestSMSHandlerProtocolCompatibility(t *testing.T) {
	app := &App{cfg: Config{PairCode: "123456"}, dir: t.TempDir()}
	installTestNotificationWorker(t, app, func(SMS) error { return nil })
	body := `{"from":"10086","text":"验证码 583921","receivedAt":1787400001000,"sim":1,"device":"Xiaomi"}`
	req := httptest.NewRequest(http.MethodPost, "/sms", strings.NewReader(body))
	req.Header.Set("X-Xgy-Key", "123456")
	recorder := httptest.NewRecorder()

	app.handler().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %q", recorder.Code, recorder.Body.String())
	}
	if recorder.Body.String() != "ok" {
		t.Fatalf("body = %q", recorder.Body.String())
	}
	recent := app.recentSnapshot()
	if len(recent) != 1 || recent[0].From != "10086" || recent[0].Text != "验证码 583921" {
		t.Fatalf("recent = %#v", recent)
	}
	history, err := os.ReadFile(app.historyPath())
	if err != nil {
		t.Fatal(err)
	}
	var persisted SMS
	if err := json.Unmarshal([]byte(strings.TrimSpace(string(history))), &persisted); err != nil {
		t.Fatal(err)
	}
	if persisted != recent[0] {
		t.Fatalf("persisted = %#v, recent = %#v", persisted, recent[0])
	}
}

func TestSMSHandlerDeduplicatesOptionalMessageID(t *testing.T) {
	app := &App{cfg: Config{PairCode: "123456"}, dir: t.TempDir(), seenIDs: make(map[string]struct{})}
	installTestNotificationWorker(t, app, func(SMS) error { return nil })
	body := `{"id":"same-message","from":"10086","text":"验证码 583921","receivedAt":1787400001000,"sim":1,"device":"Xiaomi"}`
	for attempt := 0; attempt < 2; attempt++ {
		req := httptest.NewRequest(http.MethodPost, "/sms", strings.NewReader(body))
		req.Header.Set("X-Xgy-Key", "123456")
		recorder := httptest.NewRecorder()
		app.handler().ServeHTTP(recorder, req)
		if recorder.Code != http.StatusOK || recorder.Body.String() != "ok" {
			t.Fatalf("attempt %d: status=%d body=%q", attempt+1, recorder.Code, recorder.Body.String())
		}
	}
	if got := app.recentSnapshot(); len(got) != 1 || got[0].ID != "same-message" {
		t.Fatalf("recent history = %#v, want one message", got)
	}
	contents, err := os.ReadFile(app.historyPath())
	if err != nil {
		t.Fatal(err)
	}
	if lines := strings.Count(strings.TrimSpace(string(contents)), "\n") + 1; lines != 1 {
		t.Fatalf("history lines = %d, want 1", lines)
	}
}

func TestSMSHandlerWithoutMessageIDRemainsCompatible(t *testing.T) {
	app := &App{cfg: Config{PairCode: "123456"}, dir: t.TempDir(), seenIDs: make(map[string]struct{})}
	installTestNotificationWorker(t, app, func(SMS) error { return nil })
	for attempt := 0; attempt < 2; attempt++ {
		req := httptest.NewRequest(http.MethodPost, "/sms", strings.NewReader(`{"from":"10086","text":"legacy"}`))
		req.Header.Set("X-Xgy-Key", "123456")
		recorder := httptest.NewRecorder()
		app.handler().ServeHTTP(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("attempt %d: status=%d", attempt+1, recorder.Code)
		}
	}
	if got := len(app.recentSnapshot()); got != 2 {
		t.Fatalf("legacy history length = %d, want 2", got)
	}
}

func TestCloudPairingDoesNotOverwriteExistingCredentials(t *testing.T) {
	app := &App{cfg: Config{Cloud: CloudCredentials{
		RoomID: "room", DeviceID: "win", Token: "token",
		PeerDeviceID: "phone", PeerPublicKey: "peer", PrivateKey: "private",
	}}}
	client := newCloudClient(app)
	if err := client.startPairing(); err == nil {
		t.Fatal("startPairing unexpectedly allowed replacing existing credentials")
	}
	got := app.cloudCredentials()
	if got.RoomID != "room" || got.DeviceID != "win" || got.Token != "token" || got.PrivateKey != "private" {
		t.Fatalf("credentials were changed: %#v", got)
	}
}

func TestCloudPairingRejectsDuplicatePendingSession(t *testing.T) {
	app := &App{cfg: Config{Cloud: CloudCredentials{
		SessionID: "session", PairCode: "123456", LastPairCode: "123456", PrivateKey: "private",
	}}}
	client := newCloudClient(app)
	if err := client.startPairing(); err == nil || !strings.Contains(err.Error(), "already pending") {
		t.Fatalf("duplicate pending start error = %v", err)
	}
	got := app.cloudCredentials()
	if got.SessionID != "session" || got.PairCode != "123456" || got.LastPairCode != "123456" {
		t.Fatalf("pending credentials changed: %#v", got)
	}
}

func TestForcedCloudPairingDoesNotOverwriteBeforeRelaySuccess(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "relay unavailable", http.StatusServiceUnavailable)
	}))
	defer server.Close()
	old := CloudCredentials{
		RelayURL: server.URL, RoomID: "old-room", DeviceID: "old-win", Token: "old-token",
		PeerDeviceID: "old-phone", PeerPublicKey: "old-peer", PrivateKey: "old-private",
		LastPairCode: "654321", PairedAt: 1787400001000,
	}
	app := &App{cfg: Config{RelayURL: server.URL, Cloud: old}, dir: t.TempDir()}
	client := newCloudClient(app)
	client.httpClient = server.Client()
	if err := client.startPairingForced(); err == nil {
		t.Fatal("forced pairing unexpectedly succeeded")
	}
	if got := app.cloudCredentials(); got != old {
		t.Fatalf("failed start changed old credentials: %#v", got)
	}
}

func TestForcedCloudPairingReplacesWithPendingOnlyAfterRelaySuccess(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/pair/start" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(cloudPairStartResponse{SessionID: "new-session", Code: "246810", ExpiresAt: 1787400300000})
	}))
	defer server.Close()
	app := &App{cfg: Config{RelayURL: server.URL, Cloud: CloudCredentials{
		RelayURL: server.URL, RoomID: "old-room", DeviceID: "old-win", Token: "old-token",
		PeerDeviceID: "old-phone", PeerPublicKey: "old-peer", PrivateKey: "old-private",
		LastPairCode: "654321", PairedAt: 1787400001000,
	}}, dir: t.TempDir()}
	client := newCloudClient(app)
	client.httpClient = server.Client()
	if err := client.startPairingForced(); err != nil {
		t.Fatal(err)
	}
	got := app.cloudCredentials()
	if got.SessionID != "new-session" || got.PairCode != "246810" || got.LastPairCode != "246810" || got.PairedAt != 1787400001000 {
		t.Fatalf("pending credentials = %#v", got)
	}
	if cloudCredentialsReady(got) {
		t.Fatalf("pending credentials retained old active room: %#v", got)
	}
}

func TestCloudPairingUIStateText(t *testing.T) {
	if got := cloudPairButtonText(CloudCredentials{}); got != "开始云配对" {
		t.Fatalf("unpaired button = %q", got)
	}
	pending := CloudCredentials{SessionID: "session", PairCode: "123456", PairExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
	if got := cloudPairButtonText(pending); got != "配对进行中" || !strings.Contains(cloudPairCodeText(pending), "123 456") {
		t.Fatalf("pending UI = %q / %q", got, cloudPairCodeText(pending))
	}
	paired := CloudCredentials{RoomID: "room", DeviceID: "win", Token: "token", PeerDeviceID: "phone", PeerPublicKey: "peer", PrivateKey: "key", LastPairCode: "123456"}
	if got := cloudPairButtonText(paired); got != "重新云配对" || !strings.Contains(cloudPairCodeText(paired), "已失效") || !strings.Contains(cloudPairStatusText(paired, cloudStatusSnapshot{}), "已配对") {
		t.Fatalf("paired UI = %q / %q", got, cloudPairStatusText(paired, cloudStatusSnapshot{}))
	}
	paired.LastPairCode = ""
	if !strings.Contains(cloudPairCodeText(paired), "旧版本") {
		t.Fatalf("old config UI = %q", cloudPairCodeText(paired))
	}
}

func TestPairStatusPersistsCredentialsBeforeConfirm(t *testing.T) {
	for _, confirmFails := range []bool{false, true} {
		t.Run(map[bool]string{false: "confirm succeeds", true: "confirm failure retains credentials"}[confirmFails], func(t *testing.T) {
			confirmSeen := false
			var app *App
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/v1/pair/status":
					_ = json.NewEncoder(w).Encode(cloudPairResult{
						RoomID: "room_test", DeviceID: "win_test", Token: "token_test",
						PeerDeviceID: "phone_test", PeerPublicKey: "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E",
					})
				case "/v1/pair/confirm":
					persisted, readErr := os.ReadFile(filepath.Join(app.dir, "config.json"))
					if readErr != nil {
						t.Errorf("credentials were not persisted before confirm: %v", readErr)
					} else {
						var saved Config
						if err := json.Unmarshal(persisted, &saved); err != nil || saved.Cloud.RoomID != "room_test" || saved.Cloud.Token != "token_test" {
							t.Errorf("saved credentials before confirm = %#v, err=%v", saved.Cloud, err)
						}
					}
					if r.Header.Get("Authorization") != "Bearer token_test" {
						t.Errorf("confirm authorization = %q", r.Header.Get("Authorization"))
					}
					var body struct {
						SessionID string `json:"sessionId"`
					}
					if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
						t.Errorf("confirm body: %v", err)
					}
					if body.SessionID != "session_test" {
						t.Errorf("confirm session = %q", body.SessionID)
					}
					confirmSeen = true
					if confirmFails {
						http.Error(w, "temporary confirm failure", http.StatusInternalServerError)
						return
					}
					w.WriteHeader(http.StatusNoContent)
				default:
					http.NotFound(w, r)
				}
			}))
			defer server.Close()

			app = &App{
				cfg: Config{
					RelayURL: server.URL,
					Cloud: CloudCredentials{
						RelayURL: server.URL, SessionID: "session_test", PairCode: "123456", PrivateKey: "AQ",
					},
				},
				dir: t.TempDir(),
			}
			client := newCloudClient(app)
			client.httpClient = server.Client()
			if err := client.pollOnce(context.Background()); err != nil {
				t.Fatal(err)
			}
			if !confirmSeen {
				t.Fatal("pair confirm was not called")
			}
			credentials := app.cloudCredentials()
			if !cloudCredentialsReady(credentials) || credentials.SessionID != "" || credentials.PairCode != "" {
				t.Fatalf("credentials after pairing = %#v", credentials)
			}
			if credentials.LastPairCode != "123456" || credentials.PairedAt <= 0 {
				t.Fatalf("pair metadata after pairing = %#v", credentials)
			}
			if confirmFails && !strings.Contains(client.snapshot().Detail, "确认失败") {
				t.Fatalf("confirm failure state = %#v", client.snapshot())
			}
		})
	}
}

func TestSMSHandlerRejectsWrongPairCode(t *testing.T) {
	app := &App{cfg: Config{PairCode: "123456"}, dir: t.TempDir()}
	req := httptest.NewRequest(http.MethodPost, "/sms", strings.NewReader(`{"from":"10086","text":"test"}`))
	req.Header.Set("X-Xgy-Key", "000000")
	recorder := httptest.NewRecorder()

	app.handler().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusForbidden)
	}
	if _, err := os.Stat(app.historyPath()); !os.IsNotExist(err) {
		t.Fatalf("wrong pair code created history file: %v", err)
	}
}

func TestHealthEndpointDoesNotExposeSensitiveData(t *testing.T) {
	app := &App{cfg: Config{PairCode: "123456"}, dir: t.TempDir()}
	if err := app.persistHistory(SMS{From: "secret-sender", Text: "secret SMS body", ReceivedAt: 1000}); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	recorder := httptest.NewRecorder()
	app.handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "application/json") {
		t.Fatalf("content type = %q", contentType)
	}
	var health map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &health); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(recorder.Body.String(), "123456") || strings.Contains(recorder.Body.String(), "secret SMS body") || strings.Contains(recorder.Body.String(), "secret-sender") {
		t.Fatalf("health response exposed sensitive data: %s", recorder.Body.String())
	}
}

func TestConfigSaveBackupAndRecovery(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	first := Config{PairCode: "111111", RelayURL: defaultRelayURL}
	second := Config{PairCode: "222222", RelayURL: defaultRelayURL}
	if err := saveConfigFile(path, first); err != nil {
		t.Fatal(err)
	}
	if err := saveConfigFile(path, second); err != nil {
		t.Fatal(err)
	}
	var backup Config
	backupBytes, err := os.ReadFile(path + configBackupSuffix)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(backupBytes, &backup); err != nil {
		t.Fatal(err)
	}
	if backup.PairCode != first.PairCode {
		t.Fatalf("backup pair code = %q, want %q", backup.PairCode, first.PairCode)
	}
	if err := os.WriteFile(path, []byte("{malformed"), 0600); err != nil {
		t.Fatal(err)
	}
	recovered, usedBackup, err := loadConfigFiles(path)
	if err != nil {
		t.Fatal(err)
	}
	if !usedBackup || recovered.PairCode != first.PairCode {
		t.Fatalf("recovered config = %#v, usedBackup=%v", recovered, usedBackup)
	}
	if matches, _ := filepath.Glob(filepath.Join(filepath.Dir(path), ".xgy-config-*.tmp")); len(matches) != 0 {
		t.Fatalf("temporary config files remain: %#v", matches)
	}
}

func TestConfigLoadRejectsMalformedWithoutBackup(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	original := []byte("{malformed and must survive")
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := loadConfigFiles(path); err == nil {
		t.Fatal("malformed config unexpectedly loaded")
	}
	if got, err := os.ReadFile(path); err != nil {
		t.Fatal(err)
	} else if string(got) != string(original) {
		t.Fatalf("malformed config was overwritten: %q", got)
	}
}

func TestHistorySurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	first := &App{dir: dir}
	older := SMS{From: "10086", Text: "第一条", ReceivedAt: 1000, Device: "Phone"}
	newer := SMS{From: "95555", Text: "第二条", ReceivedAt: 2000, Device: "Phone"}
	if err := first.persistHistory(older); err != nil {
		t.Fatal(err)
	}
	if err := first.persistHistory(newer); err != nil {
		t.Fatal(err)
	}

	restarted := &App{dir: dir}
	if err := restarted.loadHistory(); err != nil {
		t.Fatal(err)
	}
	history := restarted.recentSnapshot()
	if len(history) != 2 {
		t.Fatalf("history length = %d, want 2", len(history))
	}
	if history[0] != newer || history[1] != older {
		t.Fatalf("history = %#v", history)
	}
}

func TestHistoryRepairsIncompleteTail(t *testing.T) {
	dir := t.TempDir()
	app := &App{dir: dir}
	if err := os.WriteFile(app.historyPath(), []byte(`{"from":"incomplete"`), 0600); err != nil {
		t.Fatal(err)
	}
	want := SMS{From: "10010", Text: "恢复后的新记录", ReceivedAt: 3000, Device: "Phone"}
	if err := app.persistHistory(want); err != nil {
		t.Fatal(err)
	}

	restarted := &App{dir: dir}
	if err := restarted.loadHistory(); err != nil {
		t.Fatal(err)
	}
	history := restarted.recentSnapshot()
	if len(history) != 1 || history[0] != want {
		t.Fatalf("history = %#v, want %#v", history, want)
	}
}

func TestHistoryConcurrentWrites(t *testing.T) {
	const count = 40
	app := &App{dir: t.TempDir()}
	var wait sync.WaitGroup
	errorsFound := make(chan error, count)
	for index := 0; index < count; index++ {
		wait.Add(1)
		go func(index int) {
			defer wait.Done()
			sms := SMS{From: "并发测试", Text: strings.Repeat("x", index+1), ReceivedAt: int64(index + 1), Device: "Phone"}
			if err := app.persistHistory(sms); err != nil {
				errorsFound <- err
			}
		}(index)
	}
	wait.Wait()
	close(errorsFound)
	for err := range errorsFound {
		t.Fatal(err)
	}

	restarted := &App{dir: app.dir}
	if err := restarted.loadHistory(); err != nil {
		t.Fatal(err)
	}
	if got := len(restarted.recentSnapshot()); got != count {
		t.Fatalf("history length = %d, want %d", got, count)
	}
}

func TestHistoryLargeSpecialCharacterMessage(t *testing.T) {
	app := &App{dir: t.TempDir()}
	want := SMS{
		From:       "大文本测试",
		Text:       strings.Repeat(`<>&"`, 200000),
		ReceivedAt: 4000,
		Device:     "Phone",
	}
	if err := app.persistHistory(want); err != nil {
		t.Fatal(err)
	}
	restarted := &App{dir: app.dir}
	if err := restarted.loadHistory(); err != nil {
		t.Fatal(err)
	}
	history := restarted.recentSnapshot()
	if len(history) != 1 || history[0] != want {
		t.Fatalf("large history record was not restored")
	}
}

func TestSMSHandlerReturns500WhenHistoryCannotBeWritten(t *testing.T) {
	root := t.TempDir()
	blockedDir := filepath.Join(root, "not-a-directory")
	if err := os.WriteFile(blockedDir, []byte("block"), 0600); err != nil {
		t.Fatal(err)
	}
	app := &App{cfg: Config{PairCode: "123456"}, dir: blockedDir}
	req := httptest.NewRequest(http.MethodPost, "/sms", strings.NewReader(`{"from":"10086","text":"must persist"}`))
	req.Header.Set("X-Xgy-Key", "123456")
	recorder := httptest.NewRecorder()

	app.handler().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusInternalServerError)
	}
	if history := app.recentSnapshot(); len(history) != 0 {
		t.Fatalf("failed history write leaked into memory: %#v", history)
	}
}

func TestProtocolV2CryptoVector(t *testing.T) {
	privateKey, err := decodeP256PrivateKey("AQ")
	if err != nil {
		t.Fatal(err)
	}
	peer, err := decodeP256PublicKey("BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E")
	if err != nil {
		t.Fatal(err)
	}
	key, err := deriveRoomKey(privateKey, peer, "room_test")
	if err != nil {
		t.Fatal(err)
	}
	if got := encodeBase64URL(key); got != "uyxWz213tIPGxF4JXseW9uQb4tsLvfssqpnj5LuBbSk" {
		t.Fatalf("HKDF key = %q", got)
	}
	sms, err := decryptCloudEnvelope(cloudEnvelope{
		RoomID:         "room_test",
		SenderDeviceID: "phone_test",
		TargetDeviceID: "win_test",
		ID:             "00000000-0000-4000-8000-000000000001",
		Nonce:          "AAECAwQFBgcICQoL",
		Ciphertext:     "mIvB_-dGCueU53_hyEYuBaxg4nfMGqN-IcgzkX-Xe3DsVKQ_J3QWJEDyjH9ah_k9z9NBgGKB5LkE3nxOnM7C2BFMQ6Jcop3-kXO-XQ5mTq3WTO-b_qRFDUxXDey8IPk3fprGDmIjzBtyY0ji8I9f83TN3ZDGL6mgRYxw",
	}, key)
	if err != nil {
		t.Fatal(err)
	}
	want := SMS{From: "10086", Text: "验证码 123456", ReceivedAt: 1787400001000, SIM: 1, Device: "Android Test"}
	if sms != want {
		t.Fatalf("decrypted SMS = %#v, want %#v", sms, want)
	}
}

func TestCloudPollPersistsBeforeAckAndDeduplicates(t *testing.T) {
	const messageID = "00000000-0000-4000-8000-000000000001"
	envelope := cloudEnvelope{
		RoomID:         "room_test",
		SenderDeviceID: "phone_test",
		TargetDeviceID: "win_test",
		ID:             messageID,
		CreatedAt:      1787400001000,
		Nonce:          "AAECAwQFBgcICQoL",
		Ciphertext:     "mIvB_-dGCueU53_hyEYuBaxg4nfMGqN-IcgzkX-Xe3DsVKQ_J3QWJEDyjH9ah_k9z9NBgGKB5LkE3nxOnM7C2BFMQ6Jcop3-kXO-XQ5mTq3WTO-b_qRFDUxXDey8IPk3fprGDmIjzBtyY0ji8I9f83TN3ZDGL6mgRYxw",
	}
	var mu sync.Mutex
	ackCount := 0
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer token_test" {
			t.Errorf("authorization = %q", r.Header.Get("Authorization"))
		}
		switch r.URL.Path {
		case "/v1/messages":
			if got := r.URL.Query().Get("limit"); got != "20" {
				t.Errorf("message poll limit = %q, want 20", got)
			}
			_ = json.NewEncoder(w).Encode([]cloudEnvelope{envelope})
		case "/v1/ack":
			var body struct {
				MessageIDs []string `json:"messageIds"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("ack body: %v", err)
			}
			if len(body.MessageIDs) != 1 || body.MessageIDs[0] != messageID {
				t.Errorf("ack IDs = %#v", body.MessageIDs)
			}
			mu.Lock()
			ackCount++
			mu.Unlock()
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	app := &App{
		cfg: Config{
			RelayURL: server.URL,
			Cloud: CloudCredentials{
				RoomID: "room_test", DeviceID: "win_test", Token: "token_test",
				PeerDeviceID: "phone_test", PeerPublicKey: "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E", PrivateKey: "AQ",
			},
		},
		dir:     t.TempDir(),
		seenIDs: make(map[string]struct{}),
	}
	installTestNotificationWorker(t, app, func(SMS) error { return nil })
	client := newCloudClient(app)
	client.httpClient = server.Client()
	if err := client.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := client.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := len(app.recentSnapshot()); got != 1 {
		t.Fatalf("history length = %d, want 1", got)
	}
	mu.Lock()
	defer mu.Unlock()
	if ackCount != 2 {
		t.Fatalf("ack count = %d, want 2 (including duplicate)", ackCount)
	}
}
