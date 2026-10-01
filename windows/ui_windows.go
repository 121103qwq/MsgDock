//go:build windows

package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
	"golang.org/x/sys/windows"
)

const singleInstanceName = `Local\XgyLanSmsReceiver`

const historyDisplayCharacterLimit = 250000

type desktopUI struct {
	stateMu             sync.RWMutex
	app                 *App
	window              *walk.MainWindow
	notifyIcon          *walk.NotifyIcon
	trayIcon            *walk.Icon
	codeLabel           *walk.Label
	addressLabel        *walk.Label
	cloudCodeLabel      *walk.Label
	cloudStatusLabel    *walk.Label
	relayEdit           *walk.LineEdit
	pairButton          *walk.PushButton
	accountIdentifier   *walk.LineEdit
	accountUsername     *walk.LineEdit
	accountEmail        *walk.LineEdit
	accountPassword     *walk.LineEdit
	accountStatus       *walk.Label
	accountDevices      *walk.TextEdit
	accountLogin        *walk.PushButton
	accountRegister     *walk.PushButton
	accountLogout       *walk.PushButton
	accountRefresh      *walk.PushButton
	accountRemove       *walk.PushButton
	autoStartCheck      *walk.CheckBox
	trayFallbackCheck   *walk.CheckBox
	recentText          *walk.TextEdit
	statusTitle         *walk.Label
	statusDetail        *walk.Label
	accountLoginPanel   *walk.Composite
	accountSessionPanel *walk.Composite
	lastToolTip         string
	lastRecentKey       string
	lastStatusLevel     desktopLevel
	updatingSettings    bool
	pairingInFlight     bool
	accountBusy         bool
	exiting             bool
	lastTrayFallback    time.Time
	lastTrayMessageID   string
}

