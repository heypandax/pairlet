# Pairlet 日志工作提交收尾

日期：2026-09-11。状态：**提交范围已整理，用户已授权提交并推送；额外验证移至上线后观察**。

用户先要求整理待提交内容，随后授权提交并推送；本轮不追加设备和云端验证，不执行发布或部署。具体提交与远端状态以 Git 记录为准；正常发布 CI 的配置校验及符号上传保持启用。以下“已验证”仅复用已取得的证据，未重新运行测试。

## 待提交范围

工作区：`cc-pocket-worktrees/sentry-main-integration`；分支 `codex/observability-followup`。已快进同步 main `67a610ce`，原有改动完整恢复，无冲突。main 已包含正式和预览包的 Sentry workflow，不重复提交这些既有配置。

| 变更组 | 文件 / 内容 | 现有证据 |
|---|---|---|
| Android 会话可见性 | AndroidManifest 的 singleTask；App 持有自己的 FleetCoordinator；primary/satellite 前后台对称转发；FleetLifecycleTest 与 SessionOpenTimeoutTest | 23 项定向测试；真机会话打开 1501/1066ms、文件 1410ms 成功，已记录 GA4 回执 |
| iOS 安全栈和采集出口 | NativeStackFrame 仅提取项目符号、源码 basename/正整数行号；DiagnosticPlatform 接入；NativeStackFrameTest；Cocoa 关闭在途上传/重开不重放测试 | JVM 27、Native 26、Cocoa 6 项通过；真机仅证明现有函数级定位，源码行号未验 |
| GA4 schema 类型 | TelemetryMetadata 从整数 1 改为字母数字字符串 v1；TelemetryMetadataTest 保持耗时数值类型 | 5 项测试通过；已有 98 条手机普通聚合已核对；新 v1 包云端回执未验 |
| 文档与脱敏证据 | 方案、事件口径、查询/发布指南、计划/验收记录和 3 份 evidence JSON | 保留范围与数据边界，不包含 token、私钥、对话或原始设备日志 |

建议单次提交标题：`fix(observability): close mobile diagnostics and analytics gaps`。提交说明以 Android 生命周期缺陷和 GA4 schema 筛选遗漏为主要问题，并注明 iOS 安全帧/出口验证与范围收尾。待提交文件只包含本表内容，不包含本机 Firebase/Sentry 配置、`.env`、安装包或 `/tmp` 原始记录。

## GitHub 打包配置

已实时核对仓库 `heypandax/pairlet` 的 5 个 `PAIRLET_SENTRY_DSN_*` Variables、`PAIRLET_SENTRY_AUTH_TOKEN` Secret，以及 Android/iOS Firebase 配置 Secrets 均存在；无需用户重复配置。

- **运行包**：公开 DSN 由 `vars` 注入，各组件指向自己的 Sentry 项目；正式为 production，统一预览为 staging。
- **符号上传**：iOS workflow 把 `secrets.PAIRLET_SENTRY_AUTH_TOKEN` 注入上传步骤的 `SENTRY_AUTH_TOKEN`；不进入 App。令牌存在性检查在归档前，上传及处理成功后再向 App Store Connect 上传包。
- **relay**：变量已准备；实际服务器仍使用现有独立部署方式，GitHub 配置不自动改变在线服务。

精确变量名、Secrets 管理入口、workflow 示例及预览/历史 Windows 构建的区别见 [发布配置指南](RELEASE.md#github-actions-配置与维护)。本轮只核对配置存在和工作流接线，没有执行新的 CI 发布，也没有读取 Secret 明文验证有效性。

## 上线后再跟进

| 项目 | 处理方式 |
|---|---|
| schema=v1 的真实手机回执与 GA4 新页最终展示 | 随正常版本上线后观察；不重跑本轮完整手机旅程 |
| 7 天运行、D1/D7、额度与告警基线 | 等真实流量和时间，不用本地样本补生产结论 |
| 未覆盖的平台/后端、完整矩阵和足量 P95 | 保留记录，按实际故障和发布需要再安排 |
| 历史 attach 超时 | 仅出现新版本上报或实际复现时再调查 |
| iOS 真机源码行号 | 后续定位能力增强，当前不再追加开发/验收；Crashlytics 不迁移、不强制崩溃 |

桌面正式 GA4 的安全服务端入口仍是独立能力缺口；禁止把 MP API secret 打进客户端。此限制已在发布指南说明，不把它误标为“等数据即可完成”。

详细事实与历史检查点保留在 [ACCEPTANCE](ACCEPTANCE.md)；原始计划中的未验项目不因本次收尾变成已通过，也不再自动阻塞本次提交。
