# 发布电脑端（cc-pocket daemon）

电脑端发布的**只有 daemon**（用户 Mac 上跑、连本地 `claude` CLI、外拨到你托管的 relay）。relay（`wss://pocket.ark-nexus.cc`）是你的服务，用户不部署。分发走 **Homebrew tap**，artifact **自带 JRE**（用户不用装 Java）并经 **Apple 公证**（双击零警告）。

用户最终体验：

```
brew install --cask heypandax/tap/cc-pocket
cc-pocket-daemon service-install --apply    # 开机自启、断线重连
cc-pocket-daemon pair                        # 出二维码 → 手机扫
```

---

## 一次性准备（你来做）

### 1. 加入 Apple Developer Program
`developer.apple.com/programs` → Enroll（$99/年）。个人账号审核通常很快；公司账号需要 D-U-N-S 号。**Developer ID 证书只有 Account Holder 能创建**（个人账号你本人就是）。

### 2. 拿 “Developer ID Application” 证书（二选一）

**方式 A · Xcode 自动（最省事）**
1. App Store 装 Xcode。
2. Xcode → Settings（⌘,）→ Accounts → 左下 `+` → 用 Apple ID 登录。
3. 选中你的 Team → `Manage Certificates…`。
4. 左下 `+` → 选 **Developer ID Application**。
5. Xcode 自动生成密钥对 + CSR + 把证书下载进**登录钥匙串**。完成。

**方式 B · 门户手动**
1. **生成 CSR**：打开 “钥匙串访问” → 菜单 `Certificate Assistant → Request a Certificate from a Certificate Authority` → 填邮箱 + 名字 → 选 `Saved to disk` 和 `Let me specify key pair information` → `2048 bit / RSA` → 存成 `.certSigningRequest`（**私钥会留在你的钥匙串**）。
2. `developer.apple.com/account` → Certificates, IDs & Profiles → Certificates → 左上 `+`。
3. 选 **Developer ID** → **Developer ID Application** → Continue。
4. 上传刚才的 CSR → Continue → **Download** 得到 `developerID_application.cer`。
5. 双击 `.cer` 装进登录钥匙串（自动和步骤 1 的私钥配对）。

### 3. 验证证书 + 拿 DEVELOPER_ID
```bash
security find-identity -v -p codesigning
```
应看到一行 `Developer ID Application: Your Name (TEAMXXXXXX)` —— **整串**就是 `DEVELOPER_ID`，括号里是 **Team ID**。

### 4. notarytool 公证凭据（存一次进钥匙串）
- **App 专用密码**：`appleid.apple.com` → 登录 → Sign-In and Security → App-Specific Passwords → 生成一个（如 `cc-pocket-notary`），记下（形如 `abcd-efgh-ijkl-mnop`）。
- 存凭据（二选一）：
  - **脚本**（推荐）：把 `APPLE_ID` / `APPLE_APP_PASSWORD` / `APPLE_TEAM_ID` 填进仓库根 `.env`，跑 `bash scripts/notary-setup.sh`，它用这三个值执行下面的 `store-credentials`（profile 名 `cc-pocket`）。
  - **手动**：
  ```bash
  xcrun notarytool store-credentials cc-pocket \
    --apple-id you@example.com \
    --team-id TEAMXXXXXX \
    --password abcd-efgh-ijkl-mnop
  ```

### 5. GitHub 仓库
- 主仓库 `heypandax/cc-pocket`（已存在）—— 发布页挂 artifact。
- tap 仓库 **`heypandax/homebrew-tap`**（已建）—— 放 `Casks/cc-pocket.rb`（用 `packaging/homebrew/Casks/cc-pocket.rb` 当模板）。tap 名即 `heypandax/tap`。artifact（`.tar.gz`）也上传到**这个 tap 仓库**的 GitHub Release（cask 的 `url` 指向它），不是主仓库。

> 证书私钥**只在你创建它的那台 Mac**。换机器：钥匙串访问里选中证书+私钥 → 导出为 `.p12`（带密码）→ 在新机导入。Developer ID 证书一个 Team 数量有限（一般 2 个），别乱删。

