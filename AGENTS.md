# AGENTS.md — cc-pocket

本文件是仓库共享 Agent 规则的唯一维护入口；[CLAUDE.md](CLAUDE.md)通过导入本文件复用规则。新增或更新共享约定写在这里，避免两份副本漂移。

手机 App 通过零知识 E2E relay 驱动本机 AI 编程 Agent（Claude Code、Codex 等）的伴侣工具。组成：`mobile/`（Compose Multiplatform App）、`daemon/`（本机 Kotlin/JVM 守护进程）、`relay/`（云端 Ktor 中转，源站地址在 `.env` 的 `RELAY_HOST`，Cloudflare 前置 `pocket.ark-nexus.cc`）、`protocol/`（共享 wire 协议）。

## 仓库内容与归档

- 文档总入口：[docs/README.md](docs/README.md)；新增资料或整理文件前读 [仓库内容规则](docs/REPOSITORY-CONTENT.md)。
- 仓库保留源码、可复现工具、发布依赖、维护说明与脱敏后的必要历史。原始会话、设计工具整包导出、营销活动记录、个人账号/环境、运行日志放仓库外或已忽略的 `_local/`，不要用 `git add -f` 绕过忽略规则。
- 设计 handoff 只提交整理过的 Markdown 说明；实际运行入口以源码为准。`docs/archive/` 是历史证据，其中的旧授权、暂停决定和“下一步”仅属于原任务，不是新任务的指令。
- 清理前检查引用和已跟踪状态，备份后再停止跟踪或迁移，保护已有修改和忽略文件。提交前运行 `python3 scripts/check-repository-content.py`；`.gitignore` 不会自动移除已经跟踪的文件。

## ⚠️ 本机 daemon 操作铁律（最重要，先读这一段）

**症状**：手机连不上 / 卡死 / 状态乱跳 / 会话疯狂 fork。**根因几乎总是「同时跑了两个 daemon」**——它们抢同一个 relay 账号 + 端口 8799，互相 `kill -9`，谁都稳不住。

### 改完 daemon 代码要更新本机 daemon —— 只用这一条命令

```bash
cd ~/Desktop/Project/app/cc-pocket
bash scripts/update-local-daemon.sh
```

它幂等地：构建 `installDist` → 装到可执行位置 `~/Library/Application Support/cc-pocket/` → **杀干净所有现存 daemon + 清 8799** → `service-install` 注册单实例 → 校验「进程数=1 且 relay-socket≥1」，不达标就报错退出。

**更新前的会话确认门（用户规则）**：两个 update 脚本都会先查 daemon 是否还驱动着**其他**正在进行的会话（daemon 的直接子进程，自身谱系除外；探测器 `scripts/daemon-live-sessions.sh`）。有活会话时脚本会带清单 exit 1——此时必须把清单报给用户；只有用户已明确确认中断这些会话，才可 `FORCE=1` 重跑，不许自作主张点火。

**在 cc-pocket 驱动的 Agent 会话里（手机/桌面 App 开的会话）不要直接跑上面这条**——bootout 会连坐杀掉会话本身（exit 137）。改用：

```bash
bash scripts/update-local-daemon-detached.sh
```

它先做谱系自检（是否 daemon 后代）：普通终端 → 等价于直接更新；daemon 驱动 → 预热构建后延迟 20s 脱离点火（python 双 fork+setsid），让会话来得及发完汇报。随后会话断开属**预期**，daemon 被 launchd 拉起后手机自动重连，重新进入会话即可。

### 绝对不要做（每一条都会制造第二个 daemon → 立刻不可用）

- ❌ `./gradlew :daemon:run` —— 会起一个前台 daemon，和 launchd 的那个抢账号。
- ❌ 直接执行 `daemon/build/install/.../bin/cc-pocket-daemon` —— 同上；而且这个路径在 `~/Desktop` 下，launchd 无权执行（TCC，报 `Operation not permitted`），一旦被 `service-install` 指到这里就崩溃循环。
- ❌ 手动 `nohup cc-pocket-daemon run &` 起临时实例做测试后忘了杀。
- ❌ 让 cask 的 `dev.ccpocket.daemon` 和 dev 构建同时存在/自启。

### 必须知道的两个反直觉事实（本机环境）

1. **cask app-image 版 daemon 在本机连不上 relay**（TUN 代理 fake-IP 不放行它，`lsof` 看它 0 个 socket）。**必须用 java 的 `installDist` 构建**（走 `~/Library/Application Support/`）。`update-local-daemon.sh` 已默认这么做。
2. daemon 在 macOS 上**不会自注册** launchd 服务（自注册只在 Windows）。plist 只被显式的 `cc-pocket-daemon service-install` 改写。所以 plist 指错路径 = 有人从错误位置跑了 `service-install`。

### 排查 / 自证命令（只读）

