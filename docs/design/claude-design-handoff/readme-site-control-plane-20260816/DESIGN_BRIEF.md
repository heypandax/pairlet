# CC Pocket README 与官网 2.0：跨设备 Agent 控制面

## 任务与首要问题

重做 CC Pocket 的公开第一印象，让第一次访问官网或 GitHub 的人能在 10 秒内回答：

1. 这是什么：本地编码 Agent 的跨设备控制面，不是另一种云端 Agent，也不只是 Claude/Codex 遥控器。
2. 它解决什么：离开电脑后仍能查看进展、处理授权、继续会话和检查结果。
3. 它是否支持我的 Agent 和设备：即将发布的 v1.8.0 包含六个后端，但能力并不完全等价；客户端与 daemon 的平台分发也不同。
4. 为什么可信：代码和 Agent 留在用户电脑；端到端加密、零知识 relay、无 CC Pocket 账号、可自托管；权限与协作限制必须公开。
5. 如何开始：安装 App，在运行任一受支持 CLI 的电脑安装 daemon，配对。

最终产物既包括官网可见信息架构，也要给 README 的首屏和内容顺序提供明确落点。设计不能替代事实矩阵，也不能把未发布能力画成已交付。

## 产品、技术栈与现有界面

- 仓库：`heypandax/cc-pocket`。
- 当前线上正式 Release：`v1.7.7`，发布日期 2026-08-13。
- 本次 README／官网面向即将发布的 `v1.8.0`；代码事实基线为当前 `main` 的 `6162816a`（`v1.7.7-61-g6162816a`）。用户已明确要求把这批待发布代码作为 v1.8.0 能力公开，不能继续停留在 v1.7.7 口径；但未生成的 release asset 仍不得提前伪造为已验证存在。
- 官网：静态 HTML/CSS/JS，入口 `site/index.html`，能力页 `site/features.html`，设计 token 与全部响应式样式在 `site/styles.css`，交互在 `site/app.js`。
- README：`README.md` 与 `README.zh-CN.md` 独立维护。
- 当前官网基线：深色优先、可切浅色；底色 `#0E0F11`、surface `#16181B`、raised `#1E2125`、陶土 accent `#D97757`；Inter＋JetBrains Mono；hairline、克制圆角、稀疏点阵／径向 glow、真实终端感。
- 当前页面已有成熟品牌语言，必须保留。不要新建品牌皮肤、第二套导航、渐变彩虹、玻璃拟态、夸张 3D 或通用 SaaS 卡片墙。
- 现有首页约 10,948px（1440 桌面）／15,111px（390 移动），内容完整但过长；Hero 与 SEO 仍只说 Claude Code＋Codex，产品 UI 大量由 HTML/CSS 手绘，公开事实已漂移。
- 现役设计主项目：`https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d`（cc-pocket Design System 2.0）。复用这个项目及既有 CC Pocket Website、Site 1.4、Site 1.5 的设计语言和上下文，在项目内新增版本化文件，不重开无关项目。旧项目 `93b56700-6ed2-46c9-bf81-3fd0b1a6340b` 已弃用，不再作为交付目标。

## 用户与任务

### 首次评估者

- 判断 CC Pocket 是否支持自己使用的 CLI、电脑和手机。
- 看懂它与官方 Remote Control、普通远程桌面、云端 Agent 的差别。
- 在安装前理解真实权限和隐私边界。

### 准备安装的用户

- 找到自己的设备下载路径。
- 明白 App、daemon、relay 三者关系。
- 在三步内完成首次配对，而不是先读完整运维手册。

### 开源贡献者／安全审阅者

- 快速看到模块、协议、安全模型、源码构建与贡献入口。
- 能区分 v1.8.0 待发布基线、Preview、尚未进入该基线的后续内容和归档实验。

## 已核对的公开事实

### v1.8.0 待发布基线（current `main` @ `6162816a`）

v1.8.0 将发布六个 Agent 后端：

