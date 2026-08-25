# marketing/site —— 官网与 README 的产品素材管线

一条命令，把**真实 Compose UI + 脚本化演示数据**渲成官网和 README 用的全部素材。
和 `marketing/video/` 的区别：那条管线出的是有口播、有品牌舞台的宣传片；**这条只出产品证据**——
无声、无字幕、无 TTS、无网络，画面就是产品本身。

```bash
bash marketing/site/generate-assets.sh            # 完整重跑（约 4 分钟）
bash marketing/site/generate-assets.sh --reuse    # 帧已在，只重编码 / 重合成
```

## 产出（提交进仓库，落在 `site/assets/product/`）

| 文件 | 是什么 | 由谁生成 |
|---|---|---|
| `control-loop-en.mp4` / `control-loop-zh.mp4` | 10.5s 无声控制闭环，780×1688，H.264 + faststart | `ShowcaseRender.renderSiteLoop` |
| `control-loop-en-poster.jpg` / `control-loop-zh-poster.jpg` | 视频海报：减弱动效、加载失败、无 JS 时显示 | 同上第 135 帧 |
| `desktop-console.png` | 桌面端两栏控制台截图，1600 宽 | `DesktopScreenshotTest.generate` |
| `overview.png` | README 顶部总览图，1200×630，桌面截图 + 两张手机静帧 | `generate-assets.sh` 的 ffmpeg 合成 |
| `manifest.json` | 每个文件的来源、语言、尺寸、时长、SHA-256 与出处声明 | `write-manifest.py` |

`scripts/check-public-content.py` 会校验 manifest 存在、条目齐全、文件非空且哈希对得上。

## 闭环剧本（`renderSiteLoop`）

一镜到底走完四件公开任务，用的全是产品里真实的 composable：

```
Watch    0.0–1.6s  SessionsScreen —— 这台电脑上在跑什么
         1.6–3.9s  ChatScreen —— 思考、正文、工具事件逐条流出
Approve  3.9–6.0s  SecureApprovalSheet —— 倒计时 + Deny / Allow once / Allow for task
Continue 6.0–8.3s  ChatScreen —— 命令跑完、继续写、TurnDone 落章
Inspect  8.3–10.5s FileViewerScreen —— 行级 diff
```

`SHOWCASE_LANG=en|zh` 同时切**产品自身的资源语言**和**剧本文案**，不会出现半中半英的帧。
产品语言靠 `CCP_CAPTURE_LOCALE` 钉住测试 JVM 的 `user.language`（见 `mobile/composeApp/build.gradle.kts`）——
不钉的话，一台中文 macOS 渲出来的「英文」截图会混进中文按钮。

确定性：每一帧都是 t 的纯函数——Beat 在固定偏移改状态、Compose 动画由 `scene.render(tNanos)` 推进、
审批读秒靠换 `timeoutSec` 重新 key（它自带的 `delay` 计时器在离屏渲染下不确定）。
同一份代码渲两次，逐帧相同。

## 红线

- **不读真实用户数据。** 渲染进程把 `user.home` 指向临时目录，测试任务另给一份私有 SecureStore；
  画面里的路径、机器名、会话内容全部来自 `DemoData` / `SeedDesktopModel` 的虚构数据。
- **不手绘 UI。** 官网上出现的产品画面只能来自这条管线，不能用 HTML/CSS 重画 App。
- **不渲染未对外宣传的功能。** 剧本只走 Watch / Approve / Continue / Inspect 四段。
- **生成代码只住在 `desktopTest` 与 `marketing/`。** 任何 App 产物（App Store / APK / 桌面包）都不含它；
  `SITE_LOOP_OUT` 没设时 `renderSiteLoop` 直接 return，CI 不受影响。
- **对外只能说「真实产品界面，演示数据由脚本生成」**，不能说成真实客户会话或线上运行证明。

## 依赖

`ffmpeg` / `ffprobe`（macOS：`brew install ffmpeg`）、`python3`、JDK 17
（`JAVA_HOME=/opt/homebrew/opt/openjdk@17`）。缺哪个，脚本会指名道姓地报错退出。

## 什么时候要重跑

- App UI 变了（尤其 Sessions / Chat / 审批 / diff 四屏）。
- 版本号变了——桌面截图侧栏底部有 App 自己的版本 chip，发版后重跑一次即可跟上。
- 改了 `renderSiteLoop` 的剧本或时长。

重跑后 `git status` 会看到 `site/assets/product/` 下的二进制变化，连同 `manifest.json` 的新哈希一起提交。
