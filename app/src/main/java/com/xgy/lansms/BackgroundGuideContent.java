package com.xgy.lansms;

import java.util.Locale;

/** Offline, version-qualified instructions. Evidence and limits: docs/background-guide-sources.md. */
final class BackgroundGuideContent {
    private BackgroundGuideContent() {}

    static final class Guide {
        final String id, label, battery, recents, autostart, note;
        final String[][] sources;

        Guide(String id, String label, String battery, String recents, String autostart,
              String note, String[][] sources) {
            this.id = id;
            this.label = label;
            this.battery = battery;
            this.recents = recents;
            this.autostart = autostart;
            this.note = note;
            this.sources = sources;
        }
    }

    static final Guide[] GUIDES = {
        new Guide("xiaomi", "小米 / Redmi / POCO",
            "打开 MsgDock 的应用信息，找到「省电策略」或「应用智能省电」，选择「无限制」。也可在系统设置中搜索这些名称，再选择 MsgDock。",
            "先打开 MsgDock，再进入最近任务（从屏幕底部上滑并停一下，或按多任务键）。\n\n长按 MsgDock 的任务卡片；如果弹出小锁图标，点它加锁。确认卡片出现锁标志。\n\n没有这个菜单？部分 HyperOS 机型改为「手机管家 → 右上角设置 → 加速 → 锁定应用」，打开 MsgDock 的开关。",
            "在设置中搜索「后台自启动」或「自启动管理」，找到 MsgDock 并允许。部分版本也能从「应用设置 → 应用管理 → MsgDock → 自启动」进入。",
            "适用于提供这些入口的 MIUI / HyperOS。不同机型可能使用任务卡片小锁或手机管家开关，两者不必同时存在。手机管家的「锁定应用」是清理保护，别误进隐私设置里的「应用锁」。",
            new String[][] {
                {"小米：自启动、省电与长按小锁", "https://www.mi.com/global/support/faq/details/KA-694585/"},
                {"小米：新版手机管家的后台锁定", "https://www.mi.com/global/support/faq/details/KA-524985/"}
            }),
        new Guide("huawei", "华为",
            "设置中搜索「电池优化」→ 切换到「所有应用」→ MsgDock → 选择「不允许」。\n\n这里的「不允许」是不允许系统优化 MsgDock，意味着放宽限制；不是禁止应用后台运行。",
            "打开 MsgDock 后进入多任务界面，按住它的任务卡片向下拉，看到卡片上的小锁就表示加锁。不要把卡片向上划掉。",
            "设置中搜索「应用启动管理」→ MsgDock → 关闭「自动管理」。在手动管理中允许「自启动」「关联启动」「后台活动」，并确认保存。",
            "参考 EMUI 与 HarmonyOS 4.x 及以下的安卓兼容系统。此教程不代表当前 APK 支持 HarmonyOS NEXT / 原生鸿蒙。没有同名入口时按设置搜索结果和实际系统说明操作。",
            new String[][] {
                {"华为：应用无法后台运行", "https://consumer.huawei.com/cn/support/content/zh-cn00428704/"},
                {"华为：应用启动手动管理", "https://consumer.huawei.com/cn/support/content/zh-cn15850065/"}
            }),
        new Guide("honor", "荣耀",
            "设置中搜索「电池优化」→ 点列表旁的小三角，切换「所有应用」→ MsgDock →「不允许」。这个选项是不允许电池优化，不是禁止后台活动。",
            "进入多任务界面，按住 MsgDock 卡片向下滑并停一下。卡片出现小锁后就已加锁；再次下滑可能会解除，请看图标确认。",
            "设置 → 应用 → 应用启动管理（也可直接搜索）→ 关闭 MsgDock 的自动管理 → 允许自启动、关联启动和后台活动。",
            "Magic UI / MagicOS 的名称和入口随版本变化。内存紧张时，加锁的应用仍可能被系统结束；应用中的设置页无法判断这个小锁是否已经打开。",
            new String[][] {
                {"荣耀：第三方应用在灭屏后停止运行", "https://www.honor.com/cn/support/content/zh-cn00458250/"},
                {"荣耀：后台运行与卡片加锁", "https://www.honor.com/cn/support/content/zh-cn00428704/"}
            }),
        new Guide("oppo", "OPPO / 一加",
            "在 MsgDock 的应用信息中找「耗电管理 / 电池用量」，允许后台活动。部分版本入口是「设置 → 电池 → 更多设置 → 应用耗电管理」。如果有针对 MsgDock 的「应用速冻」，请关闭该项。",
            "打开最近任务，找到 MsgDock，点任务卡片的更多菜单（常见为右上角三个点）→「锁定」。确认卡片有锁标志。部分旧版本用下拉卡片加锁；按当前菜单为准。",
            "设置中搜索「自启动 / 自启动管理」，或进入「设置 → 应用 → 自启动」，允许 MsgDock。部分版本把开关放在应用的耗电管理中。没有独立开关时完成后台活动设置即可，不必寻找隐藏权限。",
            "ColorOS 与 OxygenOS 的国内外版本并不完全相同。耗电/自启动入口参考 OxygenOS 14 手册；OPPO 小锁和速冻参考厂商发布的旧版操作指南，仅在同名选项存在时适用。",
            new String[][] {
                {"一加：OxygenOS 14 官方手册（PDF）", "https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf"},
                {"vivo 官方：OPPO 手机后台保护说明（旧版）", "https://kefu.vivo.com.cn/robot/imgmsgData/97bfb207f86b476a9204a49cb2a84233/index_1.html"}
            }),
        new Guide("realme", "realme 真我",
            "设置 → 电池 → 应用耗电管理 → MsgDock，允许后台活动。部分版本从 MsgDock 的应用信息 → 电池用量进入。只修改 MsgDock，不必对所有应用放开限制。",
            "先打开 MsgDock，再进入最近任务。旧版可向下拉应用卡片加锁；如果当前卡片带更多菜单，请查看其中是否有「锁定」。最后以卡片小锁为准。",
            "在应用耗电管理中允许 MsgDock 自启动；旧版也可能在「手机管家 → 隐私权限 → 自启动应用」中。新版本可在设置中搜索「自启动」定位。",
            "参考 realme 官方 FAQ 中的旧版入口。realme UI 不同版本可能移动或不提供其中的选项，找不到时先使用下方的系统电池设置和应用信息入口。",
            new String[][] {{"realme：官方后台运行 FAQ", "https://www.realme.com/global/support/faq"}}),
        new Guide("vivo", "vivo / iQOO",
            "设置 → 电池 → 后台耗电管理（旧版叫「后台高耗电」）→ MsgDock →「允许后台高耗电」。这里是允许后台运行所需的耗电，不代表应用会持续高负载。",
            "打开最近任务，按住 MsgDock 卡片向下滑，看到小锁表示加锁；再次下滑可能解除。某些卡片布局把「锁定」放在卡片图标旁的菜单中，以实际菜单为准。",
            "设置 → 应用与权限 → 权限管理 → 权限 → 自启动，打开 MsgDock。也可从 i 管家 → 应用管理 → 权限管理进入；如果有相关的关联启动选项，一并检查。",
            "参考 vivo 官方客服给出的 OriginOS / Funtouch OS 常见及旧版路径。不同系统版本菜单可能改名；设置中可搜索「后台耗电」「自启动」。",
            new String[][] {
                {"vivo：后台权限、耗电和小锁", "https://kefu.vivo.com.cn/robot/imgmsgData/2616a9cd7cd64a5083a264d16e5767da/index_1.html"},
                {"vivo：消息延迟的后台权限检查", "https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html"}
            }),
        new Guide("samsung", "三星",
            "设置 → 电池和设备维护（或设备维护）→ 电池 → 后台使用限制 / 应用电源管理。将 MsgDock 从「休眠」「深度休眠」列表移出；若可添加，将它加入「从不休眠的应用」。应用信息中若另有电池限制选项，也请允许后台使用。",
            "进入最近任务 → 点 MsgDock 卡片上方的应用图标 → 选择「保持打开 / Keep open」。部分机型没有这个菜单，跳过即可；不要误选用于限制屏幕切换的「固定应用」。",
            "One UI 通常没有国产系统同名的独立自启动开关，先完成电池与休眠设置。旧机型如果有「自动运行应用程序」列表，再允许 MsgDock。设置完或重启手机后，打开一次 MsgDock 检查接收状态。",
            "三星官方说明菜单会因型号、地区和软件版本不同而变化。保持打开可用的数量也以手机显示为准，应用无法替你确认或自动设置。",
            new String[][] {
                {"三星：休眠与从不休眠应用", "https://www.samsung.com/us/support/answer/ANS10003442/"},
                {"三星：最近任务中的 Keep open", "https://www.samsung.com/us/support/answer/ANS10001359/"}
            }),
        new Guide("asus", "华硕 / ROG",
            "打开 MsgDock 的应用信息，检查电池 / 后台使用；如果有「不受限制」或「不优化」，可为 MsgDock 选择它。没有该项时使用下方「系统电池优化列表」按实际选项设置。",
            "打开最近任务，查看 MsgDock 卡片的菜单是否提供锁定 / 保持打开。没有统一适用于所有华硕机型的手势，未找到该项可跳过，不要把屏幕固定当作后台保护。",
            "设置 → 电池 → 自启动管理（Auto-start Manager）→ MsgDock → 允许。旧版可能从 PowerMaster / 电力达人进入。",
            "自启动路径依据华硕 2026 年官方说明；电池和最近任务使用通用排查方法，未针对各代 ZenUI / ROG UI 确认相同入口。",
            new String[][] {{"华硕：自启动管理", "https://www.asus.com/global/support/faq/1054036/"}}),
        new Guide("android", "Google / 原生 Android",
            "设置 → 应用 → 查看所有应用 → MsgDock → 应用电池用量。允许后台使用；如果子页面提供「不受限制 / Unrestricted」，可为 MsgDock 选择。旧版可在「电池优化 → 所有应用 → MsgDock」中选择「不优化」。",
            "原生 Android 通常没有厂商式的后台小锁，不需要强行寻找。「固定此应用 / 屏幕固定」是限制屏幕切换，不能替代后台保护。",
            "通常没有单独的自启动开关。完成所需权限后打开一次 MsgDock，按需启动接收；重启后再检查状态。不要通过「强行停止」来退出需要持续接收的应用。",
            "参考 Pixel 的应用电池用量入口；其他原生系统名称可能不同。只为需要持续接收的 MsgDock 放宽限制，可保留整机的自适应电池功能。",
            new String[][] {{"Google：应用电池用量与优化", "https://support.google.com/pixelphone/answer/7015477?hl=zh-Hans"}}),
        new Guide("other", "其他：魅族 / 中兴 / 努比亚 / 联想等",
            "先打开下方的 MsgDock 应用信息，查看「电池 / 后台运行 / 耗电管理」。目标是允许 MsgDock 后台活动；有「不优化 / 不受限制」时可选。不要改动不理解的整机设置。",
            "打开最近任务，查看 MsgDock 卡片的图标、更多菜单或长按菜单。有明确的「锁定 / 保持打开」选项才启用；没有则跳过。不要把需要密码的「应用锁」或「屏幕固定」当作后台小锁。",
            "在系统设置或自带手机管家中搜索「自启动 / 启动管理」。若存在 MsgDock 的开关，允许它；没有则保持默认，不使用网上的隐藏组件或批量授权命令。",
            "涵盖未单列的 Flyme、MyOS、REDMAGIC OS、ZUI、Motorola、Sony 及其他系统。这一页是通用排查，不是这些品牌逐一验证的菜单路径；具体操作请查当前机型的官方说明。",
            new String[][] {{"Android 通用：每个应用的电池设置", "https://support.google.com/pixelphone/answer/7015477?hl=zh-Hans"}})
    };

    static int indexFor(String manufacturer, String brand) {
        // Brand takes priority: e.g. older Honor reports HUAWEI, realme may report OPPO.
        String id = identify(brand);
        if (id == null) id = identify(manufacturer);
        for (int i = 0; i < GUIDES.length; i++) if (GUIDES[i].id.equals(id)) return i;
        return GUIDES.length - 1;
    }

    private static String identify(String value) {
        String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "xiaomi": case "redmi": case "poco": return "xiaomi";
            case "huawei": return "huawei";
            case "honor": return "honor";
            case "oppo": case "oneplus": return "oppo";
            case "realme": return "realme";
            case "vivo": case "iqoo": return "vivo";
            case "samsung": return "samsung";
            case "asus": case "asus_rog": return "asus";
            case "google": case "android": return "android";
            default: return null;
        }
    }
}
