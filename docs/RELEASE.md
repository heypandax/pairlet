# Pairlet 发布手册

## 首个改名版本

设备显示名为 **CC Pairlet**，应用内名称为 **Pairlet**。原 `CC Pocket.app`、启动器、
`cc-pocket-*` 发布资产、CLI、应用身份与服务名继续保留，详见
[改名兼容清单](PAIRLET-COMPATIBILITY.md) 和 [候选状态及发布顺序](PAIRLET-ROLLOUT.md)。
首个改名版本定为 **2.0.0**，版本和平台构建号已提高；正式发布等待其他模块合并与验收。

macOS `createDistributable` 自动写入名称本地化并沿用配置的签名身份重封外层 bundle，
随后 `packageDmg` 使用该已校验的 image。Windows `packageMsi` 自动运行
`scripts/brand-windows-msi.ps1`，固定旧 UpgradeCode，仅改变产品与快捷方式显示名。
所有钩子必须在签名、checksum 和上传之前成功；失败留下的 MSI 不得发布。
源与包兼容门禁不能替代原签名升级、系统搜索、Dock/快捷方式或图标实际尺寸验收。

## 只预演，不正式发布

推送 `main` 只自动运行 `ci.yml`。需要构建发布候选时，手动运行独立的
`release-preview.yml`，无需 tag 或 GitHub Release：

```bash
gh workflow run release-preview.yml --ref main \
  -f version=2.0.0 -f expected_sha="$(git rev-parse HEAD)" -f scope=all
```

预演固定使用触发时的提交；`expected_sha` 防止并行模块恰好推送后误测另一份代码。
支持 `all`、`daemon`、`desktop`、`windows-desktop`、`android`、`ios` 范围。默认检查版本和改名兼容性，
构建五种 daemon、三种桌面安装包、Android unsigned release APK 和 iOS unsigned archive，
并检查桌面包内 JVM；macOS daemon 另测新旧命令。Harmony 只执行静态发布契约检查。

观测模块合入后，预演复用正式构建的 Sentry 配置门禁：从各组件 `PAIRLET_SENTRY_DSN_*`
仓库变量注入公开 DSN，环境固定为 `staging`；上传 Actions 产物前核对真实 APK、jar 或
归档 Info.plist，拒绝缺失/混错配置及桌面 GA4 私钥资源。iOS 另检查本次 archive 的 dSYM
架构与 UUID，不上传符号。产物清单注明 staging；实际采集仍遵循已有开关，不能把
构建检查或 Firebase 占位包当作后台上报验收。

产物仅保存到 Actions，保留 14 天，随包提供提交 SHA、运行链接和 SHA-256。
流程只有仓库读取权限，不读取发布密钥，不创建 tag/Release，不上传商店或 TestFlight，
不更新 Homebrew/Scoop、镜像和生产服务。移动端使用 Firebase 占位配置；无签名 APK/archive
仅用于构建验证。预演通过后仍需正式签名、公证及旧版本升级验收；其他模块合并后应重新预演。

daemon 自动更新只查询镜像 `latest.json` 和 GitHub `/releases/latest`，不会查询 `main`
或 Actions 产物。安装脚本本身从 `main` 获取，镜像任务也会同步脚本，因此推送会让新脚本
对外可见；重新安装时下载的程序仍由正式 Release 决定，不会自动取得预演版。

## 发布模式

### iOS TestFlight：仅构建与外部用户交付

给外部反馈用户发 TF 时，按[反馈排障与测试版本交付](observability/FEEDBACK-DIAGNOSTICS.md)完成外测组和 Beta 审核提交；下方仅构建命令不是该场景的完整交付。新构建要继续外测时，将 `distribute_testflight` 设为 `true`；已有 VALID 构建时只补跑 `testflight-public-link.yml`，不要重复归档。正式商店提审仍保持关闭，除非本次另有明确要求。

仅当明确要求只构建/上传时，使用已确认版本且通过定向验证的提交运行：

```bash
gh workflow run ios-release.yml --ref <发布提交所在的固定-ref> \
  -f testflight_only=true -f submit_for_review=false \
  -f automatic_release=false -f distribute_testflight=false
```

