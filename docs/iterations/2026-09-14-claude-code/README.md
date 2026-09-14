# 后续 Issue 目录与 Claude Code 交接

核验日期：2026-09-14。源码基线：`d7f06c17b8f1154adf55926d46fca3b0d3cd4b81`，核验时本地与远端 main 一致。仓库：[heypandax/pairlet](https://github.com/heypandax/pairlet)。

**这是一份由 Claude Code 独立接手的执行目录。** 用户将把目录交给 Claude Code；之后的拆分、编码、测试、返修、独立评审和收口均在 Claude Code 内完成。不需要 Codex 在线，不调用 Codex 调度器，也不依赖本机忽略的 issue-dispatch／claude-implementation skill 或外部回执脚本。现有后端名 Codex 仍是产品支持的 Agent，不能因交接方式变化而删除其实现或测试。

本次只整理目录和方案，未启动这些任务的编码。具体哪一批开工，以用户在 Claude Code 中的指令为准；常规实施不逐步请求确认。GitHub 是需求事实源，本目录是日期快照和实施说明，不自动改写 Issue 状态。

## 给 Claude Code 的入口

把 [CLAUDE-START.md](CLAUDE-START.md) 中的启动指令连同本目录一起提供给一个 **Opus 5 主会话**。主控拆分任务，启动其他 Claude Code 子任务并行实施，持续同步进度、处理依赖并统一整合。只传一份文件时，先传本页，让 Claude Code 在仓库中读取其余文档。

| 文档 | 用途 | 方案成熟度 |
|---|---|---|
| [#380 工具调用折叠](issue-380-tool-collapse.md) | 两端共用展示投影；保留审批、异常、分页和阅读位置 | MVP 行为与技术边界已明确，可先实施 |
| [#381 更新进度与下载源](issue-381-download.md) | A：daemon CLI 下载进度；B：下载源回退与对象存储 | A 可先实施；B 的供应商与部署等待选择 |
| [#320 状态保真与历史补齐](issue-320-context-status.md) | A：未知状态可理解；B：有证据后补历史模型／占用 | A 可先实施；B 按后端证据进入编码 |
| [#363 新建模式稳定性](issue-363-session-mode.md) | 同 Agent 换模型、能力迟到、确认与启动快照 | 先建立复现，定位后按条件方案修复 |
| [#360 显式导入会话](issue-360-managed-sessions.md) | 已管理会话与外部发现分开；可恢复迁移及兼容 | 完整实施建议；先完成迁移与线协议小闭环，再接 UI |
| [#367 跨设备 CLI](issue-367-agent-cli.md) | 独立授权关系、持久任务、审批与结果拉取 | 架构方案；授权握手原型通过前不接通远程执行 |
| [验收与待取证清单](acceptance-and-evidence.md) | 已在 main 的修复、iPad、ChatGPT 接管、海外激活 | 验收／调查任务，禁止机械重复修复 |

## 推荐推进顺序

1. **第一批：#381-A、#380-MVP、#320-A 并发实施。** 三项都有明确用户收益和本地可验证的结果：更新时看见进展、聊天过程可收起、缺少上下文时知道“尚未获得数据”。主控分派三个实现子任务；#380 与 #320 并行开发独立组件，共享聊天入口由主控按 #380 → #320 串行接线。各项完成即可进入独立评审，不等整批实现结束。
2. **第二批：#363 复现与根因修复、#360 显式导入。** #363 先保护用户选择与确认链路，不能借修复改变全自动授权含义；#360 在已有分组快照修复上建立数据边界，不重写前一批。
3. **第三批：#367 授权／任务最小闭环。** 优先验证真实跨设备价值；同机跨 Agent 只是同一命令的兼容目标，不单独扩展成编排平台。第一步是受限授权原型，不是开放任意远程 shell。
4. **贯穿各批：#362、#334 以及已合入修复的实际验收。** 缺设备的项明确保留，不用模拟器启动或单测通过代替。#379 继续不投入 Linux 验证。

采用 **Opus 5 主控 + Claude Code 原生并行子任务**，实现和评审子任务默认同为 Opus 5；本目录没有已启动的实现 Agent。主控维护统一任务板，子任务回报范围、冲突、阻塞、可集成切片和验证结果；主控负责共享文件与集成工作区。并发数量适配真实可用槽位，无并行能力时明确降级串行；不能因缺少 Codex 而卡住。普通方案调整和返修也在 Claude Code 内完成。安全或 wire 评审由未参与该项编码的 Claude Code 上下文执行，主控写过的集成代码也需独立评审；若当前环境没有独立评审能力，标明该节点未完成，不自称已获非作者批准。

## 全部 open Issue 去向

本轮通过 GitHub API 读取 **22 个 open Issue 的正文、全部 11 条评论、0 个 open PR，以及全部 38 个已关闭 PR 的索引**；open Issue 均无 assignee。候选的直接线索与历史提交又与当前源码核对。核验时最新正式 Release 仍为 [v2.0.0](https://github.com/heypandax/pairlet/releases/tag/v2.0.0)；近期 main 提交不应当作用户已获得的版本。

优先级是本次建议，未写回 GitHub。S/M/L 表示规模；“困难”指诊断／设计复杂度，和等待硬件不是一回事。

| Issue | 剩余可处理范围 | 建议优先级／分类／规模／风险 | 本次去向 |
|---|---|---|---|
| [#381](https://github.com/heypandax/pairlet/issues/381) | daemon update 下载进度；慢网与镜像链路 | P2；A 常规/M/中，B 困难/L/高 | 先 A，下载源设计见专文；OSS/COS 未定 |
| [#380](https://github.com/heypandax/pairlet/issues/380) | 会话级工具过程收起，突出可读回复 | P2；困难/M/中 | 第一批；共享展示层方案，不改执行与历史存储 |
| [#379](https://github.com/heypandax/pairlet/issues/379) | Linux 桌面 deb/rpm/AppImage | 暂缓；长消耗/L，当前缺使用价值证据 | 保留未完成；不启动 Linux／Docker／多架构验证 |
| [#378](https://github.com/heypandax/pairlet/issues/378) | 手机横屏、返回列表、旋转实际验收 | P2；常规/S/低 | main 已实现；验收及随版本交付 |
| [#377](https://github.com/heypandax/pairlet/issues/377) | DSH/Kimi 真实图片、排队和拒绝后继续发送 | P2；常规/M/中 | main 已实现；真实 provider 验收 |
| [#376](https://github.com/heypandax/pairlet/issues/376) | DSH 多轮 V3 历史验收；Web 分组受上游能力约束 | P2；常规/M/中 | 历史修复已在 main；不伪造 ACP 不支持的分组 |
| [#375](https://github.com/heypandax/pairlet/issues/375) | 在正常 CI 中观察既有时序修复 | P2；简单/S/低 | main 已修；不为其另建 Linux 验证环境 |
| [#374](https://github.com/heypandax/pairlet/issues/374) | 最新项目首个会话自动出现、慢回包验收 | P2；常规/M/中 | main 已修；和 #360 回归一起观察 |
| [#373](https://github.com/heypandax/pairlet/issues/373) | 最近五项目、展开收起、跨电脑定位验收 | P2；常规/S/低 | main 已实现；不要重写侧栏 |
| [#371](https://github.com/heypandax/pairlet/issues/371) | 新安装默认平铺、旧偏好保留 | P2；简单/S/低 | main 已修；实际 App 验收 |
| [#367](https://github.com/heypandax/pairlet/issues/367) | Agent 调另一台已授权电脑执行任务并取回结果 | P2；困难/L/高 | 第三批，先受限授权与可靠投递原型 |
| [#364](https://github.com/heypandax/pairlet/issues/364) | 原 Windows 机器的真实安装布局／命令核对 | P2；常规/S/中，现场证据缺失 | 跨系统分类已修；未复现现场时不追加猜测补丁 |
| [#363](https://github.com/heypandax/pairlet/issues/363) | 手机选模型后模式变化、两端异步初始化 | P2；困难/M/高 | 模型／后端快照已修；剩余按复现方案推进 |
| [#362](https://github.com/heypandax/pairlet/issues/362) | 项目置顶双端／离线／重配对验收 | P2；长消耗/M/高 | 项目同步已在 main；会话置顶及手机新入口仍需产品决定 |
| [#360](https://github.com/heypandax/pairlet/issues/360) | 稳定受管清单、搜索与显式导入 | P2；困难/L/高 | 阶段一已修；按专文实施阶段二 |
| [#357](https://github.com/heypandax/pairlet/issues/357) | 反馈总览的交付状态串联 | 索引，无独立实现 | 不重复计数；实际结论归属子 Issue |
| [#354](https://github.com/heypandax/pairlet/issues/354) | ZCode/DSH 文件读取与差异能力的真实验收 | P2；常规/M/高 | main 已修；继续保留路径授权与证据降级 |
| [#352](https://github.com/heypandax/pairlet/issues/352) | 妙控键盘双指滚动、中文候选栏位置 | P2；困难/M/中，硬件取证阻塞 | 先当前版本复现，分别定位，和 #334 共享设备验收 |
| [#347](https://github.com/heypandax/pairlet/issues/347) | ChatGPT 无须重启可见、受支持的定位入口 | P2；困难/M/中，外部 App 能力待证 | 命名标记已发布；先有界调查，不猜深链或改外部数据库 |
| [#342](https://github.com/heypandax/pairlet/issues/342) | 海外动手前的激活动因与演示后下一步 | 暂缓编码；困难/M/中，业务证据缺失 | 既有信任／观测地基已发布；先读新信号，再定单一实验 |
| [#334](https://github.com/heypandax/pairlet/issues/334) | 真多任务／输入／审批状态；iPad 商店截图 | P2；长消耗/M/中，部分依赖设备／版本 | 布局状态修复已在 main；先补验收，截图待版本确定 |
| [#320](https://github.com/heypandax/pairlet/issues/320) | 上下文未知的说明、后端历史可信字段 | P2；A 常规/M/中，B 困难/M/中 | 第一批 A；B 按真实字段证据逐后端补齐 |

## 不重复实现的基线

前两批代码：`2360d063`、`d5c4fd40`、`c8c09491`、`fdd64a55`、`d3f92c95`。

后续代码：#364 `06524dcd`、#363 `1a4b992c`、#320 `07e3c672`、#360 `51072d7d`、#334 `f5529730`、#362 `859bcaf4`。本期明细见 [已有任务文档](../2026-09-13-issue-delivery.md)。Codex exit 143 正常回合收尾的修复也已在 main；它没有证明 MCP 请求失败／DNS 故障已经解决。

## 需要另行决定的少数事项

| 决定／条件 | 默认推进方式 |
|---|---|
| #381 OSS 还是 COS、地域／域名／CDN／预算 | 先做进度；写供应商无关的发布契约，不采购、不部署 |
| #362 是否同步会话置顶、手机如何承接 | 本轮不扩；先验收已实现的项目同步 |
| #367 哪两台电脑、如何授予调用关系 | 先本地 fixture 和隔离授权原型；真正授予执行权限时由用户在目标端确认 |
| #352 对应 iPad＋妙控键盘 | 无硬件时留复现表，不以桌面鼠标／模拟器冒充 |
| #334 截图目标版本、#342 业务信号 | 缺前提时推进其他任务，不自行扩大到提审或数据采集 |

本目录的设计取舍是实施建议，不是复现或已完成代码。凡标“待证”的条件，Claude Code 应取得对应证据；若事实与方案冲突，由 Claude Code 在本目录补充更正并继续可独立完成的任务，不转回 Codex 等待调度。

## 结果（2026-09-15 收口）

- 进入 2.1.0：#381-A 下载进度；#380 收起工具过程（进行中会话与 Codex 会话不收起，记为已知限制，#383 归并于此）；#320 A＋B；#363 跨 Agent 沿用已选模式；#360 阶段二（只登记 Pairlet 自己创建的会话）；#382；#379 Linux deb／rpm（与上文“不投入 Linux 验证”不同，用户改为加入，首次 CI 实跑随本次发版）；#347 接管后自动深链定位；#367 G1＋G2（本地候选，两台真实电脑的 G3 验收待做）。
- 顺手修复：review 收件方在对端撤销凭据后不再按重连梯度无限重拨。
- 未做或待定：#381-B 下载源（用户备案中）、#342（缓至 2026-10-15 后处理）、#352（已邀请对方 fork 并给出研发流程）、#367 G3 真机验收。
- 用户亲定的取舍：#360 只登记 owner 创建的会话；#367 限额保持原型范围、撤销写盘失败整体不可用、不持久化 ticket、源端长期身份签名后置；Codex 接管改名被 ChatGPT App 改回属对方行为，记为已知限制。
