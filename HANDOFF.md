# Codex / Zcode 交接板

更新时间：2026-09-05

## 使用方法

这是两个 Agent 切换工作的唯一交接点。每轮开始先刷新本文件；每轮结束必须把结果写回来。不要把聊天记录当作唯一上下文。

状态只使用：`IDLE`、`IN_PROGRESS`、`DONE`、`BLOCKED`。

## 当前认领

| Agent | 状态 | 本轮任务 | 独占文件/目录 | 开始时间 |
|---|---|---|---|---|
| Codex | DONE | 公开仓库、v0.7.0 预览 Release 和网页下载入口已发布 | 无 | 2026-09-05 |
| Zcode | IDLE | 无 | 无 | - |

认领规则：

1. 开工前填写自己的行，并保存本文件。
2. 再读一次对方的行；文件范围重叠时不得开工。
3. 公共协议、Gradle 根配置、README 和本文件视为共享文件，同一时刻只能由一个 Agent 修改。
4. 完工后将状态改为 `DONE` 或 `BLOCKED`，释放“独占文件/目录”，并在下方追加一条简短记录。

## 当前基线

- 源码来源：`XgyLanSms-source-v0.6.1.zip` 的干净副本；未包含构建缓存、APK、EXE、`node_modules`、Wrangler 登录信息或密钥。
- Android：v0.7.0 / versionCode 10，本地 APK 候选已构建。
- Windows：v0.7.0，本地无控制台单 EXE 候选已构建。
- Relay：旧 `/v1/*` v0.6.0 兼容逻辑保留；本地 v0.7.0 候选增加 D1 账号 API 与 Web。
- 已知部署：`https://msgdock.dpdns.org`，部署 ID `14f86902-a686-4227-b08e-57c8f2cf1b27`；旧 workers.dev 地址仍正常。
- 上一基线验证：Android 25 个单元测试通过、lint 0 errors；Relay 测试/typecheck/dry-run 通过；Windows 测试、vet、GUI 构建曾通过。
- 未完成：没有连接真实 Android/ADB 设备，因此恢复、锁屏、HyperOS 和真实 SMS 端到端仍需实机验收。

## 建议任务池

| 优先级 | 任务 | 推荐独占范围 | 前置条件 |
|---|---|---|---|
| P0 | Android 实机验收并记录可复现结果 | 先不改代码；必要时只改 `BUG.md` | 有测试手机与 ADB |
| P1 | 修复实机发现的恢复/配对状态问题 | `app/` 中明确到文件 | 先有复现证据 |
| P1 | Windows Toast/历史/开机启动回归 | `windows/` | Windows 10/11 |
| P2 | 设计 ACK 后云历史浏览/恢复 | 先只改 `DESIGN.md` | 用户确认需求与保留策略 |

## 交接记录

### 2026-09-05 / Codex / DONE — GitHub 与网页下载

- 新建公开仓库 https://github.com/121103qwq/MsgDock ，旧私有 codex-cloud-test 未修改。
- 源码提交 `06a967e0f69ff6354796d629db6ccc75d71a3952` 位于 `release/msgdock-v0.7.0`；PR #1 未合并。预览 Release `v0.7.0` 标签和源码 ZIP 均指向此提交。
- Release 只包含 Android debug APK、Windows x64 EXE、源码 ZIP 和 SHA256SUMS；四个资产的 GitHub SHA-256 与本地一致，匿名下载 HEAD 均为 200。
- 网站未登录/登录后共用静态下载区，不增加下载 API、状态或依赖；Worker 仅静态 index.html 发生变化。
- 验证：源码敏感文件排除、客户端哈希与版本、网页 JS 语法、Wrangler dry-run、线上 HTML（扣除 Cloudflare 自动注入统计脚本后）一致；主页和两条 health 地址 200，未登录 messages 401。
- 更新旧手动构建工作流，Windows 测试移到 windows-latest；本轮没有运行 GitHub Actions，不宣称 CI 通过。保留旧代码现有尾随空白，未做无关格式化。
- 消融审查：直接复用 GitHub Release 和现有静态网页，无新服务、路由或下载状态管理。
- 遗留：真实手机锁屏/HyperOS/SMS 与 Toast 点击仍待验收；Android 为调试签名，Windows 未签名。
- 共享开发仍使用本目录；`../work/msgdock-publish` 是发布快照 checkout，不作为第二开发工作区。安装包未因这次网页更新而重新构建。

### 2026-09-04 / Codex / DONE

- 建立独立共享工作区及协作规则。
- 从 v0.6.1 干净源码包提取，没有复制构建缓存和成品。
- 新增 `AGENTS.md`、`DESIGN.md`、`BUG.md`、`HANDOFF.md`、`START_PROMPT.md` 和 `.gitignore`。
- 本轮只整理工作区和长期上下文，没有重新构建应用或改变功能。

