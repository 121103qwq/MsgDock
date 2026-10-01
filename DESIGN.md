# MsgDock 设计

## 目标

把 Android 收到的传统 SMS 可靠地转发到局域网及广域网内的 Windows、Web、Android 手机或平板。局域网优先体现为低延迟，但不是云链路的前置条件；各条链路互不阻塞，并通过同一个 `client_message_id` 去重。

## 组件

- `app/`：Android SMS 发送端与 Android/Pad 接收端，负责权限、配对、加密、重试、通知、历史和设备管理。
- `windows/`：无控制台 GUI/托盘客户端，负责 LAN HTTP/UDP、云拉取、Toast、复制操作、历史和开机自启。
- `cloudflare/`：同一个 Worker 提供两套明确分离的入口：旧 `/v1/*` 使用 SQLite Durable Object 保持配对兼容；新 `/api/v1/*` 使用 D1 提供账号、设备和短信历史。
- `web-ui/`：无前端框架的响应式单页应用，通过同源 `/api/v1/*` 登录和增量拉取短信。

## 账号同步模式

Android 主备入口：主地址固定 `https://msgdock.dpdns.org`，备用固定 `https://xgy-sms-relay.xgy2021sh.workers.dev`，均指向同一 Worker。账号、旧加密 Relay 和设备备份共用一个 HTTPS 传输实现，每次先主后备，不引入探测线程或粘性状态。仅对这两个精确来源映射旧保存 URL，保留路径、查询参数与链路凭据；历史自定义 Relay 不跨域转发凭据。连接建立前失败可切备用，发送后的失败只自动重放 GET/HEAD、按消息 ID 去重的上传和 ACK；注册/创建配对等不确定写入不重放。拒绝自动重定向，401/403/409/429 不触发切换。LAN 的独立执行与 Outbox 重试不变。

账号同步是 MsgDock 的新默认广域网路径，目标是让用户仅凭用户名/邮箱和密码在任意浏览器查看历史。为满足这一点，短信正文经 HTTPS 上传并保存在 D1，Worker 可以读取正文；它不是端到端加密。旧配对 Relay 的端到端加密路径继续存在，已有链路不迁移、不失效。

认证边界：

- 密码只用于注册/登录，使用 Worker Web Crypto PBKDF2-SHA256（100,000 次生产上限）加盐哈希，
  再用已有服务端 Secret 做带 `MsgDock/password-hash/v1` 用途前缀的 HMAC，防护 D1 单独泄露。
  此 Secret 不可随意轮换，否则旧设备备份与账号登录均会失效。
- Web 使用 `HttpOnly + Secure + SameSite=Lax` session Cookie。
- 原生客户端登录后用 session token 注册设备，再使用独立的长期 device token 上传/拉取；D1 只保存两类 token 的 SHA-256。
- session token 与 device token 即使都通过 Bearer 头传输，也必须按用途分别校验，不能互换。

最小 API 契约：

```text
POST   /api/v1/auth/register    { username, email, password }
POST   /api/v1/auth/login       { identifier, password }
POST   /api/v1/auth/logout
GET    /api/v1/me
POST   /api/v1/devices          { id?, name, type } -> 一次返回 device_token
GET    /api/v1/devices
DELETE /api/v1/devices/:id
POST   /api/v1/messages         device token + { client_message_id, sender, body, received_at }
GET    /api/v1/messages?after=<seq>&limit=<n>
```

- 原生客户端在登录/注册请求中发送 `X-MsgDock-Client: native`，响应才包含可持久化的 session token；浏览器只获得 HttpOnly Cookie。
- `messages.seq` 是 D1 单调递增游标。首次获取返回最近一页，带 `after` 时只返回更大的序号，响应始终按升序排列。
- `UNIQUE(user_id, client_message_id)` 保证 Android 重试不会生成重复短信。
- 每次查询先由 token 得到 `user_id`，SQL 必须包含该 `user_id`，禁止信任请求体中的用户 ID。
- 删除设备立即令其 device token 失效，但保留历史消息的来源标识。

账号消息数据流：

```text
SMS_RECEIVED -> 先写账号 Outbox -> LAN 立即发送
                                -> HTTPS POST /api/v1/messages
                                -> 成功删除；失败按 2s/5s/15s/30s/60s/5min 重试

Web / Windows -> GET /api/v1/messages?after=last_seq
              -> 本地落盘/渲染/Toast 成功
              -> 持久化 last_seq
```

## 消息数据流

### Android 账号接收（v0.7.1）