| Agent | 状态 | 基础会话／流式／恢复 | 可执法审批与模式 | Changed files／diff | 用量聚合 |
|---|---|---:|---:|---:|---:|
| Claude Code | Supported | 是 | 是 | 是 | 是 |
| OpenAI Codex | Supported | 是 | 是 | 是 | 是 |
| OpenCode | Supported | 是 | 否；恒为 Full access，必须警示 | 否 | 是 |
| Kimi Code | Preview | 是 | 是 | 否 | 是（v1.8.0 新增） |
| ZCode | Supported | 是 | 是 | 否 | 是（v1.8.0 新增） |
| DeepSeek Harness | Supported · narrow v1 | 是：发现／回放／新建／恢复／文本收发／实时流式 | 有限：仅启动时 sandbox 默认值；远程审批未桥接并 fail-closed | 否 | 否 |

矩阵必须用清楚的“是／限制／否”或等价可访问表达，不能只靠颜色。不要把“基础会话接入”写成“所有高级能力完全一致”。

### 平台与分发

| 产品表面 | 正式分发 |
|---|---|
| Phone／tablet App | iOS／iPadOS App Store、TestFlight；Android APK |
| Desktop App | macOS Apple Silicon／Intel DMG；Windows x86_64 MSI |
| Daemon | macOS Apple Silicon／Intel；Linux x86_64／arm64；Windows x86_64 |
| HarmonyOS | Preview／受限能力；v1.8.0 release asset 是否存在必须以实际发版产物验证，不提前伪造 |
| Linux Desktop App | 没有正式安装包；只可从源码构建。不能写成已有正式桌面分发 |
| Relay | 托管 relay 默认可用，也可自托管 |

### v1.8.0 相比 v1.7.7 的主要公开增量

- DeepSeek Harness（公开名可写 DeepSeek）第六后端：会话发现／回放／新建／恢复／文本收发／实时流式；必须同时公开其无远程审批、无 Changes／diff、无 usage、无模型切换的窄 v1 边界。
- 按 Agent 筛选项目／会话／用量；Kimi／ZCode 用量补齐。
- 手机 New Task FAB＋弹窗 composer，桌面空态可直接发提示词创建新会话。
- 会话回放可继续显示用户提示词中的图片。
- managed daemon 默认自动升级（可退出），桌面端检查更新失败会明确落失败态。

以上是本次 v1.8.0 口径允许进入正文、能力页与结构化事实的增量；仍只宣传用户可验证结果，不把内部修复、代码量或未完成扩展包装成卖点。

### 明确不宣传

- **Session Handoff 与 Folder Share**：这两项当前有产品问题，官网、README、SEO、结构化数据、截图和公开能力矩阵都不得出现，也不能用“Collaborate／协作”间接包装。它们只可在本内部 brief 的排除项中出现。
- 归档分支中的 Taskboard v2 与多 Agent 协作组。
- 把“多后端”说成“已支持多 Agent 自动编排”。
- 把测试、设计稿或脚本化演示数据说成真实用户数据或线上运行证明。
- 把内部 relay 加固、竞态修复或代码行数当成用户卖点。

## 统一定位与文案基线

### 产品主句

英文方向：

> Your coding agents stay on your computer. You stay in control from anywhere.

中文方向：

> Agent 留在你的电脑，人从任何设备掌控全程。

辅助说明必须覆盖：从手机或另一台电脑控制本地 CLI；查看进展、处理授权、继续会话、检查结果；端到端加密。

允许优化语序，但不能回退到只写 Claude Code＋Codex，也不能制造“无人值守自动团队”承诺。

### 四个核心任务

1. **Watch／查看进展**：跨设备查看流式输出、工具事件和任务状态。
2. **Approve／处理授权**：Agent 卡在敏感操作时，从手机批准或拒绝；超时安全拒绝。
3. **Continue／继续会话**：原地接管，不无故 fork；断线后补齐遗漏内容。
4. **Inspect／检查结果**：Changed files、line diff、文件预览、上下文与用量；注明能力按 Agent 不同。

## 真实素材与自动化约束

产品截图和真实流程不允许用 HTML/CSS 重画 App，也不允许在 Claude Design 中发明 App 状态。实现必须复用仓库现有自动化：

