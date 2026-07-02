# CLAUDE.md — cc-pocket

手机 App 通过零知识 E2E relay 驱动本机 Claude Code 的伴侣工具。组成：`mobile/`（Compose Multiplatform App）、`daemon/`（本机 Kotlin/JVM 守护进程）、`relay/`（云端 Ktor 中转，源站地址在 `.env` 的 `RELAY_HOST`，Cloudflare 前置 `pocket.ark-nexus.cc`）、`protocol/`（共享 wire 协议）。

## ⚠️ 本机 daemon 操作铁律（最重要，先读这一段）

**症状**：手机连不上 / 卡死 / 状态乱跳 / 会话疯狂 fork。**根因几乎总是「同时跑了两个 daemon」**——它们抢同一个 relay 账号 + 端口 8799，互相 `kill -9`，谁都稳不住。

### 改完 daemon 代码要更新本机 daemon —— 只用这一条命令

```bash
cd ~/Desktop/Project/app/cc-pocket
bash scripts/update-local-daemon.sh
```

它幂等地：构建 `installDist` → 装到可执行位置 `~/Library/Application Support/cc-pocket/` → **杀干净所有现存 daemon + 清 8799** → `service-install` 注册单实例 → 校验「进程数=1 且 relay-socket≥1」，不达标就报错退出。

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
pgrep -f 'cc-pocket-daemon run|Application Support/cc-pocket.*MainKt|build/install/cc-pocket.*MainKt' | wc -l
# 谁真的连上了 relay（应有 1 条 :443 ESTABLISHED）
for p in $(pgrep -f 'cc-pocket|MainKt'); do lsof -nP -p $p 2>/dev/null | grep ':443.*ESTABLISHED' && echo "  ^pid $p"; done
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

> 更细的历史踩坑（daemon 三/四类冲突、relay 容量、fake-IP 代理等）见 Claude 记忆 `cc-pocket-daemon-service-collisions`。
