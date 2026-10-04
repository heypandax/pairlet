# 2.3.0 发布与验收记录

状态：[2.3.0 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.3.0)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并为 latest；Homebrew tap 与 Scoop bucket 已升到 2.3.0。iOS 已提交审核，尚未获批（见「iOS」）；HarmonyOS 不在本次流水线内。镜像同步见「收尾」。时间按 UTC，除非另有说明（发布日为 2026-10-04 UTC，北京时间 10-05 凌晨）。

## 本次内容

自 2.2.0 起 main 上的改动，同一 tag 发出：

- 名称统一为 Pairlet：各端显示名、界面文案与对外命令（`pairlet`），图标换回无文字版本；App 接受 `pairlet://` 链接（daemon 仍输出 `ccpocket://`）。
- macOS 桌面端新旧两个安装包并行发布：`CC Pocket.app`（`cc-pocket-desktop-*`）与 `Pairlet.app`（`pairlet-desktop-*`），本版是首个带新资产的版本。
- 修复：ZCode 3.14 会话发不出消息（#386）、手机新建的 DSH 会话在电脑端 DSH Web 可见（#388）、Kimi 后台任务结束后仍显示运行中（#391）、项目点击无反应（会话列表帧超限）、Codex 模型列表只剩内置三项并在各选择器说明原因、提问卡可收起且顶部状态块只占一行（#402）。
- daemon：会话列表扫描按文件缓存，relay 入口的列表回包移出读循环。
- 观测：激活漏斗三处埋点（演示结束深度、引导回访、iOS 安装渠道，#342）。
- 文档：新增[调研后不做的需求案例集](../../DECLINED-REQUIREMENTS.md)。

relay 与协议无变化，没有重新部署 relay。

## 发布来源与检查

- 版本锁步 2.3.0（daemon fallback、Android 2.3.0/versionCode 39、桌面包与 seed、iOS 2.3.0 (26)、Harmony、Homebrew 模板）。
- 商店说明只写 iPhone / iPad 用户可见的变化；审核备注说明名称变化。英文商店名为 `Pairlet: Coding Agents`（英文区的 `Pairlet` 被 App Store Connect 报告为已由其他账号使用），中文商店名与设备显示名为 `Pairlet`，见[命名约定](../../PAIRLET-NAMING.md)。
- 五项门禁：版本锁步、Harmony 契约、品牌兼容、商店文案与素材、仓库内容——通过。
- 本地 `check-all.sh`：第一次 2 项失败——`AnalyticsCatalogAlignmentTest`（`demo_exited` 未进白名单，是真实缺口，已在发布提交里补上；CI 不跑 desktopTest 所以此前未暴露）与 `CodexObserveParityTest` 的一次 `ConcurrentModificationException`（测试自身遍历列表时的竞态，单独重跑三次均通过，未改）。修复后重跑（9m44s）：protocol 365、observability 30、observability-sentry 19、daemon 2,534（3 项按平台条件跳过）、relay 92、手机端 desktopTest 2,280，共 5,320 项，0 失败。
- 发布提交 `d9d50b27`；[主 CI](https://github.com/heypandax/pairlet/actions/runs/37215464482) 三个 job 全部 success。
- 不可变标签 `v2.3.0` 指向 `d9d50b27`；Release 以 `gh release create --verify-tag --generate-notes` 建立。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/37216531022)从标签执行：preflight、五种 daemon、macOS 桌面端四个 job（双架构 × legacy／pairlet）、Windows、Linux 双架构、Android 与 checksums、bump-scoop 全部 success；HarmonyOS job 按默认配置跳过。**macOS 双包的签名与公证是第一次在正式流水线执行，一次通过。**

## 生产与安装包

- 28 个发布资产：五种 daemon、双架构 macOS DMG（旧名与 Pairlet 各一组，含无版本号别名）、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.3.0/SHA256SUMS) 覆盖其余 27 个。两份 macOS daemon 从 GitHub 直链下载后本地复算 SHA-256（arm64 `e60f77c2…`，111,951,519 字节；x86_64 `efabc009…`，113,031,708 字节），与清单一致。
- `releases/latest` 回读 `v2.3.0`；`releases/latest/download/` 下 APK、`pairlet-desktop-macos-*`、`cc-pocket-desktop-macos-arm64`、Windows MSI、Linux deb 与 SHA256SUMS 直链均可访问。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/8c496509370989d217fee9554a541b38012d5ae6)（`Casks/cc-pocket.rb`）回读与仓库模板逐字一致；Scoop bucket 由流水线升到 2.3.0（Windows zip `f69d00b6…`，与 SHA256SUMS 一致），仓库模板已对齐。本版没有做 Homebrew 包改名。
- 官网与 README 的 macOS 桌面端下载链接已切到 `pairlet-desktop-macos-*.dmg`（提交 `6f508399`），官网随即重新部署到 `website-20261004-6f508399`，公网回读中英文首页与仓库逐字节一致。

## iOS

- 归档前证书上限处理：干跑列出 10 张 CI 创建的开发证书，只吊销最旧一张 `NSFJUGX3TF`（[运行 37214151024](https://github.com/heypandax/pairlet/actions/runs/37214151024)）。
- [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/37216534330)：一次通过。归档 2.3.0 (68)，上传后 build 68 在 ASC 处理为 `VALID`，deliver 同步元数据、挂 build 并提交审核。
- ASC 回读：Version 2.3.0 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`、文本与仓库一致（"text verified"）。尚未获批，不能写成已上架；新的英文商店名能否通过，也要等审核结果。
- 商店素材：两段 App Preview 已在提审前替换为片尾使用新图标的版本。
- TestFlight：build 68 挂到既有 Public Beta 组，Beta App Review 已提交（`WAITING_FOR_REVIEW`），外部构建状态 `WAITING_FOR_BETA_REVIEW`。

## 收尾

- 镜像：HK 机 `cc-pocket-mirror-sync.timer` 在 00:43 CST 那次因 SHA256SUMS 尚未上传被脚本按设计拒绝；后续几轮开始拉取，按以往经验 `latest.json` 约 1–2 小时后变为 2.3.0。截至本记录提交时仍为 2.2.0，届时另行回读。
- 本机三端：桌面 App、daemon、Pandaa iPhone 是发布前同一份代码（`254e7b41` 之前的 main，功能上与 2.3.0 相同，版本号仍显示 2.2.0）的工作区构建，未从发布包重新安装。
- 未验证：`Pairlet.app` 的实际自更新（要到下一个版本才有可更新的目标）；旧包 `CC Pocket.app` 从 2.2.0 自更新到 2.3.0；Android 与 Windows 真机安装；发布前跳过了手机上的逐项点验。
- Issue 回访：#386、#388、#391、#402 已在 GitHub 说明随 2.3.0 发布，等报告者复测后关单；#402 的 iOS 端要等审核通过。小红书上的回访（chasingdu、#381、#386 的报告者）由 Owner 发送。