func newDesktopUI(app *App) (*desktopUI, error) {
	autoStartEnabled, err := app.autoStartEnabled()
	if err != nil {
		log.Printf("read Windows auto-start setting failed: %v", err)
	}
	ui := &desktopUI{app: app, updatingSettings: true}
	err = (MainWindow{
		AssignTo: &ui.window,
		// Declarative Walk treats a nil Visible property as visible. Keep the
		// native window hidden during construction; manual startup explicitly
		// calls showStatus, while --tray never flashes the window.
		Visible: false,
		Title:   appName + " v" + appVersion,
		MinSize: Size{Width: 560, Height: 460},
		Size:    Size{Width: 680, Height: 680},
		Layout:  VBox{Margins: Margins{Left: 18, Top: 18, Right: 18, Bottom: 18}, Spacing: 12},
		Children: []Widget{
			ScrollView{
				HorizontalFixed: true,
				Layout:          VBox{MarginsZero: true, Spacing: 12},
				Children: []Widget{
					Composite{
						Layout: VBox{MarginsZero: true, Spacing: 4},
						Children: []Widget{
							Label{
								AssignTo: &ui.statusTitle,
								Text:     "正在检查同步状态…",
								Font:     Font{PointSize: 16, Bold: true},
							},
							Label{AssignTo: &ui.statusDetail},
							Label{
								Text:      "关闭窗口后程序继续在系统托盘接收，收到短信时显示 Windows 通知。",
								TextColor: walk.RGB(0x66, 0x66, 0x66),
							},
						},
					},
					GroupBox{
						Title:  fmt.Sprintf("最近短信（最新在上，显示最近 %d 条）", historyDisplayLimit),
						Layout: VBox{Margins: Margins{Left: 12, Top: 12, Right: 12, Bottom: 12}},
						Children: []Widget{
							Label{Text: "收到短信后会先写入本地历史文件，再向手机确认成功；完整记录不会自动删除。"},
							TextEdit{
								AssignTo: &ui.recentText,
								ReadOnly: true,
								VScroll:  true,
								MinSize:  Size{Height: 210},
							},
						},
					},
					GroupBox{
						Title:  "账号同步（互联网）",
						Layout: VBox{Margins: Margins{Left: 12, Top: 12, Right: 12, Bottom: 12}, Spacing: 6},
						Children: []Widget{
							Label{Text: "默认 API：" + defaultAccountAPIURL + "（账号同步不会替换局域网和旧版云配对）"},
							Label{AssignTo: &ui.accountStatus, Text: "未登录"},
							Composite{
								AssignTo: &ui.accountLoginPanel,
								Layout:   VBox{MarginsZero: true, Spacing: 6},
								Children: []Widget{
									Label{Text: "登录用户名或邮箱"},
									LineEdit{AssignTo: &ui.accountIdentifier, CueBanner: "用户名或邮箱"},
									Label{Text: "登录密码"},
									LineEdit{AssignTo: &ui.accountPassword, CueBanner: "密码", PasswordMode: true},
									Composite{
										Layout: HBox{Spacing: 8},
										Children: []Widget{
											PushButton{AssignTo: &ui.accountLogin, Text: "登录", OnClicked: func() { ui.loginAccount() }},
											HSpacer{},
										},
									},
									Label{Text: "注册信息（注册时填写，登录时可留空）"},
									Composite{
										Layout: HBox{Spacing: 6},
										Children: []Widget{
											Label{Text: "用户名"},
											LineEdit{AssignTo: &ui.accountUsername, CueBanner: "用户名", StretchFactor: 1},
											Label{Text: "邮箱"},
											LineEdit{AssignTo: &ui.accountEmail, CueBanner: "邮箱", StretchFactor: 1},
										},
									},
									Composite{
										Layout: HBox{Spacing: 8},
										Children: []Widget{
											PushButton{AssignTo: &ui.accountRegister, Text: "注册并登录", OnClicked: func() { ui.registerAccount() }},
											HSpacer{},
										},
									},
								},
							},
							Composite{
								AssignTo: &ui.accountSessionPanel,
								Layout:   HBox{MarginsZero: true, Spacing: 8},
								Children: []Widget{
									PushButton{AssignTo: &ui.accountLogout, Text: "退出账号", OnClicked: func() { ui.logoutAccount() }},
									PushButton{AssignTo: &ui.accountRefresh, Text: "刷新设备", OnClicked: func() { ui.refreshAccountDevices() }},
									PushButton{AssignTo: &ui.accountRemove, Text: "移除本机设备", OnClicked: func() { ui.removeAccountDevice() }},
									HSpacer{},
								},
							},
							TextEdit{
								AssignTo: &ui.accountDevices,
								ReadOnly: true,
								VScroll:  true,
								MinSize:  Size{Height: 74},
							},
						},
					},
					GroupBox{
						Title:  "局域网与配对云端",
						Layout: VBox{Margins: Margins{Left: 12, Top: 12, Right: 12, Bottom: 12}, Spacing: 8},
						Children: []Widget{
							Label{Text: "6 位配对码"},
							Label{
								AssignTo: &ui.codeLabel,
								Text:     formatPairCode(app.pairCode()),
								Font:     Font{PointSize: 28, Bold: true},
							},
							Label{AssignTo: &ui.addressLabel},
							Label{Text: "局域网：正在监听 TCP 58123 / UDP 58124；手机仍使用原有 X-Xgy-Key 协议。"},
							Label{Text: "云中继 Relay URL（HTTPS）"},
							LineEdit{AssignTo: &ui.relayEdit, Text: app.relayURL()},
							Composite{
								Layout: HBox{Spacing: 8},
								Children: []Widget{
									PushButton{
										Text: "保存 Relay URL",
										OnClicked: func() {
											if err := app.setRelayURL(ui.relayEdit.Text()); err != nil {
												walk.MsgBox(ui.window, appName, "Relay URL 保存失败：\r\n"+err.Error(), walk.MsgBoxIconError)
												return
											}
											ui.refresh()
										},
									},
									PushButton{
										AssignTo:  &ui.pairButton,
										Text:      cloudPairButtonText(app.cloudCredentials()),
										Enabled:   !cloudPairingPending(app.cloudCredentials()),
										OnClicked: func() { ui.startCloudPairing() },
									},
									HSpacer{},
								},
							},
							Composite{
								Layout: HBox{Spacing: 8},
								Children: []Widget{
									PushButton{
										Text: "复制配对码",
										OnClicked: func() {
											ui.copyText("配对码", app.pairCode())
										},
									},
									PushButton{
										Text: "复制云配对码",
										OnClicked: func() {
											credentials := app.cloudCredentials()
											if app.cloud == nil || !cloudPairingPending(credentials) {
												walk.MsgBox(ui.window, appName, "请先开始云配对。", walk.MsgBoxIconInformation)
												return
											}
											ui.copyText("云配对码", credentials.PairCode)
										},
									},
									PushButton{
										Text: "发送测试通知",
										OnClicked: func() {
											app.showSMSNotification(SMS{
												From:       "Xgy LAN SMS 测试",
												Text:       "这是原生 Windows 通知测试，验证码 123456。",
												ReceivedAt: time.Now().UnixMilli(),
												Device:     "Windows",
											})
										},
									},
									HSpacer{},
								},
							},
							Label{Text: "云配对状态 / 配对码（仅待配对时在手机端输入）"},
							Label{
								AssignTo: &ui.cloudCodeLabel,
								Text:     "未创建",
								Font:     Font{PointSize: 22, Bold: true},
							},
							Label{AssignTo: &ui.cloudStatusLabel},
						},
					},
					GroupBox{
						Title:  "启动与通知",
						Layout: VBox{Margins: Margins{Left: 12, Top: 12, Right: 12, Bottom: 12}, Spacing: 8},
						Children: []Widget{
							CheckBox{
								AssignTo: &ui.autoStartCheck,
								Text:     "开机自动启动",
								Checked:  autoStartEnabled,
								OnCheckedChanged: func() {
									if ui.updatingSettings {
										return
									}
									if err := app.setAutoStartEnabled(ui.autoStartCheck.Checked()); err != nil {
										walk.MsgBox(ui.window, appName, "开机自动启动设置失败：\r\n"+err.Error(), walk.MsgBoxIconError)
									}
									ui.refresh()
								},
							},
							CheckBox{
								AssignTo: &ui.trayFallbackCheck,
								Text:     "原生通知失败时使用托盘提醒（不会重复弹出）",
								Checked:  app.trayFallbackEnabled(),
								OnCheckedChanged: func() {
									if ui.updatingSettings {
										return
									}
									if err := app.setTrayFallbackEnabled(ui.trayFallbackCheck.Checked()); err != nil {
										walk.MsgBox(ui.window, appName, "兼容托盘提醒设置失败：\r\n"+err.Error(), walk.MsgBoxIconError)
									}
									ui.refresh()
								},
							},
							Composite{
								Layout: HBox{Spacing: 8},
								Children: []Widget{
									PushButton{
										Text: "打开 Windows 通知设置",
										OnClicked: func() {
											if err := openWindowsNotificationSettings(); err != nil {
												walk.MsgBox(ui.window, appName, "打开 Windows 通知设置失败：\r\n"+err.Error(), walk.MsgBoxIconError)
											}
										},
									},
									Label{Text: "可在此检查横幅、声音和勿扰设置。"},
									HSpacer{},
								},
							},
						},
					},
				},
			},
		},
	}).Create()
	if err != nil {
		return nil, err
	}
	ui.updatingSettings = false

	ui.window.Closing().Attach(func(canceled *bool, reason walk.CloseReason) {
		if ui.exiting {
			return
		}
		*canceled = true
		ui.window.Hide()
	})

	icon := loadTrayIcon()
	ui.trayIcon = icon
	if icon != nil {
		_ = ui.window.SetIcon(icon)
	}

	ui.notifyIcon, err = walk.NewNotifyIcon(ui.window)
	if err != nil {
		ui.window.Dispose()
		return nil, err
	}
	if icon != nil {
		if err := ui.notifyIcon.SetIcon(icon); err != nil {
			log.Printf("set tray icon failed: %v", err)
		}
	}
	ui.updateToolTip(ui.health().Tooltip)
	ui.notifyIcon.MouseDown().Attach(func(x, y int, button walk.MouseButton) {
		if button == walk.LeftButton {
			ui.showStatus()
		}
	})

	openAction := walk.NewAction()
	_ = openAction.SetText("打开状态 / 设置")
	openAction.Triggered().Attach(ui.showStatus)
	if err := ui.notifyIcon.ContextMenu().Actions().Add(openAction); err != nil {
		ui.dispose()
		return nil, err
	}

	testAction := walk.NewAction()
	_ = testAction.SetText("发送测试通知")
	testAction.Triggered().Attach(func() {
		app.showSMSNotification(SMS{
			From:       "Xgy LAN SMS 测试",
			Text:       "这是原生 Windows 通知测试，验证码 123456。",
			ReceivedAt: time.Now().UnixMilli(),
			Device:     "Windows",
		})
	})
	if err := ui.notifyIcon.ContextMenu().Actions().Add(testAction); err != nil {
		ui.dispose()
		return nil, err
	}

	exitAction := walk.NewAction()
	_ = exitAction.SetText("退出")
	exitAction.Triggered().Attach(func() {
		ui.app.beginClosing()
		ui.exiting = true
		if ui.notifyIcon != nil {
			_ = ui.notifyIcon.SetVisible(false)
		}
		walk.App().Exit(0)
	})
	if err := ui.notifyIcon.ContextMenu().Actions().Add(exitAction); err != nil {
		ui.dispose()
		return nil, err
	}
	if err := ui.notifyIcon.SetVisible(true); err != nil {
		ui.dispose()
		return nil, err
	}

	ui.refresh()
	return ui, nil
}

