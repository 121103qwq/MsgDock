# MsgDock 协作规则

## 开始前

1. 依次阅读 `README.md`、`DESIGN.md`、`BUG.md`、`HANDOFF.md`。
2. 在 `HANDOFF.md` 自己的认领栏写清任务、独占文件和状态，再开始修改。
3. 如果目标文件已被另一个 Agent 标为 `IN_PROGRESS`，不要修改；改做不重叠的任务，或等待对方交接。

## 协作边界

- 本目录是 Codex 与 Zcode 共用的唯一开发工作区；不要从旧目录复制单个文件覆盖这里。
- 保留用户和另一个 Agent 的修改，不做大范围回退、格式化或机械重写。
- 一次认领尽量限制在一个组件和一组明确文件。跨 Android、Windows、Relay 的协议变更必须先写入 `DESIGN.md`。
- 完成或阻塞时更新 `HANDOFF.md`：写明修改、验证、遗留问题，并释放文件认领。
- `README.md` 面向用户；`DESIGN.md` 记录长期设计；`BUG.md` 只跟踪真实问题；调试流水只写在本轮交接中。

## 不可破坏的兼容约束

- LAN v1：TCP `58123`、UDP `58124`、`POST /sms`、`X-Xgy-Key`、发现串 `XGY_SMS_V1|name|ip|port`。
- 保留 6 位一次性配对流程；配对码不是加密密钥。
- LAN 与云端并发发送，同一短信沿两条路径复用同一个 UUID，接收端必须去重。
- 旧 `/v1/*` 配对 Relay 保持端到端加密，只能保存密文和路由元数据。
- 新 `/api/v1/*` 账号同步用于密码登录后的 Web Inbox，正文通过 HTTPS 上传并保存在 D1；不得把它宣传为端到端加密，也不得与旧配对 token 混用。
- Cloudflare 的 `deviceId` 是单条链路端点 ID，不是 Android 的统一机器码。
- Android 不允许手输机器码。机器码仅在首次云登记成功后显示；启动时始终校验并合并本地/云端有效链路，已撤销或有删除墓碑的链路不得复活。
- Windows 必须保持无控制台、无默认浏览器、托盘驻留、原生 Toast、复制验证码/全文、本地历史先落盘再 ACK。

## 组件基线

- Android：`app/`，v0.7.0（versionCode 10）。
- Windows：`windows/`，v0.7.0；内部配置目录和通知 AppID 仍保留旧标识以兼容升级。
- Cloudflare：`cloudflare/`，v0.7.0 账号/D1/Web 与旧 v0.6.0 Relay 共存。
- 协议：`PROTOCOL_V2.md` 与 `PROTOCOL_V2_TEST_VECTOR.json`。

## 最小验证

只运行与本轮修改有关的验证；涉及公共协议时三个组件都要验证。

```powershell
# Android
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Windows
Set-Location windows
go test ./...
go vet ./...
go build -buildvcs=false -trimpath -ldflags="-s -w -H=windowsgui" -o ..\outputs\MsgDock-v0.7.0\MsgDock-Windows-v0.7.0.exe .

# Cloudflare
Set-Location cloudflare
npm ci
npm run typecheck
npm test
npm exec wrangler deploy -- --dry-run
```

## 发布与敏感信息

- 每次交付新版本构建时，同步上传对应安装包并更新网站下载链接；核对线上资产的版本、SHA-256 和实际可下载性后才算交付完成。Android 与 Windows 独立标注版本，未更新的平台保留原有效链接。
- 不提交或分享 `local.properties`、构建目录、`node_modules`、`.wrangler`、APK、EXE、日志、账号凭据或密钥。
- 未经用户在当前任务明确要求，不部署 Worker、不安装 APK、不推送 GitHub、不创建 PR/Release。
- 真实设备结果和线上部署状态必须现场验证；不能用单元测试代替。
