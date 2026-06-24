# cc-pocket 运行与测试

> 前置：JDK 17（本机在 `/opt/homebrew/opt/openjdk@17`）；`claude` CLI 已安装并登录。所有 `./gradlew` 命令在仓库根目录执行。
> `./gradlew` 已通过 `gradle.properties` 锁定 JDK，无需设 `JAVA_HOME`；下面用 installDist 启动器时若提示找不到 java，命令前加 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`。

---

## 1. 单元测试（最快，不依赖 claude）

```bash
./gradlew test
```

覆盖：协议序列化往返、`StreamParser`（stream-json 解析）、`PermissionBridge`（授权握手：ask → allow/deny/超时/auto）、`TranscriptScanner`（消息计数排除工具结果）、`Broker`（离线推送 + 配对码单次有效）。

---

## 2. M0 —— 本机驱动真实 claude（两个终端，最能看清核心）

先构建启动器：

```bash
./gradlew :daemon:installDist
D=daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon
```

**终端 1 —— 起 daemon**（本机 WS 在 `127.0.0.1:8765`）：

```bash
$D run --claude-bin ~/.local/bin/claude
```

**终端 2 —— 交互式 test-client**：

```bash
$D test-client
```

然后逐条输入（每条等上一条的回应再发下一条）：

```
dirs                                  # 列出有历史会话的目录
ls /Users/lidapeng/Desktop/Project/app/cc-pocket   # 列该目录下的会话（不拉起 claude）
open /Users/lidapeng/Desktop/Project/app/cc-pocket  # 新建会话；等出现 [live] convo=… session=…
say 用一句话说这个项目叫什么            # 看流式输出，结尾 [done] in/out token
cd /Users/lidapeng/Desktop/Project/app/cc-dashboard # 切目录：同一 convo、新 cwd（旧 claude 进程被回收）
say 你现在的工作目录 basename 是什么？  # 确认已在新目录
quit
```

恢复已有会话：把 `ls` 列出的某个 `sessionId` 接在 open 后面 —— `open <目录> <sessionId>`。
切权限模式：`mode <default|acceptEdits|plan|bypassPermissions>` —— daemon 会用新模式重启子进程（保留本会话「总是允许」规则）。
授权弹窗：默认权限模式下，若你的 claude 配置会对某个工具询问，test-client 会显示 `[ASK] …`，输入 `allow` 或 `deny` 即可（你这台机器对常见工具是放行的，所以多数命令不弹窗——授权回环由单元测试 `PermissionBridgeTest` 确定性覆盖）。
退出后 `pgrep -fl claude` 应看不到本 daemon 的子进程（干净杀树）。

**Codex 后端**：daemon 除了 Claude Code，现在也能驱动 OpenAI Codex。它会自动探测 PATH 上的 `codex` CLI；要指定路径就在 `run` 上加 `--codex-bin <path>`（`service-install` 会把它透传进常驻配置）。后端由**客户端**（手机或桌面）在新建会话时选——Claude 或 Codex——两者的远程逐步命令 / 文件批准用法完全一样。一个会话始终绑定一个后端。

---

## 3. M1 —— 经云端 relay（端到端加密 + 配对）

relay 鉴权 daemon（Ed25519 签名挑战）、按账户路由**不透明密文**；daemon 与设备之间 Noise 端到端加密，relay 只见密文。安全模型见 `docs/SECURITY.md`。

一键自检（脚本自带启动/清理）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/relay-smoke.sh        # 本地 relay 全流程
JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/relay-smoke-prod.sh   # 经 Cloudflare 的线上 relay
```

手动三步（本地 relay；线上把 `ws://127.0.0.1:9000` 换成 `wss://pocket.ark-nexus.cc` 并跳过终端 1）：

```bash
./gradlew :daemon:installDist :relay:installDist
D=daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon
R=relay/build/install/cc-pocket-relay/bin/cc-pocket-relay
```

**终端 1 —— relay**（`--in-memory` 免落盘，正式用 `--db <path>`）：

```bash
$R --port 9000 --in-memory
```

**终端 2 —— daemon（外拨连 relay）**：首次运行自生成身份 `~/.cc-pocket/identity.json`，打印 account id，并起本地配对回环。

