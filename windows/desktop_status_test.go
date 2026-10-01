//go:build windows

package main

import (
	"strings"
	"testing"
	"time"
)

func TestDesktopHealthLanOnlyAsksForAccountWithoutError(t *testing.T) {
	now := time.Date(2026, 10, 1, 14, 30, 0, 0, time.Local)
	h := evaluateDesktopHealth(desktopHealthInput{PairCode: "123456", AccountState: "未登录", Now: now})
	if h.Level != desktopSetup || h.Title != "仅局域网接收" {
		t.Fatalf("level=%v title=%q", h.Level, h.Title)
	}
	if !strings.Contains(h.Detail, "配对码 123 456") || !strings.Contains(h.Detail, "账号：未登录") {
		t.Fatalf("detail=%q", h.Detail)
	}
}

func TestDesktopHealthConnectedShowsLastSMS(t *testing.T) {
	now := time.Date(2026, 10, 1, 14, 30, 0, 0, time.Local)
	h := evaluateDesktopHealth(desktopHealthInput{
		PairCode: "123456", AccountState: "已连接", AccountName: "harry",
		LastSMSAt: now.Add(-3 * time.Minute).UnixMilli(), Now: now,
	})
	if h.Level != desktopOK || h.Title != "同步正常" {
		t.Fatalf("level=%v title=%q", h.Level, h.Title)
	}
	if !strings.Contains(h.Detail, "账号：已连接 · harry") || !strings.Contains(h.Detail, "最近短信：14:27（3 分钟前）") {
		t.Fatalf("detail=%q", h.Detail)
	}
	if h.Tooltip != "MsgDock · 同步正常 · 最近短信 14:27（3 分钟前）" {
		t.Fatalf("tooltip=%q", h.Tooltip)
	}
}

func TestDesktopHealthReloginIsErrorAndRetryIsWarning(t *testing.T) {
	now := time.Now()
	if h := evaluateDesktopHealth(desktopHealthInput{AccountState: "需要重新登录", Now: now}); h.Level != desktopError {
		t.Fatalf("relogin level=%v", h.Level)
	}
	h := evaluateDesktopHealth(desktopHealthInput{AccountState: "云同步重试中", AccountDetail: "dial tcp: i/o timeout", Now: now})
	if h.Level != desktopWarn || !strings.Contains(h.Detail, "dial tcp: i/o timeout") {
		t.Fatalf("retry level=%v detail=%q", h.Level, h.Detail)
	}
}

func TestDesktopHealthPairedCloudFailureAndToastFailure(t *testing.T) {
	now := time.Now()
	h := evaluateDesktopHealth(desktopHealthInput{AccountState: "未登录", CloudPaired: true, CloudState: "连接失败", CloudDetail: "HTTP 502", Now: now})
	if h.Level != desktopWarn || !strings.Contains(h.Detail, "配对云端连接失败：HTTP 502") {
		t.Fatalf("cloud level=%v detail=%q", h.Level, h.Detail)
	}
	h = evaluateDesktopHealth(desktopHealthInput{AccountState: "已连接", NotifyDisabled: true, Now: now})
	if h.Level != desktopWarn || !strings.Contains(h.Detail, "原生通知不可用") {
		t.Fatalf("toast level=%v detail=%q", h.Level, h.Detail)
	}
}

func TestTrayToolTipStaysWithinWindowsLimit(t *testing.T) {
	h := evaluateDesktopHealth(desktopHealthInput{AccountState: "云同步重试中", AccountDetail: strings.Repeat("很长的错误", 80), Now: time.Now()})
	if n := len([]rune(h.Tooltip)); n > trayToolTipLimit {
		t.Fatalf("tooltip has %d runes", n)
	}
	if lastSMSText(time.Now().Add(time.Hour).UnixMilli(), time.Now()) != "" {
		t.Fatal("future SMS time should be hidden")
	}
}

func TestToastTitleCarriesCodeAndAttributionIsHonest(t *testing.T) {
	sms := SMS{From: "10086", Text: "您的验证码为 482913，5 分钟内有效", Device: "Redmi K70"}
	copied := buildSMSNotificationXMLWithCopy(sms, true)
	if !strings.Contains(copied, "<text>10086 · 验证码 482913</text>") || !strings.Contains(copied, "验证码已复制到剪贴板") {
		t.Fatalf("copied toast: %s", copied)
	}
	plain := buildSMSNotificationXML(sms)
	if strings.Contains(plain, "已复制") || !strings.Contains(plain, "来自 Redmi K70") {
		t.Fatalf("manual toast must not claim a copy: %s", plain)
	}
	order := buildSMSNotificationXML(SMS{From: "Shop", Text: "订单 123456 已发货"})
	if strings.Contains(order, "验证码 123456") {
		t.Fatalf("order number promoted to code: %s", order)
	}
}
