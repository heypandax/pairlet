# 占用环视觉节奏修正设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FGauge+Vertical+Rhythm.html>
- **生成日期**：2026-07-20，追加模式（同一对话延续 `context-statusline/` 与 `per-model-context-window/`，Opus 4.8 Medium）
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-20-cc-pocket-占用环视觉节奏设计提示词.md`
- **背景**：#170 的 Option C（无外框裸环）落地后，占用环成了 accessory 行里唯一没有外框的元素。两枚 30dp 药丸 + 三个 44dp 圆钮之间，一枚 15dp 裸环视觉高度只有邻居一半；平静态（<80%，绝大多数时间）读成「掉进控件带的散点」。反而 ≥80% 长出琥珀数字后才正常——因为数字给了它质量。

## 文件清单

| 文件 | 说明 |
|---|---|
| `gauge-rhythm.jsx` | **像素规格源**：四方向对比、3× 测量放大图、D1 五态、1:1 spec 表 |
| `Gauge Vertical Rhythm.html` | 设计板页面壳 |

## 稿面结论

设计探了四个方向，推荐 **D1 —— 有填充无边框（fill without border）**：

| 方向 | 形态 | 裁决 |
|---|---|---|
| **D1** | **30dp 胶囊，`raised` 填充，无 hairline** | ✅ **采纳** |
| D2 | 保持无 chrome，环养到 20dp + 常驻安静数字 | ❌ 常驻数字**抢走了 ≥80% 的升级信号**；且没有形状，在两枚利落药丸之间仍然是浮的 |
| D3 | 搬出药丸车道，挪到弹性间隙右侧贴 stop/send | ❌ 在 44dp 圆钮里确实自洽，但把环境信息停在了全行最响的簇旁、离发送一个拇指——安静的东西不该待在那 |
| D4 | 折进模型 chip 当前导环 | ❌ 静息态确实少一个对象，但耦合了两种点击意图，且 **375pt 最坏情况下长 gateway id 没有余地再驮一枚环** |

**D1 的核心论证**（也是它区别于 #170 否决过的 Option A 的地方）：这套系统里 chip = **raised 填充 + hairline 描边**。去掉描边就降了一档——填充胶囊读成「被动的槽位」而不是「可操作的控件」，同时又实打实占满 30dp 控制带。所以它修好了节奏，却没有把环境读数抬成第四个控件。

稿子专门给了一张 **3× 测量放大图**：胶囊上下沿与两枚药丸落在同一对虚线上，把「节奏对齐」这件事画出来而不是嘴上声称。

## 落地状态（2026-07-20 已实现）

`ui/ContextGauge.kt` 单文件改动，严格按 spec 表 1:1：

| 项 | 值 |
|---|---|
| 胶囊高度 | 30dp（与模型 / 切换器药丸同带） |
| 胶囊填充 | `Tok.raised` —— 与药丸完全一致 |
| 胶囊描边 | **无**（整个层级动作就在这一条） |
| 平静 / 丢数字后宽度 | 固定 30dp → 30×30，环居中 |
| 带数字时 padding | start 9 / end 10，环与数字间距 5 |
| 环尺寸 / 描边 | 13dp / 2.2（原 15dp） |
| 可点区 | 仍是 44dp 触摸槽，胶囊 30dp 居中其中 |

`NUMBER_ROOM` 54dp → **64dp**（环 13 + 间距 5 + 四个 mono-11 字形 ~26 + 胶囊 19 的左右 padding）。原来的 3dp 水平 padding 撤掉——胶囊自带 padding，留着会让与切换器 chip 的间距变成 9。

**没有动的**：阈值、三档升级通道、未知窗口 `~84k` 兜底、先丢数字的降级顺序、点击开 Session Info、以及输入框 / 药丸 / 动作按钮的每一个像素。

## 验证

- `ContextGaugeUiTest` 8 例全绿（含 `accessoryRowShedsTheNumberOnlyWhenItActuallyRunsOut` 这条布局契约——NUMBER_ROOM 抬到 64dp 后两个分支仍各自成立：375pt 挤窄 maxWidth 116 − reserve 96 = 20 必丢；390pt 空闲 252 − 44 = 208 必留）。
- 离屏渲染真实 accessory 行目视对照（改前：环只有药丸一半高；改后：三个对象同带，胶囊无描边比药丸低一档）。

> ⚠️ 验证是在 `HEAD` 的**独立 worktree** 里跑的——当时主工作区有另一路 gateway-model-probe 的在途改动，`desktopMain/Popovers.kt` 处于不可编译状态（与本单无关）。本单改动只碰 `ContextGauge.kt`，不依赖那路工作。**主工作区恢复可编译后，建议补跑一次 `bash scripts/check-all.sh`。**

## 未做的目验

真机（Pandaa iPhone）未过目——`raised` 填充在 OLED 上与 `base` 的对比够不够、30dp 胶囊在真实 DPI 下会不会反而显得重，建议装机扫一眼：`bash scripts/install-pandaa.sh`。
