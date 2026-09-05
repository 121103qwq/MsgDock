//go:build windows

package main

import (
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"git.sr.ht/~jackmordaunt/go-toast/v2/wintoast"
	"golang.org/x/sys/windows/registry"
)

const (
	notificationAppID         = "Xgy LAN SMS"
	notificationActivatorGUID = "{4A147B2E-9207-4D81-995C-DF072E85151E}"
)

var errNotificationWorkerStopped = errors.New("notification worker is stopped")

type notificationRequest struct {
	sms    SMS
	result chan error
}

type notificationWorker struct {
	queue   chan notificationRequest
	stop    chan struct{}
	done    chan struct{}
	once    sync.Once
	lifeMu  sync.RWMutex
	stopped bool
	push    func(SMS) error
}

func newNotificationWorker(push func(SMS) error) *notificationWorker {
	if push == nil {
		push = pushSMSNotification
	}
	return &notificationWorker{
		queue: make(chan notificationRequest, 32),
		stop:  make(chan struct{}),
		done:  make(chan struct{}),
		push:  push,
	}
}

func (a *App) initializeNotifications() error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	executable, err = filepath.Abs(executable)
	if err != nil {
		return err
	}
	activationCommand := fmt.Sprintf("\"%s\"", executable)
	data := wintoast.AppData{
		AppID:         notificationAppID,
		GUID:          notificationActivatorGUID,
		ActivationExe: activationCommand,
	}
	if err := wintoast.SetAppData(data); err != nil {
		return err
	}
	if err := refreshActivationExecutable(activationCommand); err != nil {
		return err
	}
	wintoast.SetActivationCallback(func(_ string, arguments string, _ []wintoast.UserData) {
		a.mu.RLock()
		ui := a.ui
		a.mu.RUnlock()
		if ui != nil {
			ui.handleToastAction(arguments)
		}
	})
	worker := newNotificationWorker(nil)
	a.mu.Lock()
	a.notify = worker
	a.mu.Unlock()
	go worker.run()
	return nil
}

func (worker *notificationWorker) run() {
	// Walk owns a single-threaded COM apartment on the GUI thread. WinRT toast
	// notifications require their own multithreaded apartment, so every call is
	// serialized on this dedicated, permanently locked OS thread.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(worker.done)
	for {
		select {
		case request := <-worker.queue:
			request.result <- worker.push(request.sms)
		case <-worker.stop:
			for {
				select {
				case request := <-worker.queue:
					request.result <- errNotificationWorkerStopped
				default:
					return
				}
			}
		}
	}
}

func (worker *notificationWorker) request(sms SMS) error {
	// Keep the worker alive until this request has received its result. Without
	// this lifecycle read lock, a request could win the queue send at the same
	// instant shutdown drains the channel and exits, then wait forever.
	worker.lifeMu.RLock()
	defer worker.lifeMu.RUnlock()
	if worker.stopped {
		return errNotificationWorkerStopped
	}
	result := make(chan error, 1)
	request := notificationRequest{sms: sms, result: result}
	worker.queue <- request
	return <-result
}

func (worker *notificationWorker) shutdown() {
	worker.once.Do(func() {
		worker.lifeMu.Lock()
		worker.stopped = true
		close(worker.stop)
		worker.lifeMu.Unlock()
	})
	<-worker.done
}

func (a *App) stopNotifications() {
	a.mu.Lock()
	worker := a.notify
	a.notify = nil
	a.mu.Unlock()
	if worker != nil {
		worker.shutdown()
	}
}

func refreshActivationExecutable(command string) error {
	path := filepath.Join("SOFTWARE", "Classes", "CLSID", notificationActivatorGUID, "LocalServer32")
	key, _, err := registry.CreateKey(registry.CURRENT_USER, path, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue("", command)
}

func (a *App) showSMSNotification(sms SMS) error {
	if a.isClosing() {
		return errors.New("receiver is shutting down")
	}
	a.mu.RLock()
	worker := a.notify
	ui := a.ui
	trayFallbackEnabled := a.cfg.TrayFallbackEnabled || !a.cfg.trayFallbackPresent
	a.mu.RUnlock()
	var nativeErr error
	if worker != nil {
		nativeErr = worker.request(sms)
	} else {
		nativeErr = errors.New("native Windows notification is not initialized")
		log.Printf("native Windows notification is not initialized")
	}
	if trayFallbackEnabled && ui != nil {
		title := truncateNotificationText(appName+" · "+sms.From, 60)
		body := truncateNotificationText(sms.Text, 240)
		ui.enqueueTrayFallback(title, body)
	}
	return nativeErr
}

func truncateNotificationText(value string, maxRunes int) string {
	if maxRunes <= 0 {
		return ""
	}
	text := []rune(value)
	if len(text) <= maxRunes {
		return value
	}
	return string(text[:maxRunes-1]) + "…"
}

func pushSMSNotification(sms SMS) error {
	xml := buildSMSNotificationXML(sms)
	if err := wintoast.Push(notificationAppID, xml); err != nil {
		logNotificationError(err)
		return err
	}
	return nil
}

func buildSMSNotificationXML(sms SMS) string {
	code := extractVerificationCode(sms.Text)
	actions := ""
	if code != "" {
		actions += fmt.Sprintf(`<action activationType="foreground" content="复制验证码" arguments="copy-code:%s"/>`, encodeToastValue(code))
	}
	actions += fmt.Sprintf(`<action activationType="foreground" content="复制全文" arguments="copy-full:%s"/>`, encodeToastValue(sms.Text))

	return fmt.Sprintf(
		`<toast scenario="urgent" activationType="foreground" launch="show-status" duration="long"><visual><binding template="ToastGeneric"><text>%s</text><text>%s</text></binding></visual><actions>%s</actions><audio src="ms-winsoundevent:Notification.SMS"/></toast>`,
		xmlEscape(sms.From),
		xmlEscape(sms.Text),
		actions,
	)
}

func encodeToastValue(value string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(value))
}

func decodeToastValue(value string) (string, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return "", err
	}
	return string(decoded), nil
}

func xmlEscape(value string) string {
	replacer := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"\"", "&quot;",
		"'", "&apos;",
	)
	return replacer.Replace(value)
}

func logNotificationError(err error) {
	// Deliberately do not fall back to PowerShell: it would reintroduce the
	// command-window flash that this Windows client is designed to eliminate.
	log.Printf("native Windows notification failed: %v", err)
}
