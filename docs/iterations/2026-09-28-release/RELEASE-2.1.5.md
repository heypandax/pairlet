# 2.1.5 发布与验收记录

状态：[2.1.5 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.1.5)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并为 latest。iOS 见下方「iOS」小节；HarmonyOS 不在本次流水线内。

## 本次内容

自 2.1.4 起 main 上的 4 个提交，同一 tag 发出：

- iOS 预览文档不再闪退：`previewFile` 把 NSURL 强转成 QLPreviewItem 协议，Kotlin/Native 运行时协议检查失败直接 abort（Pandaa 真机 2.1.3 (22) 崩溃日志）；改用显式实现该协议的包装类，新增 iOS 模拟器测试（ac32e60a）。
- 手机端点文档直接打开系统预览（iOS QuickLook / Android ACTION_VIEW），不再经过查看器与「预览」按钮；传输超过 300ms 才浮出可取消的进度框；读取失败、文件过大、需电脑审批或系统无法预览时才展开查看器说明；App Lock 锁定、隐私遮罩或后台时不调起预览。新增 DocumentOpenerTest 等（01ad0140）。
- 桌面端侧栏「最近」项目行操作簇按 Recent Row Actions v1 重做：折叠箭头移到行首，置顶／刷新／新会话固定按钮槽，悬停不再抖动（2029e2eb）。
- daemon：ZCode ≥3.14 官方包启动不再报「无法定位 Built-in Provider Config」——像 ZCode 桌面端一样通过 `ZCODE_BUILTIN_PROVIDER_CONFIG_FILE` 把配置路径交给 CLI，仅在包内确有该文件时设置（#386 同源，a0e990c1）。

relay、protocol 无变化。

## 发布来源与检查

- 发布提交 `f89735df`，不可变标签 `v2.1.5` 指向同一提交；tag 前 origin/main 与本地 HEAD 一致。
- 本地 `check-all.sh`（2026-09-28，9m42s）：protocol 620、daemon 2,383（3 项按平台条件跳过）、relay 92、observability 52、observability-sentry 19、手机端 desktopTest 2,034，共 5,200 项，0 失败。版本锁步、商店文案（2 locale / 18 字段）、Harmony 契约、品牌兼容、仓库内容五项门禁通过。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/36458700318)：test、windows-cwd-probe、windows-msi-branding 三个 job 全部 success。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/36460677732) 从标签执行：13 个平台 job 与 checksums、bump-scoop 全部通过；HarmonyOS job 按默认配置跳过。

## 生产与安装包

- 24 个发布资产：五种 daemon、双架构 macOS DMG、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage 及桌面端无版本号别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.1.5/SHA256SUMS) 由流水线生成。两份 macOS daemon 从 GitHub 直链下载后本地复算 SHA-256（arm64 `3b5f3877…`、x86_64 `a4410972…`），与清单一致。
- Release 于 2026-09-28 17:47 UTC 以 `gh release create` 建立并即为 latest（`releases/latest` 回读 `v2.1.5`）；`releases/latest/download/` 下 APK、双架构 macOS DMG、Windows MSI、Linux deb／rpm／AppImage 与 SHA256SUMS 直链均返回 HTTP 200（daemon 包只有带版本号的文件名）。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/95cf6ea926f219076d0b2542fb0c9a4022f4fa20)已回读与仓库模板逐字一致；Scoop bucket 由流水线 bump-scoop 升到 2.1.5（Windows zip `9bfd708b…`）并回读与仓库模板一致；仓库模板同步更新。

## iOS

- 归档前按 `asc-maintenance` 既定流程处理证书上限：干跑列出 10 张 CI 创建的开发证书（[运行 36457515738](https://github.com/heypandax/pairlet/actions/runs/36457515738)），只吊销最旧一张 `JM3LSPA97W`（[运行 36457638767](https://github.com/heypandax/pairlet/actions/runs/36457638767)），未触及分发与 Developer ID 证书。
- [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/36460683697)：一次通过：归档、导出、上传，build 66 在 ASC 处理为 `VALID` 后由 fastlane 绑定并提交审核（18:32 UTC "Successfully submitted the app for review!"）。
- ASC 回读：2.1.5／build 66 状态 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`，en-US 与 zh-Hans 各 9 个文本字段及审核备注与仓库一致。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight：build 66 挂到既有 Public Beta 组并提交 Beta App Review，外部构建状态 `WAITING_FOR_BETA_REVIEW`；[公共链接](https://testflight.apple.com/join/8z26MWWr) 不变，Beta 审核通过后外部用户才可安装。

## 收尾

- 镜像：`latest.json` 已为 2.1.5（定时同步自动完成，未手动触发，2026-09-28 20:07 UTC 回读），五种 daemon 指向 `dl/v2.1.5/` 下的源站缓存，macOS arm64 包返回 HTTP 200 且大小与 GitHub 一致（111,656,710 字节）。过程说明：HK 机 `cc-pocket-mirror-sync.timer` 01:57 CST 那次因 SHA256SUMS 尚未上传被脚本按设计拒绝；02:29 CST 这轮以约 110 KB/s 从 GitHub 拉取五个 115 MB 包，靠 `.partial/` 断点续传在约 1.5 小时内完成。
- 本机三端（桌面 App、daemon、Pandaa iPhone）本次未从发布包重新安装；本机 daemon 是 2026-09-28 00:58 的工作区构建，早于 ZCode 修复提交，需要时用 `scripts/update-local-daemon.sh` 或 `/update-devices` 同步。
- Issue 回访：#386（ZCode Windows 无法发消息）本版包含同源修复，待发布后回复报告者在 2.1.5 daemon 上复测；#378／#399／#352 仍等报告者反馈，本版未改动。
