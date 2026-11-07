# 2.6.3 发布与验收

## 来源与说明

- 产品标签 `v2.6.3` 固定到 `4f8349a77a05e38decf96df17cc5c012ca1a6923`，不移动已发布标签。
- [PR #418](https://github.com/heypandax/pairlet/pull/418)、[主线 CI](https://github.com/heypandax/pairlet/actions/runs/42412018432)通过。
- 用户要求将上一版本完整说明合并到本版，文案修正提交 `9560ee0c`；GitHub Release 已回读一致，iOS 使用相同提交的独立商店文案。变更仅涉及说明，不重新构建安装包。
- 更新说明见 [RELEASE-2.6.3.md](RELEASE-2.6.3.md)。不接入 OSS 下载，不上传安装包到 OSS。

## 已完成验证

- 本机完整测试 6,122 项，0 失败、0 错误、21 条件跳过。Windows cwd/控制台兼容和 MSI 品牌检查通过。
- 版本、仓库内容、品牌、Harmony 发布契约、商店文本及完整素材检查通过。素材检查在独立 Ubuntu 临时目录执行，包含 12 张 iPhone、12 张 iPad 截图及 2 个预览；未重新生成截图或声称进行了本版 UI 真机回放。
- [正式发布任务](https://github.com/heypandax/pairlet/actions/runs/42413525079)全部所请求平台成功，Harmony 按默认 opt-in 规则未构建。
- GitHub 27 个安装资产加 `SHA256SUMS`，27 个资产的 API digest 与清单一致；下载到本机的五种 daemon、四份桌面 DMG 和 Android APK 实际字节哈希一致。
- 五种 daemon 包内版本为 2.6.3。两种 macOS daemon 的代码签名及公证票据通过，四份桌面 DMG 公证票据通过。Android APK 2.6.3（45）签名验证通过。Windows/Linux 包内检查由 CI 执行，不等同于所有平台真机安装验收。
- Homebrew 两架构哈希由最终资产生成，tap 已更新并回读一致；Scoop 2.6.3 线上哈希与 Windows 包一致。
- GitHub 默认最新版已切至 2.6.3。更新清单延续当前配置，不把未配置的 Ed25519 签名说成已签署；平台原生签名单独验证。

## iOS 与镜像

- iOS 2.6.3（74）[构建任务](https://github.com/heypandax/pairlet/actions/runs/42413528530)的 `ios` job 成功，归档、导出、符号上传、生产 APNs entitlement 校验通过，2026-11-08 05:36（UTC+8）上传成功。为采用合并后的说明，上传成功后主动取消旧文案的下游提交，不是安装包构建失败。
- [新文案提审任务](https://github.com/heypandax/pairlet/actions/runs/42417376796)复用同一 build，文案与 `9560ee0c` 一致；等待逻辑提交 `7ed291f6`。截至 2026-11-08 05:49（UTC+8），Apple 查询返回 `NOT_VISIBLE`，尚未提审或上架。任务最多等待约 30 分钟，仅精确匹配的 build 为 `VALID` 时提交正式审核，并回读文案、构建与 `AFTER_APPROVAL` 发布方式，随后配置现有 TestFlight 外测组及 Beta 审核。若超时或处理失败，任务停止，不替换其他版本。此记录不把等待状态记成提审成功。
- 新等待逻辑已验证延迟出现并成功、处理失败、重复构建及超时四条路径；Ruby 语法检查通过。
- 美国备用镜像同步成功，HTTPS 回读 2.6.3、28 项资产。香港下载链路慢，更新清单已原子切到 2.6.3 的 28 个 GitHub 资产 URL 并经 HTTPS 回读；现有同步任务继续后台补齐本地文件，不能宣称香港本地镜像文件已全部同步完成。
- 本轮未部署 relay，未重启本机 daemon，未改变香港兼容入口状态。
