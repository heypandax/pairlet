# Pairlet 改名兼容清单

本分支是本地候选，产品名为 **Pairlet**，系统中的设备名为 **CC Pairlet**。首次提示解释两者与 CC Pocket 的关系；关于页保留旧名。图标使用带 Pocket 标签的候选，微型图标保留无字版本。本文不是发布或设备验收回执。

基线：`6ca601737b1c82381c66ef5075d534c146844390`；2026-09-10 核实 origin/main 为 `c6bf15ab`，GitHub 最新稳定版为 v1.9.8。候选暂沿用 1.9.8 以便独立审阅品牌差异；正式升级试用前必须冻结高于线上版本的统一版本和平台构建号，不能用同版本覆盖证明升级通过。

## 必须保留的兼容项

| 对象与值 | 源码位置 | 验证方式 | 后续迁移 |
| --- | --- | --- | --- |
| Android `com.panda.ccpocket` | `mobile/composeApp/build.gradle.kts` | 清单检查；正式 APK 原签名覆盖安装待验收 | 本次禁止 |
| iOS `com.panda.ccpocket`、原 Team/entitlements | `iosApp/iosApp.xcodeproj/project.pbxproj`、`iosApp/iosApp/iosApp.entitlements` | 工程不改身份；签名与 iPhone/iPad 升级待验收 | 本次禁止 |
| macOS `dev.ccpocket.app` | `mobile/composeApp/build.gradle.kts` | jpackage Info.plist + codesign；正式双架构签名包待验收 | 本次禁止 |
| HarmonyOS `com.ccpocket.app`、原签名 | `harmony/AppScope/app.json5`、`scripts/release-harmony.sh` | ArkTS/HAP 编译与 release contract；签名升级待验收 | 本次禁止 |
| `CC Pocket.app`、`CC Pocket` / `CC Pocket.exe` | Gradle `packageName`，`scripts/update-local-desktop.sh`，`DesktopUpdater.kt` | 实际包结构、原路径与更新器源码；Dock/手动 DMG/快捷方式待验收 | 有独立迁移方案后才可变 |
| Windows UpgradeCode `{230D5F5E-4C7A-3DE9-98EE-6E492CCCB7D0}` | Gradle `windows.upgradeUuid`，`scripts/brand-windows-msi.ps1` | 从 v1.9.8 发布 MSI 读取并冻结；元数据变换前后比对保护表 | 本次禁止 |
| `~/.cc-pocket/identity.json` | `daemon/.../identity/Identity.kt` | 原文件冻结；真实升级前后公钥指纹待验收，不输出私钥 | 本次禁止 |
| `~/.cc-pocket-app/store.properties` | `mobile/.../secure/SecureStore.desktop.kt` | 路径冻结；测试使用 Gradle 独立 store | 本次禁止 |
| Android `cc-pocket` prefs，iOS standard NSUserDefaults | 各平台 `secure/SecureStore.*.kt` | 实现冻结；新提示只增加 `pairlet_brand_notice_v1` | 本次禁止 |
| HarmonyOS `ccpocket` prefs / 原 Vault 别名 | `harmony/.../store/Store.ets`、`Vault.ets` | 保留 prefs 名和所有旧 key；仅新增提示标记 | 本次禁止 |
| `dev.ccpocket.daemon`、`cc-pocket-daemon.service`、Windows `cc-pocket-daemon` 计划任务 | `daemon/.../service/ServiceInstaller.kt` | 整文件冻结，所有服务安装仍走旧入口 | 本次禁止 |
| 原日志、Firebase、Sentry 配置 | 各端配置与 telemetry 实现 | 沿用本分支已有配置；不导入其他任务未提交的 Sentry 接入 | 本次禁止 |
| `ccpocket-e2e-v1`、`ccpocket-e2e-keys-v1`、`ccpocket/daemon-auth/v1`、wire 方法名 | protocol E2E/Messages、daemon Identity、Harmony E2E | 冻结文件摘要 + 原协议测试，不改变认证/密钥派生 | 本次禁止 |
| `ccpocket://`、二维码和邀请字段 | `pairing/Pairing.kt`、`IncomingLink.kt`、`ShareInvites.kt` | 原生成/解析实现冻结及现有测试 | 本期不新增 pairlet:// |
| 用户保存的 relay、自托管 URL、设备密钥 | Pairing/SecureStore；Harmony Store | 不迁移、不覆盖；新旧组合实测待验收 | 禁止因改名重置 |
| `cc-pocket-daemon-*` 资产与包内 `cc-pocket-daemon[.app]` 布局 | `UpdateService.kt`、发布脚本 | 旧选择器冻结、manifest/SHA256 校验、正式包待验收 | 兼容副本永久按旧客户端需求保留 |
| `cc-pocket-desktop-macos-<arch>.dmg`、`cc-pocket-desktop-windows-x86_64.msi` | `DesktopUpdater.kt`、发布 workflows | 旧资产选择器冻结；本次不增加资产别名 | 以后别名必须同字节并列 checksum |
| `cc-pocket-daemon` 命令 | `scripts/install.sh`、`install.ps1` | 新 `pairlet-daemon` 只转发旧稳定 symlink/shim；服务仍用原命令 | 可增加别名，不可第二套服务 |
| 旧仓库、tap、bucket、Pages、relay、下载源 | README、release client、packaging、deploy | 当前保持线上入口；新 Caddy 配置单独候选 | 需部署后独立验证 |

