# 桌面端「CDP 启动 Dia」按钮需求（→ ccpocket）

> 场景：开发者/自动化在桌面端想让 AI（或脚本）通过 CDP 驱动 Dia 浏览器，做 UI 验收、截图、页面自动化。
> 本文档只客观陈述上下文与期望结果，具体方案由 ccpocket 侧设计。

---

## 需求：输入框右下角加「CDP 启动 Dia」按钮

### 当前情况

- Dia（The Browser Company 出品，Chromium 内核）默认启动**不开** remote debugging 端口，外部无法通过 CDP（Chrome DevTools Protocol）连接它。
- 想用 CDP 驱动 Dia，每次都要手动：彻底退出 Dia → 终端敲 `--remote-debugging-port` 重开 → 再登录。反复折腾，且容易忘记先退导致端口没开。
- 桌面端已有输入框组件 `ComposerField`（`mobile/composeApp/.../ui/VoiceComposer.kt`），右侧已有圆形动作按钮区（`RoundActionButton`，发送/录音）。希望在这里加一个一键动作。

### 期望结果

- 桌面端输入框右下角新增一个按钮，点击后让 Dia 以 CDP 调试端口启动（默认 `9222`），使外部能连上做页面自动化。
- 仅桌面端出现（移动端无此能力，按钮隐藏/不存在）。
- 点击后给出明确反馈：已启动 / 端口已就绪 / 失败原因。

### 相关事实（务必纳入设计）

- **CDP 只能通过命令行 flag 开**，没有配置文件/偏好项能设（Chromium 安全设计）。启动参数：`--remote-debugging-port=9222`。
- **Dia 已在运行时，直接带 flag 再启动无效**：Chromium 会把请求转发给已有实例，端口不会打开。必须先**彻底退出**现有 Dia 进程，再带 flag 启动。这是本需求最关键的坑——一个"直接 open Dia"的按钮在 Dia 已开着时会静默失败。
- 启动方式（macOS）：`open -na Dia --args --remote-debugging-port=9222`，或直接执行 `/Applications/Dia.app/Contents/MacOS/Dia --remote-debugging-port=9222`。
- 就绪判断：启动后轮询 `http://127.0.0.1:9222/json/version` 有响应即端口就绪（注意用 `127.0.0.1` 而非 `localhost`，避免 Node/部分客户端解析到 IPv6 `::1` 连不上）。
- **项目内已有精确先例**：`desktopMain/.../desktop/TerminalLauncher.kt` 用 `ProcessBuilder("open", "-na", "Ghostty", "--args", ...)` 启动带参数的 mac app；平台差异走 expect/actual（参考 `OpenUrl.desktop.kt`、`DesktopPathOpener.kt`）。启动 Dia 可复用同一套路。
- **安全代价**：给日常主 Dia profile 开 CDP，意味着本机任何进程都能读取/操控其所有登录态（飞书、GitLab 等）。设计需考虑是否用独立 profile（`--user-data-dir`）或至少向用户显式提示风险。

### 待明确（设计侧决定）

- **Dia 已运行的处理策略**：三选一——① 按钮先杀掉现有 Dia 再带端口重启（会丢当前窗口状态，需二次确认）；② 检测到已运行且未开端口时，只提示用户手动重启；③ 用独立 `--user-data-dir` 起一个专用调试实例（不碰主 Dia，但需重新登录）。推荐 ③ 兼顾安全与不打扰。
- 端口是否可配（默认 9222，是否暴露到设置项）。
- 按钮形态与图标（是否复用 `RoundActionButton` 的 hairline outline 样式，与发送按钮区分）。
- 是否需要「一键启动后自动把 CDP endpoint 回填到某处」供下游工具消费。
- Dia 未安装时的降级（`/Applications/Dia.app` 不存在时按钮禁用或提示）。
