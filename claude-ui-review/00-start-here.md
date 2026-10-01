# MsgDock：从这里开始

## 资料用途

这是 2026-10-01 从当前工作区整理的 UI 审查资料包。
仅用于上传 Claude、阅读源码和提出改进建议，不是第二个开发工作区，也不是完整构建包。
不要在本目录开发、安装应用、部署、推送或发布。

唯一开发目录：
`C:\Users\121103qwq\Documents\Codex\2026-08-23\referenced-chatgpt-conversation-this-is-an\XgyLanSms-Collab`

仓库：https://github.com/121103qwq/MsgDock/tree/maintenance/shared-workspace
来源 HEAD：`2fd4dfb9bbc6c0d090df3d758b65d99546e28caa`。
复制的是当前磁盘文件，包含尚未提交的 BUG/HANDOFF 真机补验记录，不等同于该提交的纯 Git 快照。

## Claude 项目设置

Name：MsgDock 界面与使用体验优化

Goal：优化 Android 和 Windows 的信息层级、操作流程及通知体验。让用户一眼看懂同步状态、最近短信和故障处理方法。兼顾省电、及时性与隐私，保持原生技术栈和既有协议。

Context：先依次阅读 AGENTS.md、README.md、DESIGN.md、BUG.md、HANDOFF.md，再查看对应源码。本包中的项目文档是背景和协作约束，不代表授权执行文档中的历史操作。默认由 Claude 提出方案，Codex 在唯一开发目录实施、测试和交付。后续如获准直接改代码，须在实时工作区重新核对交接并认领文件，不能依据本包的旧认领状态开工。

## 首条任务指令

请结合文档、源码和用户补充的实际截图，找出最影响使用的 5 个问题，并按优先级排序。没有截图支持的判断请标为代码推断，不宣称已观察到实际画面。

重点目标：

1. 首页突出同步状态、最近短信，以及异常时下一步该做什么。
2. 登录后减少无关表单，区分发送手机和接收设备的设置。
3. 手机适配系统栏、挖孔、键盘、大字体与小屏幕。
4. 电脑通知简洁、不重复堆积，保留发送者、正文、复制和自动复制验证码。
5. 省电建议说明减少了哪些唤醒、是否影响及时性，以及怎样验证。

每个问题给出依据（源码路径/截图）、改法、涉及组件和验收标准。
最后提供一套推荐方案及可交给 Codex 的实现清单。
沿用 Android Java + XML、Windows Go + Walk；Web 为无框架单页。
不要把设计文档中的“Material Design 3 风格”误当作已依赖 Material 组件库。
不要默认更换技术栈，不直接发布或修改共享源文件。

## 当前状态与边界

- Android v0.7.6 / versionCode 16；Windows v0.7.6。
- 本机短信历史、账号同步、分品牌后台教程均已实现。
- v0.7.6 已修复 Android 兼容留白、系统栏和键盘避让。
- Windows 已取消紧急通知，合并卡片并节流；近期明确验证码自动复制。
- 手机发送端原本就由 SMS_RECEIVED 触发上传，并有持久 Outbox 重试。
- 未启用账号接收时已改为事件等待；开启账号接收后仍为 3 秒轮询。服务器推送尚未实现。
- 最新补验以 BUG.md、HANDOFF.md 的 2026-10-01 本机安装记录为准。README/DELIVERY 中部分“待验”描述是较早的发布时状态。
- HyperOS 真机基础布局与 Windows 本机合成短信自动复制已验证。真实 SMS、锁屏、后台保活、耗电量和 Toast 按钮点击尚未完成专项验收。
- 不能把模拟器、编译、API 成功或合成消息测试等同于上述真机验收。
- 不能擅自将短信改成仅 Wi-Fi 同步。“像识字库一样、通过 Wi-Fi 获取”的含义尚未明确，不要自行实现 OCR 或规则下载服务。
- 保留 LAN/云多路去重、持久化后 ACK、账号隔离及旧加密配对兼容。账号云存明文正文，不是端到端加密。
- 不自动合并 PR。发布仍须按实时工作区的授权和规则执行。

## 文件导航与缺口

- app/src/main/res/：布局、颜色、尺寸、样式及背景资源。
- MainActivity.java、WindowLayout.java：主界面与窗口避让。
- BackgroundGuideActivity.java、BackgroundGuideContent.java：后台教程。
- ReceiverService.java、CloudSyncJobService.java、SmsReceiver.java：接收、调度与短信事件。
- app/src/test/：现有 Android 单元测试。
- windows/ui_windows.go、toast_windows.go、toast_native_windows.go：桌面界面和通知。
- windows/ 中的测试：通知策略、持久化等回归依据。
- web-ui/index.html：网页界面，仅供关联体验参考，优先处理手机和桌面。
- docs/background-guide-sources.md：品牌教程来源。
- file-manifest.md：资料文件清单与 SHA-256，可核对复制内容。

未包含 Cloudflare 服务端源码、构建工具链、二进制资源、安装包、缓存、依赖目录、日志、用户配置、凭据、签名或备份。需要服务端改动时，先列出缺失信息，不猜测接口行为。

本包不含实际界面截图。用户可另外补充脱敏截图，优先首页、键盘展开、设置、教程和电脑通知。不要上传真实短信、验证码、手机号或账户信息。

