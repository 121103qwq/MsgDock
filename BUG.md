# Bug 跟踪

## 待验证 / 未解决

### B-001 Android 真实设备恢复链路尚未完整验收

- 状态：`NEEDS_DEVICE_TEST`
- 现象：代码与单元测试已覆盖启动合并和持久重试，但最终构建时没有连接 ADB 设备。
- 需要验证：覆盖安装、卸载重装、仅保留部分本地链接、锁屏/进程回收/手机重启、HyperOS 后台限制、真实 SMS 的 LAN + WAN 端到端去重。
- 完成标准：记录设备/系统版本、步骤和结果；失败时保存相关日志，不先重构协议。

### B-002 Android 接收端配对确认在进程重启后的恢复仍需实机确认

- 状态：`OPEN`
- 触发：接收端已生成配对会话，但确认阶段失败或进程被系统终止后重新打开。
- 风险：会话虽持久化，自动继续确认与界面状态是否始终一致尚未完成真实设备验证。
- 方向：先复现并检查持久状态；若确有缺口，再补幂等确认重试，不生成第二套链路。

### B-003 自定义 Relay 与设备备份策略尚未统一

- 状态：`OPEN_LOW_PRIORITY`
- 现状：主流程默认使用项目内置 Relay；设备恢复语义以默认 Worker 为准。
- 风险：将来开放自定义 Relay 后，若对方未实现设备备份 API，界面和恢复能力需要明确降级。
- 方向：只有用户要求支持自定义 Relay 时再设计能力探测，当前不要扩展范围。

### B-004 MsgDock D1 与自定义域名部署

- 状态：`RESOLVED_2026_09_05`
- 原因：Wrangler OAuth 缺少 D1/route/zone 权限。
- 处理：用户授权后创建 D1、应用 migration、绑定自定义域名并部署；HTTPS 及 16 项线上 API 验收通过。

### B-005 本机 Gradle 不能建立 Java NIO loopback

- 状态：`HOST_ENVIRONMENT`
- 现象：Gradle 8.14.3 在 Zulu JDK 17/21 下启动 single-use daemon 前均报
  `java.io.IOException: Unable to establish loopback connection`，根因位于 JDK `PipeImpl` 的本机 AF_UNIX 连接，
  还未进入项目编译。
- 当前处理：已用 Android SDK 的 `aapt2/javac/d8/zipalign/apksigner` 完整构建 v0.7.0，30 项 JUnit
  测试通过；Gradle lint 仍无法在本机执行。
- 方向：不为应用代码增加规避分支；换到 loopback 正常的 JDK/主机运行标准 Gradle 验证。

## 已解决

### B-101 v0.6.0 只在本地链路完全为空时恢复

- 状态：`RESOLVED_IN_0.6.1`
- 原因：启动恢复把“已有任意本地链接”误当作无需读取云备份。
- 修复：每次启动校验两侧并合并，保留有效本地凭据、补入有效远端缺失项、排除已撤销项。

### B-102 Windows Toast 未出现

- 状态：`RESOLVED_IN_0.3.1+`
- 原因：GUI 线程的 COM apartment 与 WinRT 初始化冲突。
- 修复：在锁定 OS 线程的专用通知 worker 中调用 Toast；保留兼容气泡兜底。

### B-103 旧版占用 58123 导致新版未启动

- 状态：`RESOLVED_OPERATIONALLY`
- 原因：旧接收器进程仍占用 TCP `58123`。
- 处理：替换版本前先查端口所有者并结束明确的旧版进程，不盲目终止其他程序。

### B-104 LAN 与云端重复通知

- 状态：`RESOLVED`
- 修复：两条发送路径复用相同 UUID，接收端持久去重，并在通知成功后 ACK。

### B-105 Gradle Wrapper 校验值错误

- 状态：`RESOLVED_IN_0.7.0`
- 原因：仓库中的 Gradle 8.14.3 `distributionSha256Sum` 与官方发布校验值不一致。
- 修复：改为官方 binary ZIP SHA-256
  `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`。

### B-106 Web 管理页只有演示数据

- 状态：`RESOLVED_IN_0.7.0`
- 修复：改为真实账号 SPA，接入 `/api/v1` 登录、Inbox、设备和按 `last_seq` 分页恢复。

### B-107 生产注册返回 500，但本地账号测试通过

- 状态：`RESOLVED_2026_09_05`
- 原因：生产 Web Crypto PBKDF2 最多支持 100,000 次，原代码使用 310,000 次；模拟器未复现此限制。
- 修复：改用支持的 100,000 次并增加服务端用途隔离 HMAC；不引入第三方加密依赖，不降低为快哈希。
- 验证：typecheck、10 项本地测试及 16 项线上 API 测试通过，包括正确/错误密码、设备撤销和用户隔离。
