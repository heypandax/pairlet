# 2.6.4 发布与验收

记录时间：2026-11-15 03:42（UTC+8）。**发布尚未完成，等待 Apple 账号持有人确认新版开发者协议。**

## 来源与修复

- [PR #422](https://github.com/heypandax/pairlet/pull/422) 已合入 main，修复 [#419](https://github.com/heypandax/pairlet/issues/419) 中数字键盘遮挡上下文窗口编辑按钮的问题。
- 产品标签 `v2.6.4` 固定到 `21308953ba93b5bd860dfa788eb15dc4ca4cd9b5`；标签不随发布记录更新而移动。
- [分支 CI](https://github.com/heypandax/pairlet/actions/runs/43628944411) 与 [主线 CI](https://github.com/heypandax/pairlet/actions/runs/43630623164) 全部通过，主线通过后才创建标签并启动正式构建。
- [更新说明](RELEASE-2.6.4.md) 与 iOS 中英文商店说明仅包含本次修复。Android `versionCode` 为 46。

## 验证结果

- 本机完整测试 6,124 项，0 失败、0 错误、21 条件跳过。版本、品牌、仓库内容、Harmony 发布契约及商店文本检查通过。
- 完整商店素材检查通过：12 张 iPhone、12 张 iPad 截图及 2 个预览。检查在独立 Ubuntu 临时目录执行，未改变服务器服务。
- 最终 2.6.4 模拟器包通过 Midscene 验证：数字键盘出现时按钮可见、保存、重新编辑、收键盘保留草稿、取消保留旧值、清空恢复默认。4 份成功回放已实际播放核验；测试准备期间一次演示审批遮罩导致的失败已处理并重测，不记成首次全绿。
- 设备为 iPhone 17e / iOS 27.0 模拟器；不等同于反馈用户设备或全部平台的真机安装验收。
- [正式构建](https://github.com/heypandax/pairlet/actions/runs/43632251502) 中 Windows daemon/桌面、Linux 双架构 daemon/桌面及 Android 均成功，含两架构 Linux AppImage。Harmony 按默认 opt-in 规则未构建。
- 已上传的 17 个安装资产，其 GitHub API digest 全部与 CI 构建时哈希一致。下载的 Windows/Linux 三份 daemon 和 Android APK 实际字节哈希一致；三份 daemon 包内版本均为 2.6.4，Android 2.6.4（46）的 APK 签名验证通过。Windows/Linux 桌面包由 CI 检查，不声称已在每个平台实际安装。

## Apple 阻塞与渠道状态

- 两个 macOS daemon 任务及四个桌面 DMG 任务均在公证时返回 HTTP 403：`A required agreement is missing or has expired`。没有发布未公证的 macOS 包。
- [iOS 构建任务](https://github.com/heypandax/pairlet/actions/runs/43632255411) 对应 2.6.4（75）。Xcode 在归档前的签名准备阶段报 `PLA Update available`，明确要求团队 Account Holder 接受最新 Program License Agreement；后续缺少 provisioning profile 是该阻塞下的报错。没有上传 build 75、提交 App Store 审核或配置本次 TestFlight 外测。
- 这次失败不涉及应用代码修正，也不需要删除证书或重新配对。需要账号持有人登录 [Apple Developer](https://developer.apple.com/account/) 阅读并接受协议后再继续。
- [2.6.4 Release](https://github.com/heypandax/pairlet/releases/tag/v2.6.4) 首部已标注发布未完成，且未设为 latest；GitHub 最新版仍为 2.6.3。统一 `SHA256SUMS`、更新清单及 Homebrew/Scoop 推进被完整构建门禁阻止，本次没有发布缺包的自动更新版本。
- [#419 回复](https://github.com/heypandax/pairlet/issues/419#issuecomment-6882534768) 已更新修复、模拟器验证和 iOS 尚不可安装的状态，Issue 保持打开待用户复测。
- 本轮未部署 relay，未重启本机 daemon，未改变下载镜像服务或 DNS。两处镜像同步定时器只读检查正常。

## 协议确认后的接续

在账号持有人确认协议已接受后，复用固定标签与同一次运行，只重跑失败及其依赖任务，保留已成功平台的安装包：

```bash
gh run rerun 43632251502 --repo heypandax/pairlet --failed
gh run rerun 43632255411 --repo heypandax/pairlet --failed
```

build 75 尚未上传，因此可复用；若重试后已上传而仅下游提审失败，应按发布手册复用该 build。所有平台完成后继续核验最终校验清单、macOS 签名与公证、Homebrew/Scoop、镜像及 iOS 商店/TestFlight 回读，再切换 latest 并更新本记录和 Issue。审核提交、待审核、可安装与正式上架分别记录。

原始日志、哈希回执、模拟器截图及回放放在本次独立发布工作区的忽略目录 `_local/release-2.6.4/`，不进入版本库。原工作区其他未提交修改未混入本次发布。
