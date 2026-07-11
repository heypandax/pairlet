# Workflow 编排视图 — 运行卡片／进度树／journal 回看／桌面停靠面板（设计 handoff）

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  - 手机卡片 + 进度树：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Workflow+Run.dc.html>
  - agent 详情 sheet + journal：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Workflow+Journal.dc.html>
  - 桌面停靠面板：<https://claude.ai/design/p/c41b194b-c78d-4280-bc92-48148e6b5429?file=Workflow+Desktop.dc.html>
- **生成**：2026-07-11，claude.ai/design，模型 Opus 4.8 Medium，三个 Prompt 顺序投递，均一次通过（agent 自查含渲染空白修复、`const T` 拼接冲突 IIFE 隔离等，未消耗重试名额）
- **⚠️ 项目迁移事实**：旧 cc-pocket 设计项目（pandaleeng@gmail.com 账号，`93b56700-…`）当日撞两次会话限额且共享功能缺失，改走「本地导出→新账号导入」迁移：**b01099485423@gmail.com 下的新 cc-pocket 项目（`c41b194b-…`）自本轮起为设计线正典**；旧项目保留存史（附件体系及更早各屏都在那边，源码已归档 `../attachments/` 等目录）。新项目以 9 个种子文件（ia-core / ia-parts / ios-frame / desktop-core / workflow-core / chat-cards.css / file-attach / sent-attach / desktop-attach）+ §10.1 摘要重建了设计系统上下文。
- **对应 issue／任务**：#106 多 agent 运行整体进度（daemon 解析/跟踪 + 协议字段 + 双端 UI）——**实现尚未开始**，等主会话派发；probe wire 形态的前置探测与本设计并行不阻塞
- **本地打开**：目录下起 `python3 -m http.server`，访问三个 `.dc.html`（各自引 `./support.js` 与 `workflow-scene.jsx`，本目录已自洽）

## 文件清单

| 文件 | 内容 |
|---|---|
| `Workflow Run.dc.html` | 屏 1 入口：手机聊天流卡片 + 全屏进度树（pan/zoom 画布，三台 iPhone） |
| `Workflow Journal.dc.html` | 屏 2 入口：agent 详情 sheet + 终态 journal 回看（2 页） |
| `Workflow Desktop.dc.html` | 屏 3 入口：桌面三栏停靠面板（3 页：live／terminal／light） |
| `workflow-scene.jsx` | 三屏共用的拼接产物（ia-core + workflow-core + ios-frame + desktop-core + scene，各源 IIFE 隔离）——**生成文件勿直接编辑**，改源后重拼 |
| `scene-only.jsx` | 场景专属源码（71KB）：三屏全部布局/组件/示例数据——**像素规格主参考** |
| `workflow-core.jsx` | 编排核心组件源：tokens／堆叠泳道 glyph／PhaseBar 分段条／WorkflowCard 全状态／PulseDot |
| `support.js` | DC 画布运行时（pan/zoom/页签），三个 .dc.html 共用 |

## 三屏内容

1. **屏 1 · 手机卡片 + 进度树**——Frame A：聊天流一张 live `release-pipeline` 卡（28pt 堆叠泳道瓦片 + 4 段 phase 条（done 实心/active 呼吸/pending 描边）+ meta 行「phase 2/4 · analyze · 12/34 agents · ✗2 chip」+ 计时），旁挂变体条四态（live 总数未知「12 done · 6 running」／全绿 ✓／完成带 ✗2／aborted ✗，全程无红边框）；Frame B：全屏树（`wf_8f3a21…` header + 状态丸、resolve 收成「✓ 6 agents · 48s」摘要、analyze 展开（running 行共享同步 pulse、queued 空心点、"+ 18 queued" 可点行）、checks 收起但两条失败行钉在组头下、package/publish 弱化、「↓ jump to active」浮丸）；浅色锁版 Frame A
2. **屏 2 · 详情 sheet + journal**——Frame A：~70% 高 bottom sheet（拖把手、状态 glyph + "fix module-payments" 17 semibold、`phase · fix — 1m 08s — agent-07` mono caption、Prompt 区（raised 引用块 2 行折叠 more）、Return 区（SubagentCard report 语言：内滚 ≤半屏、mono 13、Copy return + Open full transcript 页脚）），旁挂失败变体（✗ header、error 文本仅 Return 块细 danger hairline）；Frame B：终态运行页（✓ · 34 agents · 6m 32s、Final return 置顶 mono 卡 6 行折叠 Expand+Copy、[Phases | Journal] 分段切换、journal 流水列表：#07 index + label + 单行 return 预览 + glyph + 时长、失败行 danger 首行错误、点行开同一 sheet）；浅色锁版 Frame B
3. **屏 3 · 桌面停靠面板**——Frame A live：三栏（侧栏 · 聊天栏（dense 卡 active：accent hairline + 指向面板的 chevron）· ~360px 停靠面板（header 名称/`wf_8f3a21…`/状态丸/×、全宽 phase 条、28px agent 行密度树、失败钉出、+18 queued、一行 hover（raise+chevron）、一行原位 accordion 展开（≤150px 内滚 report、hover 显 copy）））；Frame B terminal：✓ · 34 agents · 6m 32s、两条 ✗ 可见（其一 accordion 展开显 AssertionError 全文）、Final return 置底（mono 折叠 hover copy）、聊天卡同步终态；浅色锁版 Frame A。**验证了「停靠优于遮盖」的 brief 立场——三栏空间成立，未出 overlay 对比版**

## 落地状态

**未实现**（本轮只到设计定稿归档）。实现按 #106 派发：daemon 目录轮询/解析 + 协议字段 + mobile & desktop UI（预计 daemon 与 App 同版）。`scene-only.jsx` + `workflow-core.jsx` 即像素规格（token 引用、尺寸、间距、示例数据形状都在内），落 Compose 以此为准。

## 占位提醒（brief 原话）

稿内 workflow 名（release-pipeline）、phase 名（resolve / analyze / checks / package / publish）、agent label、数字与时长**均为占位**，实现时按 `meta.phases` 与 journal 实际字段校准；进度数据可能来自 daemon 目录轮询而非毫秒级 wire 事件，`parallel()` 扇出会让总数中途增长（稿内「总数未知」变体即为此准备）。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——三个 Prompt 的 LAYOUT／STATES 逐条落全；「与 SubagentCard 同族但一眼可分」「失败永不折叠」「同步 pulse 密度纪律」「danger 只落 glyph/chip」「停靠优于遮盖」五条立场全部体现。
- 小注意点 1：屏 1 Frame B 的 phase 名用了 resolve/analyze/checks/package（+屏 3 有 publish），与 brief 示例 collect/analyze/fix/verify 不同——**均为占位**，无实质影响。
- 小注意点 2：brief 建议的「收起卡进度元素 generate variants（分段条 vs 单条+文字 vs 迷你堆叠行）」与「agent 行密度 40pt vs 44pt variants」两个可选探索**未展开**——设计一次成型质量已足；若主会话想比稿可在新项目里补一轮 variants。
- 小注意点 3：`workflow-scene.jsx` 为拼接生成文件，实现侧读 `scene-only.jsx` + `workflow-core.jsx` 即可，别把拼接文件当源。
