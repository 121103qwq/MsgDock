# MsgDock Web

## 概述

这是 MsgDock 的无框架单文件 SPA。Worker 会把本目录作为同源静态资源
提供，生产地址为 `https://msgdock.dpdns.org`。

## 特性

- **自动主题**：青色系深色/浅色随系统切换
- **响应式设计**：适配桌面和移动设备
- **账号**：用户名/邮箱登录、注册和退出
- **收件箱**：最近短信、3 秒增量轮询、按用户保存 `last_seq`
- **设备管理**：列出和移除已登录设备
- **复制**：展开正文、复制全文、复制 4~8 位验证码
- **主题**：跟随系统自动切换深色/浅色

## 设计原则

### 配色方案
- **表面色阶**：5 层深色表面（#050A0D 到 #21323C）
- **主色调**：亮青色 #38BDF8（消息/连接主题）
- **辅助色**：翠绿色 #6EE7B7（成功状态）
- **文字层级**：三级灰度从高对比到低对比

### 布局
- **Grid 优先**：使用 CSS Grid 构建响应式卡片布局
- **圆角**：按钮使用 999px pill 形状，卡片使用 16px 圆角
- **间距系统**：统一的 spacing token（4px 到 48px）
- **流式字体**：使用 clamp() 实现自适应文字大小

## API 约定

页面调用同源 `/api/v1/*`，浏览器凭 HttpOnly Cookie 访问。收件箱首次请求
`GET /api/v1/messages?limit=100` 获取最近记录；如果浏览器已有按账号保存的游标，会先连续调用
`GET /api/v1/messages?after=<next_seq>&limit=100` 补齐全部缺口，再加载最近页。

详细认证、设备和消息字段见 `../cloudflare/README.md`。

## 安全注意事项

- 生产环境必须使用 HTTPS；不要把 session 或 device token 写进 URL。
- Web 使用 HttpOnly Cookie，前端不读取 session token。
- 账号模式的 D1 `messages.body` 为可供 Web 展示的正文；若需要严格端到端
  加密，应继续使用旧 `/v1/*` Relay 或后续增加浏览器端密钥分发。
