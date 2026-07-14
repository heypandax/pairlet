# 0714-batch —— 07-14 issue 批次新界面设计

> 在线设计板（登录 claude.ai 可看）：
> - 预约发送三件套：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FScheduled+Prompts.html
> - skills/插件浏览页：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FSkills+%26+Plugins+Browser.html
> - 网关模型预设：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FGateway+Model+Presets.html
> - 聊天小状态件：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FChat+Components.html

对应 brief：`~/Desktop/Brain/60_Outbox/2026-07-14-cc-pocket-0714批次新界面设计提示词.md`。

## 文件清单

| 文件 | 内容 | 目标实现位置 |
|---|---|---|
| `scheduled-prompts.jsx` + `Scheduled Prompts.html` | #137 预约发送 sheet（默认／custom 展开／repeat-on 三态）、定时任务管理页（列表／空／stale 三态）、限额 auto-continue banner（offer／confirmed 两态） | mobile `ui/ScheduleUi.kt`、Settings 定时任务页、composer 上方 banner |
| `skills-plugins.jsx` + `Skills & Plugins Browser.html` | #132 桌面双栏浏览页（man-page 式详情、skill／plugin 两变体、loading／stale／empty 态） | desktop `SkillsOverlay.kt` |
| `gateway-presets.jsx` + `Gateway Model Presets.html` | #139 模型选择器网关预设（检测态置顶＋host pill／折叠态／桌面 popover 三帧、供应商 monogram 色板） | mobile ModelPicker（SessionSheets.kt）、desktop Popovers |
| `chat-components.jsx` + `Chat Components.html` | #134 DocumentCard 分片加载（determinate 进度条＋mono 字节 caption）与 too-large 平静态；#147 加载更早历史行（ambient／静默收起／seam 分隔线三态） | mobile/common `ui/DiffView.kt`、聊天顶部懒加载行 |

## 落地状态

- 生成：2026-07-15 凌晨（Opus 4.8，四轮追加式生成，复用项目内 desktop-core.jsx / ios-frame.jsx / chat-cards.css 词汇）
- 实现：进行中（见 git log 中引用本目录的 commit）

## 设计要点（实现时别丢）

- 终土陶色纪律：每帧一个 focal；供应商 monogram 用语义色板五色（blue/violet/amber/teal/pink），terracotta 只留给选中态与 suggested tick。
- 限额 banner 两态同高，原地翻转不推挤 composer。
- too-large 卡片是「事实陈述」不是错误：无红色、无警示色调。
- 加载更早历史行是 ambient 状态行不是按钮；静默失败直接淡出、不出错误不出重试。
- skills 详情页 man-page 式：mono 键左对齐 facts 表、正文 inline code 上浮小 chip、truncated 尾注安静收场。
