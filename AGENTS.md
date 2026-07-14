# AGENTS.md — cc-pocket

手机 App 通过零知识 E2E relay 驱动本机 Codex 的伴侣工具。组成：`mobile/`（Compose Multiplatform App）、`daemon/`（本机 Kotlin/JVM 守护进程）、`relay/`（云端 Ktor 中转，源站地址在 `.env` 的 `RELAY_HOST`，Cloudflare 前置 `pocket.ark-nexus.cc`）、`protocol/`（共享 wire 协议）。

## ⚠️ 本机 daemon 操作铁律（最重要，先读这一段）

**症状**：手机连不上 / 卡死 / 状态乱跳 / 会话疯狂 fork。**根因几乎总是「同时跑了两个 daemon」**——它们抢同一个 relay 账号 + 端口 8799，互相 `kill -9`，谁都稳不住。

### 改完 daemon 代码要更新本机 daemon —— 只用这一条命令

```bash
cd ~/Desktop/Project/app/cc-pocket
bash scripts/update-local-daemon.sh
```

它幂等地：构建 `installDist` → 装到可执行位置 `~/Library/Application Support/cc-pocket/` → **杀干净所有现存 daemon + 清 8799** → `service-install` 注册单实例 → 校验「进程数=1 且 relay-socket≥1」，不达标就报错退出。

**在 cc-pocket 驱动的 Codex 会话里（手机/桌面 App 开的会话）不要直接跑上面这条**——bootout 会连坐杀掉会话本身（exit 137）。改用：

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

## 构建速记

- 本机需 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`（keg-only，不在 PATH）。
- 验证移动端编译：`JAVA_HOME=... ./gradlew :mobile:composeApp:compileKotlinDesktop`。
- 三套测试一把跑：`bash scripts/check-all.sh`（protocol + daemon + mobile）。
- 装机到 Pandaa iPhone：`bash scripts/install-pandaa.sh`（generic 构建 → 新鲜度校验 → devicectl 安装拉起）。
- **升级 Codex CLI 后**跑 `python3 scripts/probe-Codex-wire.py`——回归 daemon 依赖的三条 stream-json 行为（中途消息排队/注入、AskUserQuestion answers 形状），漂移会让排队与提问卡静默变坏。

> 更细的历史踩坑（daemon 三/四类冲突、relay 容量、fake-IP 代理等）见 Codex 记忆 `cc-pocket-daemon-service-collisions`。