---

## 每次发布

1. **定版本**：更新 `daemon/build.gradle.kts` 的 `packageDaemon` 任务里 `--app-version`，以及 cask（`packaging/homebrew/Casks/cc-pocket.rb`）的 `version`。（移动端 app 是**独立版本线**——改 `mobile/composeApp/build.gradle.kts` 的 `versionName/versionCode` + `iosApp/iosApp/Info.plist`，随 App 发版单独走，不必和 daemon 对齐。）

2. **打包 + 签名 + 公证**（在 Apple Silicon Mac 上）：

   ```bash
   export DEVELOPER_ID="Developer ID Application: Your Name (TEAMID)"
   export NOTARY_PROFILE=cc-pocket
   scripts/release-macos.sh 1.1.0
   ```

   产出 `cc-pocket-daemon-1.1.0-macos-arm64.tar.gz` + 打印 sha256。

   **Intel 版**（可选，覆盖 x86 Mac 用户）：装一个 x86_64 的 JDK 17，然后在 Rosetta 下跑同一脚本：

   ```bash
   arch -x86_64 env JAVA_HOME=/path/to/x86_64-jdk-17 scripts/release-macos.sh 1.1.0
   ```

   产出 `…-macos-x86_64.tar.gz`。（先只发 arm64 也行，多数 Mac 已是 Apple Silicon。）

3. **建 GitHub Release** `v1.1.0`，把上面两个 tar.gz 作为附件上传。

4. **更新 cask**：把 `packaging/homebrew/Casks/cc-pocket.rb` 的 `version` + `url` + `sha256` 填好，提交到 `heypandax/homebrew-tap` 的 `Casks/cc-pocket.rb`。

5. **验收**：

   ```bash
   brew install --cask heypandax/tap/cc-pocket   # 干净机器上
   cc-pocket-daemon --help                      # 应正常输出（已公证，无 Gatekeeper 警告）
   ```

---

## 写进面向用户的 README

- **前置**：先装并登录 [Claude Code](https://claude.com/claude-code)（跑一次 `claude` 完成鉴权）。daemon 会自动找到系统的 `claude`。
- **装**：`brew install --cask heypandax/tap/cc-pocket`
- **跑 + 配对**：`cc-pocket-daemon service-install --apply` 然后 `cc-pocket-daemon pair`，手机 App 扫码。
- **卸载服务**：`launchctl unload ~/Library/LaunchAgents/dev.ccpocket.daemon.plist`

---

## 注意事项

- **claude 版本**：早先 2.1.169 的 headless 回归**已不复现**（实测 piped stdin/stdout 下输出完整的 stream-json：assistant + result，exit 0）。所以**发布版不再 pin** `--claude-bin`，daemon 自动用用户的 claude。若日后某个 claude 版本又坏，再在 daemon 里加版本检测告警。
- **默认 relay 已烤进 daemon**（`DEFAULT_RELAY`），`run` / `service-install` 不用传 `--relay`；本地 LAN 调试用 `run --local`。
- **双架构**：jpackage 打的是构建机的 arch，所以 arm64 / x86_64 要各打一份；cask 用 `depends_on arch: :arm64` 限制，要支持 Intel 再按 arch 给不同 `url`/`sha256`。
- **必须用 Cask 不用 Formula**：产物是预编译 + 已公证的二进制。Homebrew **Formula** 会强制跑「Command Line Tools 体检」（哪怕不编译，且常误报 CLT 过旧 → 装不上），**Cask** 是预编译通道、不碰 CLT。所以分发用 `Casks/cc-pocket.rb` + `brew install --cask`。
- **tap-trust 提示**：`heypandax/tap` 是第三方 tap，brew 会打一行 "not trusted" 警告（非阻塞）；Homebrew 6.0 后会要求 `brew trust`，到时文档补一句即可。
- **公证 vs 不公证**：叠加公证后**任何下载路径都零 Gatekeeper 警告**，最稳。
