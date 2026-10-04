# Pairlet 发布准备与回退

官网已于 2026-09-10 部署到香港源站，并启用 pairlet.org；详见 [网站部署记录](PAIRLET-WEBSITE-DEPLOYMENT.md)。仓库已改为 heypandax/pairlet，旧仓库 URL 保留跳转，旧 Pages 已提供对应路径的跳转文档。客户端仍为本地候选，改名客户端未提交商店，包管理器命令/包名迁移未发布。首个正式 Pairlet 版本按用户决定为 **2.0**，源码与发行标签使用 **2.0.0 / v2.0.0**；尚未创建标签或发布。Android versionCode 已递增为 31；iOS 正式构建号仍由发布工作流的 run number 生成；Harmony 采用发布脚本映射的 2000000。正式发布前重新核实各渠道构建号与升级路径。兼容身份见 [兼容清单](PAIRLET-COMPATIBILITY.md)，已完成检查与验收限制见 [验证记录](PAIRLET-VALIDATION.md)；后续入口复查与待补项见 [兼容入口复查](PAIRLET-COMPATIBILITY-AUDIT.md)。

## 渠道基线（2026-09-10）

| 渠道 | 读取结果 | 限制 |
| --- | --- | --- |
| GitHub Release | v1.9.8，2026-09-09 19:12:21 UTC，13 个资产 | 不是所有渠道完成证据 |
| 下载镜像 | `/dl/latest.json` HTTP 200，v1.9.8 | 各大包实际 SHA-256 仍需逐项下载核实 |
| Homebrew heypandax/tap/cc-pocket | 1.9.7 | 不擅自同步为 GitHub 版本 |
| Scoop heypandax/scoop-bucket | 1.9.8 | manifest 查询不替代 Windows 安装 |
| 中国 App Store 公开查询 | 1.9.7，原 bundle ID 与旧商店名 | 当前公开状态；不能以 CI 成功覆盖 |
| iOS v1.9.8 流水线 34393847882 | build 55，日志显示 App Review 已提交、Beta 等待审核 | 这是 9 月 9 日日志，不是当前 ASC 后台查询；后台状态待核实 |
| Pages | 实际站点来自 heypandax/heypandax.github.io，main `/`，HTTPS 开启 | cc-pocket 的 site/ 修改不会自动更新实际站点 |
| pairlet.org | 已通过 Dia 配置三个 A 记录；香港 Caddy + Cloudflare Full (Strict)，官网 HTTPS 200、www 308、新 relay healthz 200 | 香港、美国与本机验证；中国大陆网络和客户端新旧 relay 组合仍待验收 |
| 旧 relay | `/healthz` HTTP 200 | 不是新旧端到端配对验收 |

## 已部署网站与路由

- `site/` 已部署到香港独立版本目录：根页面中文优先，`/en/` 英文；`/zh/` 保留路径、query、hash 的跳转页。旧手册 `/manual/zh/` 与 `/manual/en/` 及每篇 slug 不变。
- canonical、sitemap、robots、manifest、手册生成源与支持 AI 索引使用 pairlet.org。旧 repo/安装文件/CLI URL 继续服务下载与更新。
- `deploy/Caddyfile` 已承接并部署新域站点；旧域配置块保持原样。`Caddyfile.pairlet-candidate` 保留为部署前草案，运行配置以 `Caddyfile` 为准。新官网使用 `/var/www/pairlet-site/current`，relay.pairlet.org 指向同一个 relay，www 308 保留完整 URI。服务器旧配置备份位置见部署记录。
- 新站支持页使用同源 `/support-api`；运行服务已加入新域 origin/hostname，保留旧域和 Pages。新域浏览器问答已收到回答。现有服务未配置 Turnstile 密钥，保持原有禁用策略；没有宣称挑战链验收。支持机器人仍使用既有知识库，可能返回旧品牌及旧手册链接。
- 旧 pocket.ark-nexus.cc 保留 WS、配对、API、下载；不能整站重定向。客户端默认 relay/镜像暂不切换。
- 旧 `heypandax.github.io/cc-pocket/<path>` 已通过实际 Pages 仓库的对应变更提供到新站的 HTML 跳转；仓库改名本身不会自动提供 Pages 跳转。

## 发布前必须完成

1. 冻结更高版本，构建目标平台原签名候选；Windows 执行 MSI 品牌钩子及旧版→候选升级，macOS 验证两种安装方式、两个架构和 Dock；iPhone/iPad/Android/Harmony 原安装升级。
2. 升级后尚未打开时验证原快捷入口与新旧词搜索；首次提示关闭后不再出现；身份指纹、配对、偏好、自托管地址保持不变。
3. 审阅已重新生成的真实 Compose 截图、中英预览及图标 v2，完成设备实际尺寸检查。素材来自脚本演示数据，尺寸检查不等于本次 UI 设备验收。
4. 官网、下载入口、支持问答和 TLS 已部署验证；中国大陆网络、新旧 relay 地址组合及旧二维码/六位码验证通过后才考虑新默认地址。
5. 所有旧命名资产齐全，SHA256SUMS 覆盖每一个实际下载文件。镜像清单在全量文件验证后原子切换，不在试用期间更新稳定 latest.json。
6. App Store 在原记录管理 `name.txt=CC Pairlet`，过渡期名称见 [命名决定](PAIRLET-NAMING.md)；不创建新 App。2.0.0 元数据草稿已同步，待人工确认，未上传或挂接构建、未提交审核。iPhone/iPad 双语素材同时检查，发布按实际审核状态标记。
7. 按 `packaging/pairlet-candidates/` 审阅 Homebrew/Scoop 草案；Pages 跳转由 `scripts/build-legacy-pages-redirects.py` 生成。仓库改名独立验收；旧命令始终转发原服务，不发布两套并行安装。发布前不执行外部迁移。

