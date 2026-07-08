# Chat cards — SubagentCard 与 DocumentCard（设计 handoff）

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2Fsite%2FChat+Cards.html>（登录即看，工具栏可切 running 处理方案／动效冻结／hover 态）
- **生成**：2026-07-08，claude.ai/design，cc-pocket 设计项目追加模式

## 文件清单

| 文件 | 内容 |
|---|---|
| `Chat Cards.html` | 评审文档主体：3 屏——移动端聊天流实景、组件全状态表、桌面双栏密排 |
| `chat-cards.css` | 像素规格源：状态瓦片／family tint／折角徽标／报告区／dense 修饰／hover 显隐 |
| `chat-cards.js` | 状态切换逻辑（running 三方案切换、动效冻结、hover 演示），仅供理解 |

## 三屏

1. **Screen 01** 移动端聊天流实景——两种卡片穿插在正常助手消息之间；running 三个处理方案（pulse dot／breathing line／accent bar）可切
2. **Screen 02** 组件全状态表——SubagentCard 五态（running／succeeded 折叠／succeeded 展开／failed／background）、running 三方案对比、DocumentCard 三态（normal／too-large／loading）、六族类型徽标、桌面 Changes 头部图标
3. **Screen 03** 桌面双栏聊天——同一卡片语言的 dense 版（瓦片 32→28、报告 210→150、chip 34→30），hover 才显 chevron 与抬升

## 落地状态（2026-07-08 同日实现）

- 移动端 SubagentCard：`mobile/.../ui/SubagentCard.kt`（从 App.kt 抽出为共享组件）——状态瓦片（accent／ok／danger tint）、`Type · description` 标签、ECG 活动符进度行（替代 ⚒ emoji）、pulse dot + 计时、✓/✗ 落定、报告卡内滚动（≤210dp）+ footer 摘要与复制
- 桌面端：ChatPane 的 subagent 分支改用同一卡片 `dense = true`——更紧密度、hover 显 chevron 并抬升 raised
- DocumentCard：`mobile/.../ui/DiffView.kt`——六族 tinted 徽标（family 色引用 Tok token，@12–16% 透明度集中定义在 `DocFamily`）+ mono 扩展名 + 折角纸张 cue；normal／too-large（点名 1800 KB 上限，尺寸从 daemon 错误文本解析）／loading（横扫进度条）三态；action chip 带图标与 hover
- 桌面 Changes 头部：copy · open · save-as 收成一组（间距 2dp），26dp 圆角热区，hover 抬升 raised + 提亮

与设计稿的有意偏差：

- **background 态未实现**——wire 上没有 `run_in_background` 信号到达手机（daemon 只在 jobs sheet 侧记录），需协议字段，超出本次样式打磨范围
- succeeded 摘要只有「N 个工具」——协议不带耗时与文件数（"explored 14 files · 8s" 无数据源）；历史回放不带 childCount，此时摘要行省略
- running 计时从本设备首见起算（wire 无开始时间戳），中途接入会少算
- loading 进度条为横扫动画而非字节读数——传输是单个 relay 帧，途中无进度可报
- 图标沿用 Material Rounded 体系（与全 App 一致），未 1:1 复刻稿内 1.5pt 自绘 SVG；状态瓦片与 ECG 活动符按稿内 path 用 Canvas 复刻

## 两处待拍板项（按设计稿默认实现）

1. **PDF 徽标**＝低 tint 红（danger @12%，比其他族更低一档，避免读成错误态）
2. **进度行活动符**＝ECG 心电风格矢量（Canvas 描边复刻稿内 path），替代 ⚒ emoji；running 指示取三方案中的 **pulse dot**（与现状延续性最好）