此模式仍验证归档中的 Sentry 配置、上传对应 dSYM、签名上传，并等待**本次版本和 build**
在 ASC 处理为 `VALID`；跳过商店元数据同步、商店版本绑定、正式审核与公开测试组分发。
build 号使用 ios-release 的 run number。工作流成功且目标 build 为 VALID，只能报告
构建上传/处理完成；外测提交完成与外部用户已可安装仍须分别核实测试组和 Beta App Review 状态。

### 全平台协调发布

协调发布使用同一个 `x.y.z` 版本，覆盖 daemon（macOS 双架构、Windows、Linux 双架构）、
桌面 App（macOS 双架构、Windows）、Android 和 iOS。HarmonyOS 是显式 opt-in，依赖独立的
受保护 environment 与临时自托管 runner，不属于默认全平台任务。

1. 同步版本、商店说明并跑发布门禁：

   ```bash
   bash scripts/check-release-version.sh 1.8.0
   bash scripts/check-harmony-release.sh
   bash scripts/check-all.sh
   ```

2. 提交并推送通过验证的 `main`，等待主 CI 成功；再从该提交创建并推送不可变 tag。
3. 创建同名 GitHub Release 后，从 tag 触发资产工作流；完整发布不允许从可移动的 `main` 构建：

   ```bash
   git tag -a v1.8.0 -m "v1.8.0"
   git push origin refs/tags/v1.8.0
   gh release create v1.8.0 --verify-tag --generate-notes
   gh workflow run release.yml --ref v1.8.0 -f version=1.8.0
   gh workflow run ios-release.yml --ref v1.8.0
   ```

4. 所有请求的平台 job 成功后才允许生成 `SHA256SUMS`、签名清单（`sign-manifest`，见下文
   「更新包签名」）和更新 Scoop。取得两份 macOS daemon
   的最终 SHA-256 后，更新 `heypandax/homebrew-tap`；不要把仓库模板里的上一版 hash 发布出去。
5. 按本文各平台章节完成安装、进程、商店/TestFlight 与下载链接验收。工作流启动不等于发布完成。

版本源由 `scripts/check-release-version.sh` 强制锁步：daemon fallback、Android/桌面版本、iOS
marketing version、桌面 seed 与 Homebrew 模板必须一致；Android `versionCode` 另行递增。

## 更新包签名

daemon 与桌面 App 的自动更新共用 `protocol` 的 `ReleaseClient`。安装包、`latest.json` 和
`SHA256SUMS` 都来自同一个下载源（镜像或 GitHub Release），所以 `SHA256SUMS` 不是独立的信任根。
更新包签名在下载源之外加一把发布密钥。手机 App（iOS/Android/Harmony）走商店更新，不经过这条路径。

### 机制

- 发布时 `scripts/release-manifest.py` 生成 `release-manifest.json`：内容是版本号、`publishedAt`，
  以及除 `SHA256SUMS` 和清单本身以外每个 Release 资产的 sha256，范围不小于 `SHA256SUMS`。
  它用 Ed25519 对清单的原始字节签名，签名的 base64 写入 `release-manifest.json.sig`。
  两个文件都作为 Release 资产上传。
- `release.yml` 的 `sign-manifest` job 在所有模式下都会运行，包括 `only_*` 和 `only_android`；
  热修复工作流的 `publish` job 替换 daemon 包之后会重签。清单覆盖的是**最终**发布出去的全部资产，
  每个哈希按以下顺序核对：
  - 本次重建的资产：与构建 job 上传前记录的哈希核对；
  - 未重建的资产：与本版本上一次签名的清单核对；
  - 两者都没有：只能取下载副本的哈希，并打出警告。

  任何一项对不上就拒签，工作流失败。
