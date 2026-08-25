# CC Pocket README 与官网 2.0：跨设备 Agent 控制面（设计交付归档）

本目录是一次 Claude Design 迭代的完整归档：冻结 brief、云端生成并经两轮机主裁决修正过的自包含原型、按稿截图、可复现校验脚本，以及本文件的验收证据。

> **本文件是内部归档。** 下文「排除边界」一节会点名两项被移出公开口径的功能——这是记录**排除项**所必需，**它们不得以任何形式出现在原型、官网、README、SEO、结构化数据或对外素材中**。

## 当前基线：v1.8.0（六个 Agent 后端）

**机主已把公开口径从「v1.7.7／五个后端」改为「即将发布的 v1.8.0／六个后端」**，事实基线是当前 `main` 的 `6162816a`（`v1.7.7-61-g6162816a`）。

- 线上正式 Release 仍是 `v1.7.7`（2026-08-13），但本轮 README 与官网面向 v1.8.0。
- **不得伪造 v1.8.0 的发布日期或下载产物**：原型里 `1.7.7` 与 `2026-08-13` 均已清零，版本口径统一为「v1.8.0 · 即将发布 / upcoming v1.8.0」，不承诺 asset 已可取。
- 画板内部批注保留 `current main @ 6162816a` 作为事实基线出处。

## ⚠️ 排除边界（延续上一轮裁决，仍然绝对）

**Session Handoff 与 Folder Share 当前有产品问题，不对外宣传。**

- 两项及其中英文变体在公开原型中**零出现**，涵盖矩阵、移动卡片、批注、alt、board D 的 README 参考、页脚与 SEO 式文案。
- **不得用「Collaborate／协作／接力／共享／团队」等措辞做间接替代**；第四个任务保持上一轮已定的 `Watch`。
- 本轮已对**导出源码**与**渲染后 DOM（中英双语）**各做一次零命中复查，并把该断言固化进 `verify.py`。

## 交付标识

| 项 | 值 |
|---|---|
| Claude Design 项目 | `cc-pocket Design System 2.0` — https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d |
| 原型在线地址 | https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Site+2.0+-+Agent+Control+Plane+v1.dc.html |
| 可见设计模型 | **Opus 5 Max**（composer 模型条读数；三轮生成／修正与自检全程未变更） |
| 冻结 brief SHA-256 | `db017ef68ef1ce14802f18db172b30b2ab8dcd6e874d1112dac0ce447c6ebfbb`（19,501 字节；设计执行时授权版本为 `a16eca31…`，收尾仅修正「五后端」标题残留） |
| 原型文件 | `Site 2.0 - Agent Control Plane v1.dc.html`（103,996 字节） |
| 原型 SHA-256 | `5646b0ba74bfcce105da0796f35d1a11f65347d7397fb15a3e52765e645afd0e` |
| 公开基线 | **即将发布的 v1.8.0**，代码事实基线 `main @ 6162816a` |
| 校验脚本 | `verify.py` — 全绿 exit 0，任一项失败 exit 1；**本轮实测 exit 0** |
| 归档日期 | 2026-08-16 |

### 版本沿革（同一云端文件，三轮就地修改）

| 轮次 | 内容 | 原型 SHA-256 |
|---|---|---|
| v1 初稿 | 五后端／v1.7.7，含两项后被排除的功能 | `15afe6a2…511030af`（作废） |
| v2 排除修正 | 移除两项问题功能，矩阵收敛为四列，`Collaborate` → `Watch` | `daa68ad8…c8e7aa051`（作废） |
| **v3 本轮** | **v1.8.0／六后端，新增 DeepSeek 与 △ Limited 三态** | **`5646b0ba…645afd0e`（当前）** |

三轮都在**同一 Claude Design 项目、同一文件**内就地修改，项目始终 33 个 page，未新建任何文件或项目。

### 归档文件清单

