# Pairlet Formula 候选状态

2026-09-10：按用户最新决定，等正式以 Pairlet 名义发布的 **2.0.0** 稳定版本再申请官方收录。未创建 Homebrew 官方 PR，也未推送此配方；`Formula/pairlet.rb` 仅保留实现草稿，不被当前发布流程读取。

## 当前草稿

- 暂用公开 `v1.9.8` 源码归档探索构建，SHA-256 已实测。该版本仍使用 CC Pocket 品牌，正式提交前必须更换为新的稳定版本和对应实际校验值。
- 通过 Gradle 8 / OpenJDK 17 构建 daemon，提供 `pairlet` 与 `cc-pocket-daemon`，使用相同启动脚本。
- Homebrew service 保持 macOS `dev.ccpocket.daemon`、Linux `cc-pocket-daemon`，不在安装时启动服务。
- 功能测试使用隔离的 Java home 和 identity 路径，验证两个命令共享配置。

## 验证边界与待办

- 已完成：公开源码下载及校验；v1.9.8 的 Gradle wrapper（8.13）单独执行 `:daemon:installDist` 成功；Formula Ruby 语法及初次样式检查。
- 未完成：实际 Homebrew 源码安装、`brew test`、完整 `brew audit --new --strict`、Linux 验证、HEAD 构建验证。
- 本机实际阻碍：`gradle@8` bottle 下载报 `HTTP/2 ... PROTOCOL_ERROR`；audit 在前置检查时报 `Your Command Line Tools are too outdated`，要求 Xcode 26.3 对应 CLT。没有删除或切换系统开发工具。
- 新版还须明确区分 Formula 和旧 Cask 的升级指引：v1.9.8 的 `UpdateService.updateCommand(HOMEBREW)` 仍返回旧 Cask 命令。应在上游修复并随正式版本发布，不能把此草稿视为已完成的分发兼容。
- 旧脚本安装 / Cask 向 Formula 迁移须验证命令链接、后台服务交接与卸载；不能并行注册第二套 daemon。旧用户数据与配对身份保持兼容。

## 送审条件

- [官方共同政策](https://docs.brew.sh/Package-Acceptance-Policy)：作者自荐通常需要 225 stars / 90 forks / 90 watchers 中至少一项；当日项目为 117 / 23 / 3。官方 Cask 同样适用，并非更低门槛；特殊情况由维护者决定。
- [Cask 政策](https://docs.brew.sh/Acceptable-Casks)：桌面 App 与开源 daemon 应按各自适用包类型申请，不能用 Cask 绕过 Formula 的拒绝。
- [贡献要求](https://github.com/Homebrew/homebrew-core/blob/main/CONTRIBUTING.md)：送审前由提交者本人审阅 AI 生成内容，披露使用的工具；维护者的问题须由本人回答。尚未完成本人审阅。
- 官方收录前，可维护自有 tap 和官网安装脚本作为独立安装渠道；此处没有发布新的入口。