- 客户端内嵌受信公钥列表
  [`ReleaseTrustedKeys.kt`](../protocol/src/jvmMain/kotlin/dev/ccpocket/protocol/update/ReleaseTrustedKeys.kt)：
  - **列表为空（未配置，当前状态）**：行为与以前完全一致，只核对 `SHA256SUMS`。
    启动时日志记一行 `update signatures: NOT CONFIGURED`。
  - **列表非空（强制）**：必须同时满足以下条件才安装，不回退 `SHA256SUMS`：
    - 取到清单和签名；
    - 签名能被任一受信公钥验证；
    - 清单版本等于所选版本，并且严格高于当前版本；
    - 待安装文件的 sha256 与清单一致。

    任一条件失败都不安装，用户看到 `update refused — <原因>`：
    - `pairlet update`：命令行报错，不切换版本；
    - daemon 自动更新：写进 daemon 日志和诊断，手机仍会看到“有新版本”；
    - 桌面 App：设置页显示“更新失败（原因）”。
- 镜像只搬运字节，不持有密钥，也不验签。客户端无论从镜像还是 GitHub 取文件，都按同一规则验证。

当前状态：未配置。`RELEASE_SIGNING_KEY` secret 没有设置，发版时 `sign-manifest` 只打印
`Release manifest NOT signed` 警告，其余步骤照常完成。

### 启用清单（负责人按顺序执行）

1. **生成密钥对**（在自己的机器上，路径放在仓库之外）：

   ```bash
   OPENSSL="$(brew --prefix openssl@3)/bin/openssl" \
     python3 scripts/release-manifest.py keygen --out ~/secure/pairlet-release-signing.pem
   ```

   命令会打印公钥，以及要写进 `ReleaseTrustedKeys.kt` 的那一行。私钥至少离线备份两份，
   例如密码管理器加离线介质。不要入库、不要贴进聊天、不要出现在命令行参数里。
   持有私钥的人可以向所有开启自动更新的客户端推送代码。这一步对用户没有影响。
2. **配置 Actions secret**：`gh secret set RELEASE_SIGNING_KEY --repo heypandax/cc-pocket < 私钥文件`。
   之后每次发版和热修复都会签名并上传清单。客户端还没有内嵌公钥，所以仍不影响用户。
   如需人工审批，可以把它改成 Environment secret，但要同时给两个签名 job 加 `environment:`，
   本次没有这样做。
3. **部署新的镜像同步脚本**：`bash scripts/provision-relay-mirror.sh`。部署后镜像会原样同步
   清单和签名，并在 `latest.json` 里指向镜像。旧脚本会把这两个文件指回 GitHub：仍然可用，
   但国内用户会变慢。
4. **自检一个已签名的版本**：发版，或对现有版本跑一次 `only_*` 重跑之后，下载全部资产，
   用 `scripts/README.md` 里的 `verify` 命令验证，并确认 `https://pocket.ark-nexus.cc/dl/<tag>/`
   上也有这两个文件。
5. **填入公钥并发版 N**：把公钥加进 `ReleaseTrustedKeys.kt`，按 `ReleaseTrustedKeysTest`
   的提示同步修改该测试，然后发版。
   - 从 N 开始的客户端会进入强制模式。
   - **升级到 N 这一次仍然走旧的 `SHA256SUMS` 校验**：这是信任的起点，无法避免。
   - N 之后的更新才受签名保护。
   - 可以在 release notes 里建议在意的用户手动重新安装一次 N。
6. **镜像改为严格模式**：N 发布并同步后，在 unit 里把 `MIRROR_REQUIRE_SIGNATURE` 设为 1，
   然后重新 provision。之后镜像不再同步未签名的版本。

**启用之后的硬性要求**：一旦某个已发布版本内嵌了公钥，此后**每一次**发布都必须带有效签名，
包括 `only_*` 重跑、daemon 热修复和手动补传资产。否则那些客户端会拒绝更新，并一直停在旧版本，
直到有一个签名有效的新版本。手动替换资产以后，`ci-sign` 会因为哈希与上次签名不符而拒签，
这时按以下步骤重签：

- 先自行确认新文件无误；
- 在本地用私钥运行 `build`，再运行 `sign`；
- 把两个文件 `--clobber` 上传；
- 最后运行 `verify` 回读确认。

`sign-manifest` 会核对签名公钥是否在被发布源码的 `ReleaseTrustedKeys.kt` 里，不在就发出警告。

### 轮换密钥

