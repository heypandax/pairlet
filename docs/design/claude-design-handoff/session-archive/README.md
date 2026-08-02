# 会话归档（Session Archive）— 设计 handoff

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  <https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=Session+Archive.html>
- **生成**：2026-08-03，cc-pocket 正典项目追加对话，Opus 5 Medium，一段 brief 一次通过（无自查返工）。
- **依据**：GitHub issue [#202「会话归档」](https://github.com/heypandax/cc-pocket/issues/202)；
  brief 存档 `~/Desktop/Brain/60_Outbox/2026-08-03-cc-pocket-会话归档设计提示词.md`。
- **本地打开**：本目录 `python3 -m http.server` 后访问 `Session Archive.html`
  （基础样式共享 `./session-handoff/handoff.css` 副本，增量样式在 `./session-archive/archive.css`）；
  或直接双击 `Session Archive (standalone).html`（单文件内联版，离线可开）。

## 文件清单

| 文件 | 内容 |
|---|---|
| `Session Archive.html` | 一张画布，7 帧 ＋ 右侧裁决面板 |
| `session-archive/archive.css` | 本轮增量样式（归档屏行／trailing unarchive／toast／退化态 sheet／桌面底栏行／右键菜单／⌘K ARCHIVED 域） |
| `session-handoff/handoff.css` | 与既有画布共享的基础 token 样式（副本，便于独立预览） |
| `Session Archive (standalone).html` | 单文件内联版（475 KB，字体与样式全内联，离线可开） |

## 帧清单

| 帧 | 内容 | 规格要点 |
|---|---|---|
| 1 | 手机 · 长按 sheet（完整态） | 390×844；两分区 `MOVE TO GROUP`（Active／当前组／New group…）＋ `SESSION`（Rename／Archive）；Archive 带副文案 `hides on all devices`；顶栏右侧新增归档图标入口 |
| 2 | 手机 · 长按 sheet（退化态） | 项目无分组：整段 MOVE TO GROUP ＋ **两个分区标题都消失**，sheet 塌成两行平铺（Rename／Archive），底部一条虚线提示 `No groups in this project — create one`；标题由会话标题承担 |
| 3 | 手机 · 归档屏 | 顶栏 `Archived` ＋ 计数 chip ＋ 搜索；跨项目按 `~/路径` 分组（组头带该组条数）；行 ＝ 标题／首句／`💬 N · ⑂ branch · 相对时间`；**行尾常驻 44dp unarchive 图标**；正在运行的归档会话保留绿点 `running` |
| 4 | 手机 · 反馈态 | 被取消归档的行 opacity .35 淡出、图标 press 态；底部 toast：`Back in ~/code/cc-pocket` ＋ mono 副行 `会话名 · still running` ＋ 右侧反向动词按钮 `Archive`；顶栏计数 14→13 |
| 5 | 手机 · 空态 | `Nothing archived` ＋ 一段解释：长按任意会话选 Archive；明说「它仍在跑」「在所有设备上都隐藏」。无插画、无按钮 |
| 6 | 桌面 · 侧栏 ＋ 右键菜单 | 1180×680；侧栏底部 `All projects… ⌘P` 下方新增 `Archived sessions` 行，**右缘是计数 14 而非 keycap**；右键菜单 5 项，hairline 分隔后是 `Archive session ⌘⌫`（高亮）与 `Remove from recents`；行 hover 的 ✕ 是 22px |
| 7 | 桌面 · ⌘K ARCHIVED 域 | 560dp 面板、行高 42dp；查询区左侧 terracotta `ARCHIVED` 域标签；行 ＝ 归档盒图标 ＋ 标题 ＋ mono `~/路径`；**选中行常驻 `Unarchive ⌘⏎` 文字＋图标，hover 行只出图标**；底栏常驻图例 `⏎ Open · ⌘⏎ Unarchive · ⎋ Leave archive` |

## 四条裁决（画布右侧决策面板 · 逐条对应 brief 的冲突点）

### ① 手机取消归档的形态 → **行尾常驻图标**

44dp、一下、**不确认**。滑动与长按都被否：

- 滑动 —— 这屏是「难得进来一次」的房间，没人排练过的手势就是没人找得到的手势；宁可不做，也不做半套。
- 长按 —— 会把「这屏唯一存在的动词」藏进「把你带到这屏」的同一个手势里。

配套：**tap 行的其他任何位置＝打开会话，且保持归档**（对应工程前提「打开不自动解除归档」）。归档行若仍在跑，绿点保留 —— **archived ≠ stopped**。

### ② ✕ 与「归档」如何各自表意 → **不同重量、不同位置、不同措辞**

| | 桌面 hover ✕ | 归档 |
|---|---|---|
| 尺寸／位置 | 22px，**在行上**，仅 hover 出现 | 不在行上，只在**菜单**里 |
| 措辞 | `Remove from recents` | `Archive session` ＋ 副文案 `hides on all devices` |
| 图标 | ✕ | 方盒 glyph |
| 快捷键 | 无 | `⌘⌫` |
| 语义 | 本地、临时、**重开会话即自愈** | 共享、持久、跨端 |

关键手法：两者在右键菜单里**故意紧邻**（`Archive session` 紧接 `Remove from recents`，且菜单里的 ✕ 与行上的 ✕ 用同一枚图标，让「行控件」和「菜单项」显然是同一个功能）—— 差异是**读出来的，不是猜出来的**，读一次就不再混。核心原则：**持久的跨端状态不配 hover 量级的动作**。

两者都**不用红色**：这里没有任何操作会丢工作。

### ③ 菜单层级与危险度排序 → **编辑 → 归位 → 隐藏**

- **桌面右键菜单**：`Rename…` → `Move to group ▸` → `Copy path` → ─hairline─ → `Archive session ⌘⌫`（高亮） → `Remove from recents`。
- **手机长按 sheet**：按**频率优先、重量其次** —— `MOVE TO GROUP`（日常动词）在上，`SESSION` 分区在下，`Archive` 是最后一行。
- **退化态规则**：项目没有分组时，MOVE TO GROUP 整段连同标题消失；**`SESSION` 标题也一起消失**（标签需要兄弟才成立），sheet 变两行平铺。会话标题在两种形态里都担任标题工作 —— 所以以后有了分组，**sheet 只向上长，已有行不移位**。空分组提示做成底部虚线 hint 而非一行 row：它是入口，不是「对这个会话」的动词。

### ④ 是否需要 toast / undo → **要 toast，不做 undo**

- **不做 undo 栈**：归档与取消归档互为精确逆操作，toast 给的是**反向动词**而不是 Undo —— 同一个调用，**零额外工程**。
- toast 说清**去向**（`Back in ~/code/cc-pocket`）；从聊天里归档时按钮写 `Unarchive`，从归档屏取回时写 `Archive`。
- **正在运行的会话额外标 `still running`** —— 绿点消失是用户唯一会怕「我是不是把它杀了」的时刻，必须正面回答。
- toast 4s、点击即消、**不遮 composer**。
- **桌面不弹 toast**：整张列表都在眼前，侧栏 `Archived sessions` 的**计数跳动**就是回执（这也是它右缘放计数而非 keycap 的原因 —— 读起来像「一个有内容的地方」）。

### ⑤ 桌面第二动作：⌘K 域 vs 独立面板 → **保留 ⌘K 域，不另起面板**

独立小面板会为一个「月抛级」列表重复出搜索框、空态和导航，并把「找一个会话」**分叉成两处** —— 而归档本来就是为了消灭这种分叉。⌘K 面板本身就是桌面端的「找任何东西」的房间，ARCHIVED 只是其中一个域，可由侧栏行进入，也可 ⌘K 输 `arch` 进入。

真正修复可发现性的**不是 hover 图标，而是常驻底栏图例**：面板一打开就把两个动词都念出来。所以：

- 图标 hover 时出现，**并在选中行上常驻**（键盘用户不碰鼠标，也必须看得见）；
- `Unarchive` 字样只在**选中行**拼出来，hover 行只给图标；
- 底栏 `⏎ / ⌘⏎ / ⎋` 恒显；
- 域标签是查询框里的 terracotta tag，**永远知道自己在往哪个列表里打字**；
- **`⎋` 先退出域，再关面板**（两级）。

## 设计稿自带的全局法则

- 两条规则贯穿全稿：**放进去是菜单动词，拿出来是可见控件**。归档是审慎动作，所以藏在长按／右键后面；取消归档发生在「你特意走进来的房间」，所以是每行常驻的控件，绝不做隐藏手势。
- 颜色法则不变：terracotta **只**给取回动作与当前激活域；绿色 70% ＝ still running；mono muted ＝ 路径／计数／分支。
- **归档会话默认排除在 ⌘K 默认域和侧栏之外** —— 只有点名要它们时才出现。

## 明确的边界外（设计稿主动声明）

不做删除、不做过期、不做批量选择、不做自动归档建议、不改既有列表与分组布局。

## 落地状态（2026-08-03）

- 仅设计定稿，**实现未开工**（本轮只跑设计闭环）。
- 实现时的对接点：daemon 侧归档真相 ＋ 跨端同步、会话行 trailing 控件、PocketSheet 分区退化逻辑、桌面 ⌘K ARCHIVED 域与侧栏底栏计数、toast 组件（反向动词，不引入 undo 栈）；颜色一律走 `Tok` 语义 token，不新造 hex。
