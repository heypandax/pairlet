# 跨项目会话切换器（issue #165）

claude.ai/design 生成，2026-07-19。设计对话：cc-pocket 项目 ▸「Cross-project Session Switcher」。

`frames.png` 是三帧交付稿：

| # | 帧 | 内容 |
|---|---|---|
| 1 | Chat — switcher entry | 顶栏在 `⋯` 左边多一枚 28dp 计数方块（stack chip），角标点表示别处有事 |
| 2 | Switcher sheet — default | 当前会话置顶带勾 → RUNNING（含琥珀 approval 药丸）→ RECENT（含未读点）→「全部项目」兜底 |
| 3 | Switcher sheet — empty | 只有当前会话时的空态 |

## 与实现的三处偏离（都是有意的）

**1. 琥珀色 approval 药丸没做。** daemon 把审批 ask 绑在「开着该会话的那条连接」上，手机因此只拿得到*当前*会话的 ask——稿子画的「别的会话在等审批」这个状态今天不可达。`SessionWorkingSet.buildWorkingSet` 留了 `approvals` 参数作为 seam，等 ask 变成账号级广播时接上即可，届时不需要改其它任何东西。

**2. 相对时间用 `relativeTime()`（"12m ago"）而非稿子的极简 "12m"。** 复用既有 helper 少引一套时间格式，且跟随系统语言；稿子是英文语境下的紧凑写法。

**3. 空态那帧的 chip 画了「0」，实现里不渲染。** 顶栏本来就挤（本 issue 的前提就是这个），而 `otherCount == 0` 时本就无处可切，画个「0」是纯噪音。空态 sheet 仍然实现了——从别处进来时会看到。

## 第二轮：入口位置改版（`entry-placement-option-*.png`）

第一轮的顶栏 28dp 方块真机上栽了两次：**顶栏没空间**（375pt 上被牺牲的是标题），**而且一个装着数字的方框读起来像状态徽章、不像可点控件**。这正是模型 chip 当初的病，而 `490e039` 的 composer 双层重排已经开好了药方。

两个方案：

- **A（已采纳）**：沉到 composer accessory 行，紧挨模型 chip —— `[+] [fable ⌃] [🗂 3]`。沿用模型 chip 的药丸语法（30dp 高、hairline、raised 底、永不填充强调色），但带一枚 stack 图标把含义画出来而不是让人猜。中间的弹性空隙吸收它，模型 chip 的 120dp 标签与 44dp 动作键都不受挤。
- **B（设计推荐，未采纳）**：折进顶栏状态副行当一个可点段 —— `机器 · 文件夹 · [🗂 3 ›]`。

**为什么没听设计的。** B 的论据是语义纯度（导航该待在「我在哪」那行），代价是它自己承认的发现性下降。但对照两张稿子可以直接验证：B 里文件夹被截成 `~/work/platform/edge-gatewa…`，A 里则是完整的 `~/work/platform/edge-gateway`。B 只治了「看不懂」，却把「顶栏没空间」这个**首要**抱怨弄得更糟。A 两个都治。

**A 的代价（设计点破的，如实记下）**：accessory 行回答的是「这条消息会怎么发」，而切换器是导航——点下去把你甩进另一个会话，这个控件却坐在发送键一拇指之外。所以药丸保持安静、永不填强调色，让发送继续是这行最响的东西。

## 实现落点

- `ui/SessionSwitcher.kt` —— `SessionStackChip` + `SessionSwitcherSheet`
- `data/SessionWorkingSet.kt` —— 纯逻辑与读模型（MRU / running / recent / otherCount / attention）
- `data/PocketRepository.kt` —— `workingSet()` / `switchToSession()` / `rememberOpenedSession()`
- 测试：`commonTest/…/SessionWorkingSetTest.kt`（12）+ `desktopTest/…/SessionSwitcherUiTest.kt`（5）
