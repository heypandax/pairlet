# 2.1.3 发布与验收记录

状态：[2.1.3 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.1.3)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并设为 latest。iOS 见下方「iOS」小节；HarmonyOS 不在本次流水线内。

## 本次内容

自 2.1.2 起 main 上的 13 个提交加发版当天补的 2 个修复，同一 tag 发出：

- iOS 大历史断线：Ktor 3.1.3 → 3.5.2（Darwin 引擎此前不应用 maxFrameSize，KTOR-6963）；协议新增可选 `ClientCaps.maxFrameBytes`，daemon 按客户端声明的上限收缩历史、工具结果和文件帧，未声明按 1 MiB。
- 推送通知（#389）：后台子 Agent 未结束不推「完成」；每会话一条、按会话折叠；打开会话、发送消息或回答提问时清除。发版当天补修 b58f0e3d 把清除调用写进「记住规则」分支的问题。
- Codex 工具折叠（#380）：Codex 工具行补上成败判定，实时与回放都能像 Claude 一样折叠已完成的工具。
- DeepSeek Harness 安装不完整时一键修复（新增 AgentRepair 系列帧，双向兼容）。
- Claude resume 后的后台任务结算不再被当成回合结束；`<synthetic>` 占位不再显示为 API 错误。
- 输入框改用 TextFieldState，消除中文 IME 直接提交标点时吞掉前文（#118 家族）。
- Markdown 引用块按 Chat Quote v1 渲染；语音依赖缺失时引导 Agent 安装；Windows 下载进度探针在真实 runner 上验证（#381）。

## 发布来源与检查

- 发布提交 `05df3022`，不可变标签 `v2.1.3` 指向同一提交；tag 前 origin/main 与本地 HEAD 一致。
- 本地 `check-all.sh` 4,941 项：4,941 项通过、3 项按平台条件跳过；版本锁步、Harmony 契约、商店文案、品牌兼容四项门禁通过。品牌兼容此前因 7da9891c 改了冻结文件 `Messages.kt` 未刷新哈希而失败，已在 7e749c98 补记。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/36259478202) 通过：服务端测试、桌面与 Android 编译及 Windows 检查。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/36260496800) 从标签执行，13 个平台 job 与 checksums、bump-scoop 全部通过；HarmonyOS job 按默认配置跳过。

## 生产与安装包

- 23 个发布资产：五种 daemon、双架构 macOS DMG、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage 及无版本号别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.1.3/SHA256SUMS) 由流水线生成。两份 macOS daemon 在本机下载后复算 SHA-256（arm64 `28b95856…`、x86_64 `94c1c637…`），与清单一致。
- Release 于 2026-09-26 17:51 UTC 公开并设为 latest；`releases/latest/download/` 下 APK、macOS DMG、Windows MSI、macOS daemon 与 SHA256SUMS 直链均返回 HTTP 200。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/790e62661ad114dd03cf9a6111131573953946f2)已回读；Scoop bucket 由流水线 bump-scoop 升到 2.1.3（`9a412d43…`）并回读一致；仓库模板同步更新。
- 镜像 `latest.json`：见下方「收尾」。

## iOS

- 第一次 [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/36260500946) 在归档阶段失败：开发证书达到账号上限。按 `asc-maintenance` 既定流程干跑列出 10 张 CI 创建的证书，只吊销最旧一张（`B37NS9G98P`，[运行](https://github.com/heypandax/pairlet/actions/runs/36260764883)），未触及分发与 Developer ID 证书。
- 第二次 [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/36260792849)：归档、Sentry 配置与 dSYM 检查、开发／生产 APNs 权限检查、IPA 导出与上传全部通过，build 64；ASC 处理为 `VALID` 后由 fastlane 绑定并提交审核。
- ASC 回读：2.1.3／build 64 已绑定并提交审核，状态 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`，中英文 9 项文案与审核备注回读一致；年龄分级 Messaging and Chat = Yes。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight：build 64 挂到既有 Public Beta 组并提交 Beta App Review，回读 `WAITING_FOR_REVIEW`，外部构建状态 `WAITING_FOR_BETA_REVIEW`；[公共链接](https://testflight.apple.com/join/8z26MWWr) 不变，Beta 审核通过后外部用户才可安装。

## 收尾

- 镜像同步服务由定时器自动完成，但 18:02 UTC 那一轮跑在 checksums job 结束之前，脚本以「release v2.1.3 has no SHA256SUMS」拒绝镜像；18:33 UTC 下一轮成功。回读公开 `latest.json` 为 2.1.3，五种 daemon 均指向源站缓存，macOS arm64 包返回 HTTP 200 且大小与 GitHub 一致（111,656,641 字节）。
- 本机 SSH 已改用密钥登录 HK 服务器（只读 journalctl），未手动触发同步。
- 发版当天同步到本机三端（桌面 App、daemon、Pandaa iPhone）的是同一提交的工作区构建。
- 仍开放：#389、#381 有对应提交但未关单；#391、#390、#388、#386 本版未处理。