- 账号区可选“接收账号短信”，无需旧配对或 RECEIVE_SMS 权限；登录后启动现有 remoteMessaging 前台服务。
- 服务单独执行短 HTTPS GET，每 3 秒按 last_seq 拉取一页；网络失败退避至 5 分钟，网络变化唤醒。旧 LAN/加密云循环独立保留。
- v0.7.6：未登录、未启用账号接收或凭据需重新登录时，账号循环无限期等待设置/网络事件，不再每 3 秒唤醒。设置监听器随服务注册和注销；版本计数防止检查状态到等待之间丢失唤醒。启用账号接收后仍按上述间隔轮询。SMS 广播上传本来就是事件驱动，服务器推送尚未实现；不能据此承诺真机长期功耗或后台保活。
- 复用现有 CloudInboxStore：账号历史用 user_id + seq 隔离，原 client_message_id 作为 LAN/云共享的通知去重标识。先写历史/待通知账本，再同步提交游标；通知权限关闭不妨碍历史补齐。
- 本机发送的回流短信只入历史，不重复通知。切换/退出账号后忽略旧请求响应与旧账号待通知。
- 首页本机收件箱读取同一 JSONL：未登录显示 LAN/配对云端；登录后加上当前账号。先过滤账号再按共享 deliveryId 合并多路副本，按最近到达顺序保留最多 200 条展示，底层历史不删除、不迁移。列表与详情显示设备、来源和时间，复制前再次核对账号快照。
- 设备被撤销后等待显式重新登录，不自动注册复活；不把上个账号的在途 Outbox 请求换成新账号 token。
- 继续复用 JobScheduler 和原有开机/划掉恢复逻辑；强行停止及厂商后台限制仍需用户放行，不能承诺绝对常驻。

### Web 安全入口

- Static Assets 请求先经过现有 Worker，以确保 HTTP 页面在展示登录表单前跳到 HTTPS。HTTP 非读取请求直接拒绝，不转发含密码的请求体。
- CORS 允许实际请求 URL 的 HTTPS 同源 Origin 以及显式白名单，拒绝陌生 Origin、null 与 HTTP 来源；不使用通配符放开注册。
- HTTPS 响应设置 HSTS，不扩大到子域名。微信根目录 TXT 使用普通静态文件，不增加验证 API。

```text
Android SMS_RECEIVED
        |
        +--> 本地 outbox --> LAN POST ------------------+
        |                                               |
        +--> 本地加密 --> Cloudflare 密文队列 --> 拉取 --+--> 接收端按 UUID 去重
                                                        --> 先落本地历史
                                                        --> 系统通知
                                                        --> ACK
```

- LAN 和云端共享消息 UUID，禁止为不同路径重新生成 ID。
- 发送失败先保存在本地 outbox；可重试错误使用持久任务退避，永久错误进入死信。
- 接收端必须在历史/待通知状态持久化后才返回成功或向云端 ACK，重启后重放未完成通知。

## 云端安全

- 6 位码只用于短期配对。
- 每个端点生成 P-256 密钥对，通过 ECDH + HKDF-SHA256 派生房间密钥。
- 短信正文在发送设备上用 AES-256-GCM 加密；Relay 不保存设备私钥和明文正文。
- Relay 中 token 只保留不可逆校验值；Android 原始 `ANDROID_ID` 不上传。

## Android 设备身份与恢复

- 稳定身份由 Android 用户下的设备标识、包名和 APK 签名在本机派生；它用于匹配加密备份，不等同于链路 `deviceId`，也不是认证密码。
- 机器码仅在第一次云配对并成功登记后展示，不提供手动输入框。
- 每次启动都读取设备备份并同时校验本地和云端链路，再按 `role + roomId + deviceId` 合并：有效本地凭据优先，云端只补齐缺失的有效链路。
- 撤销链路和删除设备记录必须写入云端状态；删除墓碑阻止旧安装后台恢复已删除数据。
- 同 Android 用户、包名和签名可恢复；恢复出厂、换用户/工作资料、改包名或换签名会生成新身份。

## Windows 体验

- PE GUI 子系统启动，不出现命令行窗口，不自动打开默认浏览器。
- 托盘菜单负责状态、设置、历史和退出；状态页使用原生小窗口。
- 短信使用 Windows 10/11 原生 Toast，显示发送者和正文，支持复制验证码与复制全文；托盘气泡只作兼容兜底。
- v0.7.6 通知策略：普通、短时、静音 Toast 使用固定 Tag/Group 更新同一张卡片；10 秒内的后续消息设置 SuppressPopup，不连续弹横幅。仅原生 API 失败时使用托盘兜底，兜底也节流；手动复制成功不再新增气泡。
- 通知只处理收到时间在过去 2 分钟内、未来不超过 30 秒且不早于本进程最近展示消息的记录。其余历史正常保存并完成通知账本，不弹窗、不覆盖剪贴板。新鲜且带明确验证码关键词的消息在 GUI 线程自动复制，执行时再次检查时效；手动复制保留原逻辑。
- 原生通知提交仍同步返回结果；失败不推进通知账本或 ACK。复用现有注册与激活回调，只补充 Tag、Group 和 SuppressPopup 的 WinRT 绑定，不启动 PowerShell 或新增依赖。
- 历史保存在 `%APPDATA%\XgyLanSms\history.jsonl`，窗口显示最近记录，文件不主动清空。

## 当前部署与版本边界

