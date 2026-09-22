# 2.1.2 发布与验收记录

状态：[2.1.2 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.1.2)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并设为 latest。iOS 见下方「iOS」小节；HarmonyOS 不在本次流水线内。

## 本次内容

六项修复在 2026-09-22 集成验收后进入同一提交：#397 用户／Agent 消息区分、#394 compact 摘要默认折叠、#396 compact 后占用刷新、#392 文件名恢复与完整路径、#393 文件查看器工具提示、#381 Windows 下载进度显示部分；另包含 iOS APNs 签名权限修正与推送诊断、daemon 长下载超时和 Linux 桌面打包修复。

## 发布来源与检查

- 发布提交 `6dcaf0983a8f6592008d6ab49d5c2716a882378f`，不可变标签 `v2.1.2` 指向同一提交。
- 本地完整回归 4,737 项：4,734 项通过、3 项按平台条件跳过；此前 4 项 UI 基线失败（栏标题大小写匹配）已修正测试。
- [主 CI](https://github.com/heypandax/pairlet/actions/runs/35762179758) 通过：服务端测试、桌面与 Android 编译及 Windows 检查。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/35763907714) 从标签执行，13 个平台 job 与 checksums、bump-scoop 全部通过；HarmonyOS job 按默认配置跳过。
- [商店素材同步](https://github.com/heypandax/pairlet/actions/runs/35762183980) 通过：创建 2.1.2 商店版本，iPhone／iPad 截图与预览替换并回读一致，中英文更新说明与审核备注已同步；未上传二进制、未提审。

## 生产与安装包

- 24 个发布资产：五种 daemon、双架构 macOS DMG、Windows MSI、Android APK、双架构 Linux deb／rpm／AppImage 及无版本号别名；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.1.2/SHA256SUMS) 由流水线生成。两份 macOS daemon 在本机下载后复算 SHA-256，与清单一致。
- Release 于 2026-09-22 18:28 UTC 公开并设为 latest；`releases/latest/download/` 下 APK、macOS DMG、Windows MSI 与 macOS daemon 直链均返回 HTTP 200。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/f814a18f2a57847e45b49365e1ab55e4b45bf8e9)（arm64 `e754be28…`、x86_64 `23ab8645…`）与 Scoop 更新（流水线 bump-scoop，`2.1.2`／`d7d327e8…`）已回读；仓库模板同步更新。
- 镜像同步服务由定时器自动完成：公开 `latest.json` 为 2.1.2，五种 daemon 均指向源站缓存，macOS arm64 包返回 HTTP 200 且大小与 GitHub 一致。本机 SSH 密码登录被拒，未手动触发，不影响结果。

## iOS

- 第一次 [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/35764503117) 在归档阶段失败：Apple 账号开发证书达到上限，云端签名无法新建证书。按 `asc-maintenance` 既定流程只吊销一张 CI 创建的孤儿开发证书（`V5X2N7ZA97`，[运行](https://github.com/heypandax/pairlet/actions/runs/35765708297)），未触及分发与 Developer ID 证书。
- 第二次 [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/35765755979)：归档、Sentry 配置与 dSYM 检查、开发／生产 APNs 权限检查、IPA 导出与上传全部通过，build 62。
- ASC 回读：2.1.2／build 62 已绑定并提交审核，状态 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`，中英文文案与审核备注回读一致。尚未获 App Store 审核批准，不能写成已上架。
- TestFlight：build 62 挂到既有 Public Beta 组并提交 Beta App Review，回读 `WAITING_FOR_REVIEW`，外部构建状态 `IN_BETA_TESTING`；[公共链接](https://testflight.apple.com/join/8z26MWWr) 不变，Beta 审核通过后外部用户才可安装。
