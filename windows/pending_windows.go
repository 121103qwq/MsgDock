//go:build windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	pendingNotificationsFileName = "pending-notifications.json"
	pendingNotificationRetention = 30 * 24 * time.Hour
)

type pendingCloudAck struct {
	RelayURL string `json:"relayUrl,omitempty"`
	RoomID   string `json:"roomId,omitempty"`
	DeviceID string `json:"deviceId,omitempty"`
	Token    string `json:"token,omitempty"`
}

type pendingNotification struct {
	ID         string           `json:"id"`
	SMS        SMS              `json:"sms"`
	Source     string           `json:"source"`
	NotifiedAt int64            `json:"notifiedAt,omitempty"`
	CloudAck   *pendingCloudAck `json:"cloudAck,omitempty"`
}

type pendingNotificationFile struct {
	Notifications []pendingNotification `json:"notifications"`
}

func (a *App) pendingNotificationsPath() string {
	return filepath.Join(a.dir, pendingNotificationsFileName)
}

func (a *App) loadPendingNotifications() error {
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	return a.loadPendingNotificationsLocked()
}

func (a *App) loadPendingNotificationsLocked() error {
	if a.pendingLoaded {
		return nil
	}
	data, err := os.ReadFile(a.pendingNotificationsPath())
	if errors.Is(err, os.ErrNotExist) {
		a.pending = nil
		a.pendingLoaded = true
		return nil
	}
	if err != nil {
		return fmt.Errorf("read pending notifications: %w", err)
	}
	var file pendingNotificationFile
	if err := json.Unmarshal(data, &file); err != nil {
		return fmt.Errorf("decode pending notifications: %w", err)
	}
	for _, pending := range file.Notifications {
		if pending.ID == "" || pending.SMS.ID != pending.ID || pending.Source == "" {
			return fmt.Errorf("pending notification has invalid identity")
		}
	}
	pruned := prunePendingNotifications(file.Notifications, time.Now())
	a.pending = append([]pendingNotification(nil), pruned...)
	a.pendingLoaded = true
	if len(pruned) != len(file.Notifications) {
		if err := a.savePendingNotificationsLocked(pruned); err != nil {
			return err
		}
	}
	return nil
}

func prunePendingNotifications(notifications []pendingNotification, now time.Time) []pendingNotification {
	cutoff := now.Add(-pendingNotificationRetention).UnixMilli()
	kept := make([]pendingNotification, 0, len(notifications))
	for _, pending := range notifications {
		if pending.NotifiedAt > 0 && pending.NotifiedAt < cutoff {
			continue
		}
		kept = append(kept, pending)
	}
	return kept
}

func (a *App) savePendingNotificationsLocked(notifications []pendingNotification) error {
	notifications = prunePendingNotifications(notifications, time.Now())
	data, err := json.MarshalIndent(pendingNotificationFile{Notifications: notifications}, "", "  ")
	if err != nil {
		return fmt.Errorf("encode pending notifications: %w", err)
	}
	if err := writeBytesAtomically(a.pendingNotificationsPath(), data, 0600); err != nil {
		return fmt.Errorf("save pending notifications: %w", err)
	}
	return nil
}

func (a *App) prunePendingNotificationsLocked() error {
	next := prunePendingNotifications(a.pending, time.Now())
	if len(next) == len(a.pending) {
		return nil
	}
	if err := a.savePendingNotificationsLocked(next); err != nil {
		return err
	}
	a.pending = next
	return nil
}

func (a *App) pendingNotificationsSnapshot() ([]pendingNotification, error) {
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	if err := a.loadPendingNotificationsLocked(); err != nil {
		return nil, err
	}
	if err := a.prunePendingNotificationsLocked(); err != nil {
		return nil, err
	}
	return append([]pendingNotification(nil), a.pending...), nil
}

func (a *App) findPendingNotification(id string) (pendingNotification, bool, error) {
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	if err := a.loadPendingNotificationsLocked(); err != nil {
		return pendingNotification{}, false, err
	}
	if err := a.prunePendingNotificationsLocked(); err != nil {
		return pendingNotification{}, false, err
	}
	for _, pending := range a.pending {
		if pending.ID == id {
			return pending, true, nil
		}
	}
	return pendingNotification{}, false, nil
}

func (a *App) addPendingNotification(pending pendingNotification) error {
	if pending.ID == "" || pending.SMS.ID != pending.ID || pending.Source == "" {
		return errors.New("invalid pending notification")
	}
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	if err := a.loadPendingNotificationsLocked(); err != nil {
		return err
	}
	if err := a.prunePendingNotificationsLocked(); err != nil {
		return err
	}
	for _, current := range a.pending {
		if current.ID == pending.ID {
			return nil
		}
	}
	next := append(append([]pendingNotification(nil), prunePendingNotifications(a.pending, time.Now())...), pending)
	if err := a.savePendingNotificationsLocked(next); err != nil {
		return err
	}
	a.pending = next
	return nil
}

func (a *App) removePendingNotification(id string) error {
	if id == "" {
		return errors.New("pending notification id is empty")
	}
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	if err := a.loadPendingNotificationsLocked(); err != nil {
		return err
	}
	if err := a.prunePendingNotificationsLocked(); err != nil {
		return err
	}
	current := a.pending
	index := -1
	for currentIndex, pending := range current {
		if pending.ID == id {
			index = currentIndex
			break
		}
	}
	if index < 0 {
		return nil
	}
	next := append([]pendingNotification(nil), current[:index]...)
	next = append(next, current[index+1:]...)
	if err := a.savePendingNotificationsLocked(next); err != nil {
		return err
	}
	a.pending = next
	return nil
}

