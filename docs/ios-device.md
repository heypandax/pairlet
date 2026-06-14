# cc-pocket iOS 真机安装

## 工程位置

```
/Users/lidapeng/Desktop/Project/app/cc-pocket/iosApp/iosApp.xcodeproj
```

```bash
open /Users/lidapeng/Desktop/Project/app/cc-pocket/iosApp/iosApp.xcodeproj
```

> 注意：这个工程是 xcodegen 从 `iosApp/project.yml` 生成的。**在 Xcode 里配好签名后，不要再跑 `xcodegen generate`**，否则会覆盖你在 Xcode 里改的设置（要持久化就改 `project.yml` 再重新生成）。

---

## 一、签名（在 Xcode 里）

1. 左侧选 `iosApp` 工程 → TARGETS 选 `iosApp` → 顶部 **Signing & Capabilities**。
2. 勾 **Automatically manage signing**。
3. **Team** 选你当前的 Apple ID。team 已写进 `iosApp/project.yml` 的 `DEVELOPMENT_TEAM`，`xcodegen generate` 后不用重选；换账号时改那一行即可。
4. **Bundle Identifier** 现为 `com.panda.ccpocket`——原 `dev.ccpocket.app` 已被旧账号注册（bundle ID 跨 team 全局唯一）；若再提示不可用，换一个唯一的，并同步改 `iosApp/project.yml`。

> 用免费 Apple ID 也能上自己的真机，但 App 7 天后过期、需重装；付费开发者账号无此限制。

---

## 二、选设备并运行

1. Xcode 顶部设备下拉，选 **Pandaa**（iPhone 14 Pro Max，已配对）。手机用数据线连上、解锁、点「信任此电脑」。
2. **Cmd + R** 运行。首次编译会触发那条「Compile Kotlin Framework」脚本（已注入 `JAVA_HOME`，无需你额外配 java）。
3. 第一次装到真机后，手机上会因「未受信任的开发者」打不开。去手机：**设置 → 通用 → VPN 与设备管理 →** 信任 `Apple Development: Panda Lee`，再点开 App。

---

## 三、让真机 App 连上电脑的 daemon

### 推荐：经云端 relay（任意网络，端到端加密）

不限同一 Wi-Fi、不碰防火墙；daemon 外拨连 relay，手机也连 relay，两端 Noise 端到端加密，relay 只见密文（见 `docs/SECURITY.md`）。

1. **daemon 外拨连 relay**（首跑自生成身份并起本地配对回环）：

   ```bash
   daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon run --relay wss://pocket.ark-nexus.cc --claude-bin ~/.local/bin/claude
   ```

2. **配对**：另开一个终端，让 daemon 出一张一次性配对链接：

   ```bash
   daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon pair
   # 输出 ccpocket://pair?relay=...&acct=...&dpk=...&ticket=...
   ```

3. **App 里配对**：用 App 内置相机扫终端里的二维码，或手输 6 位码，或把上面的 `ccpocket://pair?...` 链接粘进「Pair」框 → Pair。配对成功后设备记住该 daemon，以后开 App 直接「Connect」。

> 票据 120s 单次有效；过期就再 `pair` 一次。自托管 relay：把 `wss://pocket.ark-nexus.cc` 换成你自己的域名（`deploy/` 有 systemd + Caddyfile）。

### 进阶：同局域网直连（不经 relay）

真机不能用 `127.0.0.1`（那是手机自己）。同一 Wi-Fi 下可直连，省一跳：

1. daemon 监听局域网：`... run --host 0.0.0.0 --claude-bin ~/.local/bin/claude`
2. App 连接页点「Advanced · direct LAN」，URL 填 `ws://<电脑 en0 IP>:8765/v1/ws`（查 IP：`ipconfig getifaddr en0`）。
3. 手机和电脑同一 Wi-Fi；连不上多半是 **macOS 防火墙**挡了入站 8765（系统设置 → 网络 → 防火墙放行该 java 进程）。

---

## 常见问题

- **首次连接弹「本地网络」授权**：iOS 14+ 首次访问局域网会弹系统授权框，授权未决时进行中的连接会被系统直接掐断。App 已做预检：点 Connect 先触发授权（状态行显示 `checking network access…`），你点「允许」后才真正发起连接；若误点「不允许」，去 **设置 → 隐私与安全性 → 本地网络** 打开 cc-pocket 再重试（App 内状态行也会提示）。
- **「Compile Kotlin Framework」报找不到 java**：脚本已写 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`；若你的 JDK 在别处，改 `iosApp/project.yml` 里那行再 `xcodegen generate`。
- **Bundle ID 冲突 / 无法注册**：bundle ID 跨 team 全局唯一，被任何账号（含自己的旧账号）注册过就不可用；免费账号没有 developer.apple.com 的 Identifiers 管理入口，删不掉旧注册。最快做法是换一个唯一 ID（如 `com.<你的名字>.ccpocket`），同步改 `iosApp/project.yml` 和 Xcode 里的值。
- **真机能编不能连**：99% 是不同 Wi-Fi 或防火墙；先用电脑浏览器开 `http://172.16.2.49:8765`（应拒绝连接但说明端口可达），再排查。
- **模拟器版**：URL 用默认 `ws://127.0.0.1:8765/v1/ws` 即可（模拟器和电脑共享网络），不用改 daemon。
