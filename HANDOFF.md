# Codex / Zcode 交接板

更新时间：2026-09-06

## 使用方法

这是两个 Agent 切换工作的唯一交接点。每轮开始先刷新本文件；每轮结束必须把结果写回来。不要把聊天记录当作唯一上下文。

状态只使用：`IDLE`、`IN_PROGRESS`、`DONE`、`BLOCKED`。

## 当前认领

| Agent | 状态 | 本轮任务 | 独占文件/目录 | 开始时间 |
|---|---|---|---|---|
| Codex | IN_PROGRESS | Android v0.7.4 本机收件箱、独立模拟器验收及网站对应下载交付 | MainActivity.java、CloudInboxStore.java、AccountInboxTest.java、activity_main.xml、app/build.gradle、tools/test-android-ui-mcp.cjs、tools/test-web-ui.mjs、web-ui/index.html、AGENTS.md、README.md、DESIGN.md、BUG.md、HANDOFF.md；发布快照及版本说明 | 2026-09-06 |
| Zcode | IDLE | 无 | 无 | - |

认领规则：

1. 开工前填写自己的行，并保存本文件。
2. 再读一次对方的行；文件范围重叠时不得开工。
3. 公共协议、Gradle 根配置、README 和本文件视为共享文件，同一时刻只能由一个 Agent 修改。
4. 完工后将状态改为 `DONE` 或 `BLOCKED`，释放“独占文件/目录”，并在下方追加一条简短记录。

## 当前基线

- 源码来源：`XgyLanSms-source-v0.6.1.zip` 的干净副本；未包含构建缓存、APK、EXE、`node_modules`、Wrangler 登录信息或密钥。
- Android：v0.7.3 / versionCode 13，本地 APK 候选已构建并在独立 Android 14 模拟器验证，尚未上传新 Release。
- Windows：v0.7.0，本地无控制台单 EXE 候选已构建。
- Relay：旧 `/v1/*` v0.6.0 兼容逻辑保留；本地 v0.7.0 候选增加 D1 账号 API 与 Web。
- 已知部署：`https://msgdock.dpdns.org`，部署 ID `40fae349-ca91-4296-b86c-e68d0c57955d`；旧 workers.dev 地址仍正常。
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

### 2026-09-06 / Codex / IN_PROGRESS — Android 本机收件箱与对应网站下载

- 用户追加要求：每个新版本构建同步更新网站对应下载链接。已写入本项目 AGENTS；Android 与 Windows 独立标版本，不发布缺失或不匹配的链接。
- Android v0.7.4/code14：首页本机收件箱显示未登录 LAN/配对云端历史及当前账号历史，先隔离账号再按 deliveryId 去重，展示最近 200 条、保留底层历史；列表/详情标明来源时间，复制前复核账号。
- SDK 编译、56 项 JVM、APK v2/v3 签名通过。MCP 首次在安装前因未登录设备没有账号偏好文件而中止；修正测试前置假设后，v0.7.3 覆盖升级保留历史、真实 LAN HTTP 重发去重、全文/验证码实际粘贴、进程重开、本地账号 A/B/退出隔离共 5 项全部通过。报告 build/android-ui-2026-09-06T06-27-53-094Z/result.json；crash-log.txt 为空，合成账号数据已恢复，截图已人工检查。
- 网站 12 项回归、Worker typecheck、keep-vars/strict dry-run 通过；未改 Worker 逻辑、配置、绑定、D1 schema 或 Windows。
- 产物：outputs/MsgDock-v0.7.4/MsgDock-Android-v0.7.4-debug.apk，103496 字节；SHA-256 32B07514D26C74FDA406209D7941C12A498441A0CA9A32CAD040E428A804684F。与旧版使用同一调试证书。
- 消融审查：沿用一个 JSONL，只增加有上限的去重读取与来源格式化；无新数据库、后台服务、生产依赖或通用框架。测试复用已有 MCP 脚本。
- 清理：临时转发已移除，独立模拟器与 5038 ADB 已关闭；模拟器退出时设备注销有短暂滞后，待列表清空后仅重试专用 ADB 清理。
- 发布状态：本条记录随已测试源码快照提交，随后上传预览 Release 并部署网站；线上结果完成后补记。真实 SMS、锁屏、HyperOS、后台保活和真机主备切换未验证；未进行真实账号端到端收发，Gradle lint 仍受 B-005 限制。

### 2026-09-06 / Codex / DONE — Android 权限和真实接收状态

