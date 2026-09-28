# 2.1.4 发布与验收记录

状态：[2.1.4 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.1.4)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并设为 latest。iOS 见下方「iOS」小节；HarmonyOS 不在本次流水线内。

## 本次内容

自 2.1.3 起 main 上的 7 个提交，同一 tag 发出：

- 执行过程折叠块随第一步诞生，卡片内实时行显示当前步骤，运行中不再跳动（Tool Process Live v1，b113bcc5）；回合进行中不再误报「无结果」，措辞改为「未返回」（45aaff1e）。
- 折叠屏展开后横屏保持单栏：设备分类先读折叠（铰链传感器特征或 Jetpack WindowManager 折痕）再读 `sw600dp` 宽度线，新增 `androidx.window:window:1.4.0`（#378 复测，dcf00480）。桌面端布局测试与 iOS 模拟器构建通过；Android 目标由主 CI 编译，折叠屏真机待报告者复测。
- 链接轻点打开、长按出菜单（#361），Markdown 显式链接指向真实目标；文件查看器与桌面预览一键复制路径；新建会话的完全放权确认只问一次；桌面端新会话合成行按目录身份判同一项目（#58）（e1c3dbc7）。
- 手机会话详情排版按 Chat Rhythm v1 落地：整条复制移到来源行，回合／段落间距统一，工具块整行可点，头部与输入区各成一个容器（d6289b01）。
- 直连／中继路径选择与 P2P 评估结论归档（已搁置，90bc8c40）。

daemon、relay、protocol 无功能变化；daemon 版本随全平台锁步。

## 发布来源与检查

- 发布提交 `7cb77df6`，不可变标签 `v2.1.4` 指向同一提交；tag 前 origin/main 与本地 HEAD 一致。
- 本地 `check-all.sh`（2026-09-27 11:25–11:35，10m23s）：protocol 344、daemon 2,381（3 项按平台条件跳过）、relay 92、observability 30、observability-sentry 19、手机端 desktopTest 2,024，共 4,890 项，0 失败。版本锁步、商店文案、Harmony 契约、品牌兼容、仓库内容五项门禁通过。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/36341244739)：通过：服务端测试、桌面与 Android 编译（含 #378 的 androidMain 与 androidx.window 依赖）及 Windows 检查（三个 job 全部 success）。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/36358659644) 从标签执行，13 个平台 job 与 checksums、bump-scoop 全部通过；HarmonyOS job 按默认配置跳过。

## 生产与安装包

- 24 个发布资产：五种 daemon、双架构 macOS DMG、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage 及桌面端无版本号别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.1.4/SHA256SUMS) 由流水线生成。两份 macOS daemon 经镜像下载后复算 SHA-256（arm64 `4bad2a5d…`、x86_64 `f306df16…`），与清单一致；GitHub 直连从本机下载过慢，未用它复算。
- Release 于 2026-09-27 23:25 UTC 公开并设为 latest；`releases/latest/download/` 下 APK、双架构 macOS DMG、Windows MSI、Linux 包与 SHA256SUMS 直链均返回 HTTP 200（daemon 包只有带版本号的文件名，与 2.1.3 相同）。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/0bc38888b0d68300f65626c1cfbf219adfd93562)已回读与仓库模板一致；Scoop bucket 由流水线 bump-scoop 升到 2.1.4（Windows zip `0cc6a5fb…`）并回读一致；仓库模板同步更新。

## iOS

- 归档前按 `asc-maintenance` 既定流程处理证书上限：干跑列出 10 张 CI 创建的开发证书（[运行 36340618492](https://github.com/heypandax/pairlet/actions/runs/36340618492)），只吊销最旧一张 `7HJN8C22XM`（[运行 36340706074](https://github.com/heypandax/pairlet/actions/runs/36340706074)），未触及分发与 Developer ID 证书。
- [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/36358662340)一次通过：归档、导出、上传，build 65 在 ASC 处理为 `VALID` 后由 fastlane 绑定并提交审核。
- ASC 回读：2.1.4／build 65 状态 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`，文案回读一致。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight：build 65 挂到既有 Public Beta 组并提交 Beta App Review，外部构建状态 `WAITING_FOR_BETA_REVIEW`；[公共链接](https://testflight.apple.com/join/8z26MWWr) 不变，Beta 审核通过后外部用户才可安装。

## 收尾

- 镜像 `latest.json` 已为 2.1.4（定时同步自动完成，未手动触发），五种 daemon 指向源站缓存，macOS arm64 包返回 HTTP 200 且大小与 GitHub 一致（111,656,393 字节）。
- 本机三端（桌面 App、daemon、Pandaa iPhone）本次未从发布包重新安装；桌面 App 与 iPhone 此前已同步到同一提交的工作区构建，daemon 无功能变化。
- Issue 回访（2026-09-28）：#378 已回复请报告者在 2.1.4 上复测并附机型；#399 已回复根因为上游 CMP-8025，App 侧未修；#352 已回复请在当前版本复测触控板滚动。三单均保持 OPEN，等报告者反馈；#391 本版未处理。