1. 生成新密钥对。
2. 发一个**同时信任新旧两把**公钥的版本，这个版本仍然用旧私钥签名。
3. 等大多数用户升级到这个版本后，把 secret 换成新私钥。还停在步骤 2 之前版本的客户端只认旧钥，
   会拒绝之后的更新，需要手动重新安装。
4. 在之后的某个版本里移除旧公钥。

### 私钥泄露

1. 先删除或替换 `RELEASE_SIGNING_KEY`，并检查 Release 与镜像上有没有异常的版本或清单。
2. 生成新密钥。用**旧私钥**签发一个只信任新公钥的版本：只信任旧钥的客户端只认旧钥签名，
   这是把它们迁走的唯一自动途径。
3. 发公告，请用户尽快更新或用安装脚本重新安装。没有升级的客户端在此期间仍会接受旧钥签名的
   任何内容，这个窗口无法在客户端侧补救。

### 镜像要求

- 镜像必须运行新版 `deploy/mirror-sync.sh`：清单和签名逐字节同步；以下情况拒绝同步：
  - 只有其中一个文件（半签名）；
  - 清单与 `SHA256SUMS` 的 daemon 哈希不一致；
  - 清单版本不符。
- 热修复会在同一 tag 下重签，镜像每次运行都重新拉取清单和签名。
- 如果同一 tag 下的清单长时间没有更新，检查 Cloudflare 是否缓存了 `/dl/<tag>/release-manifest.json*`。

---

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
- 主仓库 `heypandax/cc-pocket`（已存在）—— **artifact（`.tar.gz`）挂在它的 GitHub Release**，cask 的 `url` 指向这里；Android APK 也放主仓 Release。
- tap 仓库 **`heypandax/homebrew-tap`**（已建）—— **只放 `Casks/cc-pocket.rb`**（用 `packaging/homebrew/Casks/cc-pocket.rb` 当模板），不挂任何 artifact。tap 名即 `heypandax/tap`。

> 证书私钥**只在你创建它的那台 Mac**。换机器：钥匙串访问里选中证书+私钥 → 导出为 `.p12`（带密码）→ 在新机导入。Developer ID 证书一个 Team 数量有限（一般 2 个），别乱删。

---

## 每次发布

1. **定版本**：协调发布按文首清单锁步更新；不要只改 daemon 或只改移动端。运行
   `bash scripts/check-release-version.sh <version>` 后再提交。Homebrew 的最终 SHA-256 只能在
   公证资产产出后填写。

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

3. **在主仓 `heypandax/cc-pocket` 建 GitHub Release** `v1.1.0`（tag 用 daemon 版本号），把上面的 tar.gz 作为附件上传。⚠️ 别传到 tap 仓库——tap 只放 cask。

4. **更新 cask**：把 `packaging/homebrew/Casks/cc-pocket.rb` 的 `version` + `sha256` 填好（`url` 已模板化为 `#{version}` 且指向主仓 Release，不用动），提交到 `heypandax/homebrew-tap` 的 `Casks/cc-pocket.rb`。

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
- **双语同步**：改 README 必须 README.md 与 README.zh-CN.md 一起改（功能列表 / 安装章节 / 支持平台）——历史上中文版漏过整段功能；发版前对照一遍两份文件的章节结构。

---

## 注意事项

- **claude 版本**：早先 2.1.169 的 headless 回归**已不复现**（实测 piped stdin/stdout 下输出完整的 stream-json：assistant + result，exit 0）。所以**发布版不再 pin** `--claude-bin`，daemon 自动用用户的 claude。若日后某个 claude 版本又坏，再在 daemon 里加版本检测告警。
- **默认 relay 已烤进 daemon**（`DEFAULT_RELAY`），`run` / `service-install` 不用传 `--relay`。
- **双架构**：jpackage 打的是构建机的 arch，所以 arm64 / x86_64 要各打一份；cask 用 `depends_on arch: :arm64` 限制，要支持 Intel 再按 arch 给不同 `url`/`sha256`。
- **必须用 Cask 不用 Formula**：产物是预编译 + 已公证的二进制。Homebrew **Formula** 会强制跑「Command Line Tools 体检」（哪怕不编译，且常误报 CLT 过旧 → 装不上），**Cask** 是预编译通道、不碰 CLT。所以分发用 `Casks/cc-pocket.rb` + `brew install --cask`。
- **tap-trust 提示**：`heypandax/tap` 是第三方 tap，brew 会打一行 "not trusted" 警告（非阻塞）；Homebrew 6.0 后会要求 `brew trust`，到时文档补一句即可。
- **公证 vs 不公证**：叠加公证后**任何下载路径都零 Gatekeeper 警告**，最稳。

