//go:build windows

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigMigrationEnablesTrayFallbackForOldConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"pairCode":"123456","relayUrl":"https://example.test"}`), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, migrated, err := loadConfigFiles(path)
	if err != nil {
		t.Fatal(err)
	}
	if migrated {
		t.Fatal("old config was incorrectly reported as backup recovery")
	}
	if !cfg.TrayFallbackEnabled || cfg.trayFallbackPresent {
		t.Fatalf("old config migration state = %#v, present=%v", cfg.TrayFallbackEnabled, cfg.trayFallbackPresent)
	}
	if err := saveConfigFile(path, cfg); err != nil {
		t.Fatal(err)
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(contents), `"trayFallbackEnabled": true`) {
		t.Fatalf("migrated config did not persist default true: %s", contents)
	}

	var loaded Config
	if err := json.Unmarshal(contents, &loaded); err != nil {
		t.Fatal(err)
	}
	if !loaded.TrayFallbackEnabled || !loaded.trayFallbackPresent {
		t.Fatalf("persisted config state = %#v, present=%v", loaded.TrayFallbackEnabled, loaded.trayFallbackPresent)
	}
}

func TestTrayFallbackSettingCanBeDisabledAndPersisted(t *testing.T) {
	app := &App{dir: t.TempDir(), cfg: Config{}}
	if !app.trayFallbackEnabled() {
		t.Fatal("missing tray setting did not default to enabled")
	}
	if err := app.setTrayFallbackEnabled(false); err != nil {
		t.Fatal(err)
	}
	if app.trayFallbackEnabled() {
		t.Fatal("tray fallback remained enabled after disabling")
	}
	contents, err := os.ReadFile(filepath.Join(app.dir, "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(contents), `"trayFallbackEnabled": false`) {
		t.Fatalf("disabled tray setting was not persisted: %s", contents)
	}
}

func TestTrayFallbackNotificationTextIsBoundedByRunes(t *testing.T) {
	if got := truncateNotificationText("短信验证码", 20); got != "短信验证码" {
		t.Fatalf("short notification text = %q", got)
	}
	got := truncateNotificationText(strings.Repeat("验", 240)+"尾", 240)
	if len([]rune(got)) != 240 || !strings.HasSuffix(got, "…") {
		t.Fatalf("long notification text length/suffix = %d/%q", len([]rune(got)), got[len(got)-3:])
	}
	if got := truncateNotificationText("abcdef", 1); got != "…" {
		t.Fatalf("one-rune notification text = %q", got)
	}
}

func TestCloudPairMetadataMigratesAndRoundTrips(t *testing.T) {
	var old Config
	if err := json.Unmarshal([]byte(`{"cloud":{"roomId":"room","deviceId":"win","token":"token","peerDeviceId":"phone","peerPublicKey":"peer","privateKey":"key"}}`), &old); err != nil {
		t.Fatal(err)
	}
	if old.Cloud.LastPairCode != "" || old.Cloud.PairedAt != 0 {
		t.Fatalf("old config unexpectedly synthesized pair metadata: %#v", old.Cloud)
	}
	current := Config{Cloud: CloudCredentials{LastPairCode: "123456", PairedAt: 1787400001000}}
	encoded, err := json.Marshal(current)
	if err != nil {
		t.Fatal(err)
	}
	var roundTrip Config
	if err := json.Unmarshal(encoded, &roundTrip); err != nil {
		t.Fatal(err)
	}
	if roundTrip.Cloud.LastPairCode != current.Cloud.LastPairCode || roundTrip.Cloud.PairedAt != current.Cloud.PairedAt {
		t.Fatalf("pair metadata round trip = %#v", roundTrip.Cloud)
	}
}