- 默认 Relay：`https://msgdock.dpdns.org`；原 workers.dev 入口作为备用。
- 2026-10-01 部署版本：`1e3ef438-2086-4ca7-9bd4-17e9b5c2d017`（Android / Windows v0.7.6 下载链接）；后续部署前必须重新核对。
- `https://msgdock.dpdns.org` 已上线；D1 ID 为 `e9538d9c-f98a-40ae-b948-af7acdb56050`。
  本地客户端候选尚需真机验收，不能用线上 API 测试替代锁屏/真实短信/Toast 点击验证。
- Android v0.7.6、Windows v0.7.6 与旧 Relay v0.6.0 保持协议兼容。下载区按平台分别标注版本，指向实际上传的 Release 资产与各自 SHA-256 清单。

## UI 设计

### Android Material Design 3

v0.7.6 布局修复：主页面和教程页共用 WindowLayout。Android 11 及以上由根容器统一应用系统栏、挖孔与输入法的合并边距，始终基于原始 padding，避免重复累加。主页面的 ScrollView 放在根容器内，键盘出现时实际缩小可视区并滚动到焦点。旧系统保留 fitsSystemWindows。SDK 兜底打包时必须把 uses-sdk 放在 application 之前，否则系统可能按旧版兼容默认值限制窗口。

后台教程：独立原生 `BackgroundGuideActivity` 使用同一套 XML 样式；`BackgroundGuideContent` 保存离线的分品牌三步说明与官方来源。品牌先匹配 `Build.BRAND` 再匹配厂家，手动选择只随页面状态保存，不新增持久配置。仅通过标准 Intent 打开应用信息/电池优化列表；返回时刷新系统电池豁免状态，不猜测 OEM 自启动或任务小锁。旧版路径标出范围，未知品牌使用诚实的通用排查；不增加 WebView、网络请求、后台线程、权限或厂商私有组件适配层。

权限与运行状态：扫描在附近设备权限回调确认允许后才开始，同一界面只运行一次有时限的扫描，页面销毁后取消。拒绝通知不阻止接收和本地历史；取消权限请求不继续尚未开始的操作。权限弹窗中的待执行动作随 Activity 重建保存，不增加持久业务配置。接收开关只表示用户意图，界面从现有服务读取启动、LAN 就绪、监听失败或停止状态；LAN 监听失败不停止独立的账号/云接收循环。

v0.7.0 起 Android 应用采用 Material Design 3 风格深色主题：

- **配色方案**：深色调青色系（#050A0D 到 #21323C）五层表面，主色调为亮青色 #38BDF8（消息/连接主题），辅助色翠绿色 #6EE7B7（成功状态）
- **布局系统**：使用 XML layout 替代纯代码构建，采用 Grid 优先布局、统一 spacing token（4dp-48dp）、卡片化分组设计
- **字体层级**：Display 28sp、Title 18-22sp、Body 14-16sp、Caption 12sp，均使用 clamp() 概念支持流式缩放
- **组件风格**：按钮使用 pill 圆角（999dp），卡片使用 16dp 圆角，输入框带描边，统一视觉节奏
- **资源文件**：`res/values/colors.xml`、`dimens.xml`、`styles.xml` 定义设计 token，`res/layout/activity_main.xml` 为主界面，`item_*.xml` 为列表项模板

### Web 管理面板

提供轻量级账号收件箱和设备管理（`web-ui/index.html`）：

- **单文件部署**：纯静态 HTML + 内联 CSS/JS，由 Worker Static Assets 同源提供
- **响应式设计**：适配桌面和移动设备，使用 CSS Grid 构建卡片布局
- **账号与历史**：HttpOnly Cookie 登录，最近短信、设备列表、正文/验证码复制
- **增量恢复**：每 3 秒按 `last_seq` 分页拉取；重开页面也从已保存游标补齐，再限制本地展示数量
- **少等待**：普通站内导航复用页面；最近页与游标恢复并行，设备按需加载；无新消息不重绘，新增时保留展开项。
- **网络控制**：请求 15 秒超时；一轮结束后再安排轮询，错误逐步退避至 60 秒；隐藏/离线暂停新轮询，回到前台/联网立即补齐。全部恢复页完成前不推进持久游标，跨账号的旧响应不得更新当前状态。
- **最小缓存策略**：保持单文件内联程序和平台 HTML 重新验证，不为约数 KB 的压缩页面引入哈希构建流水线。动态接口继续 no-store；不增加短信正文持久缓存、Service Worker、外部字体或新 WebSocket 服务。

### Windows 界面

Windows 端保持原有 Go walk 库原生 GUI，功能性优先，托盘驻留 + 状态窗口。

## 暂不支持

- RCS/网络短信广播。
- 旧 E2EE Relay 读取短信明文；账号/D1 模式为支持 Web Inbox 会保存正文，两者是不同安全模型。
- 旧 E2EE Relay ACK 后的历史浏览与恢复；账号/D1 模式已经提供长期 Inbox。
- 包名、签名或 Android 用户变化后的自动身份迁移。
- WebSocket；当前 Web/Windows 使用短 HTTPS 增量轮询。
- 国内 Relay、高级搜索和按设备筛选。