---

# 发布桌面 App（Compose Desktop）

桌面 App 是和手机端同一套 Compose Multiplatform 代码的**客户端**（用它去操控**另一台**电脑上的 Claude / Codex），**不是 daemon**。它以两种安装包分发，挂在**和 daemon 同一个 GitHub Release** 上（即 `v<X>` 那个 release）：

- macOS：签名 + 公证的 `.dmg`（Apple Silicon）。
- Windows：`.msi`。
- Linux：`.deb` 与 `.rpm`（x86_64 与 arm64 各一套，未签名）；`.AppImage` 是尽力而为项，构建并自检通过时才随版附上。

全部都用**无版本号的固定 asset 名**——`cc-pocket-desktop-macos-arm64.dmg`、`cc-pocket-desktop-windows-x86_64.msi`、`cc-pocket-desktop-linux-<arch>.{deb,rpm}`——这样 `https://github.com/heypandax/cc-pocket/releases/latest/download/<asset>` 就是永久有效的「最新版」直链，官网与 README 都引用它。

macOS 桌面端自 2026-10 起每个架构发布两个镜像：`cc-pocket-desktop-macos-<arch>.dmg`（`CC Pocket.app`，存量安装自更新用）与 `pairlet-desktop-macos-<arch>.dmg`（`Pairlet.app`，新安装用）。本地单独构建新包：`DESKTOP_VARIANT=pairlet scripts/release-desktop-macos.sh`。背景与退出条件见 [迁移安排](PAIRLET-ROLLOUT.md)。

> 桌面 App **没有 Homebrew / Scoop 入口**，纯直链下载；只有 daemon 走 cask / scoop。别把它和 daemon 的安装（brew / scoop / curl）搞混。

## CI（常规路径）

`release.yml` 现在带 `macos-desktop`、`windows-desktop` 和 `linux-desktop` 三个 job。一条命令：

```bash
gh workflow run release.yml --ref v<X> -f version=<X>
```

就会：构建 + 签名 + 公证出 DMG、构建出 MSI、在两种架构的 Linux runner 上出 deb/rpm，并全部上传到 `v<X>` 这个 release —— 和 daemon、Android APK 挂在一起。（**前提**：`v<X>` 这个 GitHub Release 必须已存在，和 daemon 的 job 一样。）

## macOS DMG 手动出包（本地兜底）

需要仓库根 `.env` 里的 Apple 凭据（`DEVELOPER_ID` / `APPLE_ID` / `APPLE_TEAM_ID` / `APPLE_APP_PASSWORD`）：

```bash
set -a; . .env; set +a
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:packageDmg \
  -Pcompose.desktop.packaging.checkJdkVendor=false -PccpocketSignId="$DEVELOPER_ID"
DMG=$(ls mobile/composeApp/build/compose/binaries/main/dmg/*.dmg | head -1)
xcrun notarytool submit "$DMG" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
xcrun stapler staple "$DMG"
gh release upload v<X> "$DMG" --clobber   # 先把文件名改成 cc-pocket-desktop-macos-arm64.dmg 再传
```

- Homebrew 的 JDK 需要加 `-Pcompose.desktop.packaging.checkJdkVendor=false`，否则 jpackage 会因 vendor 校验失败。
- 公证完成后用 `xcrun stapler validate <dmg>` 验收。

## Windows MSI

MSI 在 `windows-latest` runner 上构建（jpackage 不能跨平台出包，且 WiX 只在该 runner 上自带），由 `release.yml` 的 `windows-desktop` job 产出并上传到 release。`build-windows.yml` 也会构建一份 MSI 供临时测试，但**只上传 workflow artifact，不传到 release**。

## Linux deb / rpm（+ 尽力而为的 AppImage）

