# site-restructure —— 官网 IA 重构 section 设计（Site 1.4）

2026-07-12 经 claude.ai/design 主项目（`93b56700-…`）生成并导出。在线板：
`https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2Fsite%2FSite+1.4+-+IA+Refresh.html`

## 内容

- `site/Site 1.4 - IA Refresh.html` —— 单板四屏：
  - **Screen A** 落地页 vignette 03「See what changed」（Files changed 手机屏：`.fr` 字母章文件行 + `.mdiff` 行号 gutter 全宽 tint diff）
  - **Screen B** 落地页 vignette 04「It finds you — and loses nothing」（`.pushb` 悬浮推送横幅 + `.sdiv` 回填分隔线；替换旧「watch it think」位）
  - **Screen C** 「More capabilities」3×3 网格（转化定序九卡 + 五枚新 1.5pt 线性图标，暗 / 浅双主题 + 390px 移动单列）
  - **Screen D** features 页三个新组：D1 用量组（3 卡 + 24 小时柱 / 30 天热力 usagestrip）、D2 共享组（4 卡 + They get / They don't 边界卡 + honest 条）、D3 workflow 手机（泳道 + 阶段条，非主打配图）、D4 双语组头「The phone triages. The desktop commands. / 手机分诊，桌面指挥。」
- `site/site-14.css` —— 分子层规格（叠加在 styles.css 之上）
- `screenshots/` —— 设计侧自查截图

## 落地状态

**已实现**（2026-07-12，同日按稿校正进 `site/index.html`、`site/features.html`、`site/styles.css`）：
A/B/C/D 四屏全部落地；`site-14.css` 的分子样式（.fr/.st/.mdiff/.pushb/.sdiv/.usagestrip/.boundary/.wfcard 族）
已并入 `site/styles.css`「Site 1.4 IA Refresh board」注释块，board 专属的评审 chrome（.frame/.place/.mob/.themeframe）未搬。

来源 brief：`~/Desktop/Brain/60_Outbox/2026-07-12-cc-pocket-官网重构section设计提示词.md`
（其模块清单经四视角 Agent 评审定稿：转化 / 竞品差异化 / 视觉可演示性 / 信息架构）。
