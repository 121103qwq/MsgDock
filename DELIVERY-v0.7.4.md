# MsgDock v0.7.4 预览版

本次更新 Android；Windows 保持 v0.7.0。网站按平台分别展示对应版本和校验文件。

## Android 改进

- 首页本机收件箱：未登录也能查看局域网、配对云端已保存的短信。
- 登录后增加当前账号历史；其他账号和退出前的账号记录不混入。
- 相同短信多路到达只显示一条，显示来源、设备和时间；保留全文、验证码复制。
- 沿用同一本地账本，不迁移、不删除历史，不新增服务或运行依赖。
- 包含此前 v0.7.1–0.7.3 的账号接收、固定主备 Relay、权限回调、扫描生命周期与真实接收状态改进。

## 验证

- SDK 编译、56 项 JVM 测试和 APK v2/v3 签名校验通过。
- Codex 独立 Android 14 模拟器通过 5 项流程：v0.7.3 覆盖升级保留历史、实际 LAN HTTP 重发去重与全文复制、验证码实际粘贴、进程重开、本机合成账号 A/B/退出隔离。
- 模拟器账号隔离使用合成存储数据，未注册或操作真实账号；临时数据已恢复。崩溃日志为空。
- 12 项 Web 回归、Worker TypeScript 检查与部署 dry-run 通过。网站继续使用既有静态页面和 GitHub Release。

真实 SMS、锁屏、HyperOS、后台保活及实际主备网络切换仍需真机验收。Gradle lint 受现有主机故障限制未运行，不用 SDK 构建代替 lint 或真机结果。

## 下载

- [Android v0.7.4 APK](https://github.com/121103qwq/MsgDock/releases/download/v0.7.4/MsgDock-Android-v0.7.4-debug.apk)
- [完整源码 v0.7.4 ZIP](https://github.com/121103qwq/MsgDock/releases/download/v0.7.4/MsgDock-source-v0.7.4.zip)
- [Android / 源码 SHA-256](https://github.com/121103qwq/MsgDock/releases/download/v0.7.4/SHA256SUMS-v0.7.4.txt)
- [Windows v0.7.0 EXE（未更新）](https://github.com/121103qwq/MsgDock/releases/download/v0.7.0/MsgDock-Windows-v0.7.0.exe)
- [Windows SHA-256](https://github.com/121103qwq/MsgDock/releases/download/v0.7.0/SHA256SUMS-v0.7.0.txt)

Android versionCode 14，包名 com.xgy.lansms，沿用旧调试证书；APK SHA-256：

`32B07514D26C74FDA406209D7941C12A498441A0CA9A32CAD040E428A804684F`

Android 为调试签名，Windows 未做代码签名。请保留旧版备份；实机验收前不自动合并发布分支。

消融审查：现有 JSONL 加有上限的去重读取 → 删除会丢失未登录可见历史或显示重复副本 → 保留 → 直接解决当前问题，无数据库迁移、通用收件箱框架或额外后台状态。
