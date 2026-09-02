# 文件浏览双视角（变更 / 全部）设计 handoff

- 在线设计板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Files+Browser+v1.dc.html（登录 claude.ai 即看）
- 生成日期：2026-09-03，模型 Opus 5（claude.ai/design「cc-pocket Design System 2.0」项目）
- 设计 brief：`~/Desktop/Brain/.personal-vault/60_Outbox/2026-09-03-cc-pocket-文件浏览双视角设计提示词.md`

## 文件清单

| 文件 | 说明 |
|------|------|
| `Files Browser v1.dc.html` | 像素规格源（1760×1080 单板，桌面区＋手机区，px 注记齐全） |
| `support.js` | 设计板运行时支撑（本地打开 html 需要） |
| `preview.jpeg` | 生成完成时的画布预览截图 |

## 交互裁决（已定，勿回改）

一个「文件」入口、两个视角过滤（变更＝默认／全部），**不做 Codex 式平行双入口**。两视角靠 M/A/D 状态标记贯通：全部视角的树／下钻列表里，被本会话改过的文件带状态点，含改动的目录带 terracotta 子树计数；任何行点开都进同一个查看器（改过的落 Diff tab，未改的落全文、Diff 置灰）。

## 关键规格速查

- 状态色：A `#6FB58A`／M `#E0A458`／D `#E5604D`，底为同色 16% tint；目录计数徽章用 accent `#D97757` 16% tint。
- 桌面：pill 两态（`± N` terracotta h20 r5；零变更退化为 26×20 文件图标 chip，同 `>_` chip 几何，**入口永不消失**）；overlay 左栏 280px，分段控件 h24 r6 pad2；树行 h26、缩进步进 14、状态点 14×14 r7、目录徽章 h15 r4；选中行 `#1E2125` 底＋inset `#3A3F46` 描边；截断行「已显示前 2000 项，其余已折叠」。
- 手机（390pt）：⋯ 工具行改名「文件」＋尾注「N 处改动」（h48）；sheet 分段 h30 r8、眼睛 30×30 r8；全部视角＝面包屑 h32＋下钻行 h44（文件夹在前、文件在后、1px 分隔）、状态点 18×18 r9；变更视角行 h56 两行式；viewer tab 下划线 2px `#D97757`，禁用 tab `#4A4E55`。
- 行为：切视角保持选中文件与右栏；未改文件右栏＝全文、头部无 diff 统计；隐藏文件眼睛开关作用于两视角、per-workdir 持久化。

## 落地状态

- daemon/protocol 半边（ListPathEntries smart 过滤参数＋gitignore/dot 过滤）：分支 `feat/files-browser-daemon-half`（Opus worktree 施工）
- 两端 UI 半边（按本稿实现）：分支 `feat/files-browser-ui-half`（Opus worktree 施工）
- 均未合 main、未发版；合并前须 `bash scripts/check-all.sh` 全绿＋wire 兼容审查。