func loadTrayIcon() *walk.Icon {
	for _, candidate := range []struct {
		dll   string
		index int
	}{
		{dll: "shell32", index: 265},
		{dll: "shell32", index: 167},
		{dll: "shell32", index: 1},
	} {
		icon, err := walk.NewIconFromSysDLLWithSize(candidate.dll, candidate.index, 32)
		if err == nil {
			return icon
		}
	}
	return nil
}

func (ui *desktopUI) run() {
	done := make(chan struct{})
	var refreshWG sync.WaitGroup
	refreshWG.Add(1)
	go func() {
		defer refreshWG.Done()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				ui.stateMu.RLock()
				window := ui.window
				ui.stateMu.RUnlock()
				if window != nil && !ui.app.isClosing() {
					window.Synchronize(ui.refresh)
				}
			case <-done:
				return
			}
		}
	}()
	ui.window.Run()
	close(done)
	refreshWG.Wait()
}

func (ui *desktopUI) dispose() {
	ui.stateMu.Lock()
	notifyIcon := ui.notifyIcon
	window := ui.window
	trayIcon := ui.trayIcon
	ui.notifyIcon = nil
	ui.window = nil
	ui.trayIcon = nil
	ui.stateMu.Unlock()
	if notifyIcon != nil {
		_ = notifyIcon.SetVisible(false)
		notifyIcon.Dispose()
	}
	if window != nil {
		window.Dispose()
	}
	if trayIcon != nil {
		trayIcon.Dispose()
	}
}

