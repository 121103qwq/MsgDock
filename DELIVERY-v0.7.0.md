# MsgDock v0.7.0 交付与验收

日期：2026-09-05。Web/API 已上线；Android/Windows 为已构建、待真机回归的本地候选。

## 架构和保留项

Android 收到 SMS 后先持久化账号 Outbox，再独立执行 LAN、账号 HTTPS 上传和旧 E2EE 云链路。
新 Worker API 使用 D1 存储账号、设备、会话及短信，Web 和 Windows 按序号增量拉取。

保留：短信监听、前台服务、JobScheduler、网络切换恢复、LAN 58123/58124、6 位配对、旧
`/v1/*` 协议、Android 设备备份、Windows 托盘/Toast/本地历史/可选自启。

旧的“配对房间 + ACK 密文队列”无法单独支持账号登录后的 Web 历史，所以新增明确分开的
`/api/v1/*`；旧 Durable Object 只用于兼容，不作为新账号 API 的依赖。Web 演示数据已替换。
账号模式正文在 D1 内可读，不是端到端加密；旧云链路仍为端到端加密。

## 变更文件

- Android：`app/build.gradle`、`app/src/main/AndroidManifest.xml`；Java 目录下
  `AccountApi.java`、`AccountOutboxStore.java`、`AccountStore.java`、`CloudSyncJobService.java`、
  `DeviceBackupManager.java`、`Forwarder.java`、`MainActivity.java`、`Notifications.java`、
  `ReceiverService.java`；`res/layout/activity_main.xml`、`AccountOutboxTest.java`。
- 构建：`gradle/wrapper/gradle-wrapper.properties`（修正官方分发 SHA-256）。
- Windows：`account_windows.go`、`account_windows_test.go`、`app.manifest`、`main.go`、
  `pending_windows.go`、`ui_windows.go`。
- Worker：`src/account.ts`、`src/index.ts`、`migrations/0001_accounts.sql`、`test/account.test.ts`、
  `test/apply-migrations.ts`、`vitest.config.ts`、`wrangler.jsonc`、`package.json`、`package-lock.json`。
- Web：`web-ui/index.html`、`web-ui/.assetsignore`、`web-ui/README.md`。
- 验收：`tools/live-account-test.ps1`。
- 文档：根目录 README、DESIGN、BUG、HANDOFF、AGENTS、CLOUDFLARE_DEPLOYMENT、本文件和
  `cloudflare/README.md`。

## D1 schema 与部署

Migration：`cloudflare/migrations/0001_accounts.sql`。包含 `users`、`devices`、`sessions`、
`messages` 和公开登录所需的 `auth_rate_limits`；短信按 `(user_id, client_message_id)` 去重，
按 `(user_id, seq)` 增量查询。

- D1：`msgdock` / `e9538d9c-f98a-40ae-b948-af7acdb56050`。
- Worker：`xgy-sms-relay` / `8f889ece-e9d7-4174-b7ef-1911c72f33ee`。
- Web：<https://msgdock.dpdns.org>。
- 旧域名：<https://xgy-sms-relay.xgy2021sh.workers.dev>。

后续部署在 `cloudflare/` 执行：

```powershell
npm ci
npm run typecheck
npm test
npx wrangler d1 migrations apply msgdock --remote
npx wrangler deploy
```

详细步骤和回滚边界见 `CLOUDFLARE_DEPLOYMENT.md`。不要重建 D1 或替换原有 Secret。

## 客户端构建和产物

Android 标准构建：

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本机 Gradle 在进入编译前遭遇 Java NIO loopback 故障；本次 APK 使用同一 Android SDK 的
`aapt2 + javac + d8 + zipalign + apksigner` 构建，不代表 Gradle lint 已通过。

Windows 构建（`windows/` 内）：

```powershell
go test ./...
go vet ./...
go build -buildvcs=false -trimpath -ldflags="-s -w -H=windowsgui" -o ..\outputs\MsgDock-v0.7.0\MsgDock-Windows-v0.7.0.exe .
```

交付目录：`outputs/MsgDock-v0.7.0/`，包含 APK、EXE、源码 ZIP、SHA256SUMS。
源码包排除本地配置、缓存、node_modules、Wrangler 凭据、构建产物和日志。

## API 简表与创建账号

