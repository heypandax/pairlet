# 帮助、学习与功能发现：实现交接

> 状态：移动端帮助、官网任务首页与 App 智能客服直达均已实现；官网与 GitHub Pages 镜像已部署，桌面端与 Pandaa 已同步
> 更新：2026-07-26
> 用途：新会话开始实现前的唯一基础上下文。先读本文，再核验业务和代码，不要把完整设计稿直接当作开发清单。

## 新会话先读

1. 仓库根目录的 [`AGENTS.md`](../../../../AGENTS.md)。
2. 本文。
3. 同目录的 [`README.md`](README.md)，了解设计结论和已完成的设计验收。
4. [Claude Design 在线项目](https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=CC+Pocket+Help+Center.html)；本地归档入口为 [`CC Pocket Help Center.html`](CC%20Pocket%20Help%20Center.html)。

Claude Design 是交互与信息结构参考，不是已经确认的完整业务范围。不要按 36 个画板逐一实现，也不要直接照抄其中的数量、命令、版本号、指标或状态规则。

## 2026-07-26 官网与 App 交互实现结果

在 2026-07-25 移动端首版之上，已按 [`Help Support Direct Entry v2`](../help-support-direct-entry-v2/README.md) 完成官网与 App 交互闭环：

- 直接访问 `/support/` 显示任务优先的公开帮助首页：唯一问题输入、与 App 对齐的 5 个已核验任务、5 个使用场景、2 个紧凑故障入口，以及手册 / Issue / 隐私出口。
- 官网已移除营销 Hero、第二个智能客服 CTA、四张等权渠道卡和「把手册交给 AI」。提交问题后原地切换到共用对话工作区，并用 `history.pushState` / `popstate` 支持浏览器原生返回。
- App 原生「询问智能客服」改为一张完整可点击的行，不再嵌套重复按钮；打开固定 URL `https://pocket.ark-nexus.cc/support/?mode=chat&source=app`。
- App 只传 `mode=chat` 与 `source=app`，不附带页面、路径、仓库、会话、日志、Agent、模型、机器名、配对码或凭据；直达页不自动聚焦、不自动弹键盘、不预填或自动提交。
- 等待态如实显示「检索已核验手册，复杂问题可能约 1 分钟」；限流、繁忙、超时和不可用状态均保留原问题，提供 Retry、最贴近的已核验指南与 Issue 升级路径。
- 桌面 App 的智能客服入口同样使用直达 URL，并移除重复的「复制手册链接给 AI」动作；桌面侧栏的普通 Help 入口仍打开任务首页。
- 支持页保持中英文、深浅色、390px/桌面响应式、44pt 级触控目标、safe area 与 reduced-motion；未改智能客服后端、知识治理、daemon、relay 或 protocol wire。

实现文件：

- [`site/support/index.html`](../../../../site/support/index.html)、[`support.css`](../../../../site/support/support.css)、[`support.js`](../../../../site/support/support.js)
- [`OpenUrl.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/OpenUrl.kt)、[`HelpLearning.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/HelpLearning.kt)
- [`SettingsModal.kt`](../../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/SettingsModal.kt)
- [`test_support_experience.py`](../../../../support/tests/test_support_experience.py)、[`HelpLearningUiTest.kt`](../../../../mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/ui/HelpLearningUiTest.kt)

## 2026-07-25 移动端首版结果（历史）

已按最小切片落地：