| 文件 | 字节 | SHA-256 |
|---|---|---|
| `Site 2.0 - Agent Control Plane v1.dc.html` | 103,996 | `5646b0ba74bfcce105da0796f35d1a11f65347d7397fb15a3e52765e645afd0e` |
| `DESIGN_BRIEF.md` | 19,501 | `db017ef68ef1ce14802f18db172b30b2ab8dcd6e874d1112dac0ce447c6ebfbb` |
| `verify.py` | 7,377 | `37065e220f5f8217efdd5c08d472c1df99b358e0573a8729e26625b1c580a33f` |
| `board-A-desktop-1440.png` | 134,098 | `a43c71877ccc02cba7f1a7cd1609b6f4b69e848bd71c78719d6c2af5eae89648` |
| `board-B-mobile-390.png` | 264,372 | `8e3a7e0567fcc7c2424651b2e243fd1b6ce3239bbdbb9f9faf0c6000306a380e` |
| `board-C-agent-matrix.png` | 215,877 | `c98ff9c505e4653b8122ecbde1c4fdf50364a962fb509b0b9fd9de008ceeb16f` |
| `board-D-readme-reference.png` | 166,450 | `4f0bf37dd32ccdc46963c25f82f70a4e69e9b2eeb4b8d1240a8132f58f951451` |
| `board-E-video-states.png` | 176,076 | `6ca354be94cfcaba98049a263af9788cbafb92949b47bd05de9752ecef071f4a` |

五张 PNG 均为 2400 × 1400 真实像素，**无零字节文件**，且都是 v1.8.0 修正后重新生成的。

原型为单文件自包含：内联 CSS 与 JS，**零外部 `src` / `href` 资源**，无框架依赖。导出取 `&raw=1` 原始件，不含编辑器注入的 `data-om-id` / `omelette-injected` 埋点（计数 0）。

## 六个 Agent 与公开能力矩阵

公开矩阵仍**只有四列**（本次允许推广的能力），现在六行：

| Agent | Core session | Approval & mode | Changes & diff | Usage |
|---|---|---|---|---|
| Claude Code | ✓ Yes | ✓ Yes | ✓ Yes | ✓ Yes |
| OpenAI Codex | ✓ Yes | ✓ Yes | ✓ Yes | ✓ Yes |
| OpenCode | ✓ Yes | ✕ No · Always Full access | ✕ No | ✓ Yes |
| Kimi Code `Preview` | ✓ Yes | ✓ Yes | ✕ No | **✓ Yes · new in v1.8.0** |
| ZCode | ✓ Yes | ✓ Yes | ✕ No | **✓ Yes · new in v1.8.0** |
| DeepSeek Harness `narrow v1` | ✓ Yes · Discover, replay, create, resume, text send/receive, live streaming | **△ Limited · Startup sandbox default only; remote approval not bridged, fail-closed** | ✕ No | ✕ No |

- **新增第三种可访问状态 `△ Limited`／`△ 有限`**，与 `✓ Yes`／`✕ No` 并列，符号＋文字，不依赖颜色。DeepSeek 的 Approval 格用它，**没有写成 Yes，也没有伪装成与 Claude／Codex 等价**。
- **DeepSeek 身份**：公开短名 **DeepSeek**（hero 身份条），细节处全称 **DeepSeek Harness**（矩阵行头／卡片标题），边界徽章 `narrow v1`／`有限 v1`。**未标 Preview**——Preview 仍只属于 Kimi Code。
- **DeepSeek 移动卡默认暴露全部三条限制**（远程审批未桥接、无 Changes/diff、无 Usage），另有独立一行「No model switching.／不支持切换模型。」——**未为此新增第五列**。
- 移动卡限制计数：Claude Code `0/4`、OpenAI Codex `0/4`（均显示「All four supported. No limits.」正向态）、OpenCode `2/4`、**Kimi `1/4`、ZCode `1/4`**（Usage 已支持，只剩 Changes & diff 受限）、**DeepSeek `3/4`**。中文侧为「限制 0/4 …」同构。
- OpenCode 的 Full access 警示条仍紧贴其行与其卡。
- 矩阵脚注：“Capability boundaries match the upcoming v1.8.0. Full detail in Features and the User Manual.”
- Hero 身份条改为 **3×2 六格**，不再是单行挤压。

## v1.8.0 增量落点（未新增顶层任务）

按要求塞进既有 Watch／Continue／Inspect 的事实条，每项仍 ≤3 条，页面没有变成功能墙：

| 增量 | 落点 |
|---|---|
| 按 Agent 筛选项目／会话／用量 | **Watch** 事实 3 — “Filter projects, sessions and usage by agent.” |
| 从手机或桌面 App 发起新任务 | **Continue** 事实 2 — “Start a new task straight from the phone or desktop app.” |
| Kimi／ZCode 在 v1.8.0 已有用量 | **Inspect** 事实 1 — “…Kimi Code and ZCode report usage in v1.8.0.” |
| 回放中提示词图片仍可见 | **Inspect** 事实 3 — “Images in your own prompts stay visible in session replay.” |

**四个公开任务仍是 Watch、Approve、Continue、Inspect**，Control loop 四节点同名同序。

## 校验证据

### `verify.py`（本轮实测 **exit 0**）