func (ui *desktopUI) enqueueTrayFallback(id, title, body string) {
	if ui == nil || ui.app.isClosing() {
		return
	}
	ui.stateMu.RLock()
	window := ui.window
	icon := ui.notifyIcon
	if window == nil || icon == nil {
		ui.stateMu.RUnlock()
		return
	}
	window.Synchronize(func() {
		if ui.app.isClosing() {
			return
		}
		ui.stateMu.RLock()
		currentIcon := ui.notifyIcon
		ui.stateMu.RUnlock()
		if currentIcon == nil {
			return
		}
		if (id != "" && id == ui.lastTrayMessageID) || time.Since(ui.lastTrayFallback) < notificationPopupInterval {
			return
		}
		ui.lastTrayFallback = time.Now()
		ui.lastTrayMessageID = id
		if err := currentIcon.ShowInfo(title, body); err != nil {
			log.Printf("compatibility tray notification failed: %v", err)
		}
	})
	ui.stateMu.RUnlock()
}

func (ui *desktopUI) showStatus() {
	if ui.window == nil {
		return
	}
	// Show first: refresh() only redraws a visible window.
	ui.window.Show()
	ui.refresh()
	_ = ui.window.Activate()
}

// health gathers one snapshot for the status header and the tray tooltip.
func (ui *desktopUI) health() desktopHealth {
	input := desktopHealthInput{PairCode: ui.app.pairCode(), Now: time.Now(), NotifyDisabled: ui.app.notifyFailed.Load()}
	if account := ui.app.accountClient(); account != nil {
		snapshot := account.snapshot()
		credentials := ui.app.accountCredentials()
		input.AccountState = snapshot.State
		input.AccountDetail = snapshot.Detail
		input.AccountName = accountStatusDetail(credentials)
		if input.AccountState == "" && credentials.SessionToken != "" {
			input.AccountState = "账号已登录"
		}
	}
	if ui.app.cloud != nil {
		snapshot := ui.app.cloud.snapshot()
		input.CloudPaired = snapshot.Paired
		input.CloudState = snapshot.State
		input.CloudDetail = snapshot.Detail
	}
	if recent := ui.app.recentSnapshot(); len(recent) > 0 {
		input.LastSMSAt = recent[0].ReceivedAt
		input.LastSMSFrom = recent[0].From
	}
	return evaluateDesktopHealth(input)
}