- [`HelpLearning.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/HelpLearning.kt) 提供移动端原生「帮助与客服」页。
- 项目页、会话列表、设置页与进行中会话的快捷操作均可一步进入。
- 智能客服复用已上线的公网服务，不在 App 内重复实现聊天后端。
- 首批 5 个任务来自真实代码与公开手册：查看改动、接管终端会话、处理审批、预约提示词、选择 Agent/模型。
- 只有「查看改动」在会话上下文提供真实直达；项目页、会话列表和设置页不伪装具有会话级目标。
- 新增公开手册文章 `review-changed-files`，并重新生成中英文页面、AI 索引、全文索引和 sitemap。
- 遥测只增加固定枚举级入口/任务/动作事件，不包含问题文本、路径、会话内容或学习状态。

本轮明确未实现：跨页 coach、教学状态机、学习进度/徽章、行为触发提示、上下文上传、App 内开放式聊天、桌面 440px 完整新面板。桌面端继续使用已上线的智能客服和常用指南入口。

## 已确认的目标

优先解决：

- 用户不会使用 CC Pocket，不知道如何完成一个具体任务。
- 用户不知道产品已有的能力，因而无法形成使用预期。
- 首次安装用户需要从安装、配对走到第一次有价值的使用结果。

不是本轮主目标：

- 把运行时错误、离线、连接异常等故障处理统一塞进帮助中心。
- 建造一个替用户跨页面操作、观察点击并推进步骤的通用教练。
- 仅为了“显得智能”而先上开放式 AI 客服。

运行时失败仍应在发生位置提供紧凑恢复出口；公开手册里的故障排查内容可以保留，但不应主导帮助首页和学习旅程。

## 已冻结的交互决策

- 已砍掉通用「带我操作」和跨页面 coach。
- 教学以静态书面步骤、静态位置提示和一次性直达动作为主。
- 直达动作只能打开产品里真实存在的页面或面板；无法稳定直达时只给书面步骤。
- 不维护“当前教学到了第几步、是否点对控件、退出后如何恢复”的教学状态机。
- 功能发现优先嵌入已有信息结构，不使用依赖控件坐标的引导浮层。
- 用户主动选择「我会用了 / 还没明白」可以作为轻反馈，但首版不因此自动引入长期学习档案。

## 设计交接时的产品事实（历史）

这些是设计结束时从代码核验到的事实；实现会话仍应检查最新代码。

### 实现前帮助入口仍以外链为主

- [`OpenUrl.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/OpenUrl.kt) 定义了公开手册、故障排查和 Support URL。
- 移动端项目页、会话页的帮助按钮仍打开 Support；设置页有 Support、Manual、Troubleshooting 三个外链入口，见 [`App.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/App.kt) 与 [`Settings.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/Settings.kt)。
- 桌面端已有简单 Help 设置面板，但内容仍是 Support、Manual、热门文章和复制给 AI 的提示，见 [`SettingsModal.kt`](../../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/SettingsModal.kt)。侧边栏帮助仍打开 Support，见 [`Sidebar.kt`](../../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/Sidebar.kt)。
- 因此，原生帮助中心、学习状态和“智能客服”都不是现成能力。

### 首次安装流程已经存在

- [`PairingScreen.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/PairingScreen.kt) 已支持二维码、6 位码、配对链接、局域网高级选项和 Demo。
- [`OnboardingScreen.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/OnboardingScreen.kt) 已提供按系统区分的安装与配对步骤。
- 当前 App 与公开手册的 macOS/Linux 安装命令是 `curl ... install.sh | bash`；设计稿中曾使用 Homebrew cask。实现前必须确定发行策略和唯一可信来源，不能直接以设计稿覆盖现有命令。
- 没有证据表明首版需要重做完整 onboarding。应先分析用户实际在哪一步流失，再决定只改文案、补入口，还是调整流程。

### 可直达能力并不对称

- “打开改动”在移动端和桌面端都有真实入口及数据能力，可作为低风险直达动作，见移动端 [`App.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/App.kt) 和桌面端 [`DesktopModel.kt`](../../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/DesktopModel.kt)。
- 定时发送在移动端已有完整创建流程，见 [`ScheduleUi.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/ScheduleUi.kt) 与 [`PocketRepository.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt)。桌面模型中的 schedule 暂有空实现，不能默认桌面端也可直达。
- 每个 `DirectActionLink` 都要按平台验证目标和返回路径，不能只验证视觉状态。

### 内容基础已经存在

- [`manual-content.json`](../../../../site/manual/manual-content.json) 是公开手册的结构化内容源；实现后为 7 个分类、11 篇任务文章，并带总体验证日期。
- [`ai-index.json`](../../../../site/manual/ai-index.json) 已生成中英文标题、别名、摘要、全文和稳定 URL，可用于本地搜索、任务匹配或交给 AI；首版没有必要再建一套重复内容库。
- 当前内容同时包含学习与故障排查。帮助首页只选出 5 个学习任务，没有把全部 11 篇等权展示。

### 学习业务与数据尚未存在

- [`Telemetry.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/telemetry/Telemetry.kt) 没有帮助、学会、功能发现或 7 日复用事件。
- `SecureStore` 可以存本地偏好，但当前没有“已学会 / 已体验 / 学习进度”的领域模型。
- 因此，“已体验 5/18”、跨设备进度、7 日复用率、提示抑制等都不是纯 UI 工作，需要先定义业务口径、隐私边界和数据生命周期。

## 必要性审查

### 首个实现切片建议保留

1. **一个清晰入口**：先把最常用平台的帮助入口收敛到轻量原生页面；另一个平台可在验证后跟进，不强求首批完全同构。
2. **按任务学习**：从真实用户需求中选 3–5 个高价值任务，不使用设计稿里的固定“18 项”能力全集。
3. **静态任务指南**：价值说明、2–4 个书面步骤、位置提示、相关公开手册链接。
4. **少量真实直达动作**：只接已经存在且可稳定返回的目的地，例如“打开改动”；其他任务先保留书面步骤。
5. **渐进发现的最小形态**：指南末尾或帮助首页放少量“你还可以”关联能力，不做进度条、徽标或行为引擎。
6. **公开手册兜底**：复用现有结构化内容与稳定 URL，继续允许用户把准确文章交给 AI。
7. **基本可观测性**：只采集能回答首版决策的最小事件，并先确认现有遥测政策；不要为了指标图一次性埋完整学习漏斗。

