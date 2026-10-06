# 2.5.0 发布与验收记录

状态：[2.5.0 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.5.0)，Android、macOS／Windows／Linux 桌面端和五种 daemon 安装包齐备，已设为 latest。Homebrew、Scoop 与国内镜像已更新。iOS 2.5.0（70）已正式提交 App Store，状态为 `WAITING_FOR_REVIEW`，审核通过后手动发布；生产 relay 部署待确认，HarmonyOS 不在本次范围。

## 本次内容与复查修复

- 长会话按字节预算先取最近内容，首屏不足时自动补页；历史图片先显示预览，打开时按需获取原图。
- 放宽慢网络的静默判断，避免重复打开会话；直连拨号增加总预算与资格检查，回到局域网后在空闲时切回直连。
- 会话顶部新增「+」入口；修复 iOS 跟随系统外观、Android 深浅色切换重建界面，以及审批／提问通知被其他在线设备抑制的问题。
- Codex 模型目录使用缓存并后台刷新，避免内置旧目录覆盖新型号。
- 发版前修复历史补页与实时后缀合并导致的重复；原图下载期间禁止空闲回切；模型目录 RPC 完成、超时或取消后清理整个子进程树。
- 截图测试切回 Swing EDT，消除渲染线程问题；Linux CI 暴露的模型目录 EOF 测试竞态通过调整假服务退出时机修复，生产逻辑未因此修改。
- 语音输入 v2 仅包含 daemon／协议 M1，手机端校对自动发送尚未开放；Dot 任务接入仍暂缓，不作为本版已交付功能。

## 发布来源与验证

