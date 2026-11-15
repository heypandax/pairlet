# 2.6.4 发布与验收

截至 2026-11-15 12:31（UTC+8），**桌面、daemon、Android 及下载渠道已发布 2.6.4；iOS 2.6.4（75）正式提审成功，等待 App Store 审核，通过后自动发布；TestFlight 等待 Beta 审核。**

## 来源与修复

- [PR #422](https://github.com/heypandax/pairlet/pull/422) 已合入 main，修复 [#419](https://github.com/heypandax/pairlet/issues/419) 中数字键盘遮挡上下文窗口编辑按钮的问题。
- 产品标签 `v2.6.4` 固定到 `21308953ba93b5bd860dfa788eb15dc4ca4cd9b5`；发布记录和渠道模板的后续提交不移动产品标签。
- [分支 CI](https://github.com/heypandax/pairlet/actions/runs/43628944411) 与 [主线 CI](https://github.com/heypandax/pairlet/actions/runs/43630623164) 全部通过，主线通过后才创建标签并启动正式构建。
- [更新说明](RELEASE-2.6.4.md) 与 iOS 中英文商店说明仅包含本次修复。Android `versionCode` 为 46。

## 验证结果

- 本机完整测试 6,124 项，0 失败、0 错误、21 条件跳过。版本、品牌、仓库内容、Harmony 发布契约及商店文本检查通过。
- 完整商店素材检查通过：12 张 iPhone、12 张 iPad 截图及 2 个预览。检查在独立 Ubuntu 临时目录执行，未改变服务器服务。
- 最终 2.6.4 模拟器包通过 Midscene 验证：数字键盘出现时按钮可见、保存、重新编辑、收键盘保留草稿、取消保留旧值、清空恢复默认。4 份成功回放已实际播放核验；测试准备期间一次演示审批遮罩导致的失败已处理并重测，不记成首次全绿。
- 设备为 iPhone 17e / iOS 27.0 模拟器；不等同于反馈用户设备或全部平台的真机安装验收。
- [正式发布工作流](https://github.com/heypandax/pairlet/actions/runs/43632251502) 第二次尝试成功，包含全部默认平台及两架构 Linux AppImage。Harmony 按默认 opt-in 规则未构建。
- GitHub 27 个安装资产加 `SHA256SUMS`；27 个 API digest 与清单一致，下载到本机的五种 daemon、四份 DMG 和 Android APK 共 10 个安装包实际字节哈希一致。前次已成功的 Windows/Linux/Android 17 个资产在重试后保持原哈希。
- 五种 daemon 包内版本均为 2.6.4。两份 macOS daemon 的代码签名及公证票据、四份 DMG 的公证票据均通过；Android 2.6.4（46）的 APK 签名验证通过。Windows/Linux 桌面包由 CI 检查，不声称已在每个平台实际安装。

## 下载与包管理器

- [GitHub Release](https://github.com/heypandax/pairlet/releases/tag/v2.6.4) 已设为 latest，构建中的临时说明已移除。
- Homebrew 两架构哈希由最终包和校验清单生成，tap 已更新并回读一致；Scoop 2.6.4 的版本、下载 URL 和哈希与最终 Windows 包一致。相同模板回写本仓。
- 主下载镜像及美国备用镜像均已发布 2.6.4。先传输并在服务器校验五种 daemon 包，再由既有同步服务生成清单；两端同步服务均成功退出。
- 两端 HTTPS 回读版本 2.6.4、完整 28 项资产映射及相同 `SHA256SUMS`；五份 daemon 在两端共 10 次 HTTP Range 下载均返回 206，字节与本机已验证文件一致。备用入口验证固定到该机 IP 并保留原域 TLS 校验。
- 更新清单延续当前未配置 Ed25519 发布密钥的状态，不把 `sign-manifest` 任务成功说成清单已签署。平台原生签名和公证单独验证。
- 本轮未部署 relay，未执行本机 daemon 更新或重启，也未改变 DNS。

## iOS 发布过程

- [iOS 工作流](https://github.com/heypandax/pairlet/actions/runs/43632255411) 固定版本 2.6.4、build 75。首次归档被 `PLA Update available` 阻止；macOS 公证也因缺少有效协议返回 HTTP 403。用户确认接受新协议后重试，macOS 公证已成功。
- 第二次 iOS 尝试遇到开发证书数量上限。[只读检查](https://github.com/heypandax/pairlet/actions/runs/43671885384) 列出 10 张 CI 创建的开发证书；[维护任务](https://github.com/heypandax/pairlet/actions/runs/43671960930) 指定并仅撤销最早到期的一张 `QQ7P963AJ5`，回读 `revoke ...: ok`。没有清理本机命名的开发证书、发行证书或 Developer ID。
- 第三次尝试复用相同源码与 build 75，归档、符号上传、发行 IPA 导出及生产 APNs 权限验证均通过，2026-11-15 12:16（UTC+8）上传成功，12:18 处理为 VALID。前两次没有上传 IPA，因而没有重复上传已存在的构建。
- 正式提审在添加 review submission item 时收到 `Provider name mismatch. - Your developer name does not match MIIT's records.`。当时 ASC 页面回读版本为 `Prepare for Submission`，已绑定 build 75，设置为审核通过后自动发布。App Information 显示正确的已备案 App 号，并提供 `Confirm Information`，要求确认当前开发者是备案主体或关联方；用户随后明确告知已完成 Confirm。该阻塞不属于人工审核拒绝，处理入口见[提交流程阻塞记录](../../APP-STORE-REJECTIONS.md#已发生的上传--提交流程阻塞)。
- [独立 TestFlight 工作流](https://github.com/heypandax/pairlet/actions/runs/43676403419) 已成功：复用 build 75、绑定 `Public Beta`、同步中英测试说明并提交 Beta 审核，回读 `WAITING_FOR_REVIEW` / `WAITING_FOR_BETA_REVIEW`。[公测链接](https://testflight.apple.com/join/8z26MWWr) 保持不变，尚不声称 2.6.4 已可供外部安装。
- 备案确认后，[复用已有构建的提审任务](https://github.com/heypandax/pairlet/actions/runs/43676973018) 全部成功，未重建或重复上传 IPA。12:30 正式回读 `WAITING_FOR_REVIEW`、`AFTER_APPROVAL`、绑定 build 75；`en-US` 与 `zh-Hans` 各 9 个文本字段及审核备注全部与标签源码一致。12:31 再次确认 TestFlight 已提交，外测状态仍为 `WAITING_FOR_BETA_REVIEW`。正式审核、Beta 审核均待 Apple 处理，尚未宣称 iOS 新版已上架或可外部安装。
- [#419 原回复](https://github.com/heypandax/pairlet/issues/419#issuecomment-6882534768) 已更新修复、模拟器测试和审核进度，Issue 保持打开，等待用户设备复测。

原始日志、哈希回执、模拟器截图及回放放在独立发布工作区的忽略目录 `_local/release-2.6.4/`，不进入版本库。原工作区其他未提交修改未混入本次发布。
