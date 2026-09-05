# Xgy LAN SMS UI 更新说明

## 概览

v0.7.0 为 Xgy LAN SMS 带来全新的 Material Design 3 深色主题 UI，提供更现代、更美观的用户体验。

## Android 应用 UI 更新

### 设计理念

采用深色调青色系配色方案，符合消息传递应用的特征：

- **深色表面层级**：从 #050A0D（最深）到 #21323C（浅层），共 5 层渐变
- **主色调**：亮青色 #38BDF8，代表连接和消息传递
- **辅助色**：翠绿色 #6EE7B7（成功）、橙色 #F59E0B（警告）、红色 #EF4444（错误）
- **文字层级**：三级灰度，从高对比 #F8FAFC 到低对比 #94A3B8

### 主要改进

#### 1. 卡片化布局
每个功能区域都被包装在圆角卡片中，清晰分组：
- ① 云端转发
- ② 设备管理
- ③ 手机发送端
- ④ Pad 接收端
- ⑤ 系统设置

#### 2. 统一的视觉节奏
- 所有按钮使用 pill 圆角（999dp）
- 卡片使用 16dp 圆角
- 统一的 spacing token（4dp, 8dp, 16dp, 24dp, 32dp, 48dp）
- 一致的文字大小层级

#### 3. 改进的交互体验
- 主要操作按钮使用亮青色填充
- 次要操作按钮使用深色填充
- 轻量操作按钮使用描边样式
- 危险操作（删除）使用红色文字

#### 4. 更好的可读性
- 增加行间距（1.5 倍）
- 使用 monospace 字体显示 IP 地址、设备 ID 等技术信息
- 机器码使用醒目的青色高亮显示

### 技术实现

**之前**：所有 UI 都在 Java 代码中用 `new TextView()` 手动构建

**现在**：
- XML layout 文件定义 UI 结构（`activity_main.xml`）
- 设计 token 统一管理（`colors.xml`, `dimens.xml`, `styles.xml`）
- 可复用的列表项模板（`item_cloud_link.xml`, `item_target.xml`）
- Java 代码只负责逻辑和数据绑定

### 文件清单

```
app/src/main/res/
├── layout/
│   ├── activity_main.xml          # 主界面布局
│   ├── item_cloud_link.xml        # 云链路列表项
│   └── item_target.xml            # LAN 目标列表项
├── values/
│   ├── colors.xml                 # 颜色定义
│   ├── dimens.xml                 # 尺寸 token
│   └── styles.xml                 # 样式定义
└── drawable/
    ├── card_background.xml        # 卡片背景
    ├── input_field.xml            # 输入框样式
    ├── button_primary.xml         # 主要按钮背景
    ├── button_secondary.xml       # 次要按钮背景
    └── button_outlined.xml        # 描边按钮背景
```

## Web 管理面板

新增轻量级 Web 管理面板（`web-ui/index.html`），用于从浏览器查看状态和管理设备。

### 特性

- **单文件部署**：纯 HTML，内联 CSS/JS，无需构建
- **响应式设计**：自适应桌面和移动设备
- **实时状态**：显示消息数量、在线状态、已配对设备
- **深色主题**：与 Android 应用保持一致的视觉风格

### 部署方式

```bash
# Python
cd web-ui && python -m http.server 8080

# Node.js
cd web-ui && npx serve .

# Go（Windows 接收端可内置）
http.Handle("/", http.FileServer(http.Dir("web-ui")))
```

访问 `http://localhost:8080` 即可查看管理面板。

### 未来集成

Web 面板可以集成到：
- **Windows 接收端**：内置 HTTP 服务器，访问 `http://localhost:8080/status`
- **Cloudflare Worker**：提供公网访问的管理界面（需认证）
- **Android WebView**：作为应用内的统计页面

## 迁移指南

### 对现有用户的影响

**完全向后兼容**，所有功能逻辑保持不变：
- ✅ 所有配对流程不变
- ✅ LAN 和云端协议不变
- ✅ 数据存储格式不变
- ✅ 权限要求不变

只是 UI 更漂亮了。

### 构建验证

```bash
# Android
cd app
./gradlew clean assembleDebug

# 预期输出
# BUILD SUCCESSFUL
# APK 位置：app/build/outputs/apk/debug/XgyLanSms-v0.7.0.apk
```

如果编译出错，检查：
1. `R.id.*` 和 `R.layout.*` 引用是否正确
2. `AndroidManifest.xml` 中的 theme 是否为 `@style/AppTheme`
3. 所有 XML 文件格式是否正确

## 设计原则

这套 UI 遵循以下设计原则（来自 ZCode 的设计判断）：

1. **深色调色板**：构建 5-7 层表面色阶，从 3-6% 亮度开始，按主题色相染色
2. **单一主色调**：青色系，代表数据和连接
3. **流式字体**：使用 clamp() 概念（Android 中通过不同字号实现）
4. **Grid 优先**：布局使用 LinearLayout 模拟 Grid 行为
5. **Token 系统**：spacing、radius、color 都定义为可复用的 token
6. **圆角几何**：按钮 pill 形状，组件充分圆角

## 已知限制

1. **未测试真机渲染**：需要在真实设备上验证视觉效果
2. **Web 面板为演示**：需实现后端 API 才能显示真实数据
3. **无认证机制**：Web 面板仅适合本地/可信网络使用
4. **Windows UI 未更新**：Windows 端保持原有功能性界面

## 下一步

建议优先级：
1. **P0**：在真机上测试新 UI，确认无布局问题
2. **P1**：为 Windows 接收端添加内置 Web 服务器
3. **P2**：实现 Web 面板后端 API
4. **P3**：添加 Web 面板认证机制
5. **P4**：考虑为 Windows 端重新设计 UI（WPF/Avalonia）

## 截图对比

由于当前环境无法运行 Android 模拟器，请在真机上对比：

**旧版**：纯白底黑字，按钮间距不一，无视觉层次
**新版**：深色主题，卡片分组，统一圆角，醒目的青色强调

---

*UI 设计与实现：ZCode · 2026-09-04*
