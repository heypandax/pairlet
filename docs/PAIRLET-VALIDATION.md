# Pairlet 本地候选验证记录

日期：2026-09-10。实施基线 `6ca601737b1c82381c66ef5075d534c146844390`，分支
`codex/pairlet-implementation`，原生 worktree `f880`。未导入主工作区正在进行的可观测性改动。

## 已完成的本地验证

| 项目 | 结果与证据边界 |
| --- | --- |
| 全量 JVM | `bash scripts/check-all.sh` 通过；protocol、daemon、relay、mobile desktopTest 全绿，daemon 有 1 项既有 skip。包括新增改名提示的 3 项状态测试。 |
| 支持服务 | Python 42 项通过；支持插件 Node 9 项通过。新旧手册 host/path allowlist 正反例通过。 |
| macOS arm64 | 最终 DMG 生成；包内仍为 `CC Pocket.app`，启动器不改；Foundation 显示名为 `CC Pairlet.app`。只读挂载后严格 codesign 校验通过、ICNS 与当前源字节相同。打包 JVM 冒烟通过。此包为 ad-hoc，不是 Developer ID/公证验证。 |
| Android | `assembleDebug` 通过；实际 APK 清单为 `com.panda.ccpocket`、`CC Pairlet`、1.9.8 / versionCode 30。debug 签名不用于覆盖正式安装。 |
| iOS | Xcode generic iOS 完整 Debug App 构建通过；产物 `cc-pocket.app` 保留原物理名，bundle ID `com.panda.ccpocket`、显示名 `CC Pairlet`，源版本 1.9.8 / build 19。关闭签名，只核对本机复制缓存中的锁定依赖；未安装到 iPhone/iPad。 |
| HarmonyOS | 独立 ArkTS 编译及 unsigned HAP 通过；发布源码契约检查通过。该契约检查不代表本次产物已签名或升级通过。 |
| Windows | 已下载线上 v1.9.8 MSI 并核对官方 SHA-256；读取并固定旧 UpgradeCode。离线 ProductName 原型变换前后 7 张保护表不变。新 PowerShell COM 钩子及新增快捷方式尚未在 Windows 执行。 |
| 兼容保护 | 15 个身份、数据、协议、配对、链接、服务及更新器文件摘要一致；平台 ID、旧命令/链接和 macOS 实际包检查通过。 |
| 官网/手册 | SEO 39 页、36 sitemap URL、全部本地链接通过；公开内容 29 个目标与 6 份 UI 素材 hash 通过。能力合同仍明确标记历史 v1.9.4 @ e9ee816f，未冒充本次业务验收。 |
| 商店素材 | 当前 Compose 场景生成 12 张 iPhone + 12 张 iPad 双语截图、两段 29.9 秒预览；10 份元数据及尺寸/编码校验通过。视觉抽查修复了截图画布的全大写旧字标。数据为脚本演示。 |
| 图标 | 原标签在 40–60 CSS px 偏小；选用放大标签 v2，本地 48/60/76 px 对照明显更易读。微型图标无字；浅深色尺寸页在 `assets/brand/preview.html`。浏览器采样不等于系统遮罩/设备验收。 |
| 新域路由 | 本地 Caddy v2.10.2 解析候选通过；随后已在香港 Caddy 2.11.4 校验并 reload。三个新域源站证书通过信任链检查，Cloudflare Full (Strict)；详见 [部署记录](PAIRLET-WEBSITE-DEPLOYMENT.md)。显式源 IP 覆盖保留，旧 relay 未重启。 |
| 外部迁移草案 | Homebrew rename map、Scoop 同一安装的命令别名、Pages 39 个已知路径跳转已准备。4 个 Pages 路径/query/hash 检查通过；Scoop 下载/更新/服务字段不变。均未应用到外部仓库。 |
| 运行环境 | 未启动或重启产品 daemon，结束前只读快照仍恰好 1 个。没有覆盖安装现用 App。 |

本机完整日志、渠道快照、旧 MSI、候选文件 SHA-256 与恢复记录在被忽略的
`docs/plans/pairlet-implementation/`，入口为 `PROGRESS.md`、`candidate-artifacts.json`。
外部草案的可版本化副本在 `packaging/pairlet-candidates/`。

## 仍须单独验收

候选沿用 1.9.8，方便审阅品牌差异；正式升级试用前应重新核实线上版本并递增统一版本及构建号。
尚未完成任何平台的原签名覆盖升级、升级前后身份/配对/偏好核对、未首次打开时的系统搜索与
旧 Dock/任务栏入口验证。macOS Intel、Windows runner 和各移动设备仍需各自验证。

网站 DNS、TLS、静态内容和支持问答已完成线上验证，详见网站部署记录。中国大陆网络、
Turnstile 挑战链及客户端新旧 relay 组合，当前 ASC 后台状态，
签名、公证、TestFlight/商店审核、资产清单切换、仓库及 tap/bucket 迁移均未完成。
本记录只关闭可本地实施与验证的候选工作，不宣称 P0 升级验收、正式发布或迁移完成。