`packaging/brand-compatibility.json` 冻结本次不能改动的具体实现文件，`scripts/check-brand-compatibility.py` 检查平台标识与实际 macOS 包。它是针对本次候选的保护门，不是所有旧字符串的白名单；文案仍需人工核对。

## macOS 打包决策

Gradle `nativeDistributions.packageName` 同时控制 `.app` 文件夹、启动器和 Windows 安装目录，保持 `CC Pocket`。新名通过英文和中文 `InfoPlist.strings` 提供，同时设置 `dockName` 与 AWT application name。Apple 要求未本地化的 CFBundleDisplayName 匹配磁盘文件名，Finder 才采用本地化名称；直接把该字段改成新名，实测仍显示旧名。候选保持两项基础名称为 CC Pocket，localized CFBundleName/CFBundleDisplayName 为 CC Pairlet。全新隔离路径的 FileManager.displayName 已返回 **CC Pairlet.app**；同路径缓存与实际升级后的 Spotlight/Dock 仍需验收。[Apple 名称本地化规则](https://developer.apple.com/library/archive/documentation/General/Reference/InfoPlistKeyReference/Articles/CoreFoundationKeys.html)

createDistributable 钩子只在私有 build 目录生成本地化资源，再用同一签名身份重封外层 bundle，保留 entitlements/requirements/flags/runtime，严格验证后供 DMG 使用；不修改嵌套运行时或已安装 App。正式 Developer ID 签名链仍待正式候选包验证。

旧更新器按当前 `.app` 路径复制新包。新 DMG 内仍为 `CC Pocket.app`，从源头避免用户手动拖入时产生默认不同路径的第二个安装。本机旧 App 是 ad-hoc 签名，没有 TeamIdentifier，不能作为正式 Developer ID 的升级起点。

## Windows 包调查和候选

SHA-256 已核实的 v1.9.8 MSI：`7e4b22f01626163f51dc758d9ff930d3869383bcf67781923b728220eaf8811e`，183205617 字节。ProductCode 为 `{3DA32B2E-8B7E-3DAA-9D94-0F61DB7AAEE0}`，ProductName 为 CC Pocket，Manufacturer 为 Unknown，ALLUSERS=1；原包没有 Shortcut 表。

候选保持 `packageName`，固定旧 UpgradeCode，启用系统快捷方式，再通过事务将 MSI ProductName 与快捷方式显示名改为 CC Pairlet。`Directory`、`Component`、`File`、`Registry`、`Upgrade`、`Media`、`MsiFileHash` 和身份属性不得改变；签名后禁止再执行变换。离线 msibuild 原型已证明仅改 ProductName 时这些表不变。PowerShell COM 构建钩子、新快捷方式及 Windows 真实 MSI 升级尚待 Windows runner/设备验证。

## 验收层级

编译、单元测试、静态兼容检查、包内结构、模拟器、真机升级、正式签名安装包和线上渠道分别记录。实际搜索输入为 `CC`、`CC Pairlet`、`Pairlet`，另记完整旧名 `CC Pocket`、`ccpocket`、`cc-pocket`。图标文字不能当搜索关键词或尺寸验收证据。

首次提示仅声明改名事实；在原配对和设置的设备升级验收完成前，不在产品中加入“升级已保证保留”的验收承诺。新安装在首次初始化时直接记为完成，老安装未关闭前保留提示，关闭后不再显示；旧数据不写回、不迁移。
