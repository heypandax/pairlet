# Pairlet 兼容入口复查

检查日期：2026-09-10。检查前源码为本地 main `e1c4f2a7`。检查覆盖安装/更新、发布脚本、服务与数据身份、URL 与配对、网站本地存储和支持系统；不代表新客户端已发布或完成真机覆盖升级。

## 本次修正

帮助中心的 `site/support/support.js:isTrustedSupportUrl` 与 `support/web/server.py:is_public_source_url` 只接受 `/heypandax/cc-pocket`。仓库已改为 Pairlet 后，新地址会被前端当成不可点击的普通文字，并从后端引用来源列表中排除。

已同时允许 `/heypandax/pairlet` 和 `/heypandax/cc-pocket`，包括各自的 issue、文件和 Release 子路径；仍限定 HTTPS、准确的 GitHub 主机/仓库边界，不放开其他仓库、相似名称、带凭据 URL 或其他端口。之前一条手册新旧域名用例误放在 `FakeResponse` 辅助类中，没有被 unittest 发现，现已移回实际测试类。

验证：support 测试 44 项通过；直接执行前端 URL 判断函数的 24 项新旧链接及相似地址检查通过；SEO 检查与 15 个兼容文件摘要检查通过。本次修正尚未部署到网站和支持服务器。

## 仍需安排的入口工作

| 项目 | 当前情况 | 建议处理 |
| --- | --- | --- |
| 环境变量 | 安装器和 daemon 仍读取 `CC_POCKET_*`，例如 MIRROR、AUTO_UPDATE、IDENTITY、CODEX_BIN、CACERTS；旧配置继续有效，尚无统一 `PAIRLET_*` 别名 | 如需统一新命名，应集中实现“新名优先、旧名兜底”，在安装器、共享 ReleaseClient、daemon 和关联身份存储中一致生效；不能只改单个读取点造成身份/配对存储分离 |
| Homebrew / Scoop 包名 | 现有配方源码会提供两个 CLI；公开包名仍为 `heypandax/tap/cc-pocket` 和 `cc-pocket-daemon`。`pairlet` Homebrew rename map 仍是候选 | 先发布双命令，再单独验收包名迁移、升级和卸载；避免注册第二套 daemon |
| 直接解压的发行包 | Gradle installDist 有转发器，官方安装脚本/包管理器也已处理；`packageDaemon` 的 jpackage 只取 `lib`，不会把 installDist 的额外 bin 脚本带入 Windows ZIP / Linux tarball | 若便携包也要支持 `pairlet`，需在打包时加入转发入口；必须执行原 `cc-pocket-daemon.exe` / launcher，保留进程名、更新资产和服务身份 |
| 原签名覆盖升级 | 源码身份保持和本地包检查通过；Windows 原生执行检查已接入 CI，商店/原签名/真实设备升级并未由这次复查完成 | 正式发布前验证同一个 App 覆盖升级、原配对与偏好、自动启动、快捷方式及新旧名称搜索 |

前两项中的旧入口目前仍有效；缺少新名字本身不等于老用户升级已损坏。

## 已保留的长期兼容项

- 配置与数据：`~/.cc-pocket`、`~/.cc-pocket-app`、安装根目录、设备身份、配对记录和各平台偏好存储。官网的 `ccp-theme`、`ccp-lang`、支持页 `ccp-support-session` 存储键也保持原值。不同网站 origin 的浏览器存储不会因域名改名自动迁移。
- 后台服务：macOS `dev.ccpocket.daemon`、Linux `cc-pocket-daemon.service`、Windows `cc-pocket-daemon` 计划任务与旧日志路径；进程识别和本机更新脚本继续匹配原程序。
- App 身份：Android/iOS 原 application/bundle ID、macOS `dev.ccpocket.app`、Windows UpgradeCode、Harmony bundle、`CC Pocket.app` 实际安装路径；可见名称与安装身份分开处理。
- 配对和协议：`ccpocket://` 各入口、`_ccpocket._tcp` Bonjour 服务、二维码/六位码格式、E2E/认证常量以及 wire 方法名保持原值。当前不生成 `pairlet://`，不让旧客户端收到无法识别的新协议链接。
- 更新与分发：`cc-pocket-daemon-*`、桌面端旧名称 DMG/MSI、APK/HAP 文件和包内结构保留；旧客户端的资产选择器及 SHA256SUMS 对照继续成立。发布任务没有发现按旧 `github.repository` 精确比较而禁用的新仓库任务。
- 支持插件：`cc-pocket-support` agent/session 前缀、`cc-pocket-support-guard` 插件 ID 及包名保留，避免已安装配置失去插件关联。新旧官网 origin 已进入支持 API 配置；仓库引用链接缺口见本次修正。

## 当前在线入口检查

- 旧 GitHub 仓库地址跟随跳转后为 Pairlet，HTTP 200；新旧 raw 安装脚本地址均为 HTTP 200。
- 旧 Pages 安装手册页面仍返回 HTTP 200，并包含指向 `pairlet.org/manual/zh/install-and-pair/` 的跳转文档。这是 HTML/JavaScript 跳转，不是 HTTP 308。
- `pocket.ark-nexus.cc/healthz` 与 `relay.pairlet.org/healthz` 均为 HTTP 200。健康检查不替代跨版本客户端的端到端配对。
- 新旧 `/dl/latest.json` 均为 HTTP 200，响应字节相同，版本 1.9.8、13 个资产；不是对所有大文件的重新下载验签。

本次只提交源码修正和复查记录；没有部署、改动包管理器仓库、启动发布流水线或重启当前 daemon。
