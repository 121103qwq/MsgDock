//go:build windows

package main

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows/registry"
)

type fakeStartupRegistry struct {
	values map[string]string
}

func (fake *fakeStartupRegistry) read(name string) (string, error) {
	value, ok := fake.values[name]
	if !ok {
		return "", registry.ErrNotExist
	}
	return value, nil
}

func (fake *fakeStartupRegistry) write(name, value string) error {
	if fake.values == nil {
		fake.values = make(map[string]string)
	}
	fake.values[name] = value
	return nil
}

func (fake *fakeStartupRegistry) remove(name string) error {
	delete(fake.values, name)
	return nil
}

func TestAutoStartRegistrationUsesQuotedAbsoluteExecutable(t *testing.T) {
	store := &fakeStartupRegistry{}
	executable := filepath.Join("relative", "XgyLanSmsReceiver.exe")
	if err := setAutoStartEnabled(store, true, executable); err != nil {
		t.Fatal(err)
	}
	want, err := autoStartCommand(executable)
	if err != nil {
		t.Fatal(err)
	}
	if got := store.values[autoStartValueName]; got != want {
		t.Fatalf("Run value = %q, want %q", got, want)
	}
	if !strings.HasSuffix(store.values[autoStartValueName], " --tray") {
		t.Fatalf("Run value did not request tray startup: %q", store.values[autoStartValueName])
	}
	enabled, err := readAutoStartEnabled(store)
	if err != nil {
		t.Fatal(err)
	}
	if !enabled {
		t.Fatal("registered auto-start value was reported disabled")
	}
}

func TestAutoStartUnregistrationOnlyRemovesOwnValue(t *testing.T) {
	store := &fakeStartupRegistry{values: map[string]string{
		autoStartValueName: "\"C:\\old\\receiver.exe\"",
		"UnrelatedApp":     "unrelated.exe",
	}}
	if err := setAutoStartEnabled(store, false, "ignored.exe"); err != nil {
		t.Fatal(err)
	}
	if _, ok := store.values[autoStartValueName]; ok {
		t.Fatal("auto-start value was not removed")
	}
	if got := store.values["UnrelatedApp"]; got != "unrelated.exe" {
		t.Fatalf("unrelated Run value changed to %q", got)
	}
	if enabled, err := readAutoStartEnabled(store); err != nil {
		t.Fatal(err)
	} else if enabled {
		t.Fatal("removed auto-start value was reported enabled")
	}
}

func TestAutoStartReadPropagatesUnexpectedRegistryError(t *testing.T) {
	want := errors.New("registry unavailable")
	store := startupRegistryError{err: want}
	if _, err := readAutoStartEnabled(store); !errors.Is(err, want) {
		t.Fatalf("read error = %v, want %v", err, want)
	}
}

func TestAutoStartRefreshReplacesOnlyExistingOwnValue(t *testing.T) {
	store := &fakeStartupRegistry{values: map[string]string{
		autoStartValueName: "\"C:\\old\\XgyLanSmsReceiver.exe\"",
		"UnrelatedApp":     "unrelated.exe",
	}}
	if err := refreshAutoStartRegistration(store, "C:\\new\\XgyLanSmsReceiver.exe"); err != nil {
		t.Fatal(err)
	}
	want, err := autoStartCommand("C:\\new\\XgyLanSmsReceiver.exe")
	if err != nil {
		t.Fatal(err)
	}
	if store.values[autoStartValueName] != want {
		t.Fatalf("refreshed Run value = %q, want %q", store.values[autoStartValueName], want)
	}
	if store.values["UnrelatedApp"] != "unrelated.exe" {
		t.Fatal("refresh changed unrelated Run value")
	}
}

func TestAutoStartRefreshDoesNotEnableMissingValue(t *testing.T) {
	store := &fakeStartupRegistry{}
	if err := refreshAutoStartRegistration(store, "C:\\new\\XgyLanSmsReceiver.exe"); err != nil {
		t.Fatal(err)
	}
	if len(store.values) != 0 {
		t.Fatalf("refresh created Run value: %#v", store.values)
	}
}

type startupRegistryError struct{ err error }

func (store startupRegistryError) read(string) (string, error) { return "", store.err }
func (startupRegistryError) write(string, string) error        { return nil }
func (startupRegistryError) remove(string) error               { return nil }