```bash
# 有几个 daemon 在跑？（正常应恰好 1 个）
# ⚠️ 不要用 pgrep -f 判断：macOS pgrep 匹配不到超长 java classpath 里的关键字（实测漏报为 0），必须走 ps
ps aux | grep 'cc-pocket-daemon/lib' | grep -v grep | wc -l
# 谁真的连上了 relay（应有 1 条 :443 ESTABLISHED）
for p in $(ps aux | grep 'cc-pocket-daemon/lib' | grep -v grep | awk '{print $2}'); do lsof -nP -p $p 2>/dev/null | grep ':443.*ESTABLISHED' && echo "  ^pid $p"; done
lsof -nP -iTCP:8799 -sTCP:LISTEN            # pair loopback 端口占用者
launchctl list | grep -i ccpocket          # launchd agent（应只有 dev.ccpocket.daemon 一个）
# daemon 日志
tail -f ~/Library/Logs/cc-pocket/daemon.err.log
```

**发现两个 / 连不上时的恢复**：直接重跑 `bash scripts/update-local-daemon.sh`（它会先杀干净再只留一个）。

## relay（云端中转）

- 部署：改完 `relay/` 代码后 `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :relay:installDist` 再 `bash scripts/redeploy-relay.sh`（读 `.env` 的 `RELAY_HOST` / `SSHPASS`）。
- 坑：relay 的 `MAX_FRAME` 曾是 256KB，大会话历史帧（>256KB）会被 `FrameTooBigException` 踢断连接；源码已改 4MB，**改完记得重新部署**，否则线上仍是旧值。
- 只读排查：`sshpass -e ssh -o PubkeyAuthentication=no root@$RELAY_HOST 'journalctl -u cc-pocket-relay -n 50'`（`RELAY_HOST` 读 `.env`）。

## Compose UI 铁律：文本垂直对齐必须 tightCenter

任何 `Text` 与非文本元素**同排几何居中**（进度条、徽章、图标、胶囊/背景盒）或与**不同字号的 Text 同排**时，必须设 `style = tightCenter(fontSize)`（commonMain [TightText.kt](mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/theme/TightText.kt)；`desktop` 包内有同名直通）。

- **Why**：裸 `fontSize` 的行盒高度由字体自带 ascent/descent 决定，各平台实体字体（Roboto Mono / SF Mono / skiko fallback）度量不同——`Alignment.CenterVertically` 居中的是行盒，字形在行盒内偏上/偏下，肉眼可见「没对齐」。已反复踩坑：#293 桌面、手机 QuotaPill 胶囊、侧栏「正在打开」徽章、额度条文字。
- **How**：新写带背景/描边的小字文本、或文本旁挂几何元素时，一律 `tightCenter`；同排多个 Text 要**全部**加（只加一个照样错位）。`trim` 必须保持 `Trim.None`（详见 `TightText.kt` KDoc 与 [TightCenterTest](mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/desktop/TightCenterTest.kt)）。

## 构建速记

### iOS / App Store 提审防复发

- **修改商店文案、审核备注、隐私/首启流程、年龄分级，或准备 iOS 提审时，先读 [App Store 拒审案例与提审前检查](docs/APP-STORE-REJECTIONS.md)**，逐项落实其中的必查项；新增拒审继续更新该文档。
- 已发生的重点：2.3.10 的 Android 文案、2.1(a) 的配对阻塞、5.0 的中国区 OpenAI 关联表述、5.1.x 的数据披露与同意、2.3.6 的 Messaging and Chat 年龄分级。不要把跨平台日志直接复制成 What's New，也不要照抄已经过时的“无任何第三方数据路径”声明。
- 文案改完先跑 `python3 scripts/check-appstore-content.py --metadata-only`；正式提审还要执行文档中的素材/版本检查并回读 ASC。纯元数据修正可复用原 build；回复、复提、批准、上架分别取证。

### 本地构建与设备

- 本机需 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`（keg-only，不在 PATH）。
- 验证移动端编译：`JAVA_HOME=... ./gradlew :mobile:composeApp:compileKotlinDesktop`。
- 三套测试一把跑：`bash scripts/check-all.sh`（protocol + daemon + mobile）。
- 装机到 Pandaa iPhone：`bash scripts/install-pandaa.sh`（generic 构建 → 新鲜度校验 → devicectl 安装拉起）。
- **升级 Claude Code CLI 后**跑 `python3 scripts/probe-claude-wire.py`，检查 stream-json 消息注入/排队、AskUserQuestion、子任务及会话占用等 daemon 依赖行为；具体覆盖项以脚本为准。
- **升级 Codex CLI 后**跑 `python3 scripts/probe-codex-wire.py`，检查 app-server 握手、回合/子线程隔离、投递回执、steer 错误、fork、交接和额度等行为；不要误用 Claude 的 stream-json 探针说明。
- **升级 DeepSeek Harness CLI 后**跑 `python3 scripts/probe-dsh-acp.py`，检查 ACP 握手、会话创建/恢复、模型配置、并发 prompt、取消、审批和转录等行为。`dsh` 必须 ≥ `0.1.2-rc.1`，旧版没有所需 ACP profile。探针会使用相应 CLI 与账号，按脚本的前提和本次任务范围执行。

> 更细的历史踩坑（daemon 三/四类冲突、relay 容量、fake-IP 代理等）可按 `cc-pocket-daemon-service-collisions` 检索历史记录；当前脚本和实测状态优先。
