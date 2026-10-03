//go:build windows

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

const configBackupSuffix = ".bak"

// loadConfigFiles never replaces a malformed primary configuration with a
// newly generated one. A valid backup is preferred for recovery; otherwise
// startup fails without destroying the user's data.
func loadConfigFiles(configPath string) (Config, bool, error) {
	primary, primaryErr := os.ReadFile(configPath)
	if primaryErr == nil {
		var cfg Config
		if err := json.Unmarshal(primary, &cfg); err == nil {
			return cfg, false, nil
		} else {
			primaryErr = fmt.Errorf("parse %s: %w", filepath.Base(configPath), err)
		}
	}

	backupPath := configPath + configBackupSuffix
	backup, backupErr := os.ReadFile(backupPath)
	if backupErr == nil {
		var cfg Config
		if err := json.Unmarshal(backup, &cfg); err == nil {
			return cfg, true, nil
		} else if primaryErr == nil {
			primaryErr = fmt.Errorf("parse %s: %w", filepath.Base(backupPath), err)
		}
	}

	if primaryErr != nil && !errors.Is(primaryErr, os.ErrNotExist) {
		if backupErr != nil && !errors.Is(backupErr, os.ErrNotExist) {
			return Config{}, false, fmt.Errorf("%w; backup unavailable: %v", primaryErr, backupErr)
		}
		return Config{}, false, primaryErr
	}
	if backupErr != nil && !errors.Is(backupErr, os.ErrNotExist) {
		return Config{}, false, fmt.Errorf("read %s: %w", filepath.Base(backupPath), backupErr)
	}
	return Config{}, false, nil
}

func saveConfigFile(configPath string, cfg Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("encode config: %w", err)
	}
	if err := backupValidConfig(configPath); err != nil {
		return err
	}
	if err := writeBytesAtomically(configPath, data, 0600); err != nil {
		return fmt.Errorf("replace config: %w", err)
	}
	return nil
}

func backupValidConfig(configPath string) error {
	data, err := os.ReadFile(configPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read current config for backup: %w", err)
	}
	var current Config
	if err := json.Unmarshal(data, &current); err != nil {
		// Keep an existing valid backup intact when the primary is malformed.
		return nil
	}
	if err := writeBytesAtomically(configPath+configBackupSuffix, data, 0600); err != nil {
		return fmt.Errorf("save config backup: %w", err)
	}
	return nil
}

func writeBytesAtomically(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	temp, err := os.CreateTemp(dir, ".xgy-config-*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tempPath)
		}
	}()
	if err := temp.Chmod(mode); err != nil {
		_ = temp.Close()
		return err
	}
	if written, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return err
	} else if written != len(data) {
		_ = temp.Close()
		return io.ErrShortWrite
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := replaceFile(tempPath, path); err != nil {
		return err
	}
	cleanup = false
	return nil
}

func replaceFile(source, destination string) error {
	sourcePtr, err := windows.UTF16PtrFromString(source)
	if err != nil {
		return err
	}
	destinationPtr, err := windows.UTF16PtrFromString(destination)
	if err != nil {
		return err
	}
	return windows.MoveFileEx(sourcePtr, destinationPtr, windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH)
}
