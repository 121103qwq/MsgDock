# Android 后台运行教程：来源与适用范围

核对日期：2026-09-06。应用内文字为简要改写，离线可读；官方链接由用户点击后联网打开。以下资料支持系统菜单的解释，不等于 MsgDock 已在所有厂商真机验证。

## 来源

| 教程分组 | 官方资料 | 采用内容与限制 |
|---|---|---|
| 小米 / Redmi / POCO | [小米 REDMI Watch 6 FAQ](https://www.mi.com/global/support/faq/details/KA-694585/)，[REDMI Note 14 Pro+ 后台应用锁定](https://www.mi.com/global/support/faq/details/KA-524985/) | 小米示例的无限制、自启动、长按最近任务卡片点小锁；部分新机型使用手机管家设置中的加速/锁定应用。示例中的 Mi Fitness 替换为 MsgDock，只采用系统设置，不移植手表 App 的专有入口。 |
| 华为 | [应用无法后台运行](https://consumer.huawei.com/cn/support/content/zh-cn00428704/)，[EMUI 智能关怀后台保护](https://consumer.huawei.com/cn/support/content/zh-cn15850065/) | 电池优化列表选不允许、下拉卡片加锁、启动管理关闭自动管理并允许自启动/关联启动/后台活动。官方范围是 EMUI 和 HarmonyOS 4.x 及以下，不能据此声称 APK 支持原生鸿蒙。 |
| 荣耀 | [灭屏后第三方应用停止运行](https://www.honor.com/cn/support/content/zh-cn00458250/)，[应用无法后台运行](https://www.honor.com/cn/support/content/zh-cn00428704/) | 下滑卡片小锁、手动启动管理的三个开关、不允许电池优化。仅保留与 MsgDock 后台运行有关的部分，不建议恢复出厂或关闭全部高耗电提醒。 |
| OPPO / 一加 | [OxygenOS 14 官方手册](https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf)（页 18、121、178–179），[vivo 官方客服：其他品牌后台保护](https://kefu.vivo.com.cn/robot/imgmsgData/97bfb207f86b476a9204a49cb2a84233/index_1.html)（2021 年） | 一加手册提供应用耗电/后台活动和 Apps → Auto launch；vivo 厂商文档提供 OPPO 卡片更多菜单锁定、自启动管理和应用速冻旧版入口。后者并非 OPPO 对当前全机型的承诺，页面明确标旧版。手册一句英文对 background activity 的解释有自相矛盾，未照抄，只使用菜单位置和含义明确的允许选项。 |
| realme 真我 | [realme 官方 FAQ](https://www.realme.com/global/support/faq) | 应用耗电管理允许后台活动及自启动、旧版下拉任务卡片和手机管家自启动。FAQ 未限定当前 UI 版本，应用内作为旧版参考，有同名入口才使用。 |
| vivo / iQOO | [第三方计步软件后台权限](https://kefu.vivo.com.cn/robot/imgmsgData/2616a9cd7cd64a5083a264d16e5767da/index_1.html)（2021 年），[消息通知的后台权限检查](https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html) | 权限管理中的自启动、后台耗电管理允许高耗电、下拉卡片小锁。旧路径需按当前系统搜索核对；卡片菜单只提示检查是否存在，不声称所有机型都有。 |
| 三星 | [Sleeping apps](https://www.samsung.com/us/support/answer/ANS10003442/)，[Manage Recent apps](https://www.samsung.com/us/support/answer/ANS10001359/) | 从休眠列表移除/加入从不休眠列表，最近任务应用图标里的 Keep open。旧机型自动运行列表参考上述 vivo 厂商文档；不把它当作 One UI 全机型通用开关。 |
| 华硕 / ROG | [Auto-start Management](https://www.asus.com/global/support/faq/1054036/)（2026-05-05 更新） | Battery → Auto-start Manager。电池与任务卡片部分只提供通用检查，不承诺各代 ZenUI / ROG UI 存在相同锁定手势。 |
| Google / 原生 Android | [Pixel：自适应电池与应用电池优化](https://support.google.com/pixelphone/answer/7015477?hl=zh-Hans) | 应用电池用量/允许后台使用入口、单应用放宽优化可能增加耗电。具体不受限制选项按设备实际显示，保留整机自适应电池。 |
| 其他品牌 | 同上 Android 通用资料；当前机型官方帮助优先 | 魅族 Flyme、中兴 MyOS、努比亚 / REDMAGIC OS、联想 ZUI、Motorola、Sony 等未核实到足够明确的当前专用路径，故明确提供通用搜索/检查，不编造对应的厂商手势。 |

## 交互与验证边界

- 品牌根据 Android `Build.BRAND` 优先识别，再回退 `MANUFACTURER`，未识别则选通用页。没有读取隐藏系统属性、设备标识或发送遥测。
- 只显示 Android `PowerManager.isIgnoringBatteryOptimizations` 的实际结果；不推断自启动、小锁或后台存活状态。
- 只有标准系统设置 Intent 和用户主动打开的官方 HTTPS 链接。没有 OEM 私有组件路径表、自动授权、无障碍代点或新增权限。
- 小锁用于减少被最近任务清理；系统内存压力、强行停止、省电策略等仍可影响运行。它与密码应用锁、屏幕固定是不同功能。
- 模拟器只能验证教程页面、导航、状态读取与标准设置跳转。真实短信、锁屏与不同厂商后台策略仍须实际手机验收。