- 目标：按用户要求逐项改进并在 Android 设备运行验收。本轮未检测到 USB 调试真机，使用 Codex 独立模拟器；仍保留真机验收门槛，不将本目标标为整体完成。
- 修改：MainActivity 权限回调及 Activity 重建恢复、拒绝通知仍接收、一次有时限 LAN 扫描/防重复/销毁取消/失败提示；ReceiverService 显示实际启动与监听结果。保留 SMS 广播、6 位配对、LAN 协议和独立云循环；未改 Worker/Windows。
- 三轮验证：SDK 完整编译 + 53 项 JVM + APK v2/v3 签名；Android 14 MCP 权限/界面及实际 LAN HTTP 7 项；端口占用、故障解除、进程重开及权限弹窗旋转 4 项。后两轮通过报告：build/android-ui-2026-09-05T17-40-08-418Z/result.json、build/android-ui-2026-09-05T17-45-48-124Z/result.json，截图和空崩溃日志在同目录。
- 具体结果：拒绝通知时仍鉴权接收并持久保存，同 UUID 重发只保存一次；故意占用端口复现 EADDRINUSE，界面报 LAN 失败；释放后停止/启动可恢复。显式重开进程保留启用设置与历史；不是强行停止后自动复活，也不是整机重启验收。
- 产物：outputs/MsgDock-v0.7.3/MsgDock-Android-v0.7.3-debug.apk；SHA-256 FF9C7925A144AF52F52CCAAEEBAB715A699F2155C31B11971FAE81117D56BBC4。versionCode 13，包名/调试证书与旧版相同。
- 消融审查：保留一个待授权动作编号和一个实时服务状态，分别保证权限返回后正确继续和避免误报；删除每次扫描永久存活的 Executor，使用单次有时限线程。无新 Manager、服务、配置文件、生产依赖或外部测试框架。
- 清理：本轮临时 ADB forward/reverse 已移除，独立模拟器及 5038 ADB 已关闭，原 Oppo Connect 5037/PID 14480 未动。首次停止时设备注销记录短暂滞后，先核对模拟器已退出且列表清空，再仅重试专用 ADB 清理，没有强制终止其他进程。
- 边界：未登录或创建真实账号、未发送真实短信、未安装到真机、未推送 GitHub/发布 Release/部署。Gradle lint 仍受 B-005 主机故障限制。下一步需连接测试手机并允许 USB 调试，覆盖安装后验收首次授权、真实收发、锁屏及重开行为。

### 2026-09-05 / Codex / DONE — Web 优化上线

- 用户在告知待部署后回复“继续”，授权发布。现场确认旧生产版本 4016d74e-15c9-49c6-8a0c-3ef4d5af04a5；使用现有 Wrangler 4.125.0，keep-vars/strict 发布，未更改配置、Secret 或 D1 schema。
- 新生产版本 40fae349-ca91-4296-b86c-e68d0c57955d。部署输出只新增/修改 /index.html 一个静态文件，保留微信 TXT、旧 Relay、主/备用域名及所有绑定。
- 两轮必要验证：部署前 11 项 Web 回归、TypeScript 与 dry-run 通过；部署后 / 和 /inbox 返回 200，线上完整内联脚本与本地一致，HTML public/must-revalidate/max-age=0；两个域名 health 200；未登录 messages 401/no-store；微信 TXT 200 且与本地一致。
- 所有本轮命令进程正常退出，无新增服务器/浏览器标签。未改客户端下载 v0.7.0、未重新构建 APK/EXE、未推送 GitHub。实机浏览器体验/跨网络性能仍需用户验收，不把 HTTP 成功当成性能评分。

### 2026-09-05 / Codex / DONE — Web 低复杂度性能优化（本地）

- 根据用户粘贴建议核对现状：域名已绑定同源 Web/API；首页本轮 HTTP 基线 200、Brotli、CF-Cache-Status HIT、public/max-age=0/must-revalidate、alt-svc h3，动态 JSON 原有 no-store 已具备。保留这些有效配置，不重复加缓存层。
- 修改 web-ui/index.html：站内导航不重载；恢复/最近页并行；无消息不重绘，有消息保留展开项；单次请求 15 秒超时；完成后调度下一轮，错误退避到 60 秒；隐藏/离线暂停，恢复事件立即补齐。分页内存上限 200，全部分页成功才提交游标，旧账号响应隔离；API 客户端显式 no-store。
- 新增 tools/test-web-ui.mjs（Node 自带 test/vm，无依赖），11 项通过：缓存选项、空轮询不重绘、并行恢复去重、304 条缺口分页/200 条保留上限、账号隔离、无并发堆积、退避上限、隐藏/离线恢复、请求超时、坏 JSON 不推进游标、401 和站内导航。
- 两轮必要验证：线上只读 HTTP 基线 + 本地行为测试。IPv4 DNS 0.015582s / TCP 0.216848s / TLS 0.513620s / TTFB 0.772442s / total 0.772545s，收到 7378 字节；IPv6 curl 6 无法解析，不能比较速度。使用 --noproxy，但未切换用户代理/TUN，不能声称纯大陆直连。
- 新 HTML 25480 字节，本地 Brotli 7014 字节；本地压缩条件与线上不同，不作为线上优化百分比。Chrome DevTools MCP 不可用，按 web-perf 技能停止浏览器性能 trace，不报告 LCP/INP/CLS 分数。
- 消融审查：保留现有单文件/短 HTTPS API，不增加哈希资源构建、长连接、缓存正文、数据库表、第三方依赖或优选 IP；共享超时和单一轮询延迟状态分别解决卡死与请求堆积，需保留。Android/Windows/Worker、TXT 验证文件和下载版本未改。
- 同步 web-ui/README.md 和 DESIGN.md。按 AGENTS 发布边界，本轮未部署 Worker、未推送 GitHub；用户授权发布后部署此静态页面即可，无 D1 migration/Secret/客户端重打包需求。