| 方法 | 路径 | 认证 |
|---|---|---|
| POST | `/api/v1/auth/register`、`/api/v1/auth/login` | 无，基础限速 |
| POST | `/api/v1/auth/logout` | 当前 session |
| GET | `/api/v1/me` | session |
| GET / POST | `/api/v1/devices` | session |
| DELETE | `/api/v1/devices/:id` | 设备所属账号的 session |
| POST | `/api/v1/messages` | device token |
| GET | `/api/v1/messages?after=<seq>` | session 或 device token |

在 <https://msgdock.dpdns.org/register> 创建自己的账号，再在客户端使用同一账号登录。
未内置公开测试密码；线上测试生成随机账号/密码，不分享或写入源码。session 与 device token
分离，D1 只保存 token 哈希，Web Cookie 为 HttpOnly/Secure/SameSite=Lax。

## 测试结果

| 验证 | 结果 |
|---|---|
| Worker typecheck / Vitest | 通过，2 文件 10 项；205 条消息分页恢复 |
| Worker dry-run | 通过；随后实际部署成功 |
| HTTPS `/health`、Web `/login`、旧 `/v1/health` | 200 |
| 未授权短信请求 | 401 |
| 线上 API 闭环 | 16 项通过，见下面明细 |
| Windows test / vet / 构建 | 通过；PE subsystem = 2，无控制台 |
| Android JUnit | 30 项通过 |
| Android 手动 SDK 构建 / 签名 | 通过；v2/v3，包名 com.xgy.lansms，0.7.0 / 10 |
| Android Gradle lint | 未运行成功，主机 NIO loopback 阻塞 |
| 真机 SMS、锁屏/重启、Toast 点击、自启 | 尚未实机验收 |
| 浏览器实际布局与交互 | HTTP/脚本检查已做；本轮浏览器状态读取超时，未完成视觉验收 |

线上 16 项：原生注册、第二账号注册、正确密码登录、错误密码拒绝、Web Cookie 属性/无 JS
token、设备注册、上传、幂等重试、自身 Inbox/正文、跨账号隔离、外来设备删除拒绝、session
不能上传、增量游标、设备撤销、撤销后拒绝、session 注销。

线上验收保留 `smoke_64d405971c30_a`、`smoke_64d405971c30_b` 两个合成账号和一条测试短信；
所有测试 token 已撤销，随机密码已丢弃。不影响真实账号，不执行用户数据删除。

## 消融式审查

| 设计 | 删除/简化后的影响 | 决策与原因 |
|---|---|---|
| Android 持久 Outbox + 重试计时器/JobScheduler | 断网或进程回收会丢消息/延迟无法恢复 | 保留，分别负责持久性与进程内/外重试 |
| 独立 AccountApi/Store/Outbox | 混入旧 E2EE token 与队列会破坏账号隔离 | 保留，只有网络、凭据、消息队列三类实际职责 |
| D1 账号/设备/session/消息四表 | 无法隔离账号、撤销设备或保存历史 | 保留，没有额外 Repository/Service 层 |
| auth_rate_limits | 公开密码接口无基础防滥用 | 保留，一个 D1 表，不新增服务 |
| PBKDF2 + 用途隔离 HMAC | 平台迭代上限下，D1 单独泄露更易离线猜密码 | 保留，复用 Web Crypto/已有 Secret，无新依赖 |
| 旧 Durable Object | 已配对设备无法继续使用 | 仅为兼容保留，新账号链路不使用 |
| Windows pending/history/cursor | 重启、通知失败和双路径到达容易漏/重复 | 复用原账本，未新增第二套历史系统 |
| 无框架 Web + 分页轮询 | 去掉轮询无法自动更新；去掉分页会漏跨页消息 | 保留；没有引入前端状态框架或 WebSocket |

未增加 JWT/refresh-token、Redis、KV、R2、Queue、微服务、角色系统、QR 或国内 Relay。

## 剩余问题

- 真机回归、HyperOS 后台限制和系统强行停止边界仍需用户实际测试。
- APK 是测试签名，EXE 未商业签名；本机 Gradle/lint 环境问题见 BUG B-005。
- Web/Windows 每 3 秒请求会消耗 Workers/D1 配额；未做大规模并发或中国大陆多运营商网络压测。
- 账号云模式的短信正文在 D1 可读；不能宣传为 E2EE。
- 本轮额外安装的 Cloudflare Skills/MCP 属于本机 Codex 环境，不打包进 MsgDock；MCP OAuth 状态
  与 Wrangler 部署授权相互独立，不应混淆“已配置”和“已授权”。
