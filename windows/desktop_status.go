//go:build windows

package main

import (
	"fmt"
	"strings"
	"time"
)

// desktopLevel orders how urgently the status header asks for attention.
type desktopLevel int

const (
	desktopOK desktopLevel = iota
	desktopSetup
	desktopWarn
	desktopError
)

// desktopHealthInput is a plain snapshot so the wording rules stay testable
// without a window, a tray icon or network clients.
type desktopHealthInput struct {
	PairCode       string
	AccountState   string
	AccountDetail  string
	AccountName    string
	CloudPaired    bool
	CloudState     string
	CloudDetail    string
	LastSMSAt      int64
	LastSMSFrom    string
	Now            time.Time
	NotifyDisabled bool
}

type desktopHealth struct {
	Level   desktopLevel
	Title   string
	Detail  string
	Tooltip string
}

const trayToolTipLimit = 100

func evaluateDesktopHealth(in desktopHealthInput) desktopHealth {
	var problems []string
	level := desktopOK
	raise := func(next desktopLevel, text string) {
		if next > level {
			level = next
		}
		if text != "" {
			problems = append(problems, text)
		}
	}

	accountLoggedIn := in.AccountState != "" && in.AccountState != "未登录"
	switch in.AccountState {
	case "需要重新登录":
		raise(desktopError, "账号授权已失效：在下方“账号同步”重新登录，期间互联网短信不会到达。")
	case "云同步重试中":
		raise(desktopWarn, "互联网同步暂时连不上，正在自动重试："+shortError(in.AccountDetail))
	case "登录失败":
		raise(desktopWarn, "登录失败："+shortError(in.AccountDetail))
	}
	if in.CloudPaired && (in.CloudState == "连接失败" || in.CloudState == "配置错误") {
		raise(desktopWarn, "配对云端"+in.CloudState+"："+shortError(in.CloudDetail))
	}
	if in.NotifyDisabled {
		raise(desktopWarn, "原生通知不可用，短信仍会保存到下方历史。可在“启动与通知”里打开 Windows 通知设置。")
	}

	title := "同步正常"
	switch level {
	case desktopError:
		title = "同步受阻，需要处理"
	case desktopWarn:
		title = "同步可用，有提醒"
	default:
		if !accountLoggedIn && !in.CloudPaired {
			level = desktopSetup
			title = "仅局域网接收"
			problems = append(problems, "登录账号后，手机不在同一 Wi‑Fi 时也能收到短信。")
		}
	}

	var lines []string
	lines = append(lines, problems...)
	lines = append(lines, "局域网：正在接收 · 配对码 "+formatPairCode(in.PairCode))
	switch {
	case !accountLoggedIn:
		lines = append(lines, "账号：未登录")
	case in.AccountName != "":
		lines = append(lines, "账号："+in.AccountState+" · "+in.AccountName)
	default:
		lines = append(lines, "账号："+in.AccountState)
	}
	if in.CloudPaired {
		lines = append(lines, "配对云端：已配对")
	}
	last := lastSMSText(in.LastSMSAt, in.Now)
	if last != "" {
		lines = append(lines, "最近短信："+last)
	}

	tip := appName + " · " + title
	if last != "" {
		tip += " · 最近短信 " + last
	}
	return desktopHealth{
		Level:   level,
		Title:   title,
		Detail:  strings.Join(lines, "\r\n"),
		Tooltip: clampRunes(tip, trayToolTipLimit),
	}
}

// lastSMSText is clock time plus a coarse age, e.g. "14:05（3 分钟前）".
func lastSMSText(receivedAt int64, now time.Time) string {
	if receivedAt <= 0 || now.IsZero() {
		return ""
	}
	at := time.UnixMilli(receivedAt)
	age := now.Sub(at)
	if age < -time.Minute {
		return ""
	}
	clock := at.Format("15:04")
	if now.YearDay() != at.YearDay() || now.Year() != at.Year() {
		clock = at.Format("01-02 15:04")
	}
	switch {
	case age < time.Minute:
		return clock + "（刚刚）"
	case age < time.Hour:
		return fmt.Sprintf("%s（%d 分钟前）", clock, int(age/time.Minute))
	case age < 24*time.Hour:
		return fmt.Sprintf("%s（%d 小时前）", clock, int(age/time.Hour))
	default:
		return clock
	}
}

func shortError(detail string) string {
	detail = strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(detail, "\r", " "), "\n", " "))
	if detail == "" {
		return "原因未知"
	}
	return clampRunes(detail, 80)
}

func clampRunes(value string, limit int) string {
	runes := []rune(value)
	if limit <= 0 || len(runes) <= limit {
		return value
	}
	return string(runes[:limit-1]) + "…"
}