首批任务名单不是设计结论。若缺少用户反馈、Support 记录或使用数据，可以把“安装配对、查看改动、定时发送、选择 Agent/模型”作为待验证假设，而不是直接写死。

### 实现前必须深入分析

- 主要目标用户是谁；新手与已有用户各自最常见的 3–5 个学习任务是什么。
- “第一次成功”究竟是完成配对、打开项目、发送第一条提示词，还是获得一次 Agent 结果。
- 手册内容由谁维护、何时算已核验、App 内内容与公开页面如何避免双份漂移。
- 输入框是关键词搜索、任务匹配、检索式问答，还是开放式 AI 客服；每一种的失败兜底和评估标准是什么。
- 如果引入 AI，模型、服务端、成本、延迟、引用来源、不可用回退和错误答案责任如何处理。
- 是否需要上传当前页面、Agent、可见控件等上下文；若需要，逐字段明确来源、用户可见性、传输和留存策略。
- 学会/已体验状态是即时反馈、本机状态、账号状态还是跨设备状态；删除、重装和版本升级时如何处理。
- 每个平台真实支持哪些直达动作，返回后能否保留原任务上下文。
- 安装命令和版本文案的唯一可信来源是什么。
- 公共 Web、移动端、桌面端是否必须同一期交付；没有证据时不要同时铺开三套完整界面。

### 首版延后或删除

- 固定“18 项能力”和“已体验 5/18”进度。
- 自动判断用户是否学会，以及跨设备学习档案。
- “忽略两次后永久抑制”等行为触发与提示频控引擎。
- What’s New 摘要、红点、未读状态和新功能生命周期。
- 当前页面、平台、Agent、可见控件的上下文共享预览。
- 新建一套公开 Web 帮助站或重复的内容后台。
- 桌面 440px 完整帮助面板与所有深浅色/异常变体同时上线。
- 首次成功 checklist，除非先定义成功口径并证明现有 onboarding 的缺口。
- 开放式智能客服；在任务搜索和内容质量尚未验证前，它不是解决“不会用、不知道功能”的必要前提。
- 7 日复用等长期指标作为首版验收门槛。

## 建议的首版验收

- 用户从现有帮助入口最多一次跳转即可看到按任务组织的学习内容。
- 至少 3 个经业务核验的任务能给出准确、可维护的静态步骤。
- 每个直达动作在目标平台都有真实目的地、可返回，并有测试或人工验证记录。
- 内容来源、核验日期和外部手册兜底清楚可见。
- 不出现通用「带我操作」、虚假的进度、未经确认的能力数量或错误安装命令。
- 无新增 AI、上下文上传或长期学习状态，除非相应业务和隐私问题已经单独决策。

## 新会话启动语

可以直接让新会话执行：

> 请先读取仓库 `AGENTS.md`、`docs/design/claude-design-handoff/help-learning-discovery/IMPLEMENTATION-HANDOFF.md` 和同目录 `README.md`。Claude Design 只作交互参考。先用当前业务与代码证据复核目标用户、首批任务、内容源、平台差异、直达能力和安装命令，再提出最小实现切片；不要恢复通用「带我操作」，不要按 36 个画板或固定 18 项能力整套实现。完成分析后再开始改代码。

## 当前交付边界

- 已修改移动端与桌面端帮助入口、公开帮助官网、公开手册、测试与本实现交接。
- 未修改 daemon、relay、protocol wire 或智能客服生产链路，因此不需要重启本机 daemon 或重新部署 relay。
- 已部署官网与 GitHub Pages 镜像，并同步桌面 App 与 Pandaa；本次没有创建 PR。

## 实现验证

- `:mobile:composeApp:compileKotlinDesktop` 通过。
- 新增原生帮助页与入口 UI 测试通过；上下文直达和非会话场景禁用行为均有覆盖。
- 官网支持体验契约与智能客服后端测试共 21 项通过；支持页 JavaScript 语法检查通过。
- `:protocol:jvmTest :daemon:test :relay:test :mobile:composeApp:desktopTest` 通过。
- `scripts/check-site-seo.py` 通过：38 个 HTML 页面、38 条 sitemap URL，站内链接无断链。
- App 390×844 深色与浅色离屏截图已人工复核。
- 真实浏览器已复核：桌面深色帮助首页与对话工作区、390×844 浅色帮助首页、App 直达移动页、双语切换、失败保留问题、相关指南和浏览器返回；均无横向溢出。
- `scripts/check-all.sh` 唯一未跑项为 `protocol:iosSimulatorArm64Test`：本机 Xcode 缺少 `ios_simulator_arm64` SDK；失败发生在测试设备解析前，不是代码断言失败。
