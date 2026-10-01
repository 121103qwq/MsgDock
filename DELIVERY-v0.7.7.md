# MsgDock Android v0.7.7 预览构建

本次按用户要求，基于 harry-1211/MsgDock PR #1 重新构建 Android APK。PR 未合并，Windows 下载保留已发布的 v0.7.6。

## 来源与平台边界

- PR：https://github.com/harry-1211/MsgDock/pull/1
- 锁定提交：fd8d63bba21acc6dc9fd87e83b8bc3a21d2da4ca。
- Android：versionName 0.7.7，versionCode 17，包名 com.xgy.lansms。
- 构建分支：release/msgdock-android-v0.7.7，位于唯一共享开发工作区。
- Windows 源码包含 PR 的 v0.7.7 候选，但本次没有构建、安装或发布 Windows EXE。网站保留 v0.7.6 的 EXE 和对应校验链接。
- Cloudflare 业务源码、配置和绑定未修改。网站只更新下载区。

首次构建锁定 0595ee4；复查发现 PR 新增 fd8d63b。新增内容仅涉及 Windows 和文档。两个提交的 app Git tree 均为 a588e067a53c45228a08d683a78e66a1ee99f500，因此 Android APK 和本轮测试覆盖的源码完全相同。

## 本次 Android 改进

- 首页按状态卡、待处理事项、最近短信、接收端、账号和高级设置排序。
- 五种状态统一为同步正常、正在同步、同步延迟、同步中断和需要处理。
- 合并接收端列表，账号和高级设置默认折叠。
- 明确验证码在最近短信中单独显示，通知增加复制验证码按钮。
- 保留此前省电候选：有界短信广播唤醒锁、网络回调去抖和空闲轮询策略。该实现不代表已通过真机续航测量。
- 保留 LAN v1、多路同 ID 去重、持久化后 ACK、账号隔离和旧加密配对兼容。

## 本机构建与验证

- 标准 Gradle 命令在任务开始前失败：Unable to establish loopback connection，未进入应用编译。Gradle lint 未运行。
- 使用 tools/build-android-local.ps1、已有 Android API 36 SDK 和 Build Tools 35.0.0 完成资源编译、Java 编译、DEX、对齐及签名。
- 96 项 JVM 测试全部通过。
- APK v2/v3 签名验证通过。证书 SHA-256 为 8b72e245377b56f06af95753d8fd396a880bf608a3161b6c175e443d94dbf82a，与已发布 v0.7.6 实际证书一致。
- aapt2 核对包名、版本、minSdk 26 和 targetSdk 36。
- app/ 源码与锁定 PR 完全一致，本轮没有改动应用代码。

## 独立设备验证

仅使用 Codex 独立 android-test MCP，目标为 MsgDock_Codex_API34 / emulator-5680 / Android 14 / ADB 5038。开始前核对 AVD 名称、ro.kernel.qemu=1 和 boot_completed=1。已有无线真机未操作。

tools/test-android-pr1-mcp.cjs 通过 6 组检查：

1. v0.7.7 覆盖安装保留模拟器历史；首页状态优先、账号初始折叠，系统栏范围正常。
2. 展开账号后密码输入框在键盘上方可达，收起键盘后滚动区域恢复。
3. 360 dp／200% 字体下登录按钮可达，真实横屏和页面重建保留展开状态。
4. 高级设置可展开，离线后台教程可打开并返回。
5. 真实本地 HTTP 提交合成消息并落盘，同 ID 重发仅保存一条；最近短信和完整历史显示验证码。
6. Android 系统通知中存在复制验证码操作。未据此宣称按钮点击或剪贴板实际内容通过。

报告：build/android-pr1-2026-10-01T13-02-29-008Z/result.json，passed=true。7 张截图已人工检查，crash-log.txt 为空。字体、密度及竖屏已恢复，临时端口转发已移除。

模拟器测试不代表真实 SMS、HyperOS 锁屏、Doze、后台保活、耗电量、厂商兼容或真实账号云端端到端验收。

## 交付物

目录：outputs/MsgDock-Android-v0.7.7-PR1/。

- MsgDock-Android-v0.7.7-debug.apk
- MsgDock-source-v0.7.7.zip：从本次固定交付提交生成，不含工作区未跟踪资料包、备份、凭据或构建产物。
- SHA256SUMS-v0.7.7.txt：记录上述两个资产的实际 SHA-256。

APK SHA-256：D621D5212DB4192524E71311CC14DFFE09C2274992B01D5123D7B12BE270B83F。

源码 SHA-256：F1A95FAB62C9654CC71B67E19660520384772CCE05E3A9B18B6CFA333452B358。

清单自身 SHA-256：C9A22983EBE3491F761A73A5D25E0CE59110B0780203D0554DDE1F1DC146BB84。

## 发布核对

已发布到 [v0.7.7 预览 Release](https://github.com/121103qwq/MsgDock/releases/tag/v0.7.7)。Release ID 为 400984204，draft=false、prerelease=true。固定交付源码提交为 e7554572a6f8f69947938be6bd84457047c076e4，远端标签与源码 ZIP 均对应该提交。发布后补记文档不移动标签，也不替换已核对的资产。

三个资产逐项匿名实际下载均为 HTTP 200。下载文件的 SHA-256 与本地文件和 GitHub digest 完全一致。PR 仍为 open、merged=false。本次未修改 main，也未自动合并 PR。

网站已部署到 [下载区](https://msgdock.dpdns.org/#downloads)，部署 ID 为 6f0a8d9b-1347-4392-b2fa-57e2b98c0c72。本轮只上传 index.html 的下载区修改，保留既有业务源码、绑定、变量、路由和凭据。

线上检查通过：

- 主站首页、/inbox 和备用首页返回 200。下载区和业务脚本与本地文件一致。
- 主备 health 返回 200。匿名 messages 返回 401，带 no-store。
- 微信验证 TXT 返回 200，内容与原文件一致。
- Windows v0.7.6 EXE 和对应清单仍使用原链接。实际下载均为 200，原 SHA-256 不变。

线上证据：build/release-v0.7.7-verification/live-result.json。独立模拟器已退出，原无线真机连接未操作。v0.7.7 未安装到用户真机。
