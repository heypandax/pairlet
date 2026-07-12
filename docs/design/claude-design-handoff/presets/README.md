# API-key 预设管理 — Settings ▸ Account pane（设计 handoff）

- **在线设计板**（登录 b01099485423@gmail.com 即看）：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Settings+Account.dc.html>（单文档 11 帧，画布可平移缩放；三段自上而下＝Prompt 3 切换反馈 / Prompt 2 表单 / Prompt 1 总览）
- **生成**：2026-07-11，claude.ai/design 新正典项目（b01099485423 账号），模型 Opus 4.8 Medium，三个 Prompt 顺序追加进同一 `Settings Account.dc.html`，均一次通过（0 重试）
- **项目沿革**：本项目 `c41b194b-…` 为设计线正典（自 Workflow 批次账号迁移后，见 `../workflow-view/README.md`）；旧 pandaleeng 项目 `93b56700-…` 存史
- **对应 issue／任务**：#113 —— daemon（预设存储 + 会话启动 env 注入 + 协议）+ desktop（Settings UI）。**UI 实现等本设计定稿、且 daemon 侧预设协议（掩码回传形状 / 切换语义 / 拒切 blockers 复用）确定后再派发**，手机端后续跟进
- **本地打开**：目录下 `python3 -m http.server` 后访问 `Settings Account.dc.html`（仅依赖同目录 `./support.js`，自洽）

## 文件清单

| 文件 | 内容 |
|---|---|
| `Settings Account.dc.html` | 全部 11 帧的单一自包含文档（JSX 内联，64KB）——三段：切换反馈 3a-3d / 表单 2a-2c / 总览 1a-1d。**像素规格主参考**（token、尺寸、掩码格式、示例数据都在内联 script） |
| `support.js` | claude.ai DC 画布运行时（平移/缩放/页签），文档唯一外部依赖 |

## 三段 11 帧内容

**Prompt 1 · Account pane 总览（1a-1d）**
- 1a API-key·presets·dark：Authentication 卡（锁图标 + "API key" + provenance pill "preset · Work proxy"；BASE URL 明文 `https://api.example-proxy.com/v1`；TOKEN 掩码 `ANTHROPIC_AUTH_TOKEN · sk-…••••3f9a`；MODEL ROUTE `sonnet → gpt-4o · haiku → gpt-4o-mini`）+ 预设列表三行（Work proxy active / Personal key / Local llama，hover 显 Edit·Delete，active 显 mono "active" tag）+ 虚线「＋ New preset」+ 收尾脱敏 caption
- 1b Empty·dark：Authentication 显 "No API key set on this computer · unconfigured"，预设组退化为友好提示
- 1c OAuth 共存·dark：Authentication 换成账号卡（jordan@example.com + MAX pill + Switch account…/Log out），预设组仍在但空，附「presets 是给第三方 API 端点，激活即让新会话跑那个 key 而非 Claude 登录」解释——证明两类用户共用一个 pane
- 1d 1a 的浅色副本

**Prompt 2 · 新建/编辑表单（2a-2c）**
- 2a New preset·clean·dark：‹ Presets 返回 + Name/Base URL（带 `ANTHROPIC_BASE_URL` helper）/Auth token（掩码输入 + eye toggle + `AUTH_TOKEN|API_KEY` 分段切换 + "never shown here again" helper）/Model routing 可折叠/Cancel·Save；旁附 token 字段 **Masked（默认）vs Revealed while typing** 对照，让 eye-toggle 行为无歧义
- 2b Edit·validation errors·dark：三条内联错误（重名/非法 URL/空 token）+ danger hairline + Save 禁用 + Delete 钉左 + edit 占位 "•••• stored — leave blank to keep"
- 2c 2a 的浅色副本

**Prompt 3 · 切换反馈（3a-3d）**
- 3a Activating·dark：点中行 "Personal key" 显 terracotta spinner + "Activating…"，旧 active 行 accent 淡出
- 3b Settled·dark：Personal key 成 active（accent + mono "active"），顶部 Authentication 卡更新到新 base URL + 新掩码 token，列表下 muted 说明 "New sessions on studio-mini use this preset. Sessions already open keep the endpoint they started with."
- 3c Blocked·dark：warn 三角 blockers 卡（acme-web "Mid-turn right now" / robotics-firmware "2 background tasks"，逐行 Stop + "Stop all & switch" + resumable copy），下附 inline error line "Couldn't reach the computer — try again."——**复用 OAuth account-switch 的拒切处理**
- 3d 3b 的浅色副本

## 落地状态

**已实现**（分支 `issue-113-api-presets`，与本 README 同一提交）。落点：`SettingsModal.kt` 的 `AccountPane` 改版 + `PresetsSection`/`PresetForm`/`PresetAuthCard`，复用既有 Group / 选中行 / dashed「Add」行；blockers 卡提取为 `WorkingBlockersCard` 与 OAuth 切换共用。daemon 侧 `PresetStore`（0600，明文仅存本机）+ `PresetService`（复用 busy/idle 切换守卫）+ `ClaudeLauncher.applyPresetEnv`（新会话注入）；协议 `pocket/presets.*` 尾追。占位校准结果：模型路由 = `ANTHROPIC_MODEL` / `ANTHROPIC_SMALL_FAST_MODEL` 两字段（卡片显示 `model → x · fast → y`）；掩码阈值 ≥16 字符才显示前缀 + 末 4；手机端本期未做（协议与 repo 层 seam 已留）。

## 脱敏红线核验（设计已全部遵守）

- token 明文永不回传：全帧展示恒为掩码 `sk-…••••3f9a`，**不 hover 揭示**（生成侧总结明确 "Token stays masked, no hover reveal"）；
- 编辑态 token 留空 = 保留原值（2b 占位 "•••• stored — leave blank to keep"，空 token 在 CREATE 才报错）；
- base URL 明文完整展示，不参与掩码；
- 掩码 = 短前缀 + 末 4 位中段点掩（位数占位）。

## 占位提醒（brief 原话）

所有 token 掩码格式、env 变量名、`{computer}`（稿内示例为 studio-mini）、模型路由示例（sonnet → gpt-4o）、「新会话生效」与拒切文案**均为占位**，实现时按 #113 最终 daemon 协议校准。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——三个 Prompt 的 LAYOUT／STATES／DELIVERABLES 逐条落全（11 帧 = 4+3+4），脱敏红线四条全守，OAuth 共存与拒切复用两个「共用锚点」立场都体现。
- brief 建议的「预设行 / 当前认证卡 generate variants」未展开——一次成型质量已足；要比稿可在本项目补一轮。
- 小注意：稿把全部帧堆进单一 `.dc.html`（自包含内联，非 workflow 那种 scene-jsx 外链）——实现读该文件内联 script 即可，无独立 jsx 源。
