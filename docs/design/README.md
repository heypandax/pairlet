# 设计文档

这里维护产品设计与技术方案。按场景阅读，结合文内状态与当前源码判断适用性；名称带 `-EVALUATING` 的文件是评估材料，不能直接当作已经定案的方案。

## UI 与平台

| 入口 | 用途 |
|---|---|
| [UI 设计说明](claude-design-handoff/README.md) | 按主题检索整理后的设计记录、移动端 2.0 及后续修订；原型来源也在这里 |
| [Mobile UI 2.0 取代关系](claude-design-handoff/mobile-ui-2.0/supersession-map.md) | 区分现用方向与已被取代的早期稿 |
| [桌面 UI 优化评估](DESKTOP-UI-OPTIMIZATION-EVALUATING.md) | 桌面信息层级与导航优化的评估 |
| [Android](android/) / [iOS](ios/) | 平台专项设计 |
| [macOS 菜单栏](macos-app-menu-bar.md) | 桌面菜单栏设计 |
| [早期 UI 规格](UI-DESIGN.md)、[早期设计 prompt](CLAUDE-DESIGN-PROMPT.md)、[V1 审计](V1-DESIGN-AUDIT.md)、[V1 补充](V1-DESIGN-SUPPLEMENT.md) | V1 背景资料；视觉取代关系优先参考 Mobile UI 2.0 |

## 功能与技术方案

| 主题 | 文档与适用范围 |
|---|---|
| 审批 | [统一审批方案](APPROVAL-SYSTEM.md)、[风险评估子设计](SMART-APPROVAL.md) |
| 协作 | [异步评审请求](REVIEW-REQUEST.md)、[运行时接力](SESSION-HANDOFF.md)、[接力实现复审](SESSION-HANDOFF-IMPLEMENTATION-REVIEW.md)、[飞书评审信任](FEISHU-REVIEWED-TRUST.md) |
| 后端 | [Codex 多 Agent](CODEX-MULTI-AGENT.md)、[DSH ACP](DSH-ACP-TRANSPORT.md)、[DSH 提问桥接](DSH-ASK-BRIDGE.md)、[Kimi 设计](kimi-backend-design.md) |
| 工作区 | [Git 面板](GIT-PANEL.md)、[Worktree](WORKTREE-MANAGEMENT.md)、[回退与 fork](REWIND-FORK.md)、[桌面分屏](SPLIT-PANES.md) |
| 输入 | [语音输入](VOICE-INPUT.md) |
| 诊断 | [总体方案](OBSERVABILITY.md)、[配置与验证入口](../observability/README.md)、[核心错误路径](../observability/ERROR-PATHS.md)、[产品分析口径](../observability/PRODUCT-INSIGHTS.md) |
| 评估与历史探索 | [渠道集成评估](CHANNEL-INTEGRATIONS-EVALUATING.md)、[会话打开诊断评估](SESSION-OPEN-DIAGNOSTICS-EVALUATING.md)、[旧观测供应商评估](OBSERVABILITY-EVALUATING.md)、[已暂停的 Peer Call](PEER-CALL.md) |

## 维护与存档

完整设计导出、原始对话和重复截图放仓库外，当前库只保留可维护说明与来源链接；详见[仓库内容规则](../REPOSITORY-CONTENT.md)。旧 `~/Desktop/Brain/...` 路径已失效，不作为本仓库依赖或恢复目的地。

运行计划和历史验收统一从[归档索引](../archive/README.md)查询。新任务以当次范围和当前源码为准，旧文档中的执行指令不自动延续。