func (ui *desktopUI) updateToolTip(tip string) {
	if ui.notifyIcon == nil || tip == ui.lastToolTip {
		return
	}
	if err := ui.notifyIcon.SetToolTip(tip); err != nil {
		log.Printf("set tray tooltip failed: %v", err)
		return
	}
	ui.lastToolTip = tip
}

func statusColor(level desktopLevel) walk.Color {
	switch level {
	case desktopError:
		return walk.RGB(0xC6, 0x28, 0x28)
	case desktopWarn:
		return walk.RGB(0xB2, 0x6A, 0x00)
	case desktopSetup:
		return walk.RGB(0x44, 0x44, 0x44)
	default:
		return walk.RGB(0x1B, 0x7F, 0x3B)
	}
}

func (ui *desktopUI) refresh() {
	if ui.window == nil || ui.app.isClosing() {
		return
	}
	health := ui.health()
	ui.updateToolTip(health.Tooltip)
	// The 1 s ticker keeps running while the window sits in the tray. Hidden
	// windows only need the tooltip; skipping the registry read and the large
	// history SetText saves wakeups and keeps the reader's scroll position.
	if !ui.window.Visible() {
		return
	}
	if ui.statusTitle != nil {
		ui.statusTitle.SetText(health.Title)
		if health.Level != ui.lastStatusLevel || ui.statusTitle.TextColor() != statusColor(health.Level) {
			ui.statusTitle.SetTextColor(statusColor(health.Level))
			ui.lastStatusLevel = health.Level
		}
	}
	if ui.statusDetail != nil {
		ui.statusDetail.SetText(health.Detail)
	}
	ui.updatingSettings = true
	if ui.autoStartCheck != nil {
		if enabled, err := ui.app.autoStartEnabled(); err != nil {
			log.Printf("read Windows auto-start setting failed: %v", err)
		} else if ui.autoStartCheck.Checked() != enabled {
			ui.autoStartCheck.SetChecked(enabled)
		}
	}
	if ui.trayFallbackCheck != nil {
		enabled := ui.app.trayFallbackEnabled()
		if ui.trayFallbackCheck.Checked() != enabled {
			ui.trayFallbackCheck.SetChecked(enabled)
		}
	}
	ui.updatingSettings = false
	ui.codeLabel.SetText(formatPairCode(ui.app.pairCode()))
	host, _ := os.Hostname()
	ui.addressLabel.SetText(fmt.Sprintf("设备：%s    地址：http://%s:%d", host, localIPv4(), httpPort))
	credentials := ui.app.cloudCredentials()
	if ui.pairButton != nil {
		ui.pairButton.SetText(cloudPairButtonText(credentials))
		ui.pairButton.SetEnabled(!ui.pairingInFlight && !cloudPairingPending(credentials))
	}
	if ui.app.cloud == nil {
		ui.cloudCodeLabel.SetText(cloudPairCodeText(credentials))
		ui.cloudStatusLabel.SetText("云中继：尚未启动 · " + cloudPairStatusText(credentials, cloudStatusSnapshot{}))
	} else {
		snapshot := ui.app.cloud.snapshot()
		ui.cloudCodeLabel.SetText(cloudPairCodeText(credentials))
		detail := cloudPairStatusText(credentials, snapshot)
		if snapshot.Backoff > 0 {
			detail += fmt.Sprintf(" · 当前间隔 %s", snapshot.Backoff.Round(time.Second))
		}
		ui.cloudStatusLabel.SetText("云中继：" + detail)
	}
	if account := ui.app.accountClient(); account != nil {
		credentials := ui.app.accountCredentials()
		snapshot := account.snapshot()
		if ui.accountStatus != nil {
			ui.accountStatus.SetText("账号同步：" + accountStatusText(snapshot, credentials))
		}
		if ui.accountDevices != nil {
			ui.accountDevices.SetText(formatAccountDevices(account.devicesSnapshot(), credentials.DeviceID))
		}
		busy := ui.accountBusy
		loggedIn := credentials.SessionToken != ""
		if ui.accountLoginPanel != nil && ui.accountLoginPanel.Visible() == loggedIn {
			ui.accountLoginPanel.SetVisible(!loggedIn)
		}
		if ui.accountSessionPanel != nil && ui.accountSessionPanel.Visible() != loggedIn {
			ui.accountSessionPanel.SetVisible(loggedIn)
		}
		if ui.accountLogin != nil {
			ui.accountLogin.SetEnabled(!busy)
		}
		if ui.accountRegister != nil {
			ui.accountRegister.SetEnabled(!busy)
		}
		if ui.accountLogout != nil {
			ui.accountLogout.SetEnabled(!busy && credentials.SessionToken != "")
		}
		if ui.accountRefresh != nil {
			ui.accountRefresh.SetEnabled(!busy && credentials.SessionToken != "")
		}
		if ui.accountRemove != nil {
			ui.accountRemove.SetEnabled(!busy && credentials.SessionToken != "" && credentials.DeviceID != "")
		}
	}

	recent := ui.app.recentSnapshot()
	// Re-setting identical text every second resets the scroll position and
	// selection; only redraw when the newest/oldest record or count changed.
	recentKey := fmt.Sprint(len(recent))
	if len(recent) > 0 {
		recentKey += "|" + recent[0].ID + "|" + fmt.Sprint(recent[0].ReceivedAt) + "|" + recent[len(recent)-1].ID
	}
	if recentKey == ui.lastRecentKey {
		return
	}
	ui.lastRecentKey = recentKey
	if len(recent) == 0 {
		ui.recentText.SetText("尚无历史记录。\r\n\r\n保存位置：" + ui.app.historyPath())
		return
	}
	var out strings.Builder
	for index, sms := range recent {
		entry := fmt.Sprintf("%s\r\n%s · %s\r\n%s", sms.From, sms.Device, time.UnixMilli(sms.ReceivedAt).Format("2006-01-02 15:04:05"), sms.Text)
		separator := ""
		if index > 0 {
			separator = "\r\n────────────────────────\r\n"
		}
		if out.Len()+len(separator)+len(entry) > historyDisplayCharacterLimit {
			out.WriteString("\r\n\r\n显示内容已达到上限，完整记录仍保存在：\r\n")
			out.WriteString(ui.app.historyPath())
			break
		}
		out.WriteString(separator)
		out.WriteString(entry)
	}
	ui.recentText.SetText(out.String())
}

