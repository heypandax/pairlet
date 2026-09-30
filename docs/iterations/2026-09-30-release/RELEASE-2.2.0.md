# 2.2.0 发布与验收记录

状态：[2.2.0 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.2.0)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并为 latest；Homebrew tap 与 Scoop bucket 已升到 2.2.0。iOS 见下方「iOS」小节（已提交审核，尚未获批）；HarmonyOS 不在本次流水线内。镜像同步见「收尾」。

## 本次内容

自 2.1.5 起 main 上的 1 个功能提交（`7a190de9`），同一 tag 发出：

- 语音备忘转任务（实验功能，默认关闭，仅手机入口）：手机录音 ≤3:00 经 E2E 连接上传到本机 daemon，daemon 用一次性 whisper-cli 本地转写，再由可插拔的整理 Agent（Claude `--print` / `codex exec`，无工具、无 MCP、空私有目录、不保存会话）整理成标题、摘要和待办；没有整理 Agent 时只转写，待办手动拆分或整段作为一项发出。用户核对后选择目标（最近会话 / 项目 → 会话或新建会话），确认后逐条作为 SendPrompt 发到目标会话；每条只在确认时的连接上发送一次，未收到回执记为“待核对”，永不自动重发。设计、契约与验收见 [产品设计](../../design/VOICE-MEMO-TO-TASK.md)、[代码设计](../../design/VOICE-MEMO-CODE-DESIGN.md)、[UI 交接](../../design/claude-design-handoff/voice-memo-tasks/README.md)。
- 协议：新增 VoiceMemoStart/Audio/Get/Cancel/State 五种帧与 `ClientCaps.supportsVoiceMemo`、`DaemonInfo.voiceMemoVersion/Agents/Status` 能力协商（尾部可选字段，旧端互不发送；契约 v2）。`packaging/brand-compatibility.json` 已按 Messages.kt 的新增字段刷新。
- 手机端统一返回按钮：`BackTarget` 改为绘制的 chevron，各页面顶部左侧返回统一走它，支持可选描述与禁用态。

relay 无变化。

## 发布来源与检查

- 版本锁步 2.2.0（daemon fallback、Android 2.2.0/versionCode 38、桌面包与 seed、iOS 2.2.0 (25)、Harmony、Homebrew 模板）。
- 商店说明只写 iPhone / iPad 用户可见变化（语音备忘、返回按钮）；审核备注新增 VOICE MEMO TO TASKS 一段说明数据流与同意；`site/privacy.html` 新增 “Voice memo to tasks” 一节，与 App 内首次录音前的同意页一致。App 隐私填报（ASC 表单）未改：新增数据只在手机与用户自己的电脑之间流转，经用户自装的编程工具发往其提供方，Pairlet 服务不接收。
- 五项门禁：版本锁步、Harmony 契约、品牌兼容、商店文案（2 locale / 18 字段）、仓库内容——通过。
- 发布提交 `d71e858c`（锁步）在功能提交 `7a190de9` 之上；tag 前 origin/main 与本地 HEAD 一致。
- 本地 `check-all.sh`（2026-09-30 CST，10m37s）：protocol 365、daemon 2,510（3 项按平台条件跳过）、relay 92、observability 30、observability-sentry 19、手机端 desktopTest 2,258，共 5,274 项，0 失败。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/36668111551)：test、windows-cwd-probe、windows-msi-branding 三个 job 全部 success。
- 不可变标签 `v2.2.0` 指向 `d71e858c`；[Release v2.2.0](https://github.com/heypandax/pairlet/releases/tag/v2.2.0) 于 2026-09-30 04:32 UTC 以 `gh release create --verify-tag --generate-notes` 建立（创建时即为公开、资产随后由流水线上传）。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/36669295630) 从标签执行（04:32→约 05:00 UTC）：preflight、五种 daemon、三种桌面端（macOS 双架构、Windows、Linux 双架构）、Android 共 12 个平台 job 与 checksums、bump-scoop 全部 success；HarmonyOS job 按默认配置跳过。

## 生产与安装包

- 24 个发布资产：五种 daemon、双架构 macOS DMG、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage 及桌面端无版本号别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.2.0/SHA256SUMS) 由流水线生成。两份 macOS daemon 从 GitHub 直链下载后本地复算 SHA-256（arm64 `169fb0bf…`，111,924,540 字节；x86_64 `f0451233…`，113,005,959 字节），与清单一致。
- `releases/latest` 回读 `v2.2.0`；`releases/latest/download/` 下 APK、双架构 macOS DMG、Windows MSI、Linux deb／rpm／AppImage 与 SHA256SUMS 直链均返回 HTTP 200（daemon 包只有带版本号的文件名）。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/86033c917b1ad5f4aaba178b2a4ef49233a5c79e)（`Casks/cc-pocket.rb`，经 API 以原 sha `7d49646b` 覆盖）回读与仓库模板逐字一致；Scoop bucket 由流水线 bump-scoop 升到 2.2.0（Windows zip `665827c3…`），回读与仓库模板 `packaging/scoop/cc-pocket-daemon.json` 逐字一致；两份仓库模板随本记录一起提交。

## iOS

- 归档前证书上限处理：干跑列出 10 张 CI 创建的开发证书（[运行 36667330132](https://github.com/heypandax/pairlet/actions/runs/36667330132)），只吊销最旧一张 `496296Z5XH`（[运行 36667388458](https://github.com/heypandax/pairlet/actions/runs/36667388458)）。
- [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/36669300395)：一次通过。归档 2.2.0 (67)，归档与导出 IPA 的 APNs 签名检查通过（development / production），上传后 build 67 在 ASC 处理为 `VALID`，deliver 同步元数据、挂 build 并提交审核（05:00 UTC "Successfully submitted the app for review!"）。
- ASC 回读：Version 2.2.0 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`、en-US 与 zh-Hans 文本与仓库一致（"text verified"）。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight：build 67 挂到既有 Public Beta 组，What to test 取自两种语言的 release_notes，Beta App Review 已提交（`WAITING_FOR_REVIEW`），外部构建状态 `WAITING_FOR_BETA_REVIEW`；[公共链接](https://testflight.apple.com/join/8z26MWWr) 不变，Beta 审核通过后外部用户才可安装。

## 收尾

- 镜像：HK 机 `cc-pocket-mirror-sync.timer` 12:48 CST 那次因 SHA256SUMS 尚未上传被脚本按设计拒绝（"release v2.2.0 has no SHA256SUMS — refusing to mirror"）；下一轮 13:20 CST 开始拉取，按 2.1.5 经验（约 110 KB/s、五个 115 MB 包、`.partial/` 断点续传）`latest.json` 约 1–2 小时后变为 2.2.0，届时另行回读并补记。截至本记录提交时 `latest.json` 仍为 2.1.5。
- 本机三端（桌面 App、daemon、Pandaa iPhone）本次未从发布包重新安装：本机 daemon 与 iPhone 是发布前同一份代码的工作区构建（分别经 `update-local-daemon-detached.sh` 与 `install-pandaa.sh`），桌面 App 未更新。
- 语音备忘的真机验证：装机后由用户试用只转写与整段派发路径；已验证的是上传→转写→Claude 整理与新建会话派发。
- Issue 回访：本版未针对具体 issue；#378／#399／#352／#386 仍等报告者反馈。
