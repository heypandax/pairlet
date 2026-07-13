# marketing/video —— 全自动宣传视频管线

一条命令把「真实 App UI + mock 数据剧本」渲染成竖屏成片（1080×1920，H.264 + AAC，AI 配音）。
画面来自**产品真实的 Compose UI**（不是手绘 mock），所以永远和上架版本长得一样；
出新功能时只需加一段剧本 + 一行分镜，重跑即可出片。

## 架构

```
┌ 真 UI 帧（手机画面）────────────────────────────────────────────┐
│ ShowcaseRender.kt（composeApp desktopTest 源集，永不进产物）      │
│   剧本 Beat(t, action) 直接驱动 PocketRepository（receiveForTest │
│   注入协议帧 / 直接 poke 状态）→ ImageComposeScene 离屏逐帧渲染   │
│   真实的 ChatScreen / SessionsScreen / QuestionCard / FileViewer │
│   / UsageScreen → out/appframes/<scene>/f%05d.png（390×844@2x）  │
└──────────────────────────────────────────────────────────────────┘
┌ 舞台与成片 ──────────────────────────────────────────────────────┐
│ storyboard-*.json（分镜：场景序列 + 口播 + 花字）                 │
│   → render.py：edge-tts 出口播（缓存，时长决定每段长度）          │
│   → Playwright 逐帧渲 scenes/*.html（stage.html 把真 UI 帧嵌进    │
│     品牌舞台：花字/字幕/角标/可选推送横幅；terminal/montage/      │
│     endcard 是纯 HTML 场景）                                     │
│   → ffmpeg 分段编码 → 拼接 →（assets/bgm/*.mp3 存在则自动混入）   │
│   → out/<id>-<lang>.mp4                                          │
└──────────────────────────────────────────────────────────────────┘
```

确定性：所有画面都是 t 的纯函数——Beat 按固定偏移改状态、Compose 动画由
`scene.render(tNanos)` 推进、CSS 动画被 rig/scene.css 冻结、rig.js 驱动 HTML 侧动效。
同一份剧本渲染两次，逐帧相同。

## v3 管线（segments+shots，134.5s 正片用；仓库根 `./video.sh` 统一入口）

```bash
./video.sh lock-facts                        # git 采数 → output/facts.lock.json（显式执行才更新）
./video.sh resolve-timeline storyboard-v3.json   # 分段 TTS → output/timeline.lock.json（时长唯一权威）
./video.sh validate storyboard-v3.json --allow-placeholder   # 红线词 + 素材检查
./video.sh animatic storyboard-v3.json       # 灰卡动态分镜稿 → output/animatic.mp4
./video.sh render storyboard-v3.json         # 正式渲染（缺素材直接报错，不允许占位出片）
./video.sh render-scene storyboard-v3.json hook   # 单段迭代
./video.sh footage-contact-sheet             # Seedance 候选缩略对比页
```

- **段（segment）= 一段口播**，时长 = max(TTS 实测 + lead + 尾停留, Σ镜头 minMs)，富余给 `flex` 镜头；**镜头（shot）**类型：`html`（舞台场景）/ `frames`（真 UI 帧序列）/ `footage`（Seedance 素材，ffmpeg 预抽帧走 img 换帧，**禁 `<video>` seek**）/ `placeholder`
- footage 六字段：`source/trimStart/trimEnd/fit/voiceOffset(段级)/shortagePolicy(freeze|loop|error)`；素材规范见 `assets/footage/README.md`
- 换口播/音色后必须重跑 resolve-timeline 再生成素材——分镜秒数是派生值，不许手写

## 出片（两步，v2 老路径仍可用）

```bash
# 1) 渲真 UI 帧（改了剧本或升级了 App 之后跑；约 1.5 分钟）
SHOWCASE_OUT=$PWD/marketing/video/out/appframes \
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :mobile:composeApp:desktopTest --tests dev.ccpocket.app.showcase.ShowcaseRender --rerun

# 2) 合成成片（约 3 分钟）
cd marketing/video && python3 render.py storyboard-v2.json
# → out/v2-realui-zh.mp4
```

调试提效：`--stills`（每场景 3 张静帧速览）、`--only <sceneId>`（单段出 mp4）、
`SHOWCASE_ONLY=<scene>`（只重渲一个真 UI 场景）。

依赖：ffmpeg、`pip install edge-tts playwright` + `python3 -m playwright install chromium`。
BGM：往 `assets/bgm/` 放一个 mp3 即自动以 −14dB 混入（版权自理）。

## 新功能怎么沿用（可持续性）

1. **加真 UI 场景**：在 `ShowcaseRender.kt` 的 `shows()` 里加一个 `Show(id, duration, beats, content)`——
   beats 用 `receiveForTest(协议帧)` 或直接写 repo 的 public state；content 指向真实屏幕 composable。
2. **加分镜**：storyboard JSON 里加一条 `{"id","frames":"<sceneId>","vo":"口播","caption":"花字"}`。
3. 重跑上面两条命令。英文版：复制 storyboard 换 `lang/voice`（如 `en-US-ChristopherNeural`）+ 口播文案。

## 隔离红线（勿破坏）

- ShowcaseRender 与全部 mock 数据住在 **desktopTest 源集**——任何 App 产物（App Store / APK / 桌面包）
  都不包含它；`SHOWCASE_OUT` 未设置时它是个空跑测试，CI 不受影响。
- 它对 prod 的唯一触碰是 `ChatScreen` 由 private 放宽为 internal（无行为变化，沿用
  SessionsScreen 供 UI 测试驱动的既有先例）。
- 渲染进程把 `user.home` 指到临时目录，不读写开发者真实的 `~/.cc-pocket-app`。

## 文件

- `render.py` — 管线主脚本（TTS / 逐帧 / ffmpeg）
- `storyboard-v2.json` — V1「出门版」分镜（真 UI 版）
- `scenes/stage.html` — 真 UI 帧的品牌舞台（花字 / 字幕 / 推送横幅叠层）
- `scenes/terminal.html · montage.html · endcard.html` — 纯 HTML 场景（电脑侧 / 快切 / 收尾卡）
- `rig/` — 确定性动效工具 + 9:16 舞台样式（复用 `site/styles.css` 设计系统）
- 真 UI 渲染器 — `mobile/composeApp/src/desktopTest/.../showcase/ShowcaseRender.kt`