后续记录格式：

```text
### YYYY-MM-DD HH:mm / Agent / DONE|BLOCKED
- 目标：
- 修改：
- 验证：
- 遗留：
```
### 2026-09-04 21:48 / Zcode / DONE

- **目标**：为 Xgy LAN SMS 创建现代化 UI 并构建新版本
- **修改**：
  - Android：创建 Material Design 3 深色主题资源（colors.xml, dimens.xml, styles.xml，青色系配色）
  - Android：创建 XML layout 文件（activity_main.xml, item_cloud_link.xml, item_target.xml）
  - Android：重写 MainActivity.java 使用 XML 布局替代纯代码构建，保持所有功能逻辑 100% 向后兼容
  - Android：更新 AndroidManifest.xml 使用新的 AppTheme
  - Android：修复中文引号导致的编译错误（activity_main.xml 和 MainActivity.java）
  - Windows：成功构建新 UI 版本 EXE（保持功能不变）
  - Web：创建单文件 Web 管理面板（web-ui/index.html）用于状态查看和设备管理
  - Web：提供响应式设计、实时状态展示（演示数据）、部署文档（web-ui/README.md）
  - 文档：更新 DESIGN.md 记录 UI 设计原则和实现细节
  - 配置：创建 local.properties 配置 Android SDK 路径
- **验证**：
  - ✅ Android：成功编译 `app-debug.apk`（90KB，versionCode 9）
  - ✅ Windows：成功构建 `XgyLanSmsReceiver-v0.5.0-new-ui.exe`（11MB）
  - ✅ Windows：所有测试通过（`go test ./...`）
  - ⚠️  未在真机上测试 UI 渲染和交互
- **构建产物**：
  - `XgyLanSms-v0.6.1-new-ui.apk` (90KB)
    - SHA-256: `eb290e563d4c876d967ac89ccff5a11175613ee939e76d5928c5c302066ab409`
  - `XgyLanSmsReceiver-v0.5.0-new-ui.exe` (11MB)
    - SHA-256: `9e5458ba928b85e4b4423eb61deac6d00592319591272407a60b3ae21186326f`
- **遗留**：
  - P0：需要在 Android 真机上验证新 UI 的渲染、布局、交互和深色主题对比度
  - P1：Web 面板当前为静态演示，需实现后端 API（/api/status, /api/devices, /api/messages）
  - P2：Web 面板未包含认证机制，仅适合可信网络
  - P2：考虑未来为 Windows 端添加内置 Web 服务器以提供管理面板

### 2026-09-05 15:30 / Codex / BLOCKED

- 目标：实现 MsgDock 账号/D1/Web 模式、Android 可靠 Outbox、Windows 账号轮询与本地交付。
- 修改：新增 `/api/v1` 账号、设备和消息 API 与 D1 migration；Web 改为真实登录收件箱；Android
  增加先落盘账号 Outbox、独立三路转发、指定退避与网络切换重试；Windows 增加账号登录、3 秒
  增量补齐，并保持无控制台、托盘、Toast、历史和旧协议。
- 验证：Worker typecheck、10 项 Vitest 与 Wrangler dry-run 通过；Windows test/vet 和 GUI PE
  subsystem 验证通过；Android 使用 SDK 工具完整编译，30 项 JUnit 与 APK v2/v3 签名校验通过。
- 产物：`outputs/MsgDock-v0.7.0/` 下 Android APK、Windows EXE、源码 ZIP 与 SHA-256 清单。
- 遗留：当前 Cloudflare 页面未登录，Wrangler token 缺少 D1/route 权限；用户登录后继续创建 D1、
  应用 migration、部署 `msgdock.dpdns.org` 并做线上与真机闭环验收。

### 2026-09-05 / Codex / DONE

- 用户登录并授权 Wrangler；创建 `msgdock` D1、应用 0001 migration、部署同源 Web/API 和自定义域名。
- 线上发现并修复 PBKDF2 迭代上限：100,000 次 + 用途隔离 HMAC；旧 Secret、DO 与云链路未替换。
- 本地 typecheck/10 项测试通过，线上 16 项账号/API 验收通过；旧云正在使用的拉取请求仍返回 200。
- 新增 `DELIVERY-v0.7.0.md` 汇总架构、文件、构建、API、测试和消融审查；更新源码 ZIP/校验清单。
- 待用户真机检查锁屏 SMS、网络切换、Toast 操作和开机自启。本轮 UI 读取超时，未把 HTTP 成功等同于视觉验收。
