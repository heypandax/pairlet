# 文件夹级共享 — owner 邀请 / 管理 / guest 加入 / 终态（设计 handoff）

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  - Owner 邀请流：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Share+Folder.dc.html>
  - Owner 管理页：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Shared+Folders.dc.html>
  - Guest 加入流：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Join+Folder.dc.html>
  - 共享终态：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Share+Endings.dc.html>
- **生成**：2026-07-11，claude.ai/design 正典项目（b01099485423 账号 `c41b194b-…`），模型 Opus 4.8 Medium，四个 Prompt 顺序追加，各自建独立 `.dc.html`；Prompt 1 曾在收尾自查阶段撞一次会话限额（5 帧已全建完，Resume 后收尾，无内容损失），其余 3 段一次通过
- **对应 issue／任务**：#115 文件夹级共享——本 brief 只解 UI/UX；协议（scope ticket）、daemon（路径守卫 + 净室 profile + 审批路由）、relay（范围化 ticket）另拆。**实现等设计定稿 + 最终安全模型/crypto review 结论后派发**
- **本地打开**：目录下 `python3 -m http.server` 后访问四个 `.dc.html`（各自包含帧内联，仅依赖同目录 `./support.js`）

## 文件清单

| 文件 | 内容 |
|---|---|
| `Share Folder.dc.html` | Prompt 1 · owner 邀请流（5 页/4 帧 + 变体）——**含全组核心「They get / They don't get」边界卡** |
| `Shared Folders.dc.html` | Prompt 2 · owner 管理页（列表三态+活动 feed / 撤销确认 sheet / 空态） |
| `Join Folder.dc.html` | Prompt 3 · guest 加入（兑换+error / 接受预览 / presentation+Shared 徽标 / 浅色） |
| `Share Endings.dc.html` | Prompt 4 · 终态（guest 终态行/终态卡/中途断连 banner / owner 历史+Share again） |
| `support.js` | claude.ai DC 画布运行时（平移/缩放/页签），四文档共用 |

## 四段内容

**Prompt 1 · Owner 邀请流（`Share Folder.dc.html`）**
- 1a entry：Projects 列表某行长按 → 上下文菜单（New session / Pin / 分隔 / Share this folder…），余项压暗
- 1b share composer（owner 信任屏）：mono 文件夹头 + Access level 三档 radio 卡（Review / Collaborate 预选带 recommended / Autonomous 带 warning）+ Expires pill 行（1h/24h/7d/30d）+ **边界卡 hero**（"They get" ✓ 三条 / "They don't get" ✗ 四条 + warning footer "Shell commands are not sandboxed…"）+ 折叠 Integrations 行 + Create invite
- 1c invite ready：QR 卡 + 大 mono 短码 "K7QX–2MRD" + 倒计时 caption + recap 条 + Share…/New code + revoke 提示
- 1d 1b 的浅色副本（锁边界卡浅色）

**Prompt 2 · Owner 管理页（`Shared Folders.dc.html`）**
- 2a 列表：share 卡三态（active now 带脉冲 dot + 活动一瞥 / idle / near-expiry warning 色）+ guest chip + tier/有效期 chip + 每卡 Revoke ghost + 一张展开显只读活动 feed（mono 时间戳事件行 + "Permission requests go to Alex. You see activity here." info footnote）+ 底部 caption
- 2b 撤销确认 bottom sheet：danger shield + mono 标题 + 三句诚实后果 + Cancel/Revoke access，无 checkbox 摩擦
- 2c 空态："You haven't shared any folders" + 长按提示

**Prompt 3 · Guest 加入（`Join Folder.dc.html`）**
- 3a redeem：相机取景框 + "or enter invite code" 分段码输入 + 单用提示；error 变体分段框 danger 抖动 "This invite has expired."
- 3b accept preview（guest 信任屏）：文件夹 mono + origin "shared by panda" + tier/有效期 pill + 边界清单 ✓ 三条（沿用 owner 边界卡形态）+ Join folder/Decline
- 3c presentation：Projects 列表混本地 + 共享行（**中性 "Shared" pill，hairline+link glyph，刻意非陶土**，陶土留给 attention）+ origin mono caption + "6d left"；下方叠共享项目会话列表 + 一次性可关 info 条 "Permission requests for agents here come to you…"
- 3d 3b 的浅色副本（锁 guest 信任屏浅色）

**Prompt 4 · 终态（`Share Endings.dc.html`）**
- 4a guest 终态行：Projects 列表两条 ended（Expired 灰行 muted pill / Access ended 灰行 danger-muted pill "revoked by owner"），可开 4b
- 4b guest 终态卡：muted 文件夹带斜杠 glyph + "Access ended" + "panda ended this share on Jul 11…" + Remove from list / Ask for a new invite——**muted 收场无红墙**
- 4c 中途撤销：guest 会话 Chat 屏，连接条下滑入细 danger-hairline banner "This share was revoked · session disconnected"，消息流保留可读但压暗 20%，composer 禁用占位 "Access ended"，无 modal 无倒计时
- 4d owner 历史：Shared folders 分 Active / History，History 卡 "Revoked · Jul 11" / "Expired · Jul 18" + guest chip 灰 + Revoke 换成 ghost "Share again"（生成新邀请跳 composer）

## 落地状态

**未实现**（本轮到设计定稿归档为止）。移动端定稿；桌面端（管理页进 Settings modal、共享行进侧栏项目区、邀请码走弹窗）按同语言后续适配，不单独出 brief。实现读各 `.dc.html` 内联 script 即像素规格。

## 设计语言约束核验（全部遵守）

- 不引入新强调色：陶土=需要你 / 青色=Codex 保持；**共享徽标走中性 hairline pill**（3c 明确非陶土）；
- 文案**禁用 "sandbox" / "fully isolated"**：owner 信任屏用 "scoped access for a trusted collaborator"，边界卡 footer 诚实告知 "Shell commands are not sandboxed — they run as your user"（生成侧总结明确守约）；
- 终态 muted 无红墙、无 retry spinner（4b/4c 体面收场）。

## 占位提醒（brief 原话）

所有安全文案（权限档名称与描述、边界清单措辞、shell 提示语、有效期档位、邀请码形态）均为**占位**，实现时按最终安全模型与 crypto/security review 结论逐条校准（权限档实际映射、净室 profile 覆盖面、Bash 如实告知措辞——宁可少承诺）。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——四个 Prompt 的 LAYOUT／STATES 逐条落全，两张信任屏（P1 Frame B、P3 Frame B）各带浅色锁版，边界卡是全组核心已充分表达。
- brief 建议的 variants（边界卡双栏 vs 堆叠 vs 单列 checklist、Shared 徽标 link vs 双人 glyph）**未展开**——一次成型质量足；要比稿可在本项目补一轮。
- 四段各建独立 `.dc.html`（自包含内联，非单文件堆叠），实现读各文件内联 script 即可，无独立 jsx 源。
