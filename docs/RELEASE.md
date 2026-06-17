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

---

# 发布 iOS App（App Store）

移动端 iOS app（`com.panda.ccpocket`）走 **App Store**，与 daemon 完全独立。**关键结论：不需要 App Store Connect API key / Issuer ID**——签名和上传都靠 Xcode 已登录账号的自动签名（cloud-managed distribution）。

## 前提（一次性）

- Apple Developer 账号，Team `SC9S2SJ42G`（个人账号 `pandaleecn@gmail.com`；`dev.ccpocket.app` 被旧账号占用，故 bundle id 改用 `com.panda.ccpocket`）。
- **Xcode 登录该账号**：Settings（⌘,）→ Accounts → 加 Apple ID。自动签名 + 上传都依赖这个会话。
- 自动签名已写进 `iosApp/project.yml`（`CODE_SIGN_STYLE: Automatic`、`DEVELOPMENT_TEAM: SC9S2SJ42G`）。
- 发布证书是 **Apple 云端托管**，**不会**出现在本机 `security find-identity -v -p codesigning` 里——看不到属正常，不代表缺证书。
- `build/ios/ExportOptions.plist` 已在仓库里：`method=app-store-connect`、`destination=upload`、`teamID=SC9S2SJ42G`、`uploadSymbols`。

> `.env` 里的 `APPLE_ID` / `APPLE_APP_PASSWORD` 是给 **daemon 公证**（notarytool）用的，与 App Store iOS 上传**无关**，别混。

## 每次发布

1. **定版本（两处保持 lockstep）**：
   - `iosApp/iosApp/Info.plist`：`CFBundleShortVersionString`（营销版本，如 `1.0`）+ `CFBundleVersion`（构建号）。
   - `mobile/composeApp/build.gradle.kts`：`versionName` / `versionCode` 与上面一致。
   - **构建号每次上传必须自增**（同一营销版本下唯一且递增）；如 `1.0(1)` 被拒后重传用 `1.0(2)`。

2. **归档（Release，自动签名）**：
   ```bash
   cd <repo 根>
   xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
     -destination 'generic/platform=iOS' \
     -archivePath build/ios/CCPocket.xcarchive \
     -allowProvisioningUpdates archive
   ```
   - archive 的 `Compile Kotlin Framework` build phase 会自动跑 `./gradlew :mobile:composeApp:embedAndSignAppleFrameworkForXcode`；Release 的 Kotlin/Native 编译较慢，**几分钟正常**。

3. **导出 + 直传 App Store Connect**：
   ```bash
   xcodebuild -exportArchive \
     -archivePath build/ios/CCPocket.xcarchive \
     -exportOptionsPlist build/ios/ExportOptions.plist \
     -exportPath build/ios/export \
     -allowProvisioningUpdates
   ```
   - 因为 ExportOptions 里 `destination=upload`，这条会**签名后直接上传**（不落 IPA 到磁盘，`build/ios/export/` 为空属正常）。
   - **成功标志**：日志出现 `Progress 100%: Upload succeeded.` + `** EXPORT SUCCEEDED **` + `Uploaded iosApp`。
   - `Upload Symbols Failed`（Firebase / Google 第三方框架缺 dSYM）是**非致命告警**，可忽略（只影响这些框架的崩溃符号化）。

4. **等 Apple 处理**：约 10–30 分钟，构建在 App Store Connect 从 “Processing” 变为可选。

5. **网页操作（无 API，只能手动）**：
   - 进对应 version → Build 区选中刚上传的构建。
   - 回答出口合规（Export Compliance）等问询。
   - **首次提交**：填完信息 → Submit for Review。
   - **被拒后重新提交**（如 Guideline 2.1a）：同一 version 选新构建 → **Resolution Center** 回复审核员 → 重新提交。Resolution Center 回复**没有 API，只能网页手动**。

## 注意事项 / 坑

- **不需要 issuer / API key**：`1.0(1)` 和 `1.0(2)` 都是 Xcode 账号自动签名 + `-allowProvisioningUpdates`。若哪天要做无人值守 CI，才需要 App Store Connect API key（`.p8` + Key ID + **Issuer ID** + key 角色 ≥ App Manager）。
- **本机看不到发布证书是正常的**（云端托管），别误判为缺证书去乱建。
- **CLI `tail` 吞退出码**：`xcodebuild ... | tail` 的退出码是 `tail` 的，会把失败误判成成功；要判结果就 `> log 2>&1` 后单独看 `$?` 或 grep `** EXPORT SUCCEEDED/FAILED **`。
- **伴侣 App 审核**：本 app 主功能需配对桌面 daemon，审核员进不去 → 触发 2.1a。已内置免配对 **Demo 模式**（配对页底部 “Try Demo”）供审核；详见提交时的 App Review Notes。