```bash
$D run --relay ws://127.0.0.1:9000 --claude-bin ~/.local/bin/claude
```

**配对**：让正在运行的 daemon mint 一张一次性票据，打印 `ccpocket://pair?...` 链接（内含 relay 地址 / account / daemon E2E 公钥 / 票据）。

```bash
$D pair        # 另开一个终端；输出 ccpocket://pair?relay=...&acct=...&dpk=...&ticket=...
```

**终端 3 —— 设备 test-client（自带 redeem + E2E）**：从上面的链接里取 `dpk` 和 `ticket`。

```bash
$D test-client --relay ws://127.0.0.1:9000 --daemon-pub "<dpk>" --ticket "<ticket>"
```

之后 `dirs / open / say …` 一样用，流量走 device ↔ relay ↔ daemon、全程加密（relay 看不到内容）。
手机端：把 `ccpocket://pair?...` 链接粘进 App 的「Pair」框即可（见 `docs/ios-device.md`）。

---

## 4. M2 —— 桌面客户端（Compose）

需要图形界面（在你的 Mac 上直接跑）。先按 §2 起一个本机 daemon，再：

```bash
./gradlew :mobile:composeApp:run
```

窗口里把地址填 `ws://127.0.0.1:8765/v1/ws` → Connect → 点目录 → 点会话/新建 → 在 Chat 里发消息。
（这是 Desktop 目标；Android/iOS 目标需要先装 Android SDK / Xcode。）

**桌面客户端（给用户的另一种选择）**：除了手机 App，cc-pocket 也能作为桌面 App 运行——从 GitHub Release 下载 DMG（macOS）/ MSI（Windows）：

- macOS：<https://github.com/heypandax/cc-pocket/releases/latest/download/cc-pocket-desktop-macos-arm64.dmg>
- Windows：<https://github.com/heypandax/cc-pocket/releases/latest/download/cc-pocket-desktop-windows-x86_64.msi>

或像上面那样从源码 `./gradlew :mobile:composeApp:run`。它通过和手机端**同一套配对**连到**另一台**机器上的 daemon——桌面端没有摄像头，所以请输入 `cc-pocket-daemon pair` 打印的那 6 位配对码。

---

## 5. M3 —— 打包成自带运行时的 App

```bash
./gradlew :daemon:packageDaemon
open daemon/build/jpackage      # 里面是 cc-pocket-daemon.app（自带 JRE，无需系统 java）
# 直接用打好的二进制：
daemon/build/jpackage/cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon run --claude-bin ~/.local/bin/claude
```

生成后台常驻配置（默认只打印，加 `--apply` 才真正安装）：

```bash
$D service-install --exec "$PWD/daemon/build/jpackage/cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon" --claude-bin ~/.local/bin/claude
```

---

## 6. Android（模拟器或真机）

SDK 已装在 `/opt/homebrew/share/android-commandlinetools`，AVD `ccpocket`（android-35, arm64）已建好。

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# 1) 起一个带界面的模拟器（想看就别加 -no-window）
emulator -avd ccpocket &

# 2) 起本机 daemon（App 默认连 ws://10.0.2.2:8765 —— 模拟器访问宿主机的别名）
daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon run --claude-bin ~/.local/bin/claude

# 3) 构建并安装 APK 到运行中的模拟器/真机
./gradlew :mobile:composeApp:installDebug
#   或手动：./gradlew :mobile:composeApp:assembleDebug
#           adb install -r mobile/composeApp/build/outputs/apk/debug/composeApp-debug.apk

# 4) App 里点 Connect → 看到目录列表 → 点目录 → 选/建会话 → Chat 发消息
```

真机：用数据线连上、开 USB 调试，`adb devices` 能看到后同样 `installDebug`；真机连本机 daemon 要把 App 里的 URL 改成你电脑的局域网 IP（如 `ws://192.168.1.20:8765`），并让 daemon 监听 `0.0.0.0`（`run --host 0.0.0.0`）。
运行截图见 `docs/design/android/`。

---

## 一键自检（可选）

```bash
./gradlew test                      # 全部单元/契约测试
./gradlew build                     # 四个模块全量编译 + 测试
```
