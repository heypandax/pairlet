# CC Pocket 帮助、学习与功能发现

- 在线设计：[CC Pocket Help Center](https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=CC+Pocket+Help+Center.html)
- 生成日期：2026-07-25
- 来源：Claude Design 项目归档，仅提取本轮相关文件
- 实现状态：移动端首版已实现（2026-07-25）；桌面端继续使用既有帮助与智能客服入口
- 实现前必读：[`IMPLEMENTATION-HANDOFF.md`](IMPLEMENTATION-HANDOFF.md)（含代码事实、业务待确认项与首版减法清单）

## 最终方向

这套设计优先解决两件事：用户不会使用 CC Pocket，以及用户不知道产品具备哪些能力。主路径分为：

1. 开始使用：安装、配对、完成第一次成功会话。
2. 学会一件事：围绕真实任务说明价值、给出静态位置提示、简短书面步骤和一个可行的直接动作。
3. 发现更多能力：按结果组织能力目录，并在合适的行为时刻用行内提示安静露出。

运行时故障不是帮助中心的主要目标，只以 `CompactStateFallback` 形式保留在失败发生处，不进入学习主旅程。

## 已实现的首版切片

- 移动端新增原生「帮助与客服」页：智能客服是主操作，下方按任务提供 5 个静态学习指南。
- 项目页、会话列表和设置页原有帮助入口改为先进入原生页；进行中会话的 `⋯` 快捷操作新增同一入口。
- 首批任务为：查看改动文件、无分叉接管终端会话、处理工具审批、预约提示词、选择 Agent 与模型。
- 从进行中会话打开「查看改动文件」指南时，可一步直达真实的改动文件面板；其他场景明确说明需要先进入会话，不伪造不可用的直达。
- 公网智能客服沿用既有 `https://pocket.ark-nexus.cc/support/`，本轮没有重复实现客服 Agent、接口或上下文上传。
- 公开手册新增「查看本次会话改过的文件」，并重新生成中英文页面、AI JSON 索引、全文索引与 sitemap。
- 只记录固定枚举级的入口、任务和动作事件；不上传问题文本、路径、会话内容，也不建立长期学习档案。

实现入口：[`HelpLearning.kt`](../../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/HelpLearning.kt)。

## 范围决策

- 移除通用的跨页面操作教练，不承诺高亮真实控件、观察用户点击或替用户推进任务。
- 不维护当前教学步骤、跳过/确认状态或退出后恢复原任务的状态机。
- `TaskStepList` 只呈现静态书面步骤；是否学会由用户通过「我会用了 / 还没明白」主动反馈。
- `DirectActionLink` 只做一次性导航，打开产品里已经存在的页面或面板；目标不可直达时退化为书面步骤。
- 功能发现提示采用已有界面中的卡片或行，不使用依赖坐标的浮层。

## 关键旅程

- 首次安装 → 首次成功会话。
- 继续已有会话 → 阅读简短步骤 → 一次性「打开改动」→ 用户标记「我会用了」。
- 发现定时发送 → 行内微提示 → 一次性「打开定时发送」→ 第一次成功使用 → 标记已体验。

## 文件

- `CC Pocket Help Center.html`：两页、13 个主板面的入口画布。
- `help/board-logic.js`：触发优先级、三种模式和成功指标。
- `help/onboarding.js`：首次安装、配对和首次成功清单。
- `help/helpcenter.js`：移动端学习入口、教学式回答、书面步骤和静态位置说明。
- `help/discovery.js`：Explore、行内功能提示与能力发现组件板。
- `help/desktop-web.js`：桌面帮助面板、静态任务指南、设置页和公开帮助页。
- `help/board-system.js`：低复杂度学习组件、状态、图标和三条红线。
- `help/help-core.js`、`help/help.css`：共用组件与视觉样式。

## 检查结果

- Claude Design 对 36 个画板执行了全画布检查，最终状态为 PASS：无裁切、重叠、画板外内容、内部滚动溢出或深浅色一致性回退。
- 验收时发现 FRAME 08B 底部约 250px 空白，已补为与 FRAME 06 一致的手册来源页脚并复查深浅色版本。
- 验收时还发现预览缓存仍展示旧系统板；已通过入口文件版本号强制刷新，并以当前渲染 DOM 独立复核。
- 当前渲染结果中的 12 项旧机制文案计数均为 0；`TaskGuideCard`、`TaskStepList`、`LocationHint`、`DirectActionLink` 均已实际渲染。
- 导出源码再次全文搜索，旧机制词为 0；`help/*.js` 全部通过 `node --check`。
- 已移除 `解决问题 / Fix a problem`、GitHub 问题上报、`RecoveryCard` 和 `SanitizedReportPreview` 等主路径表达。
- 设计稿中的 macOS 安装命令使用 `brew install --cask heypandax/tap/cc-pocket`；当前 App onboarding 与公开手册使用 `curl ... install.sh | bash`。实现前必须确认发行策略与唯一可信来源，不能直接照抄设计稿。
- 深色主设计、浅色变体、移动端、桌面端、公开 Web 页与助手不可用回退均已覆盖。
- 产品实现已用 390×844 深浅色离屏截图复核；中英文长文案、任务展开、双操作和整页滚动无裁切或横向溢出。
