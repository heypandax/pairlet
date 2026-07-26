# CC Pocket 智能客服直达入口与公开帮助 IA v2

- 在线设计：[Help Support Direct Entry v2](https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=Help+Support+Direct+Entry+v2.html)
- 生成日期：2026-07-26
- 来源：Claude Design 项目归档，仅提取本轮新增设计与必要的共用依赖
- 实现状态：已完成开发与验证；官网与 GitHub Pages 镜像已部署，桌面端与 Pandaa 已同步

## 实现记录

- 官网默认 `/support/` 已改为任务优先帮助首页；`?mode=chat&source=app` 进入同一支持界面的直达对话态。
- App 原生支持入口已改为完整可点击单行；非敏感入口参数继续固定为 `mode` / `source`。
- 会话页入口恢复安全上下文：当前界面、平台、App 版本、Agent、模型、运行状态和可用控件通过 `#ctx=` fragment 交给网页，提问前可见且可一键取消；公开 API 再做固定 schema 白名单校验。
- 上下文不包含对话内容、会话 ID、仓库/路径、文件内容、日志、机器身份、配对材料或凭据。
- 公开提问原地切换对话工作区，浏览器返回回到帮助首页；App 直达页不自动聚焦、预填或提交。
- 429、busy、timeout 与 unavailable 均保留问题，提供 Retry 与关键词匹配的已核验指南。
- 移动输入框固定为 16px 并采用动态视口，避免 iOS 聚焦时自动放大或遮掉页面；桌面 App 已移除「复制手册给 AI」重复动作。
- 新增支持体验契约测试，并完成中英文、深浅色、390×844 与 1440×900 的真实浏览器检查。

生产实现位于 `site/support/`、`mobile/composeApp/.../OpenUrl.kt`、`HelpLearning.kt` 与桌面 `SettingsModal.kt`；验证记录汇总在相邻的 [`IMPLEMENTATION-HANDOFF.md`](../help-learning-discovery/IMPLEMENTATION-HANDOFF.md)。

## 设计结论

同一个公开支持界面提供两种进入方式：

1. App 直达模式：原生「问智能客服」整行可点，打开 `/support/?mode=chat&source=app`；落地首屏已经可以输入问题，不再经过官网 Hero 和第二次「咨询智能客服」。
2. 公开帮助模式：直接访问 `/support/` 时先显示任务优先的帮助首页；用户在统一输入框提交问题后，原地进入同一个对话工作区。

## 内容架构

- 第一层：统一问题输入框「今天想解决什么？」。
- 第二层：五个已经核验的高频任务，与 App 的「帮助与客服」内容保持一致。
- 第三层：按使用场景浏览，分为开始使用、继续工作、对话与控制、Agent 与模型、分享与隐私。
- 第四层：紧凑的故障排查，只保留「电脑没有上线」「daemon 需要更新」等高频恢复入口。
- 页脚出口：完整用户手册、报告可复现问题、隐私与安全。

完整手册继续承担深度参考；GitHub Issue 降为自助之后的升级路径。「把手册交给 AI」不再作为独立入口，因为页面已经提供第一方智能客服。

## 交互与状态

- App 卡片改为单一、完整可点击的原生行，不再在卡片内部嵌套重复按钮。
- App 的请求 query 只传递 `mode=chat` 与 `source=app`；会话页可额外准备上述最小安全环境，并在用户发送问题时一并提交。
- 等待态如实反映整体返回接口：「正在检索已核验手册 · 复杂问题可能需要约 1 分钟」，不伪造逐字流式或进度百分比。
- 429、服务繁忙和超时状态均保留原问题，并提供重试与最接近的已核验手册指南。
- 返回使用浏览器原生返回；不新增账号、历史记录、上传或 WebView 专用导航。

## 画板

- FRAME 0：入口与信息架构契约。
- FRAME 1：原生 App「帮助与支持」。
- FRAME 2：App 打开的移动端直达对话首屏。
- FRAME 3：移动端回答。
- FRAME 4：等待、限流、繁忙、超时回退组件。
- FRAME 5：桌面公开帮助首页。
- FRAME 6：移动端浅色公开帮助首页。
- FRAME 7：桌面对话工作区。
- Decision Notes：实现契约、验收清单与明确排除项。

## 交付边界

设计轮只重新设计入口流程、交互 UI、帮助内容层级、响应式布局与前端回退状态。2026-07-26 的实现修正额外改动了公开支持 API 的安全上下文白名单、元数据提示封装和内部旁白过滤；现有 Agent、已核验知识库及其治理机制仍沿用既有实现。

## 文件

- `Help Support Direct Entry v2.html`：8 个主画板与决策说明的交互入口。
- `help-support-v2/v2-frames.js`：App 入口、直达对话、回答与失败回退。
- `help-support-v2/v2-web.js`：桌面与移动公开帮助首页、桌面对话工作区。
- `help-support-v2/v2-kit.js`：本轮共用组件。
- `help-support-v2/v2-notes.js`：设计决策与实现假设。
- `help-support-v2/v2-interactions.js`：可点击交互与状态切换。
- `help-support-v2/v2.css`：本轮样式。
- `help/help-core.js`、`help/help.css`：从既有帮助设计复用的基础依赖。

## 检查结果

- 已在 Claude Design 中检查 390×844 深色 App 入口、App 直达对话、桌面公开帮助首页、390×844 浅色帮助首页和桌面对话工作区。
- App 直达首屏包含问候、4 条建议和固定输入框；移动公开帮助首屏包含输入框与 3 个高频任务。
- 修复了手机壳样式类名冲突、移动端页脚溢出和决策说明板高度不足。
- 设计稿覆盖键盘焦点、44pt 触控目标、safe area、深浅色与 reduced-motion。
- 导出的 JavaScript 已通过语法检查；入口引用的本地依赖均已归档。
