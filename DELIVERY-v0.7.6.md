# MsgDock v0.7.6 预览版

## 本次更新

- Android 修复上下兼容留白、系统栏与键盘遮挡。首页和后台教程共用安全边距处理，保留原有主题、历史、账号同步和品牌教程。
- 未登录、未启用账号接收或需重新登录时，账号接收线程改为等待设置/网络事件，减少无效唤醒。发送手机原本就是收到 SMS 广播后立即入队上传；已开启的账号接收仍为轮询，尚未引入服务器推送。
- Windows 取消紧急、长时通知，显示发送者和短信正文，保留复制验证码与全文。连续短信更新同一张卡片，10 秒内不反复弹横幅，历史补齐不弹窗。
- 近期且包含明确验证码关键词的短信自动复制验证码；旧验证码不覆盖剪贴板。原生通知成功后不再额外弹托盘气泡，手动复制成功也不新增提醒。
- Android 与 Windows 均升级为 v0.7.6，协议不变。旧版本保留，Cloudflare 业务代码、配置、D1 与旧加密 Relay 不变。

## 验证

- Android SDK/JDK 完整构建、59 项 JVM 测试及 v2/v3 签名检查通过。包名 com.xgy.lansms，versionCode 16、versionName 0.7.6，沿用调试证书 SHA-256：8b72e245377b56f06af95753d8fd396a880bf608a3161b6c175e443d94dbf82a。
- 本轮仅提升版本。最终 APK 的 classes.dex 和 resources.arsc 与上一轮独立 Android 14 模拟器验证的候选逐字节哈希一致，没有把版本重打包说成重新执行了真机测试。
- 前序候选已由独立 android-test MCP 验证全屏不再 letterbox、键盘输入焦点可见、收起键盘恢复、大字体真实横屏和教程返回。首轮横屏断言误判，补测证据为 build/android-ui-2026-10-01T08-52-56-992Z/；完整边界见 HANDOFF。
- Windows 全套 go test、go vet 及 GUI 构建通过，PE subsystem 为 2。前序真实 WinRT 测试确认固定 Tag/Group 与 SuppressPopup，隔离 QA 通知连续投递 3 次后只保留 1 张卡片；没有操作用户真实剪贴板。
- 网站 12 项回归、TypeScript 检查及 keep-vars/strict 部署 dry-run 通过。网页仅下载区变化，内联业务脚本未改。

## 产物与升级

- [Android APK](https://github.com/121103qwq/MsgDock/releases/download/v0.7.6/MsgDock-Android-v0.7.6-debug.apk)
- [Windows x64 EXE](https://github.com/121103qwq/MsgDock/releases/download/v0.7.6/MsgDock-Windows-v0.7.6.exe)
- [完整源码 ZIP](https://github.com/121103qwq/MsgDock/releases/download/v0.7.6/MsgDock-source-v0.7.6.zip)
- [SHA-256 清单](https://github.com/121103qwq/MsgDock/releases/download/v0.7.6/SHA256SUMS-v0.7.6.txt)
- [网站下载区](https://msgdock.dpdns.org/#downloads)

| 文件 | SHA-256 |
|---|---|
| MsgDock-Android-v0.7.6-debug.apk | C9E099BC7CEE9DD93EA55A0F86DD3E4093D73F9301A4D237B28EC64F9FD92226 |
| MsgDock-Windows-v0.7.6.exe | 2B00BFB62C40639C249DEB4DBC593A71E85819AA4D4F1B38A20D9B59DE6B9719 |

Android 可使用相同签名覆盖升级。Windows 先退出旧版，再运行新版；配置目录、历史和通知 AppID 沿用旧值。请保留旧版备份。本文件随版本源码提交固定；源码 ZIP 从该提交生成，其哈希以随包清单为准。发布后的远端提交、下载核验与网站部署结果记录在 HANDOFF。

## 发布核验

预览 Release 已公开，标签 v0.7.6 指向 `7b728d723ef8cba8144d74f84e9779a8b4e4bd15`。APK、EXE、源码 ZIP 和 SHA-256 清单均已匿名实际下载，HTTP 200，哈希全部与本地及 GitHub digest 匹配。源码 ZIP 含 123 个文件，其 SHA-256 为 `4A26C082AFE738EBD4C46F05B8E8A5DC4F98D7B42DE11895BF94F7AF023BD79A`。

网站部署 ID 为 `1e3ef438-2086-4ca7-9bd4-17e9b5c2d017`。主站根页、收件箱和备用域名的下载区均匹配本地；健康检查和匿名鉴权边界保持正常。原 PR 均未合并。源码包仍固定在功能提交，本节为发布后文档补记，不修改既有资产或标签。

## 已知边界

这是预览版，Android 为调试签名，Windows 未做代码签名。真实 SMS、HyperOS 锁屏、长期后台保活与耗电量未验收；Windows 可见横幅、复制按钮激活和真实剪贴板端到端操作仍待确认。Gradle lint 受已记录的本机故障影响，本轮未运行。原生通知 API 成功或模拟器通过均不能替代这些验收。
