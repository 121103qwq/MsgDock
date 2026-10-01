//go:build windows

package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
)

const (
	historyFileName     = "history.jsonl"
	historyDisplayLimit = 500
	historyMaxLineBytes = 4 << 20
)

var errHistoryDuplicate = errors.New("history record already exists")

func (a *App) historyPath() string {
	return filepath.Join(a.dir, historyFileName)
}

func (a *App) loadHistory() error {
	a.historyMu.Lock()
	defer a.historyMu.Unlock()

	file, err := os.Open(a.historyPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open history: %w", err)
	}
	defer file.Close()

	records := make([]SMS, 0, historyDisplayLimit)
	seenIDs := make(map[string]struct{})
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), historyMaxLineBytes)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var sms SMS
		if err := json.Unmarshal(line, &sms); err != nil {
			log.Printf("skip invalid SMS history line %d: %v", lineNumber, err)
			continue
		}
		if sms.From == "" {
			sms.From = "短信"
		}
		if sms.ID != "" {
			seenIDs[sms.ID] = struct{}{}
		}
		records = append(records, sms)
		if len(records) > historyDisplayLimit {
			records = records[len(records)-historyDisplayLimit:]
		}
	}
	for left, right := 0, len(records)-1; left < right; left, right = left+1, right-1 {
		records[left], records[right] = records[right], records[left]
	}
	a.mu.Lock()
	a.recent = records
	a.seenIDs = seenIDs
	a.mu.Unlock()
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("scan history: %w", err)
	}
	return nil
}

func (a *App) persistHistory(sms SMS) error {
	var encoded bytes.Buffer
	encoder := json.NewEncoder(&encoded)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(sms); err != nil {
		return fmt.Errorf("encode history: %w", err)
	}
	data := encoded.Bytes()

	a.historyMu.Lock()
	defer a.historyMu.Unlock()
	if sms.ID != "" {
		a.mu.RLock()
		_, alreadySeen := a.seenIDs[sms.ID]
		a.mu.RUnlock()
		if alreadySeen {
			return errHistoryDuplicate
		}
	}

	file, err := os.OpenFile(a.historyPath(), os.O_CREATE|os.O_RDWR|os.O_APPEND, 0600)
	if err != nil {
		return fmt.Errorf("open history: %w", err)
	}

	stat, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return fmt.Errorf("stat history: %w", err)
	}
	startSize := stat.Size()
	if startSize > 0 {
		last := []byte{0}
		if _, err := file.ReadAt(last, startSize-1); err != nil {
			_ = file.Close()
			return fmt.Errorf("inspect history tail: %w", err)
		}
		if last[0] != '\n' {
			if _, err := file.Write([]byte{'\n'}); err != nil {
				_ = file.Close()
				return fmt.Errorf("repair history tail: %w", err)
			}
		}
	}

	if written, err := file.Write(data); err != nil {
		rollbackErr := rollbackHistory(file, startSize)
		_ = file.Close()
		return errors.Join(fmt.Errorf("append history: %w", err), rollbackErr)
	} else if written != len(data) {
		rollbackErr := rollbackHistory(file, startSize)
		_ = file.Close()
		return errors.Join(fmt.Errorf("append history: %w", io.ErrShortWrite), rollbackErr)
	}
	if err := file.Sync(); err != nil {
		rollbackErr := rollbackHistory(file, startSize)
		_ = file.Close()
		return errors.Join(fmt.Errorf("sync history: %w", err), rollbackErr)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close history: %w", err)
	}

	a.mu.Lock()
	a.recent = append([]SMS{sms}, a.recent...)
	if sms.ID != "" {
		if a.seenIDs == nil {
			a.seenIDs = make(map[string]struct{})
		}
		a.seenIDs[sms.ID] = struct{}{}
	}
	if len(a.recent) > historyDisplayLimit {
		a.recent = a.recent[:historyDisplayLimit]
	}
	a.mu.Unlock()
	return nil
}

func (a *App) hasHistoryID(id string) bool {
	if id == "" {
		return false
	}
	a.mu.RLock()
	defer a.mu.RUnlock()
	_, ok := a.seenIDs[id]
	return ok
}

func isHistoryDuplicate(err error) bool {
	return errors.Is(err, errHistoryDuplicate)
}

func rollbackHistory(file *os.File, size int64) error {
	if err := file.Truncate(size); err != nil {
		return fmt.Errorf("rollback history: %w", err)
	}
	if err := file.Sync(); err != nil {
		return fmt.Errorf("sync history rollback: %w", err)
	}
	return nil
}