- `v2.5.0` 指向 `f1cb885453f70c14cb6c5335d8867fca8eca8163`，版本锁步为 2.5.0；Android versionCode 41，iOS 源码 build 28（实际发布 build 由工作流 run number 决定）。
- 本地分项测试共 5,826 项：5,808 通过、18 跳过，覆盖 observability、Sentry、protocol、relay、daemon 和 mobile desktop。截图线程与两个受外加超时影响的安全测试分别修正或按原超时配置复验通过。
- [发布提交主 CI](https://github.com/heypandax/pairlet/actions/runs/37492887019)全部成功，包含测试、桌面编译、Android debug APK 构建和 Windows 检查。
- [正式资产工作流](https://github.com/heypandax/pairlet/actions/runs/37495784681)从上述标签构建，一次通过全部请求的平台任务及 checksums、sign-manifest、bump-scoop；HarmonyOS 按默认配置跳过。
- 版本锁步、改名兼容、仓库内容和 App Store 素材检查通过；商店检查覆盖 18 个文本字段、24 张截图和 2 个预览。

## 安装包与更新渠道

- 共 28 个发布资产：27 个安装包／固定名称别名及 `SHA256SUMS`。全部安装包哈希在构建时记录、GitHub 资产 digest 与最终校验清单之间一致，清单无缺项或多余条目。
- 五种 daemon 压缩包均已下载，SHA-256 与 GitHub 资产 digest 一致，包内版本属性均为 2.5.0。
- macOS arm64／x86_64 daemon 均通过 `codesign --verify --deep --strict` 与 `stapler validate`。本机 Gatekeeper 策略已关闭，故本次不将 `spctl` 结果视为严格系统策略验收。
- 更新清单签名沿用仓库的未配置状态；`sign-manifest` 明确报告未配置密钥，本版不带签名清单。这与已完成的 macOS 应用签名／公证是不同机制。
- [Homebrew tap 更新](https://github.com/heypandax/homebrew-tap/commit/e1c7fc024b1d8ab8c4db3dd9f1c89ba7d0c298d8)回读与仓库模板逐字一致；Scoop bucket 由发布工作流更新，版本、URL、hash 及其余字段均已回读核对。
- GitHub `releases/latest` 回读为 `v2.5.0`；公开的 APK、Pairlet macOS arm64 DMG、Windows MSI 与 `SHA256SUMS` 的 latest 下载链接均返回 HTTP 200。
- Homebrew 最终 SHA-256：arm64 `f8091e02a72cc90564a61a804397c0e3f8fcaf4f35474b5ab880f49a63220705`，x86_64 `787dc37fa30375c9f5a00c52e3210e25763e8d57e8ebcf65b661ea463e60e9d6`。
- Windows daemon SHA-256：`7d01ec955847ed98c35acdf21c78ac4a485c5f89e2b5f9fed0f381130ad33099`。

## 本机、iOS 与生产服务

- 本机 daemon 已通过官方更新脚本更新到 2.5.0；验收时单实例运行且 relay socket 为 1。
- iOS arm64／模拟器 arm64 Kotlin 编译与新鲜的 arm64 模拟器 Debug 构建通过。全新安装实测隐私同意、免电脑 Demo 入口、示例会话及继承项目的新会话入口；空输入 Send 禁用。配对页的 Demo 入口也已验证。
- 按实测结果修正 App Review notes 中过时的 Demo 按钮文案与路径；iOS 正式提审进度见下文。
- relay 发行目录已构建且测试通过，但线上仍运行上一版，不包含本次推送瞬时失败重试。部署会重启在线服务，待本次部署范围确认。
- 镜像同步脚本已更新并保留远端旧脚本备份；未重启 relay。正式发布后启动现有 systemd 同步任务，完整同步后原子切换。首次记录时公网仍为 2.4.0；随后在 iOS 提审期间回读已为 2.5.0，包含全部 28 个资产，五种 daemon 镜像下载链接均返回 HTTP 200，镜像 `SHA256SUMS` 与 GitHub 正式发布逐字节一致。

## iOS 正式提审

- 用户于 2026-10-06 确认将新版本提交 App Store 审核。本次设为审核通过后手动发布，不额外分发 TestFlight 外测。
- [商店素材同步](https://github.com/heypandax/pairlet/actions/runs/37535821760)在 ASC 版本保护检查允许创建后建立 2.5.0 草稿，没有重命名或撤回其他版本。中英各 9 个文本字段、审核备注、24 张 iPhone／iPad 截图和 2 个已处理预览均回读通过，发布方式为 `MANUAL`。
- 官网、支持页与隐私政策公开链接均返回 HTTP 200。归档前按既有维护流程仅清理一张最旧的 CI 临时开发证书（[维护运行](https://github.com/heypandax/pairlet/actions/runs/37535966809)），发行签名证书与本机开发证书未动。
- [iOS 构建与提审](https://github.com/heypandax/pairlet/actions/runs/37536064634)固定从 `v2.5.0`／`f1cb8854` 执行，版本 2.5.0、build 70。Release 归档、发行签名导出、Sentry 配置与符号检查通过；归档和导出 IPA 的 APNs 签名／描述文件权限分别验证为 development 与 production。上传回执为 `UPLOAD SUCCEEDED with no errors`，ASC 随后将本次构建处理为 `VALID`。
- 本次导出 IPA 的 SHA-256：`f3fdc89105db8a2768c8e91afa3fe2015eb742ad936addb73fa2c5c19de3e680`。
- 提交前实时回读年龄分级 `Messaging and Chat = Yes`。2026-10-06 22:34 UTC 提交成功，最终回读为 `Version 2.5.0: WAITING_FOR_REVIEW; MANUAL`；关联 build 70、中英各 9 个文本字段和审核备注全部一致。工作流成功，TestFlight 分发任务按本次范围跳过。等待审核不代表已经获批或上架。

## 尚未验证

Android／Windows 真机安装、旧版 App 与新 daemon 的真实设备互通、实际弱网下长会话首屏耗时，以及本版 iOS 在真实手机上的逐项验收。已完成的 CI 签名验证与模拟器 Debug 验证不能替代这些检查。
