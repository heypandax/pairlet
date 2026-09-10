# Pairlet 名称与过渡期约定

2026-09-10 用户确认：当前先叫 **CC Pairlet** 做过渡。最终品牌名不等于立即简化每个用户入口。

| 位置 | 当前名称或文案 |
| --- | --- |
| 最终品牌、域名、源码仓库 | Pairlet、pairlet.org、heypandax/pairlet |
| App Store 中英文名称 | **CC Pairlet** |
| 安装后的设备显示名、系统 App 入口 | **CC Pairlet** |
| 中文商店副标题 | AI 编程伴侣 |
| 英文商店副标题 | Coding agents, within reach |
| 中文主张 | 随时接续你的 AI 编程任务 |
| 英文完整表达 | Your coding agents, within reach. |
| 描述及改名说明 | CC Pairlet（原名 CC Pocket）；英文 formerly CC Pocket |
| 图标过渡标签 | 保留原主体，主图标带 Pocket 标签 |
| CLI | pairlet；旧 cc-pocket-daemon 继续可用 |

副标题按商店 30 字符限制独立配置，不能用旧商店的「随身编程遥控 / AI Code Remote」覆盖原来的 AI 编程伴侣定位。以后移除商店或设备名称中的 CC，须由用户另行确定；移除图标 Pocket 标签是另一个决定。

`scripts/check-brand-compatibility.py` 要求商店名称与 iOS 设备显示名保持一致，防止只修正文案时丢失过渡名称。

## 安装后搜索的当前证据

| 平台 | 当前实现 | 尚未证明的部分 |
| --- | --- | --- |
| iOS / iPadOS | CFBundleDisplayName 为 CC Pairlet，Bundle ID 沿用 com.panda.ccpocket | 没有旧名的 Core Spotlight 索引或搜索别名实现；完整输入 CC Pocket 能否命中新名称，尚未经过原签名覆盖升级实测 |
| macOS | 原 CC Pocket.app 文件路径不变，本地化显示为 CC Pairlet.app；Foundation displayName 检查通过 | 真实升级后的 Spotlight 旧名搜索及缓存行为尚未验收 |
| Windows | 安装目录与启动器保留原名，MSI 产品和快捷方式显示 CC Pairlet；MSI 检查通过 | 开始菜单完整旧名搜索尚未实测 |
| Android / HarmonyOS | 设备显示 CC Pairlet，包名与安装身份保留 | 各系统启动器完整旧名搜索尚未实测 |

因此，当前不能承诺「安装后搜索 CC Pocket 一定出现 CC Pairlet」。App Store 的 CC Pocket 关键词只管理商店搜索；图标上的 Pocket 文字也不是系统搜索关键词。后续分别记录全新安装、覆盖升级后首次启动前、首次启动后的结果，避免用其中一种结果代替另一种。

来源及其他升级边界见 [兼容约定](PAIRLET-COMPATIBILITY.md) 与 [候选验收记录](PAIRLET-VALIDATION.md)。