func formatAccountDevices(devices []accountDevice, currentID string) string {
	if len(devices) == 0 {
		return "尚未读取设备列表。登录后点击“刷新设备”。"
	}
	var out strings.Builder
	for index, device := range devices {
		if index > 0 {
			out.WriteString("\r\n")
		}
		marker := "  "
		if device.ID == currentID {
			marker = "* "
		}
		name := device.Name
		if name == "" {
			name = device.ID
		}
		out.WriteString(marker)
		out.WriteString(name)
		out.WriteString(" · ")
		if device.Type == "" {
			out.WriteString("unknown")
		} else {
			out.WriteString(device.Type)
		}
		out.WriteString(" · ")
		if device.LastSeenAt > 0 {
			out.WriteString(time.UnixMilli(device.LastSeenAt).Format("2006-01-02 15:04:05"))
		} else {
			out.WriteString("最后在线未知")
		}
	}
	return out.String()
}

func (ui *desktopUI) runAccountAction(action func(*accountClient) error, success string) {
	if ui == nil || ui.app == nil || ui.app.isClosing() || ui.accountBusy {
		return
	}
	client := ui.app.accountClient()
	if client == nil {
		walk.MsgBox(ui.window, appName, "账号同步尚未启动。", walk.MsgBoxIconError)
		return
	}
	ui.accountBusy = true
	ui.refresh()
	go func() {
		err := action(client)
		ui.stateMu.RLock()
		window := ui.window
		ui.stateMu.RUnlock()
		if window == nil || ui.app.isClosing() {
			return
		}
		window.Synchronize(func() {
			if ui.app.isClosing() || ui.window == nil {
				return
			}
			ui.accountBusy = false
			ui.refresh()
			if err != nil {
				walk.MsgBox(ui.window, appName, "账号同步操作失败：\r\n"+accountErrorDetail(err), walk.MsgBoxIconError)
			} else if success != "" {
				walk.MsgBox(ui.window, appName, success, walk.MsgBoxIconInformation)
			}
		})
	}()
}

func (ui *desktopUI) loginAccount() {
	if ui.accountIdentifier == nil || ui.accountPassword == nil {
		return
	}
	identifier := strings.TrimSpace(ui.accountIdentifier.Text())
	password := ui.accountPassword.Text()
	if identifier == "" || password == "" {
		walk.MsgBox(ui.window, appName, "请输入用户名或邮箱和密码。", walk.MsgBoxIconInformation)
		return
	}
	ui.runAccountAction(func(client *accountClient) error {
		return client.login(context.Background(), identifier, password)
	}, "登录成功，本机 Windows 设备已连接。")
}

