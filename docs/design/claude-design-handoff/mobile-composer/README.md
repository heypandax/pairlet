# 手机 composer 双层重排（issue #157 后续）设计交付

- **在线设计板**：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FMobile+Composer.html>
- **生成日期**：2026-07-19，追加模式（复用项目内 Model Chip 稿的 pill 像素规格与 iOS 机框），Opus 4.8 生成
- **brief 源**：`~/Desktop/Brain/60_Outbox/2026-07-19-cc-pocket-composer模型入口重排设计提示词.md`
- **背景**：#157 的模型 chip 与输入框同行，375pt 小屏上输入框被挤到 ~110–160dp（streaming 时更窄）；本稿把 composer 拆成双层——输入框整行全宽，＋附件／模型 chip／动作按钮沉到下方 accessory 行（Claude 官方 App 同构）。

## 文件清单

| 文件 | 说明 |
|---|---|
| `mobile-composer.jsx` | 像素规格源码（390pt 机框 × 四态） |
| `Mobile Composer.html` | 设计板页面壳 |

## 稿面内容（四态）

1. **Idle · empty**——placeholder 全宽，chip “fable”，右侧描边麦克风。
2. **Text · 2 行**——字段向上自撑，terracotta 实心发送。
3. **Generating · queued**——chip 置灰 42%（切换下轮生效），■ 停止与发送并排。
4. **Gateway 长 id**——chip 中截断 “deepseek…chat”（cap 放宽至 ~120）。

## 落地状态（2026-07-19 已实现）

- `App.kt` composer else 分支：单行五件套 → `Column{ ComposerField 全宽 ＋ accessory Row }`（间距 top 10／字段水平 16／行 top 6·start 8·end 10·minHeight 44，左簇 gap 6）。
- `VoiceComposer.kt` ComposerField：radius 12→14、字号 14.5→15（对齐稿面）。
- `SessionSheets.kt` ModelChip：新增 `labelMax` 参数——手机 accessory 行传 120dp，桌面 ChatPane 走默认 82dp 维持单行现状（brief 边界：desktop untouched）。
- 底色体系**未跟稿反转**（稿：外壳 base＋字段 surface；现状：外壳 surface＋字段 base）——稿是孤立帧，未背 strips／附件托盘等邻居，对比关系等价，落地以 App 现状体系为准。
- 验证：`scripts/check-all.sh` 全绿。注意 cap 全局 120 会让桌面 `DesktopUiTest.selectingCodex…` 挂（inline 审批卡在 LazyColumn tail item，对像素布局脆弱），故 cap 收敛为按端参数。