- `mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/showcase/ShowcaseRender.kt`：真实 Compose UI、脚本化协议帧、固定时间轴、离屏确定性渲染；素材代码只在 `desktopTest`，不进入产品。
- `mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/desktop/DesktopScreenshotTest.kt`：真实桌面 Compose UI 的确定性离屏截图。
- `marketing/video/`：把真实 UI 帧编码成视频／海报的现有 Playwright＋ffmpeg 管线。
- `marketing/preview/`：需要完整模拟器交互时的 App Store Preview 管线；本版官网优先使用可无头重现的 ShowcaseRender，除非它不能表达必要状态。

素材在公开页面必须如实称为“由真实产品 UI 自动生成的演示流程”或保持无额外证明性文案；不得称为 live customer session。脚本化数据不得包含用户真实路径、密钥、账号或聊天内容。

设计需要为以下生产资产预留稳定构图，不在原型里伪造最终像素：

1. 一个 8–12 秒无声控制闭环视频：查看运行中会话 → Agent 工作 → 授权 → 继续 → 检查 diff。英文／中文各一份，语言切换时切换源。
2. reduced-motion 与视频加载失败时的静态 poster。
3. 一张桌面控制台真实截图。
4. README 顶部复用的真实 UI overview 静态图；不要让 README 依赖自动播放视频。

原型可以复用设计项目中已有真实 CC Pocket 截图作为比例和构图参考，但必须把对应容器标明为自动生成资产落点；实现阶段以重新生成的当前 UI 素材替换。

## 官网信息架构要求

目标不是简单重写旧 section，而是把阅读顺序收敛为以下八段。桌面和移动端都保持同一语义顺序：

### 1. Hero：是什么＋唯一主行动

- Eyebrow 可表达 `OPEN SOURCE · LOCAL FIRST · E2E ENCRYPTED`，不放版本号堆叠。
- 主句使用统一定位。
- 副文案提到六个 v1.8.0 Agent，但不暗示能力一致。
- 主 CTA：`Get the app／获取 App`，锚到下载。
- 次 CTA：`See the control loop／看真实流程`，锚到下一段；GitHub 可放导航或次级文字入口，不与两个主 CTA 并列抢焦点。
- Hero 视觉必须是自动生成真实 UI 的视频／poster，而不是现有 CSS 手绘手机与悬浮审批卡。
- 在视觉下方或副文案附近展示六个 Agent 的克制身份条；Kimi 紧贴 `Preview`，DeepSeek 可用短名，细节处写明 DeepSeek Harness。

### 2. Control loop：一个完整流程

- 标题表达“离开电脑，但不离开控制回路”。
- 左／上方是 4–5 个顺序节点：Watch → Approve → Continue → Inspect；当前节点可由视频时间或点击切换高亮，但页面没有 JavaScript 时仍完整可读。
- 右／下方是自动生成的真实 UI 视频。
- 允许用户暂停；视频 muted、playsinline；reduced motion 不自动播放并显示 poster。
- 该段代替旧的 moment＋四个重复手机 vignette 的冗长组合。

### 3. Four jobs：四个任务，不是功能墙

- Watch、Approve、Continue、Inspect 四项，每项一句结果＋最多三条事实。
- 只在最能证明的 1–2 项使用真实静态截图；其余使用克制线性图标／架构图，不重复四台长手机。
- 任何 Agent 特定能力都链接或下钻到能力矩阵。

### 4. Agent support：六后端＋能力矩阵

- 先用六个 Agent identity card／chip 表明 v1.8.0 支持集合；Kimi Preview，DeepSeek narrow v1。
- 矩阵只包含本次允许公开推广的四类能力：Core session、Approval／mode、Changes／diff、Usage。
- 移动端不得变成横向不可读大表；可以每个 Agent 一张可展开卡，但默认必须看出限制，不能把“否”藏到二级页面。
- OpenCode 的 Full access 警告必须在其行／卡旁，不只放脚注。
- 矩阵下面放一句：功能边界跟随正式 Release；完整说明链接到 Features／Manual。

### 5. Product surfaces：Phone＋Desktop＋Daemon＋Relay

