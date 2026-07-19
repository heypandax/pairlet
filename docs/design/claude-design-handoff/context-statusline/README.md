# 上下文占用指示器入驻 composer 设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FContext+Occupancy+Placement.html>
- **生成日期**：2026-07-20，追加模式（复用 Model Chip / Mobile Composer / Session Switcher 三稿的 pill 语法与 iOS 机框），Opus 4.8 Medium 生成
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-20-cc-pocket-上下文指示器入驻composer设计提示词.md`
- **背景**：上下文占用一直是悬浮在消息流右下角的 `Context 42%` 药丸（issue #15，#81 又给它打过 gutter 补丁）。三宗罪——压内容、不可点、气质像 debug overlay。`490e039` 的 composer 双层重排 + `e8dc42c` 的切换器下沉，已经把 accessory 行做成常驻控件带，占用理应入驻。

## 文件清单

| 文件 | 说明 |
|---|---|
| `context-occupancy.jsx` | 像素规格源码（三方向对比 + 推荐方向五态） |
| `Context Occupancy Placement.html` | 设计板页面壳 |

## 稿面结论

设计探了三个方向，推荐 **Option C —— 无外框占用环（chrome-less gauge）**：

| 方向 | 形态 | 裁决 |
|---|---|---|
| A | 左簇第三枚药丸，沿用模型/切换器 chip 语法 | ❌ 一行已是两 chip + 三个 44pt 目标，第三枚药丸读起来与模型 chip 等权，环境信息开始喊；且百分比形态表达不了未知窗口 |
| B | 输入框接缝处 3pt 细线进度条 | ❌ 轻盈但不是点击目标、打不开 Session Info、显示不了数字与 `~84k`，退役不掉整条警示带 |
| **C** | **裸环读数，无边框无填充** | ✅ **采纳** |

Option C 的四条规则：

1. **平静态只有环**（<80%，`Tok.tx2`）——不喊，无 chrome，不会读成挨着发送键的第四个控件。
2. **靠长大而非喊叫升级**：≥80% 长出琥珀数字，≥95% 转红。阈值走既有 `contextColor()`，与 Session Info sheet 的 ContextBar 恒定同步。
3. **宽度不够先丢数字**，保留承载颜色的环——模型 chip / 切换器 chip / 停止 / 发送四者永不位移。
4. **未知分母**（非 Claude 后端）：空心环 + 原始 `~84k`，**绝不编百分比**。

**整条 amber 警示带退役**。它当初存在是因为悬浮药丸太安静、警告不了人；现在 ≥80% 由环自己承担，只剩「你要开始掉轮次了」这句在 **≥95%** 以一行细红 caption 出现（旧阈值是 90%）。一条升级通道，三档音量：环 → 琥珀数字 → 红数字 + caption。

## 落地状态（2026-07-20 已实现）

- **新增** `ui/ContextGauge.kt` —— `ContextGauge`（裸环读数）+ `ContextCriticalCaption`（≥95% 细红行）。环用 Canvas 画：15dp、stroke 2.2、12 点起画、round cap、track 走 `Tok.hair`。
- `App.kt` accessory 行：`SessionStackChip` 之后、弹性 `Spacer(weight 1f)` 之前插入 gauge —— `[+] [fable ⌃] [🗂 3] [◔ 84%] ——弹性—— [■] [发送]`。点击走 `showSessionInfo = true`（与顶栏标题同一个入口）。
- `App.kt` **删除**悬浮 `ContextStatusline` 及其 `pillHeightPx` / `onSizeChanged` 测量机制；列表底部 gutter 从「按药丸实测高度动态保留」收回常量 24dp（#81 的补丁随病因一起消失）。
- `App.kt` **删除** ≥90% 的整条 `StatusBanner(Tok.warn, …)`；`sessionDegraded` 的 danger 带保持不动（那是 API 失败，另一回事）。
- 文案：`context_high_banner` 退役 → 新增 `context_critical_caption` + `qa_context_gauge`（en / zh 双 locale）。
- 测试：新增 `desktopTest/…/ContextGaugeUiTest.kt`（8 例）钉住稿面契约——平静不印数字、≥80% 长出数字、挤窄先丢数字但环还在、未知窗口只给 `~84k` 且绝不出现 `%`、点击打开 Session Info、无用量时整个不渲染、caption 文案，外加一条**布局契约**（见下）。
- 验证：`bash scripts/check-all.sh` 全绿（protocol + daemon + relay + mobile）。

## 与稿面的三处偏离（都是有意的）

**1. 点击目标高度做到 44dp，稿子是 30dp。** 稿面 `Gauge` 是 `height:30, minWidth:30`。视觉照搬（环仍是 15dp、无边框），但可点区域撑满 accessory 行的 44dp 高——项目铁律是可点元素 ≥44pt，而一个 chrome-less 读数更不该同时是个小靶子。宽度仍按稿保持 30dp 起，再宽会吃掉弹性间隙。

**2. 「丢数字」的触发用真实剩余宽度算，不是稿子的手动 `degraded` 开关。** 稿里 `degraded` 是给 state 5 手动传的 prop。实现里 gauge 用 `BoxWithConstraints` 读自己的约束宽度来决定。

这条依赖一个已实测确认的 Compose 行为：**Row 会把前驱已占用的宽度扣给后续非 weighted 子项**（探针实测 `Row(300) { Box(100); Box(80); BoxWithConstraints }` → `maxWidth == 120.dp`）。但 Row 看不见**尾部**还没测量的动作按钮，所以必须显式传 `reserveEnd`（空闲 44dp / 轮次进行中 96dp = ■ + 间距 + 发送）——这是唯一的魔法数字来源，含义明确。

⚠️ 这个假设一旦不成立，失效方式是**静默**的：`maxWidth` 恒等于整行宽，数字永远不会被丢，稿子的 state 5 就没了。`ContextGaugeUiTest.accessoryRowShedsTheNumberOnlyWhenItActuallyRunsOut` 用真实 accessory 行形状（375pt 挤窄必丢 / 390pt 空闲必留）双分支守住它——把 gauge 单独塞进固定宽 Box 的那种测试证明不了这件事。

**3. 未知窗口在挤窄时也会丢 `~84k`，稿子会保留。** 稿面 `showNum` 对 unknown 分支不看 `degraded`，但稿子从未画过「未知窗口 + 挤窄」这一格。按稿面自己声明的原则（先于任何人让位），让它一起丢更自洽——否则它会把模型 chip 顶走。代价是这种罕见组合下只剩一个空心环，信息量为零；与已知窗口的降级地板一致。

## 未做的目验

真机（Pandaa iPhone）视觉尚未过目——环的 15dp 在真实 DPI 下是否够读、琥珀/红在 OLED 上的观感、以及 ≥95% caption 与输入框的间距，都建议装机后扫一眼。装机：`bash scripts/install-pandaa.sh`。
