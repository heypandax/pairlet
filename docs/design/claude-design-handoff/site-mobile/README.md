# site-mobile —— 官网移动端整页设计（Site 1.5）

2026-07-12 经 claude.ai/design 主项目（`93b56700-…`）生成并导出。在线板：
`https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2Fsite%2FSite+1.5+-+Mobile.html`

## 内容

- `site/Site 1.5 - Mobile.html` —— 单板六屏，全部 390px：
  - **M1** 移动导航（收起态 + 展开抽屉）+ hero（含浅色对照帧）+ moment 条
  - **M2** vignette 01「Approve from anywhere」/ 02「Take over, don't fork」的 mfeat 变体（文案在上、手机在下——与 Site 1.4 的 A/B 变体统一，四个主打位移动端全部锁定）
  - **M3** Desktop + fleet 区（窗口全宽藏侧栏、fleet 卡、mini 3-up 单列）
  - **M4** 架构纵向流（竖直渐变连接线）+ 安全卡单列 + pullquote
  - **M5** 下载区**双态**（手机访客=按钮 / 电脑访客=QR）+ 三步连接流（长命令横滚 + 渐隐提示）+ 页脚
  - **M6** features 页手机节奏（组头 + 单列卡 + usagestrip 堆叠 + boundary 卡堆叠 + workflow 手机 + CTA）
- `site/mobile-15.css` —— 移动分子层规格
- 每屏带 spacing note（与桌面的差值：section padding 44px vs 104px、卡间距 14px、桌面窗口 crop ≈300px 等），可直接抄进实现。

## 设计端标记的三个定案

1. **M1 hero 构图**：授权卡不再悬浮，改为**全宽停靠在手机 mock 下方**（更稳的移动模式）；单一陶土焦点仍是 Allow 按钮。
2. **M2 vignette 02** 需要一块尚不存在的手机屏，用既有原子新拼了一块：terminal live 行 → 下箭头 →陶土「Continue on this phone」按钮 → 成功绿「resumes in place · ⑂」。
3. **M6 features hero 文案**是推断的（"Everything it does, in your pocket."），实现时可换成线上真实文案。

## 落地状态

**未实现**（按用户要求本轮只出稿）。实现时注意：现 `site/styles.css` 移动断点是「手机图在上」，与本板 mfeat「文案在上」相反，需按本板改。

来源 brief：`~/Desktop/Brain/60_Outbox/2026-07-12-cc-pocket-官网移动端设计提示词.md`