- 用一张简洁选择地图解释四个组成，而不是重复 feature card。
- 明确 Desktop App 与 daemon 是不同分发物。
- 可使用自动生成桌面截图，保留当前窗口 chrome 与两栏密度。
- Fleet／多电脑是这一段的结果，不要把它写成自动编排团队。

### 6. Trust：为什么可以让它碰本机 Agent

- 用本地执行 → E2E → 零知识 relay → 到期／撤销的因果链。
- 必须明确：Agent 仍按本机用户权限执行；OpenCode 无可执法交互审批。
- 主要行动：Read security model；自托管作为次级入口。
- 不使用“military grade”“绝对安全”等空泛表述。

### 7. Get started：三步＋平台路径

1. 安装手机／平板 App。
2. 在装有任一受支持 CLI 的电脑安装 daemon；不能写“必须安装 Claude CLI”。
3. 扫码或输入 6 位码配对。

- 继续复用现有 OS 检测、Install／Update 切换、手机直达商店／桌面 QR 行为。
- 下载矩阵必须分开 Phone／tablet、Desktop App、Daemon、HarmonyOS Preview；Linux 只在 daemon 正式分发，Desktop App 写 build from source。

### 8. Open source／Docs／Community

- GitHub、User Manual、Security、Contributing、Smart Support。
- 更完整的安装、运维与安全说明进入 Features／Docs，不再追加一整组长 feature grid。

## README 信息架构要求

README 不是官网全文副本。目标顺序：

1. Logo／CI／Release／License＋独立语言入口。
2. 与官网一致的一句话定位，说明六个 v1.8.0 Agent 和事实边界。
3. 真实 UI overview 静态图。
4. `Get the app`、`Latest release`、`User manual` 三个入口。
5. 四步 control loop。
6. 同源 Agent 能力矩阵。
7. 同源平台／分发矩阵。
8. 三步 Quickstart；平台细节折叠到短节或 Docs，不让 Windows 等单个平台占据首屏后大量篇幅。
9. 架构图与安全模型。
10. Build、Docs、Contributing、License。

英文根 README 与中文 `README.zh-CN.md` 结构一一对应，不逐段双语混排。第三方网关降为独立短节，不能插在核心能力与安装之间争抢主线。

## 字段到文案映射

| 内部／事实概念 | 对外名称 | 禁止误写 |
|---|---|---|
| AgentKind | Agent backend／Agent 后端 | model provider、自动团队 |
| daemon | Local daemon／本机 daemon | cloud runner |
| relay | Zero-knowledge relay／零知识中继 | VPN、存储服务 |
| PermissionMode | Execution mode／执行模式 | 所有 Agent 都有相同审批能力 |
| SessionLive／resume | Continue the same session／继续同一会话 | 一律 fork、同步整个终端 |
| SessionFilesService | Changes & diff／改动与 diff | 所有 Agent 都支持文件改动 |
| scripted showcase data | Reproducible product demo／可复现产品演示 | live customer data、线上运行证明 |

## 主路径与交互验收

原型和最终实现至少支持：

1. 首次进入桌面首页，10 秒内看到定位、真实 UI、主 CTA 和六 Agent 集合。
2. 点击 `See the control loop` 滚到流程；点击各步骤会切换高亮／定位视频进度，或在无脚本时仍按顺序读完。
3. 暂停／播放视频；启用 reduced motion 后刷新，视频不自动播放且 poster 可见。
4. 切换中文／英文，Hero、流程、矩阵、视频源／poster 和下载文案一起切换；不混入另一语言。
5. 切换深色／浅色，真实 App 截图保持自身暗色 UI，不被站点主题滤镜改色。
6. 在 Agent 矩阵中能立即看出 OpenCode 无审批、Kimi Preview、DeepSeek 的窄 v1／fail-closed 边界，以及 Kimi／ZCode 在 v1.8.0 已有 Usage。
7. 在下载区选择 macOS／Linux／Windows，以及 Install／Update；Linux Desktop 不出现正式下载按钮。
8. 390px 移动端打开菜单、关闭菜单，完成 Control loop 阅读和平台选择；没有横向页面滚动。
9. 键盘 Tab 可依次访问导航、CTA、流程控制、矩阵展开、语言／主题、下载控制；Escape 关闭移动菜单或任何展开层。

