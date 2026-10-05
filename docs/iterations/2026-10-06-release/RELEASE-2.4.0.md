# 2.4.0 发布与验收记录

状态：[2.4.0 已公开发布](https://github.com/heypandax/pairlet/releases/tag/v2.4.0)，Android、macOS／Windows／Linux 桌面端及五种 daemon 资产齐备并为 latest；Homebrew tap 与 Scoop bucket 已升到 2.4.0；relay 与官网已部署。iOS 已提交审核，尚未获批（见「iOS」）；HarmonyOS 不在本次范围。

## 本次内容

自 2.3.0 起 main 上的改动，同一 tag 发出：

- 移除：评审请求、会话交接与协作者链接、文件夹分享（访客）、明文局域网模式 `run --local`、桌面端未接线的面板与若干死代码（案例见[调研后不做的需求案例集](../../DECLINED-REQUIREMENTS.md) D3–D5）。协议类型保留；旧端发来的已下线请求得到空列表或 `unsupported`；遗留的协作者／访客凭据启动时失效并向 relay 吊销。
- 配对安全第 0 阶段：交互式票据本地限时 130 秒，`pairlet pair` 等待结果并显示指纹，新增 `pairlet devices` 列出与吊销，App「关于」显示本机与电脑指纹，回环旧路由要求本地控制令牌，relay 只为交互式配对登记 6 位码。主人吊销的设备落盘，daemon 重启后 relay 重放也不能写回。
- daemon 会话生命周期：会话级锁、进程世代隔离、注册表同一会话只开一次；Kimi／DSH 共用 ACP 客户端，所选模型与权限模式真正下发。
- 新增 `pairlet config --direct-connect local|lan|off`：服务方式运行的 daemon 也能对局域网开放 E2E 直连（默认仍只监听本机）。
- 语音听写：超时改为中性等待并采用迟到结果，写出后才计时，重试与原请求先到先用；daemon 对同一段录音共享一次转写。
- App 保留最近离开的 4 个会话的回放内容（仅内存），重开时先显示并只取增量。
- 审批卡出现后 400 ms 内不接受点击；图片压缩期间拦住发送；一批 `tightCenter` 对齐修复。
- relay：出站积压按连接与全站设上限、握手超时、限流按路由拆分、推送限频等审计修复。
- 更新包 Ed25519 验签已实现，受信公钥列表为空，线上行为与 2.3.0 相同。
- 首窗字节预算的取窗机制与打开会话耗时日志已合入，预算未对任何客户端启用（手机端首屏不满一屏时不会向上翻页，需先改客户端）。

## 发版前复查与修复

两份独立复查（安全、协议兼容）在发版前完成，协议线上形状无破坏性变更。随本版修复：

- 非交互（headless）票据在其受限 intent 过期后仍可被弹出并把设备锚进全权限名单（2.3.0 已存在）——现在只有交互式票据可以锚定全权限设备。
- relay 单连接出站上限 16 MiB 会切断大文件分块下载——调到 72 MiB，总量仍由 96 MiB 全站预算约束。
- 直连门不查吊销墓碑、`prefs.json` 被运行中的 daemon 整体写回、一处关于二维码配对的错误注释。

留到后续：6 位配对码全站预算可被少量来源占满、直连监听在认证前计入「局域网在线」、bridge 吊销在 relay 断线时未落盘、启用更新包签名、分块下载按接收方节流。

## 发布来源与检查

- 版本锁步 2.4.0（daemon fallback、Android 2.4.0/versionCode 40、桌面包与 seed、iOS 2.4.0 (27)、Harmony、Homebrew 模板）。
- 本地 `check-all.sh` 在发布提交 `9aeba875` 上全绿。
- 主 CI 前两轮失败，均为本周期新增、此前从未在 Linux 上跑过的 relay 测试自身的平台假设：`OutboundBudgetTest`（Linux 回环缓冲吸收了积压，无人被断开）与 `WsRateLimitTest`（被拒连接的关闭先于客户端发送到达）。修正测试后 [主 CI](https://github.com/heypandax/pairlet/actions/runs/37352278584) 三个 job 全部 success。
- 不可变标签 `v2.4.0` 指向 `6a4509dd`；Release 以 `gh release create --verify-tag --generate-notes` 建立。
- [正式资产构建](https://github.com/heypandax/pairlet/actions/runs/37354759554)从标签执行。首轮 Windows 桌面（Maven Central 403）与 macOS Intel 旧名安装包（Apple 公证请求超时）失败，重跑失败任务后全部 success，随后 checksums 与 bump-scoop 完成；HarmonyOS job 按默认配置跳过。

## 生产与安装包

- 28 个发布资产；[SHA256SUMS](https://github.com/heypandax/pairlet/releases/download/v2.4.0/SHA256SUMS) 已附。两份 macOS daemon：arm64 `52f0ad74…`、x86_64 `b20a8ca5…`，与 GitHub 资产 digest 一致。
- `releases/latest` 回读 `v2.4.0`；`releases/latest/download/` 下 APK、`pairlet-desktop-macos-arm64.dmg`、Windows MSI 与 SHA256SUMS 直链可访问。
- [Homebrew 更新](https://github.com/heypandax/homebrew-tap/commit/3bcb30ad25245dd3e10f1639c67058c4f9bee4ba)回读与仓库模板逐字一致；Scoop bucket 由流水线升到 2.4.0（Windows zip `9ef1d899…`，与 SHA256SUMS 一致），仓库模板已对齐。
- relay：本版有 relay 改动，已用 `RELAY_SSH_KEY=1 bash scripts/redeploy-relay.sh` 部署到香港源站（服务器已拒绝密码登录，脚本新增密钥登录开关），服务与公网健康检查通过，本机 daemon 随即重新连上。部署的是 `9aeba875` 的构建，与标签相比只差测试与脚本。
- 官网：`git archive HEAD:site` 部署到 `website-20261005-6a4509dd`（66 个文件，远端清单哈希与本地一致后切换），公网回读中英文首页、功能页、安全页、`llms.txt` 与一张产品图与仓库逐字节一致；上一版 `website-20261004-6f508399` 保留用于回退。

## iOS

- 归档前证书上限处理：干跑列出 10 张 CI 创建的开发证书，只吊销最旧一张 `956M5LDJ3M`（[运行 37354989246](https://github.com/heypandax/pairlet/actions/runs/37354989246)）。
- [iOS 发布](https://github.com/heypandax/pairlet/actions/runs/37354766571)：一次通过。build 69 挂到版本 2.4.0 并提交审核。
- ASC 回读：Version 2.4.0 `WAITING_FOR_REVIEW`、发布方式 `AFTER_APPROVAL`、文本与仓库一致（"text verified"）。尚未获批，不能写成已上架。What's New 写明了移除的功能。
- TestFlight：build 69 挂到既有 Public Beta 组，Beta App Review 已提交，外部构建状态 `WAITING_FOR_BETA_REVIEW`。

## 收尾

- 镜像：截至本记录提交时 `pocket.ark-nexus.cc/dl/latest.json` 仍为 2.3.0，按以往经验约 1–2 小时后由定时任务同步。
- 本机三端：Pandaa iPhone 与桌面 App 已装发布提交的工作区构建；本机 daemon 在本记录提交后用更新脚本更新。
- 未验证：Android 与 Windows 真机安装；旧版 App（2.3.0）对新 daemon、新 relay 的互通只做了静态核对，没有真机验证；2.4.0 的 App 没有在手机上逐项点验；长会话重开的实际耗时未测。