func (ui *desktopUI) registerAccount() {
	if ui.accountUsername == nil || ui.accountEmail == nil || ui.accountPassword == nil {
		return
	}
	username := strings.TrimSpace(ui.accountUsername.Text())
	email := strings.TrimSpace(ui.accountEmail.Text())
	password := ui.accountPassword.Text()
	if username == "" || email == "" || password == "" {
		walk.MsgBox(ui.window, appName, "注册时请填写用户名、邮箱和密码。", walk.MsgBoxIconInformation)
		return
	}
	ui.runAccountAction(func(client *accountClient) error {
		return client.register(context.Background(), username, email, password)
	}, "注册成功，本机 Windows 设备已连接。")
}

func (ui *desktopUI) logoutAccount() {
	result := walk.MsgBox(ui.window, appName, "退出账号后将停止互联网短信同步，但不会影响局域网和旧版云配对。确定退出吗？", walk.MsgBoxYesNo|walk.MsgBoxIconQuestion)
	if result != walk.DlgCmdYes {
		return
	}
	ui.runAccountAction(func(client *accountClient) error {
		return client.logout(context.Background())
	}, "已退出 MsgDock 账号。")
}

func (ui *desktopUI) refreshAccountDevices() {
	ui.runAccountAction(func(client *accountClient) error {
		return client.refreshDevices(context.Background())
	}, "设备列表已刷新。")
}

func (ui *desktopUI) removeAccountDevice() {
	credentials := ui.app.accountCredentials()
	if credentials.DeviceID == "" {
		walk.MsgBox(ui.window, appName, "当前没有已注册的 Windows 设备。", walk.MsgBoxIconInformation)
		return
	}
	result := walk.MsgBox(ui.window, appName, "移除本机设备后，互联网短信同步会停止，确认继续吗？", walk.MsgBoxYesNo|walk.MsgBoxIconWarning)
	if result != walk.DlgCmdYes {
		return
	}
	ui.runAccountAction(func(client *accountClient) error {
		return client.removeDevice(context.Background(), credentials.DeviceID)
	}, "本机 Windows 设备已移除。")
}

func (ui *desktopUI) startCloudPairing() {
	if ui.app.isClosing() || ui.app.cloud == nil || ui.pairButton == nil {
		return
	}
	credentials := ui.app.cloudCredentials()
	if cloudPairingPending(credentials) || ui.pairingInFlight {
		ui.refresh()
		return
	}
	force := cloudCredentialsReady(credentials)
	if force {
		result := walk.MsgBox(ui.window, appName,
			"当前设备已经完成云配对。重新云配对会替换旧凭据；旧设备以及尚未接收的云消息可能无法继续解密。\r\n\r\n确定要继续并创建新的配对码吗？",
			walk.MsgBoxYesNo|walk.MsgBoxIconWarning|walk.MsgBoxDefButton2)
		if result != walk.DlgCmdYes {
			return
		}
	}
	ui.pairingInFlight = true
	ui.pairButton.SetEnabled(false)
	go func() {
		var err error
		if force {
			err = ui.app.cloud.startPairingForced()
		} else {
			err = ui.app.cloud.startPairing()
		}
		if ui.window == nil || ui.app.isClosing() {
			return
		}
		ui.window.Synchronize(func() {
			if ui.app.isClosing() || ui.window == nil {
				return
			}
			ui.pairingInFlight = false
			if err != nil {
				walk.MsgBox(ui.window, appName, "云配对启动失败：\r\n"+err.Error(), walk.MsgBoxIconError)
			}
			ui.refresh()
		})
	}()
}

func cloudPairButtonText(credentials CloudCredentials) string {
	if cloudPairingPending(credentials) {
		return "配对进行中"
	}
	if cloudCredentialsReady(credentials) {
		return "重新云配对"
	}
	return "开始云配对"
}

func cloudPairCodeText(credentials CloudCredentials) string {
	switch {
	case cloudPairingPending(credentials):
		return formatPairCode(credentials.PairCode)
	case cloudCredentialsReady(credentials):
		if credentials.LastPairCode == "" {
			return "未保留（旧版本）"
		}
		return formatPairCode(credentials.LastPairCode) + "（已失效）"
	default:
		return "未创建"
	}
}