func (a *App) markPendingNotified(id string, notifiedAt int64) (pendingNotification, error) {
	if id == "" {
		return pendingNotification{}, errors.New("pending notification id is empty")
	}
	if notifiedAt <= 0 {
		notifiedAt = time.Now().UnixMilli()
	}
	a.pendingMu.Lock()
	defer a.pendingMu.Unlock()
	if err := a.loadPendingNotificationsLocked(); err != nil {
		return pendingNotification{}, err
	}
	if err := a.prunePendingNotificationsLocked(); err != nil {
		return pendingNotification{}, err
	}
	next := append([]pendingNotification(nil), a.pending...)
	for index := range next {
		if next[index].ID != id {
			continue
		}
		if next[index].NotifiedAt == 0 {
			next[index].NotifiedAt = notifiedAt
			if err := a.savePendingNotificationsLocked(next); err != nil {
				return pendingNotification{}, err
			}
			a.pending = next
		}
		return next[index], nil
	}
	return pendingNotification{}, errors.New("pending notification was not found")
}

func (a *App) completePendingNotificationLocked(pending pendingNotification) error {
	if a.isClosing() {
		return errors.New("receiver is shutting down")
	}
	// Callers may have taken a snapshot before another delivery path marked the
	// same ID notified. Always reload under pendingProcessMu so a stale snapshot
	// cannot emit a second Toast.
	current, found, err := a.findPendingNotification(pending.ID)
	if err != nil {
		return err
	}
	if !found {
		return nil
	}
	pending = current
	if !a.hasHistoryID(pending.ID) {
		if err := a.persistHistory(pending.SMS); err != nil && !isHistoryDuplicate(err) {
			return err
		}
	}
	a.deliverSMS(pending.SMS)
	if pending.NotifiedAt == 0 {
		if err := a.showSMSNotification(pending.SMS); err != nil {
			return fmt.Errorf("push Windows notification: %w", err)
		}
		if _, err := a.markPendingNotified(pending.ID, time.Now().UnixMilli()); err != nil {
			return fmt.Errorf("persist notification completion: %w", err)
		}
	}
	return nil
}

func (a *App) processLANMessage(sms SMS) error {
	a.pendingProcessMu.Lock()
	defer a.pendingProcessMu.Unlock()
	if a.isClosing() {
		return errors.New("receiver is shutting down")
	}
	pending, found, err := a.findPendingNotification(sms.ID)
	if err != nil {
		return err
	}
	if !found {
		if a.hasHistoryID(sms.ID) {
			return nil
		}
		pending = pendingNotification{ID: sms.ID, SMS: sms, Source: "lan"}
		if err := a.addPendingNotification(pending); err != nil {
			return err
		}
	}
	if err := a.completePendingNotificationLocked(pending); err != nil {
		return err
	}
	return nil
}

// processAccountMessage shares the durable history and pending notification
// pipeline with LAN/legacy cloud messages. The account cursor is advanced by
// accountClient only after this method returns successfully, so a failed Toast
// leaves the message available for the next after=<last_seq> poll.
func (a *App) processAccountMessage(sms SMS) error {
	a.pendingProcessMu.Lock()
	defer a.pendingProcessMu.Unlock()
	if a.isClosing() {
		return errors.New("receiver is shutting down")
	}
	if strings.TrimSpace(sms.ID) == "" {
		return errors.New("account SMS has no client message ID")
	}
	pending, found, err := a.findPendingNotification(sms.ID)
	if err != nil {
		return err
	}
	if !found {
		if a.hasHistoryID(sms.ID) {
			return nil
		}
		pending = pendingNotification{ID: sms.ID, SMS: sms, Source: "account"}
		if err := a.addPendingNotification(pending); err != nil {
			return err
		}
	}
	return a.completePendingNotificationLocked(pending)
}

func (c *cloudClient) replayPending(ctx context.Context) error {
	if c == nil || c.app == nil {
		return nil
	}
	pendingList, err := c.app.pendingNotificationsSnapshot()
	if err != nil {
		return err
	}
	var firstErr error
	for _, pending := range pendingList {
		if c.app.isClosing() {
			return errors.New("receiver is shutting down")
		}
		// A notified LAN entry is a 30-day cross-path de-duplication ledger,
		// not work that needs replay. Cloud entries still continue to ACK.
		if pending.Source != "cloud" && pending.NotifiedAt > 0 {
			continue
		}
		c.app.pendingProcessMu.Lock()
		if err := c.app.completePendingNotificationLocked(pending); err != nil {
			c.app.pendingProcessMu.Unlock()
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		c.app.pendingProcessMu.Unlock()
		if c.app.isClosing() {
			return errors.New("receiver is shutting down")
		}
		if pending.Source != "cloud" {
			continue
		}
		credentials, relayURL, ok := pending.cloudCredentials(c.app.cloudCredentials())
		if !ok {
			if firstErr == nil {
				firstErr = errors.New("pending cloud notification has incomplete ACK credentials")
			}
			continue
		}
		if err := c.ackMessagesWithRelay(ctx, credentials, []string{pending.ID}, relayURL); err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		if err := c.app.removePendingNotification(pending.ID); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (pending pendingNotification) cloudCredentials(current CloudCredentials) (CloudCredentials, string, bool) {
	if pending.CloudAck == nil || pending.CloudAck.RoomID == "" || pending.CloudAck.DeviceID == "" || pending.CloudAck.Token == "" {
		return CloudCredentials{}, "", false
	}
	if current.RoomID == pending.CloudAck.RoomID && current.DeviceID == pending.CloudAck.DeviceID && current.Token != "" {
		return current, pending.CloudAck.RelayURL, true
	}
	return CloudCredentials{
		RoomID: pending.CloudAck.RoomID, DeviceID: pending.CloudAck.DeviceID, Token: pending.CloudAck.Token,
	}, pending.CloudAck.RelayURL, true
}
