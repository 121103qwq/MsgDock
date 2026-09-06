# MsgDock Android v0.7.5 预览版

## 本次更新

- 首页新增“后台运行教程 · 电池 / 小锁 / 自启动”，全部教程离线可读。
- 根据品牌推荐教程，并允许手动切换小米/Redmi/POCO、华为、荣耀、OPPO/一加、realme、vivo/iQOO、三星、华硕/ROG、Google/原生 Android；其他品牌使用明确标注的通用排查。
- 小米写明长按最近任务卡片点小锁，以及部分新版手机管家的锁定入口；各品牌分开说明电池优化、自启动和任务保护。
- 区分后台小锁、密码应用锁和屏幕固定；说明华为/荣耀“不允许电池优化”的真实含义。
- 可打开系统电池优化列表和 MsgDock 应用信息，返回后刷新系统可读取的电池优化状态；不自动修改设置、不新增权限或后台服务。
- 网站 Android 安装包、源码和校验清单切换到 v0.7.5，Windows 保持 v0.7.0。

来源及版本范围见 [后台教程资料](docs/background-guide-sources.md)。原版手册针对其他 App 的示例只采用系统操作，未移植与 MsgDock 无关的专有入口或权限建议。

## 验证

- 现有 SDK/JDK 本机构建成功；59 项 JVM 测试通过，APK v2/v3 签名校验通过。包名 `com.xgy.lansms`，versionCode `15`，versionName `0.7.5`，沿用旧调试签名。
- 独立 Android 14 模拟器经实际 Android MCP 连接验证：v0.7.4 覆盖升级保留收件箱、首页教程入口、品牌自动识别、10 组内容切换。
- 同一候选 APK 上继续以直接 MCP 交互确认：1.3 倍字体/横竖屏后保留所选品牌、长文可滚到底、对应官方资料选择框可打开、应用信息与电池优化列表进入真实系统页面并返回、教程返回首页。
- 整个过程没有改变电池白名单；字体恢复原值 1.0，模拟器测试结束后关闭。没有登录、注册或发送真实短信。
- 自动化脚本第一轮在大字体横屏时使用过长的滑动距离而未找到页尾；改为按当前 UI 滚动区域计算手势，并通过直接 MCP 验证剩余流程。原始失败报告保留，未伪造全脚本重跑通过。
- 网页 12 项检查、TypeScript 检查与部署 dry-run 通过；本轮只改下载区版本链接，网页脚本、Cloudflare 业务代码和配置不变。

本地 UI 证据目录：`build/android-ui-2026-09-06T08-20-24-654Z/`；初始脚本结果见 `result.json`，后续直接 MCP 的结论见交接记录。构建只执行一次，之后仅修改测试和文档。

## 产物

- [Android APK](https://github.com/121103qwq/MsgDock/releases/download/v0.7.5/MsgDock-Android-v0.7.5-debug.apk)
- [完整源码 ZIP](https://github.com/121103qwq/MsgDock/releases/download/v0.7.5/MsgDock-source-v0.7.5.zip)
- [SHA-256 清单](https://github.com/121103qwq/MsgDock/releases/download/v0.7.5/SHA256SUMS-v0.7.5.txt)
- [网站下载区](https://msgdock.dpdns.org/#downloads)

APK SHA-256：`C30FEB52E80FD01EAE5973987A35E5069753A7029D82FB78D4978A58AD938F5D`。

源码 ZIP SHA-256：`B62C96438E9C19C2D6E75D53269089ED2D1E36DC380721621A6869C3E3C763ED`。对应远端标签 `v0.7.5` / 代码提交 `ed3a2d93d9b948418e4367d64575f27cf34e184e`；[PR #3](https://github.com/121103qwq/MsgDock/pull/3) 保持未合并。三个公开资产均已无登录实际下载，HTTP 200，哈希与本地一致。

网站已切换到 v0.7.5，部署 ID `07c86e6c-741d-478b-8afb-668183edd0b0`。主域根页/收件箱与备用域名的下载区、原网页脚本均匹配本地；健康检查 200、匿名消息 API 401/no-store；旧 Windows 链接仍为 200。

## 边界

这是调试签名的预览版。教程依据官方公开资料整理，并非所有品牌、系统版本都做过真机测试；旧路径和通用排查已分别标注。模拟器结果不能替代真实短信、HyperOS 锁屏和长时间后台运行验收。后台小锁与自启动权限不能保证应用永远存活。Gradle lint 仍受已记录的本机 loopback 故障影响，本轮没有运行。

消融审查：独立教程页与离线内容表 → 删除会把大量厂商文案和页面逻辑堆回首页 → 保留 → 这是明确的内容/页面职责边界，没有新增通用适配框架或持久状态。
