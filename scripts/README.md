# 构建 / 发版脚本

jpackage 不能跨平台编译，所以每个平台的产物都在对应 OS 上构建。脚本只负责“构建一个产物”，编排（建 release、传产物）交给 CI（`.github/workflows/release.yml`）或下面的 `release-all.sh`。

版本号统一取 `mobile/composeApp/build.gradle.kts` 的 `appVersionName`，脚本不传参时自动读取，避免漂移。

## 构建脚本

| 脚本 | 产物 | 跑在哪 |
|---|---|---|
| `release-all.sh` | 当前主机能出的全部产物（编排，引用下面的脚本） | 本机 |
| `release-desktop-macos.sh` | **桌面 App** `cc-pocket-desktop-<ver>-macos-arm64.dmg`（签名+公证+staple） | macOS |
| `release-macos.sh` | daemon `cc-pocket-daemon-<ver>-macos-<arch>.tar.gz`（签名+公证） | macOS |
| `release-linux.sh` | daemon Linux tarball | Linux |
| `release-windows.ps1` | daemon Windows zip | Windows |
| `ios-fir.sh` | **iOS IPA** 签名并发布到 fir.im 做真机测试分发（`development` 开发版 / `release-testing` ad-hoc）。token 放 `scripts/.fir-token`（已 gitignore）；设备需先登记 UDID。详见脚本头注释 | macOS |

> 桌面 App 的 Windows MSI / 便携 zip 目前只走 CI（`build-windows.yml` 的 `windows-app` job + `release.yml` 的 `windows-desktop` job）；Linux 桌面包（.deb/AppImage）尚未配置（`build.gradle` targetFormats 只有 Dmg、Msi）。

## 常用命令

```bash
# 只构建桌面端（mac，签名+公证；要 .env 里的 DEVELOPER_ID + APPLE_* 凭证）
scripts/release-desktop-macos.sh

# 只构建桌面端，跳过公证（本地快速冒烟，几十秒）
SKIP_NOTARIZE=1 scripts/release-desktop-macos.sh

# 完全构建（本机：daemon + 桌面）
scripts/release-all.sh

# 完全构建里只要桌面那一份
scripts/release-all.sh --desktop-only

# 传产物到 GitHub release（release 须先存在）
gh release upload v<ver> cc-pocket-desktop-<ver>-macos-arm64.dmg --clobber

# 全平台发版（CI，需 release 先建好）
gh workflow run release.yml -f version=<ver>
```

## 凭证

签名 / 公证凭证放仓库根的 `.env`（gitignored），脚本自动 `source`：
`DEVELOPER_ID`、`APPLE_ID`、`APPLE_APP_PASSWORD`、`APPLE_TEAM_ID`（或 `NOTARY_PROFILE` keychain 档）。
不带 `DEVELOPER_ID` 时桌面脚本出**未签名** dmg（仅本机自测；拷到别的 Mac 会被 Gatekeeper 拦）。