在归档目录跑 `python3 verify.py`。脚本在下列任一项不满足时 **exit 1**：

| 断言 | 结果 |
|---|---|
| `exclusion-zero`（两项功能名＋中文变体＋`collaborat`／协作／接力／guest 全为 0） | PASS |
| `matrix-4-columns`（EN 恰为 Core session / Approval & mode / Changes & diff / Usage） | PASS |
| `matrix-6-agents`（六行且每行四格取值逐一比对） | PASS |
| `kimi-usage-yes` | PASS |
| `zcode-usage-yes` | PASS |
| `deepseek-yes-lim-no-no` | PASS |
| `limited-state-accessible`（`△ Limited`／`△ 有限` 存在且符号＋文字） | PASS |
| `jobs-watch-approve-continue-inspect` | PASS |
| `loop-watch-approve-continue-inspect` | PASS |
| `baseline-v1.8.0`（`1.7.7` = 0、`1.8.0` > 0、`2026-08-13` = 0） | PASS |
| `self-contained`（外部资源 0、编辑器埋点 0） | PASS |

关键计数：排除词合计 **0**；`1.7.7` **0** 次；`1.8.0` **17** 次；`2026-08-13` **0** 次；外部资源 **0**；`<button>` 33、`<table>` 2、`<ol>` 4。

### 渲染 DOM 复查（中英双语）

重载 present 视图后对**渲染后的 DOM**再查一遍：

| 项 | EN | 中文 |
|---|---|---|
| 排除词命中 | 0 | 0 |
| `1.7.7` 命中 | 0 | 0 |
| `1.8.0` 命中 | 11 | — |
| 伪造日期 `2026-08-13` | 0 | — |
| 四任务 / 四列 / 六 Agent 全部可见 | ✓ | ✓ |
| `△ Limited`／`△ 有限` | ✓ | ✓ |
| `narrow v1`／`有限 v1` | ✓ | ✓ |
| 「No model switching」 | ✓ | ✓ |
| 移动卡限制计数 | 0/4, 0/4, 2/4, 1/4, 1/4, 3/4 | 同 |

另有一道**导出在途闸门**：抓回 HTML 后若命中任一排除词、或仍含 `1.7.7`、或不含 `1.8.0`，直接拒绝落盘。本次未触发。

## 确认的信息架构（八段未变）

1. **Hero** — `OPEN SOURCE · LOCAL FIRST · E2E`；主句 “Your coding agents stay on your computer. You stay in control from anywhere.”；副文案改为「六个 Agent 后端…能力按后端不同」；主 CTA `Get the app ↓`、次 CTA `See the control loop →`；六 Agent 身份条（Kimi `Preview`、DeepSeek `narrow v1`）。
2. **Control loop** — “Leave the computer. Stay in the control loop.”；Watch → Approve → Continue → Inspect 四节点（真实 `<ol>`，无 JS 时按序可读）＋视频面板。
3. **Four jobs** — Watch / Approve / Continue / Inspect，每项一句结果 ＋ ≤3 条事实。
4. **Agent support** — 六身份卡 ＋ 四列六行矩阵 ＋ OpenCode 警示条 ＋ v1.8.0 边界脚注。
5. **Product surfaces** — Phone／tablet app、Desktop App、Local daemon、Zero-knowledge relay；Fleet 是结果而非「自动编排团队」。
6. **Trust** — 本地执行 → 端到端加密 → 零知识 relay → 配对可到期／可撤销；限制条含「Agent 仍以本机用户权限执行」「OpenCode 无可执法交互审批」「各后端能力不同，先看矩阵」。
7. **Get started** — 三步 ＋ 下载矩阵（Phone app／Desktop App／Local daemon／HarmonyOS Preview），macOS／Linux／Windows 与 Install／Update 切换。
8. **Open source／Docs／Community** — GitHub、User Manual、Security、Contributing、Smart Support。

五块画板：A 桌面全流程 · B 移动关键折叠＋菜单 · C 矩阵两形态（C1 1024 表格 / C2 390 卡片）· D README §1–5 排版参考 · E 视频三态。

