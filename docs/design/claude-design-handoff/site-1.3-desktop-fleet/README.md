# site 1.3 — Desktop + Fleet 官网板块设计交付

- 在线设计板：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2Fsite%2F1.3+Desktop+%2B+Fleet.html（登录即看）
- 生成时间：2026-07-05，模型 Opus 4.8 Max（Fable 周配额已满时的常规回退）

## 文件

| 文件 | 内容 |
|---|---|
| `1.3 Desktop + Fleet.html` | 评审板：Screen 1 首页「桌面版 + Fleet」展示区（Comp A/B 两种构图 + 暗/浅主题切换）、Screen 2 features 页「What 1.3 adds」fgroup（改动文件 SQL 高亮、模型选择器带自定义 id、fork before/after 图）、Screen 3 hero 代码块高亮升级 |
| `v13.css` | 新分子样式：window-chrome（红绿灯）、sidebar-zone、machine-row、badge-pill（accent/warning）、diff-stat、语法高亮四类 token 色、fork 图 |

## 落地状态

- 实现进 `site/index.html`（新 section + hero 代码块）与 `site/features.html`（新 fgroup），样式并入 `site/styles.css`——见本目录同日的实现 commit。
- 构图采用 Comp A（窗口居左 / fleet 卡浮右），与 hero 的 float-perm 呼应。