jpackage 打包 Linux 同样不能跨架构（它把宿主 JRE 打进包里，并调用宿主的 `dpkg-deb` / `rpmbuild`），所以 `release.yml` 的 `linux-desktop` job 用和 daemon `linux` job 相同的矩阵：x86_64 在 `ubuntu-latest`，arm64 在 `ubuntu-24.04-arm`。runner 自带 `dpkg-deb`，但**不带 `rpmbuild`**，job 里会先 `apt-get install -y fakeroot rpm`。

实际出包走 `scripts/release-desktop-linux.sh`：`createDistributable` → 用打好的 app image 跑 `scripts/smoke-desktop-image.sh`（拿捆绑 JVM 自检，和 mac/win 同一道闸）→ `packageDeb` + `packageRpm` → 校验包内确实含 `bin/CC Pocket`、`lib/runtime/` 和菜单项。Linux 没有 Gatekeeper/公证的对应物，所以没有签名步骤。

AppImage 走 `scripts/build-desktop-appimage.sh`，在 workflow 里挂 `continue-on-error: true`：appimagetool 只有滚动的 `continuous` 版本，上游抽风不能拖住 deb/rpm。脚本会用 `--appimage-extract` 把产物拆开、再跑一遍 `--package-smoke`，不过关就**删掉产物**，所以 release 上出现的 AppImage 一定是自检通过的。

> deb 的包名是 `cc-pocket`（`linux { packageName }`）：jpackage 默认拿 `--name` 当包名，而 `CC Pocket` 带空格和大写，dpkg 直接拒。app image 目录与启动器仍是 `CC Pocket`，与兼容清单一致。

## 注意事项

- Windows MSI 目前**未签名**（没有 Authenticode 证书）→ 首次运行会弹 SmartScreen（点「更多信息 → 仍要运行」）。macOS DMG 是完整签名 + 公证的，双击零警告。
- 桌面 App **没有 Homebrew / Scoop** 入口，是直链下载；只有 daemon 用 cask / scoop。

---

# 发布 iOS App（App Store）

准备提审或处理拒审前，先读 [App Store 拒审案例与提审前检查](APP-STORE-REJECTIONS.md)。该文档集中维护历史条款、修复证据和防复发要求。

移动端 iOS app（`com.panda.ccpocket`）走 **App Store**，由独立的 `ios-release.yml` 构建，
但协调发布仍与 daemon 使用同一 marketing version。CI 使用 App Store Connect API key 做
cloud-managed signing、上传、提交审核及 TestFlight 公测；本机 Xcode 登录流程只作为兜底。

## 前提（一次性）

- Apple Developer 账号，Team `SC9S2SJ42G`（Account Holder 的个人 Apple ID；`dev.ccpocket.app` 被旧账号占用，故 bundle id 改用 `com.panda.ccpocket`）。
- CI 仓库 secrets：`APPSTORE_API_KEY_P8`、`APPSTORE_API_KEY_ID`、
  `APPSTORE_API_ISSUER_ID` 与 `GOOGLE_SERVICE_INFO_PLIST`。
- 本地兜底时，Xcode → Settings（⌘,）→ Accounts 登录该账号；本地自动签名依赖这个会话。
- 自动签名已写进 `iosApp/project.yml`（`CODE_SIGN_STYLE: Automatic`、`DEVELOPMENT_TEAM: SC9S2SJ42G`）。
- 推送权限绑定也必须写进 `iosApp/project.yml` 的 `CODE_SIGN_ENTITLEMENTS`；CI 会重新生成工程，只修改本地 `.xcodeproj` 会在发布时丢失。源码 entitlement 保留 `development`，由 Xcode 根据签名 profile 在分发导出时选择 `production`。
- 发布证书是 **Apple 云端托管**，**不会**出现在本机 `security find-identity -v -p codesigning` 里——看不到属正常，不代表缺证书。
- CI 使用已跟踪的 `iosApp/ExportOptions.plist`：`method=app-store-connect`、`destination=export`、自动签名；先导出并验收 IPA，再上传同一个文件。

> `.env` 里的 `APPLE_ID` / `APPLE_APP_PASSWORD` 是给 **daemon 公证**（notarytool）用的，与 App Store iOS 上传**无关**，别混。

