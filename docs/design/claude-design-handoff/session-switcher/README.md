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

## 实现落点

- `ui/SessionSwitcher.kt` —— `SessionStackChip` + `SessionSwitcherSheet`
- `data/SessionWorkingSet.kt` —— 纯逻辑与读模型（MRU / running / recent / otherCount / attention）
- `data/PocketRepository.kt` —— `workingSet()` / `switchToSession()` / `rememberOpenedSession()`
- 测试：`commonTest/…/SessionWorkingSetTest.kt`（12）+ `desktopTest/…/SessionSwitcherUiTest.kt`（5）