### 2026-09-05 / Codex / DONE — Android 固定主备 Relay

- 主地址 msgdock.dpdns.org、备用 xgy-sms-relay.xgy2021sh.workers.dev 固定内置；移除地址编辑和保存按钮。每次优先主地址，旧保存链路也采用主备顺序，原凭据/消息 ID/历史不修改。
- RelayHttp 复用账号、加密 Relay 和设备备份原有 HTTP 请求。确定的 DNS/TCP/TLS 建连失败可走备用；发送后仅安全读取、去重消息上传和 ACK 能重放。未知写入结果、认证失败、冲突、限流和重定向不触发重放；历史自定义来源不跨域发送凭据。LAN 与原有 Outbox/轮询调度未改。
- 文件：app/build.gradle；RelayHttp.java；RelayHttpTest.java；CloudRelay.java；AccountApi.java；DeviceBackupManager.java；MainActivity.java；activity_main.xml；README.md；DESIGN.md；HANDOFF.md。
- 两轮必要验证：最终 SDK 编译、53 项 JVM 测试、APK v2/v3 签名通过；aapt 确认包名 com.xgy.lansms、v0.7.2/code12；主备 /health 均返回 200。未运行 Gradle lint；ADB 无连接设备，手机覆盖安装/锁屏/真实断网主备切换仍待验收。
- APK：outputs/MsgDock-v0.7.2/MsgDock-Android-v0.7.2-debug.apk；SHA-256 D16357439C18EFB0463F3F1F70378940789E855623E4C9A78E3D0049034FFBB2。调试证书 SHA-256 8b72e245377b56f06af95753d8fd396a880bf608a3161b6c175e443d94dbf82a 与旧版一致。
- 消融审查：合并三份重复 HTTP 实现，保留一个主备传输实现及无状态测试入口；无探测线程、缓存、额外后台服务或依赖。未修改 Windows/Worker，未推送 GitHub/发布 Release/安装 APK；网站下载仍是 v0.7.0。

### 2026-09-05 18:00 / Codex / DONE — Android 账号接收、注册修复、微信验证

- Android 新增可选账号接收与本机历史/复制，独立短 HTTPS 轮询、持久游标、LAN/云通知去重与账号隔离；设备撤销等待重新登录。旧 LAN、SMS 广播和配对接收保留。
- APK：outputs/MsgDock-v0.7.1/MsgDock-Android-v0.7.1-debug.apk；SHA-256 1AC6B21C8CA8EEE2467E012D434AF3E7EECE8166D1C95BAE6328D1A3893A0BF7。versionCode 11，包名及调试证书与旧版一致。40 项 JVM 测试、SDK 编译和签名验证通过；ADB 无真机，未执行覆盖安装和锁屏验收。
- 复现网页 HTTP 表单可用但 Origin 被拒；HTTPS 同源入口也误受域名白名单影响。Worker 先处理静态页面、HTTP 308 HTTPS、拒绝 HTTP POST、允许 HTTPS 精确同源，陌生/null Origin 继续拒绝。
- Worker typecheck、13 项测试及 dry-run 通过；线上同源注册、Secure/HttpOnly Cookie /me、注销通过；HTTP 注册页 308，主域名/旧 workers.dev 预检 204，外部来源 403，两个 health 200。仅新增无短信内容的合成测试账号，测试 session 已注销。
- 按用户截图发布 web-ui/26a982afdfe8ade2696ce7d9359ea892.txt；HTTPS 返回 200 text/plain 且内容一致。用户需要在微信页面点击开始验证，不能把文件可访问当成微信审核通过。
- 消融审查：复用旧前台服务、JobScheduler、收件账本和 Worker；不增加数据库表/服务/生产依赖。仅测试使用 org.json；SDK 构建脚本保留用于已知 Gradle 主机故障。
- 本次没有推送 GitHub、修改已有 Release 或更新网站 APK 下载版本；当前网站下载仍是 v0.7.0。本地新源码与 APK 待用户实机测试/后续授权发布。

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
