# App Store 预览视频流水线（App Preview）

用当前真实 Compose UI 自动生成 30 秒 App Store 预览视频，中英文各一版，输出符合上架要求的 `886×1920` H.264。推荐使用确定性的离屏 Compose 路线；需要验证完整 iOS 模拟器交互时，仍可使用原来的免配对 Demo 录制路线。

## 一次产出

```bash
cd marketing/preview
# 推荐：先生成当前真实 Compose UI 帧，再无窗口、确定性合成
bash ../site/generate-assets.sh
./make-preview.sh en --compose
./make-preview.sh zh --compose

# 可选：完整 iOS 模拟器交互录制（前置见下方「准备」）
./make-preview.sh en
./make-preview.sh zh
```

## 模拟器路线准备（可选，每台机器一次）

1. 工具：`brew install cliclick ffmpeg`；Python 带 `Pillow`（`pip install pillow`）。
2. 授权：首次会弹「辅助功能 / 屏幕录制」，需在系统设置里**允许**控制电脑的终端，然后重启终端。
3. 模拟器：优先用 **iPhone 16 Pro Max**；自动化设备为其他近代 iPhone 时也可录制，
   `assemble.sh` 会先统一到 1320×2868 原生画布，再输出 App Store 视频尺寸。
   ```bash
   xcrun simctl boot "iPhone 16 Pro Max"; open -a Simulator
   # 干净状态栏（9:41 / 满电满格）：
   xcrun simctl status_bar booted override --time 9:41 --batteryState charged --batteryLevel 100 \
     --cellularBars 4 --wifiBars 3 --dataNetwork wifi
   ```
4. 装一个**预览能力**的构建（`isPreviewMode` 由启动参数 `-ccpPreview YES` 触发，平时对真实用户休眠）：
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17
   xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
     -destination 'id=<模拟器UDID>' -derivedDataPath build/sim build
   xcrun simctl install booted build/sim/Build/Products/Debug-iphonesimulator/cc-pocket.app
   xcrun simctl privacy booted grant speech-recognition com.panda.ccpocket   # 免语音弹窗
   ```
   > `simctl io` 若报 `Timeout waiting for screen surfaces`，重启模拟器图形栈即可：
   > `xcrun simctl shutdown booted; killall Simulator; open -a Simulator; xcrun simctl boot <UDID>`。

## 预览模式（App 侧，已合入、默认休眠）

`isPreviewMode()`（`Platform.kt`，iOS 读启动参数 `-ccpPreview YES`）为真时，免配对 Demo 会：

- 开场加一段「连接电脑 → 端到端加密」过场（`DemoConnectScreen`，文案走多语言资源 `preview_connecting/encrypted`）；
- 隐藏「演示模式 · 示例数据」横幅；
- 审批演示用破坏性命令 `rm -rf ./build/cache`（红黄 danger 卡，文案 `preview_cmd_title/note`）。

平时（无该启动参数）Demo 行为完全不变。所有文案都在
`composeResources/values{,-zh}/strings.xml`，**不要写死**——加语言只需补一份 `values-xx`。

## 6 个分镜（字幕在 `render_assets.py` 的 `TEXT` 里改）

| 时间 | 画面 | 字幕(en) |
|------|------|----------|
| 0–3s | 连接 → 端到端加密 | Your AI coding agent, now in your pocket. |
| 3–8s | 项目列表，点开 running 会话 | See what's running on your computer. |
| 8–13s | 流式输出 + 工具调用 | Stream progress in real time. |
| 13–17s | 危险命令审批卡 → Allow once | Approve sensitive actions from anywhere. |
| 17–24s | 斜杠命令 + 语音 | Send prompts, screenshots, and voice. |
| 24–30s | 任务完成推送 + Logo | Your computer keeps working. You stay in control. |

## 文件

- `make-preview.sh` — 编排：渲染素材 → Compose 离屏合成，或模拟器录制 → 合成。
- `assemble-compose.sh` — 推荐路线：消费 `marketing/site/build/loop-{lang}` 的真实 UI 帧。
- `record.sh` — 用 `cliclick` 驱动 Demo（坐标按设备比例 + 自动探测窗口几何），`simctl recordVideo` 抓干净帧。
- `render_assets.py` — 渲染 6 条字幕条 + Logo 尾卡 + 通知横幅（PIL，按语言）。
- `assemble.sh` — 变速 / 拼接场景 6 / 叠字幕 / 缩放到 886×1920（纯 ffmpeg）。
- `out/` — 成片。

## 调整提示

- 节奏：改 `record.sh` 里各 `sleep`；同时把 `assemble.sh` 的 `SPEED` 与 `CW`（字幕时间窗）对齐。
- 尺寸：`assemble.sh` 的 `SIZE`（`886:1920` 或 `1320:2868`）。
- 坐标失灵：多为模拟器窗口缩放非默认；保持窗口默认比例，`record.sh` 会自动探测位置。
