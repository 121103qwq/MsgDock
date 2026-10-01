//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	autoStartRegistryPath = `Software\Microsoft\Windows\CurrentVersion\Run`
	autoStartValueName    = "XgyLanSmsReceiver"
)

// startupRegistry keeps the registry boundary small enough for deterministic
// tests without touching the user's actual Run key.
type startupRegistry interface {
	read(name string) (string, error)
	write(name, value string) error
	remove(name string) error
}

type windowsStartupRegistry struct{}

func (windowsStartupRegistry) read(name string) (string, error) {
	key, err := registry.OpenKey(registry.CURRENT_USER, autoStartRegistryPath, registry.QUERY_VALUE)
	if err != nil {
		return "", err
	}
	defer key.Close()
	value, _, err := key.GetStringValue(name)
	return value, err
}

func (windowsStartupRegistry) write(name, value string) error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, autoStartRegistryPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue(name, value)
}

func (windowsStartupRegistry) remove(name string) error {
	key, err := registry.OpenKey(registry.CURRENT_USER, autoStartRegistryPath, registry.SET_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	defer key.Close()
	if err := key.DeleteValue(name); errors.Is(err, registry.ErrNotExist) {
		return nil
	} else {
		return err
	}
}

func autoStartCommand(executable string) (string, error) {
	if executable == "" {
		return "", errors.New("executable path is empty")
	}
	absolute, err := filepath.Abs(executable)
	if err != nil {
		return "", fmt.Errorf("resolve executable path: %w", err)
	}
	return fmt.Sprintf("\"%s\" --tray", absolute), nil
}

func readAutoStartEnabled(store startupRegistry) (bool, error) {
	_, err := store.read(autoStartValueName)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

func setAutoStartEnabled(store startupRegistry, enabled bool, executable string) error {
	if enabled {
		command, err := autoStartCommand(executable)
		if err != nil {
			return err
		}
		return store.write(autoStartValueName, command)
	}
	return store.remove(autoStartValueName)
}

// refreshAutoStartRegistration updates only this application's Run value when
// auto-start was already enabled. This lets an installed/newly replaced EXE
// take over from the previous version without enabling auto-start implicitly.
func refreshAutoStartRegistration(store startupRegistry, executable string) error {
	current, err := store.read(autoStartValueName)
	if errors.Is(err, registry.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	command, err := autoStartCommand(executable)
	if err != nil {
		return err
	}
	if current == command {
		return nil
	}
	return store.write(autoStartValueName, command)
}

func currentExecutablePath() (string, error) {
	executable, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.Abs(executable)
}

func (a *App) autoStartEnabled() (bool, error) {
	return readAutoStartEnabled(windowsStartupRegistry{})
}

func (a *App) setAutoStartEnabled(enabled bool) error {
	executable, err := currentExecutablePath()
	if err != nil {
		return err
	}
	return setAutoStartEnabled(windowsStartupRegistry{}, enabled, executable)
}

func refreshCurrentAutoStartRegistration() error {
	executable, err := currentExecutablePath()
	if err != nil {
		return err
	}
	return refreshAutoStartRegistration(windowsStartupRegistry{}, executable)
}

func openWindowsNotificationSettings() error {
	verb, err := windows.UTF16PtrFromString("open")
	if err != nil {
		return err
	}
	target, err := windows.UTF16PtrFromString("ms-settings:notifications")
	if err != nil {
		return err
	}
	return windows.ShellExecute(0, verb, target, nil, nil, windows.SW_SHOWNORMAL)
}
