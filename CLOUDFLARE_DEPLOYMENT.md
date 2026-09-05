# MsgDock v0.7.0 Cloudflare 部署

## 目标

- Worker：`xgy-sms-relay`（沿用原名，避免旧 `/v1/*` 客户端失联）
- Web/API：`https://msgdock.dpdns.org`
- 兼容地址：`https://xgy-sms-relay.xgy2021sh.workers.dev`
- D1：`msgdock`，绑定名 `DB`
- Durable Object：`RELAY`，继续承载旧端到端加密协议
- 静态资源：`../web-ui`，通过 `ASSETS` 同源提供

已于 2026-09-05 部署并完成 HTTPS API 验收：
- D1 ID：`e9538d9c-f98a-40ae-b948-af7acdb56050`，区域 WNAM。
- 当前 Worker 版本：`8f889ece-e9d7-4174-b7ef-1911c72f33ee`。
- 原有 `DEVICE_BACKUP_PEPPER` 保留，旧 `/v1/health` 正常。

后续更新直接复用配置中的真实 D1 ID，不要再次创建同名数据库。

## 部署顺序

```powershell
Set-Location cloudflare
npm ci
npm run typecheck
npm test

# 确认登录账号和权限
npx wrangler whoami

# 只在数据库还不存在时创建一次
npx wrangler d1 create msgdock

# 把上一步返回的 database_id 写入 wrangler.jsonc 后：
npx wrangler d1 migrations apply msgdock --remote
npx wrangler deploy
```

不要重新生成或覆盖已有 `DEVICE_BACKUP_PEPPER`。同名 Worker 的 Secret 会在普通部署时保留；
它同时用于旧设备备份和新密码哈希的用途隔离 HMAC；丢失或替换会影响备份恢复与账号登录。
只有 `npx wrangler secret list` 明确显示缺失时，才需要用户提供/创建新的随机 Secret：

```powershell
npx wrangler secret put DEVICE_BACKUP_PEPPER
```

## 验证

```powershell
Resolve-DnsName msgdock.dpdns.org -Server 1.1.1.1
Invoke-RestMethod https://msgdock.dpdns.org/health
```

浏览器检查：

1. 打开 `https://msgdock.dpdns.org/register`。
2. 注册临时账号并进入 `/inbox`。
3. `/devices` 只能显示本账号设备。
4. Android 用同一账号登录并发送一条测试 SMS。
5. Web 在 3 秒内出现短信；Windows Toast 出现且可复制验证码/全文。
6. 断开 Windows 网络，产生多条短信，再联网确认从 `last_seq` 补齐且不重复。

## 数据库结构

Migration：`cloudflare/migrations/0001_accounts.sql`

- `users`：账号与 PBKDF2 密码哈希。
- `devices`：账号设备与 device token 哈希。
- `sessions`：Web/原生会话与 session token 哈希。
- `messages`：按用户隔离的短信历史，`UNIQUE(user_id, client_message_id)` 去重。
- `auth_rate_limits`：登录和注册的基础 IP 限速。

Migration 由 Wrangler 的 D1 migrations 机制记录，重复执行不会重新创建表。旧 Relay 的
SQLite 数据位于 Durable Object 内，与 D1 表互不冲突。

生产 Workers 限制 PBKDF2 单次最多 100,000 次；本项目使用随机 salt、100,000 次
PBKDF2-SHA256，再对派生结果做带独立用途前缀的服务端 HMAC-SHA256。
Secret 不在 D1 和源码中。不要再调到 310,000 次：本地模拟器可通过但生产注册会返回 500。

线上 API 回归脚本：`tools/live-account-test.ps1`。2026-09-05 共 16 项通过。
脚本只产生合成短信，不读取真实短信；保留两个随机命名测试账号和一条合成历史，最终撤销
全部测试 session/device token，丢弃随机密码，不在仓库留下可登录凭据。

## 回滚边界

部署前不要删除旧 Durable Object binding、`v1` migration 或 `DEVICE_BACKUP_PEPPER`。如果新账号
API 有问题，可以回滚 Worker 版本；D1 migration 只新增表，不修改旧 Relay 数据。Android/Windows
旧 LAN 和 `/v1/*` 路径仍可继续工作。