## 观测模块合入复查（2026-09-11）

main 已合入观测模块及发行配置跟进（PR #369、#370）；`2f2dba8b` 的 CI 已通过。官网配置保护仍在部署入口，不能把旧分支的 Caddyfile 覆盖到生产。

- 预演补齐各组件 staging Sentry 配置、真实包回读与 iOS 本地符号校验；需在所有待合模块结束后冻结同一 SHA，重新跑 `release-preview.yml`。正式版本仍为 v1.9.8，预演不更新用户的 daemon。
- 本地 2.0 中英更新说明和审核备注补充采集开关、复制诊断编号与 App/daemon 独立控制；官网隐私说明补充 Sentry 数据去向。新增文案尚未同步到 App Store，需先对照人工修改再同步。已同步的旧草稿回执为 [34515999937](https://github.com/heypandax/pairlet/actions/runs/34515999937)，不能将其当作新文案回执。
- 后续按 [最新观测验收](archive/observability-2026-09/ACCEPTANCE.md) 处理两种会话超时、手机 GA4 后台回执、iOS 真实错误源码行号等剩余项。Sentry 自动 fatal 尚未迁移，继续保留 Crashlytics；不作为本次更新的已完成功能宣传。
- 2.0 正式候选仍需原签名旧版升级、旧名称搜索及新旧命令安装渠道验收。网站源文件、App Store 草稿和实际安装包分别记录同步状态。

## 过渡名结束后的迁移安排（2026-10-03）

用户决定：名称统一为 Pairlet（见 [命名决定](PAIRLET-NAMING.md)），其余旧标识分三类处理。本节是源码与流水线状态，不是发布回执；双包流水线尚未在 CI 带签名跑过。

**不迁移（永久保留）**：launchd / systemd / 计划任务的服务 ID、日志与数据目录（`~/.cc-pocket` 等）、Bundle ID、协议常量、Windows 升级码、Scoop 包名 `cc-pocket-daemon`。用户基本看不到，迁移要么丢配对身份，要么制造第二个 daemon。

**先加新名、旧名长期保留**：

| 项 | 现状 | 下一步 |
| --- | --- | --- |
| `pairlet://` 链接 | App 各入口已同时接受两种 scheme，iOS / Android 已注册；daemon 仍输出 `ccpocket://` | 接受新 scheme 的 App 版本铺开后，daemon 改为输出 `pairlet://`；旧链接永久可解析 |
| 官网仓库链接 | 已改为 `heypandax/pairlet`，2026-10-04 已部署（见[网站部署记录](PAIRLET-WEBSITE-DEPLOYMENT.md)） | macOS 下载链接已随 2.3.0 切到 `pairlet-desktop-*`（2026-10-04） |
| relay 域名 | `relay.pairlet.org` 已指向同一个 relay，客户端默认值未切 | 中国大陆网络与新旧地址组合验证通过后再切默认；已配对设备保存的地址不改写 |
| Homebrew 包名 | 草稿在 `packaging/pairlet-candidates/` | 发版时填入真实版本与哈希，用 tap 的 rename map 把 `cc-pocket` 改为 `pairlet`，并实测旧安装升级与卸载 |

**macOS 桌面端：新旧两个包并行发布**

- `release.yml` 的 `macos-desktop` 任务每个架构构建两个镜像：`legacy` → `CC Pocket.app`，资产 `cc-pocket-desktop-*-macos-<arch>.dmg`；`pairlet` → `Pairlet.app`，资产 `pairlet-desktop-*-macos-<arch>.dmg`（`-PdesktopPackageName=Pairlet`）。Bundle ID、数据目录、签名身份相同。
- 已有安装继续从旧资产原地自更新，不搬路径；`Pairlet.app` 的更新器按自身包名选择 `pairlet-desktop-*` 资产。
- 官网与 README 的下载链接在**首个包含 `pairlet-desktop-*` 资产的版本发布之后**再切换，提前切会 404。
- 同一台 Mac 上两个包并存时共用数据，不会丢配对；`Pairlet.app` 首次启动会提示可以删除旧的 `CC Pocket.app`。
- 观测：比较每个版本两类资产的 `download_count`（`gh api repos/heypandax/pairlet/releases`）。自更新也会下载对应资产，所以它同时反映存量。经镜像下载的不计入，只能看趋势。桌面埋点加维度要改 relay 的校验白名单并重新部署，本次没有做。
- 退出条件：旧资产下载占比降到很低后，给旧包发最后一版并提示下载新版，之后停止构建 `legacy`。
- Windows 与 Linux 保持单包：Windows 两个 MSI 共用升级码，安装新包会替换旧包，不存在并存；显示名已是 Pairlet。

## 回退

新网站有问题只回退站点与新域配置，旧 relay/下载不动。客户端有升级问题暂停分发并以更高版本修复，不重置应用身份。已发版本/资产不覆盖。包管理器迁移失败保持旧入口，不恢复第二个 daemon 服务。