## 状态清单

- 视频：loading、ready、paused、failed、reduced-motion poster。
- GitHub star：API 成功才展示；失败／限流保持隐藏，不显示假数。
- Agent 矩阵：完整静态事实，不依赖 API；Preview／限制不只靠颜色。
- 下载：OS 自动检测、手动切换、GitHub API 失败时使用静态可用 APK fallback、移动端直接链接、桌面 QR 库失败时回退链接。
- 语言／主题：localStorage 不可用时仍正常；默认英文＋深色。
- 页面无 JavaScript：核心定位、矩阵、下载链接和安全说明仍可读；视频显示 poster 和 controls。

## 响应式要求

- 重点验收：1440px 桌面、1024px 小桌面／平板横向、390px 手机。
- 桌面最大内容宽延续约 1120px；Hero 文案与真实 UI 视觉平衡，不把手机放大成唯一主体。
- 移动端 section padding 约 44–56px，核心流程文案在素材上方；下载路径不依赖横向表格。
- Agent 矩阵桌面可用表格，移动端转为可读卡片／折叠，但限制默认可见。
- 真实桌面截图可以裁掉次要侧边，但不能改造产品 UI；保留足够上下文让人知道是桌面控制台。

## 可访问性

- 所有视频、poster、截图有描述结果的 alt，不把 UI 内小字全部塞进 alt。
- 视频有 controls；无声音，不需要字幕，但流程节点提供同等文字信息。
- 状态用文字／符号＋颜色共同表达；矩阵表头、行头和 caption 语义正确。
- 键盘焦点清晰；`Escape` 关闭菜单／浮层；按钮使用 button，不用不可聚焦 div。
- `prefers-reduced-motion` 禁止自动播放、滚动 reveal 和非必要过渡。
- 对比度延续当前 token；陶土不作为小字号低对比正文。

## 不变量

- 不修改 App、daemon、relay 的权限或数据契约来迎合文案。
- 不把 v1.8.0 待发布能力写成 v1.7.7 已有；本次公开口径明确以 `6162816a` 对应的 v1.8.0 候选基线为准。
- 不在任何公众页面、README、SEO、结构化数据或营销素材中出现本 brief「明确不宣传」列出的两项问题功能或相应协作卖点。
- 不把 OpenCode 画成有 Ask／Plan 等可执法审批阶梯。
- 不把 Linux 源码可编译写成正式 Desktop App 包。
- 不伪造 GitHub star、用户数、性能、安全认证或客户 logo。
- 不覆盖或依赖用户现有未跟踪目录 `docs/design/logo-concepts/`。

## 设计交付要求

在现有 Claude Design 项目内新增一个版本化、自包含原型，建议文件名 `Site 2.0 - Agent Control Plane v1.dc.html`，至少包含：

1. 1440px 首页全流程／关键折叠。
2. 390px 首页关键折叠与菜单展开态。
3. Agent 能力矩阵的桌面与移动形态。
4. README 首屏与前五段的轻量排版参考，不需要模拟完整 GitHub chrome。
5. 视频 ready／failed／reduced-motion poster 三种状态。

原型需支持语言、主题、流程节点、视频状态和移动菜单的交互自测。归档 README 必须记录本 brief SHA、Claude Opus 5 正式回执、Claude Design 项目 URL、导出文件 SHA、信息架构与已验证交互。实现按现有 HTML/CSS/JS 技术栈落地，不逐行照搬原型代码。

## 成功标准

- 首屏不再是“Claude Code 与 Codex 遥控器”，而是清楚定义跨 Agent、跨设备控制面。
- 真实 UI 资产取代关键位置的手绘产品 UI，且可由仓库命令重现。
- README、首页、Features、Manual、`llms.txt`、SEO／JSON-LD 对 v1.8.0、六 Agent 和平台状态给出一致答案。
- Agent 与平台差异可被首次访问者在一个屏幕内识别。
- 首页明显变短，仍保留安全、下载和开源路径。
- 现有 `python3 scripts/check-site-seo.py` 通过，并新增或扩展事实一致性检查，防止旧版本、旧 Agent 集合和错误平台声明回流。