func cloudPairStatusText(credentials CloudCredentials, snapshot cloudStatusSnapshot) string {
	if cloudPairingPending(credentials) {
		if snapshot.State == "清理失败" && snapshot.Detail != "" {
			return snapshot.State + " · " + snapshot.Detail + " · 当前码 " + formatPairCode(credentials.PairCode)
		}
		detail := "等待手机确认 · 当前码 " + formatPairCode(credentials.PairCode)
		if credentials.PairExpiresAt > 0 {
			expires := time.Until(time.UnixMilli(credentials.PairExpiresAt))
			if expires <= 0 {
				detail += " · 已过期"
			} else {
				detail += " · " + expires.Round(time.Second).String() + " 后到期"
			}
		}
		return detail
	}
	if cloudCredentialsReady(credentials) {
		if credentials.LastPairCode == "" {
			return "已配对 · 上次配对码未保留（旧版本配置）"
		}
		return "已配对 · 上次配对码 " + formatPairCode(credentials.LastPairCode) + " 已失效"
	}
	if snapshot.State != "" && snapshot.State != "未配对" {
		if snapshot.Detail != "" {
			return snapshot.State + " · " + snapshot.Detail
		}
		return snapshot.State
	}
	return "未创建 · 点击“开始云配对”"
}

func (ui *desktopUI) onSMS(sms SMS) {
	if ui.app.isClosing() {
		return
	}
	ui.stateMu.RLock()
	window := ui.window
	ui.stateMu.RUnlock()
	if window == nil {
		return
	}
	window.Synchronize(func() {
		if ui.app.isClosing() || ui.window == nil {
			return
		}
		ui.refresh()
	})
}

func (ui *desktopUI) handleToastAction(arguments string) {
	if ui.app.isClosing() {
		return
	}
	ui.stateMu.RLock()
	window := ui.window
	ui.stateMu.RUnlock()
	if window == nil {
		return
	}
	window.Synchronize(func() {
		if ui.app.isClosing() || ui.window == nil {
			return
		}
		switch {
		case arguments == "show-status":
			ui.showStatus()
		case strings.HasPrefix(arguments, "copy-code:"):
			value, err := decodeToastValue(strings.TrimPrefix(arguments, "copy-code:"))
			if err == nil {
				ui.copyText("验证码", value)
			}
		case strings.HasPrefix(arguments, "copy-full:"):
			value, err := decodeToastValue(strings.TrimPrefix(arguments, "copy-full:"))
			if err == nil {
				ui.copyText("短信全文", value)
			}
		}
	})
}

func (ui *desktopUI) copyText(label, value string) {
	if err := walk.Clipboard().SetText(value); err != nil {
		log.Printf("copy %s failed: %v", label, err)
		walk.MsgBox(ui.window, appName, "复制失败："+err.Error(), walk.MsgBoxIconError)
		return
	}
}

// Clipboard access belongs to Walk's GUI thread; copying never opens another
// notification. Re-check age when dequeued after a busy UI.
func (ui *desktopUI) enqueueVerificationCode(sms SMS, code string) {
	ui.stateMu.RLock()
	defer ui.stateMu.RUnlock()
	if ui.window == nil || ui.app.isClosing() {
		return
	}
	ui.window.Synchronize(func() {
		if ui.app.isClosing() || !freshNotification(sms, time.Now()) {
			return
		}
		if err := walk.Clipboard().SetText(code); err != nil {
			log.Printf("automatic verification-code copy failed: %v", err)
		}
	})
}

func acquireSingleInstance() (windows.Handle, bool, error) {
	name, err := windows.UTF16PtrFromString(singleInstanceName)
	if err != nil {
		return 0, false, err
	}
	handle, err := windows.CreateMutex(nil, false, name)
	if errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return handle, true, nil
	}
	if err != nil {
		return handle, false, err
	}
	return handle, false, nil
}

func releaseSingleInstance(handle windows.Handle) {
	_ = windows.CloseHandle(handle)
}

func showAlreadyRunning() {
	walk.MsgBox(nil, appName, "程序已在系统托盘中运行。", walk.MsgBoxIconInformation)
}

func showFatalError(title string, err error) {
	walk.MsgBox(nil, appName, title+"：\r\n"+err.Error(), walk.MsgBoxIconError)
}
