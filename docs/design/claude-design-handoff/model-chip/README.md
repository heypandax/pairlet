# 切模型快捷入口（issue #157）设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FModel+Chip.html>
- **生成日期**：2026-07-16，追加模式（复用项目内 model-picker／gateway-preset 语法与 iOS 机框）
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-16-cc-pocket-切模型快捷入口设计提示词.md`

## 文件清单

| 文件 | 说明 |
|---|---|
| `model-chip.jsx` | 像素规格源码（手机两组合成＋桌面一帧） |
| `Model Chip.html` | 设计板页面壳 |

## 稿面内容

1. **手机主屏**——composer 右下 send 旁的静音 model chip（hairline 描边、mono 11px、chevron-up），点开直达现有 ModelPicker sheet（alias 行＋GATEWAY MODELS 节＋自定义 id field），选中 terracotta 勾。
2. **chip 状态条**——默认 “fable”／长网关 id 中截断（“deepseek…chat”）／生成中置灰＋sheet 内 “switch applies to the next turn” 注。
3. **桌面帧**——composer 右下同款 chip，点开锚定 popover（同行内容）；⋯ 快捷操作里的 Model 行降为同一 popover 的快捷方式。

## 落地状态

- [ ] 待实现——排在 #153 内嵌终端实现合入之后派 worktree agent（触点：App.kt composer／SessionSheets.kt 入口／桌面 ChatPane＋Popovers）。