## CI（常规路径）

从同名 release tag 触发，不传 `marketing_version`，让 workflow 读取并校验仓库中的锁步版本：

```bash
gh workflow run ios-release.yml --ref v1.8.0
```

workflow 会归档、上传、等待处理、同步 metadata、提交 App Review，并把同一 build 挂到稳定的
TestFlight 公测链接。只有要把新 build 附到另一个仍开放的版本列车时，才显式传
`marketing_version`。

推送签名有两道上传前门禁：归档的签名及 profile 必须包含 `aps-environment=development`；导出 IPA 中的应用签名及 profile 必须包含 `aps-environment=production`，且应用/团队一致、不可调试。检查脚本为 `scripts/check-ios-push-entitlements.py`。任何一项不满足都阻止上传，不能用 archive/export 成功标志代替权限检查。最终仍需用目标 TestFlight build 验证 APNs token 注册、relay 登记和真机通知展示。

仅验证云签名构建时，在明确的待验收分支上触发同一工作流并传 `verify_only=true`。该模式完成归档、符号匹配、生产 IPA 导出与签名检查，并输出 IPA SHA-256；跳过 Sentry 符号上传、ASC 上传、审核及 TestFlight 分发。构建通过仅证明包的签名配置，不证明真机通知恢复。

## 本地手动兜底

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

3. **导出、验证推送签名，再上传**：
   ```bash
   python3 scripts/check-ios-push-entitlements.py \
     build/ios/CCPocket.xcarchive/Products/Applications/cc-pocket.app --environment development
   xcodebuild -exportArchive \
     -archivePath build/ios/CCPocket.xcarchive \
     -exportOptionsPlist iosApp/ExportOptions.plist \
     -exportPath build/ios/export \
     -allowProvisioningUpdates
   ditto -x -k build/ios/export/cc-pocket.ipa build/ios/ipa-check
   python3 scripts/check-ios-push-entitlements.py \
     build/ios/ipa-check/Payload/cc-pocket.app --environment production
   ```
   - 全部命令成功后，用 Transporter 上传已检查的 `build/ios/export/cc-pocket.ipa`；不要再从 archive 重新导出并直传另一个未验收的包。命令行上传可复用 CI 的 `altool` 与 App Store Connect API key 配置。
   - `** EXPORT SUCCEEDED **` 仅证明导出完成；上传成功及 Apple 处理为 `VALID` 分别取证。
   - `Upload Symbols Failed`（Firebase / Google 第三方框架缺 dSYM）是**非致命告警**，可忽略（只影响这些框架的崩溃符号化）。

4. **等 Apple 处理**：约 10–30 分钟，构建在 App Store Connect 从 “Processing” 变为可选。

5. **网页兜底与审核沟通**：
   - 进对应 version → Build 区选中刚上传的构建。
   - 回答出口合规（Export Compliance）等问询。
   - **首次提交**：填完信息 → Submit for Review。
   - **被拒后处理**：先依据[拒审案例文档](APP-STORE-REJECTIONS.md)区分产品代码与元数据问题。改了二进制才上传并选择新构建；仅修元数据可复用原 build。通过 ASC 网页回复审核员，并按该次审核信决定是否重新提交；若明确允许回复后继续处理原提交，不重复创建审核草稿。每个动作单独回读确认。

## 注意事项 / 坑

- CI 需要 App Store Connect API key（`.p8` + Key ID + Issuer ID，角色至少 App Manager）；
  本地兜底仍可使用 Xcode 账号自动签名与 `-allowProvisioningUpdates`。
- **本机看不到发布证书是正常的**（云端托管），别误判为缺证书去乱建。
- **CLI `tail` 吞退出码**：`xcodebuild ... | tail` 的退出码是 `tail` 的，会把失败误判成成功；要判结果就 `> log 2>&1` 后单独看 `$?` 或 grep `** EXPORT SUCCEEDED/FAILED **`。
- **伴侣 App 审核**：本 app 主功能需配对桌面 daemon，审核员进不去 → 触发 2.1a。已内置免配对 **Demo 模式**（配对页底部 “Try Demo”）供审核；详见提交时的 App Review Notes。