## 已验证的交互与状态

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 桌面首屏见定位、真实 UI 落点、主 CTA、**六** Agent 集合 | 通过（board A / B 目视） |
| 2 | Control loop 节点点击切换高亮／定位 | 通过（`CUE → Approve` ↔ `CUE → Inspect`） |
| 3 | 视频三态 Ready／Failed＋poster fallback／Reduced | 通过 |
| 4 | 中英切换整体一致、不混语 | 通过；控制条自身亦全本地化 |
| 5 | 深浅主题切换 | 通过（`--bg` `#0E0F11` ↔ `#FAF9F7`） |
| 6 | 矩阵中即刻看出 OpenCode 无审批、Kimi Preview、**DeepSeek 窄 v1／fail-closed**、**Kimi／ZCode 已有 Usage** | 通过（board C） |
| 7 | 下载区 macOS／Linux／Windows；Linux Desktop 无正式下载按钮 | 通过（仅 Linux 显示 `No official package` ＋ `Build from source`） |
| 8 | 390px 无横向滚动 | 通过（**六后端重排后重跑**：中英双语各扫一次 385–400px 宽节点的 `scrollWidth > clientWidth`，**溢出节点 0**；3×2 身份条自身也不溢出） |
| 9 | 键盘可达与 Escape | 通过（33 个 `<button>`，无不可聚焦点击层，Escape 关菜单与展开卡） |
| 10 | 不伪造 GitHub star／用户数 | 通过 |
| 11 | 排除词零出现（源码＋渲染 DOM，中英双语） | 通过 |
| 12 | 四任务 / 四列 / 六行 / DeepSeek 三态 | 通过（`verify.py` exit 0） |

### 保留未变项

品牌 token、八段 IA 与顺序、无障碍（`<button>`／`aria-pressed`／`aria-expanded`／Escape／符号＋文字）、视频三态、平台与下载行为、全部「自动生成真实 UI 资产落点」占位与来源管线标注（`ShowcaseRender.kt → marketing/video/control-loop-{lang}.mp4`、`DesktopScreenshotTest.kt`）。HarmonyOS 已从写死的「v1.7.7 signed HAP」改为**不绑版本**的「签名 HAP · 能力受限 / Preview」。

### 云端修改轮次（本轮）

1. 在同一 chat 发出 v1.8.0 修正指令 → `Edited Site 2.0 - Agent Control Plane v1.dc.html`。
2. Claude Design checker 自查发现一处**语言泄漏**并自行修复：矩阵行头的状态标签改为跟随语言（`已支持` / `Supported`），Kimi 的 `Preview` 仍由 `status === tag` 抑制重复。
3. 第二轮 checker 未再报问题。

## 实现边界

- 本目录只是设计交付物。**未改动任何应用源码，未改动生产 `README.md` / `README.zh-CN.md` / `site/`**，未 commit、未 push、未部署，未触碰 `docs/design/logo-concepts/`。
- 实现按现有 `site/index.html` ＋ `site/styles.css` ＋ `site/app.js` 落地，**不逐行照搬原型代码**。
- 资产落点在实现阶段必须换成**重新生成的当前 UI 素材**；公开页只能称其为「由真实产品 UI 自动生成的演示流程」。
- 落地后跑 `python3 scripts/check-site-seo.py`，并**新增事实一致性检查**：断言 `site/`、两份 README、`llms.txt` 与结构化数据中 ①两项被排除功能名零出现、②无 `v1.7.7` 残留、③六后端集合与四列矩阵一致。
- **发版前必须复核**：v1.8.0 的实际 release asset（尤其 HarmonyOS HAP）是否真的产出；原型只写「即将发布」，没有承诺产物已存在。

## 遗留限制与待确认

1. **v1.8.0 尚未发布**。原型所有版本文案都是「即将发布 / upcoming」，不含日期、不含产物承诺。真正发版时需要回填日期与下载链接，并重跑 `verify.py`（届时 `baseline-v1.8.0` 断言可放宽）。
2. **HarmonyOS** 已改为不绑版本的通用 Preview 表述；v1.8.0 是否真有签名 HAP 必须以实际发版产物为准。
3. **原型内没有真实 `<video>` 元素**，三态是标注化的资产落点演示；`controls`／`muted`／`playsinline` 与 `prefers-reduced-motion` 的真实媒体行为只能在实现阶段验证。
4. **未模拟的状态**：`localStorage` 不可用回退、GitHub API 失败／限流、桌面 QR 库失败回退、完全禁用 JavaScript 的渲染。
5. **1024px 断点**以 board C 内 `C1 · 1024 table form` 表达，没有独立整页画板。
6. **截图为视口捕获＋zoom-to-fit**（board A 0.252、board D 0.928，其余 1.0）；跨源 iframe 的元素级截图不稳定，故用此法。board A 是缩略全景，细节以在线原型或本地 HTML 为准。
7. **账号用量**：期间页面提示 “You've used 81% of your weekly limit · resets Mon 1:00 AM”。
