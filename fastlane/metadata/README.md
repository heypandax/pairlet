# App Store 元数据与截图（fastlane deliver）

编辑或上传前先读 [App Store 拒审案例与提审前检查](../../docs/APP-STORE-REJECTIONS.md)，覆盖本项目已经遇到的配对、平台文案、地区、隐私与年龄分级问题。

`fastlane/metadata/<locale>/` 管理名称、副标题、描述、关键词、宣传文本、版本说明以及官网、支持、隐私政策 URL；`fastlane/screenshots/<locale>/` 是商店截图来源，`fastlane/previews/<locale>/app-preview.mov` 是 App Preview 来源。当前启用 `zh-Hans`、`en-US`。

## 2.0 改名准备

- 中英文商店过渡名称均为 `CC Pairlet`（`name.txt`）。副标题独立写入 `subtitle.txt`：中文 `AI 编程伴侣`，英文 `Coding agents, within reach`。名称和副标题各不超过 30 字符。
- 当前口径见[名称与过渡期约定](../../docs/PAIRLET-NAMING.md)。原命名评估表述为「Pairlet｜AI 编程伴侣」「你的编程任务，随时接续」以及 `Pairlet — Your coding agents, within reach.`；实施方案的中文主张为「随时接续你的 AI 编程任务」。`Your coding agents, within reach.` 共 33 字符，商店副标题去掉 `Your` 和句号后为 27 字符，其余宣传场景可保留完整原句。不要再从旧商店标题回填「随身编程遥控 / AI Code Remote」。
- 介绍保留原有功能与能力边界，在首段说明原名 CC Pocket，并补齐 iPhone / iPad；关键词保留 `CC Pocket` 供老用户搜索。
- `marketing_url.txt` 指向中文官网 `https://pairlet.org/` 或英文官网 `https://pairlet.org/en/`；`support_url.txt`、`privacy_url.txt` 分别指向 `https://pairlet.org/support/`、`https://pairlet.org/privacy.html`。上传前要检查公开访问；本地校验只验证 URL 格式。
- 当前过渡阶段在商店及设备上统一显示 `CC Pairlet`；最终品牌仍为 Pairlet，后续移除 `CC` 必须经用户另行确定。`CC Pocket` 关键词用于商店搜索，不能作为安装后系统搜索命中的证明。
- 沿用 App Store 记录 `6778773969` 与 Bundle ID `com.panda.ccpocket`。桌面显示名 CC Pairlet、旧命令和旧本地目录不等于商店需要创建新应用。
- `release_notes.txt` 按 iPhone / iPad 用户体验组织；2.0.0 包含改名说明、官网与帮助入口、诊断设置和会话文字显示修复。后续版本按实际提交构建的代码差异更新；最终 UI 改动也需重新核对中英 iPhone / iPad 截图与预览。
- 2026-09-10 用户已授权创建 2.0.0 商店草稿并同步素材供人工复核；允许运行仅操作目标草稿的元数据工作流，不运行 `ios-release`、不挂 build、不提交审核、不发布。公开商店、ASC 元数据、构建上传、审核、上架是不同状态。宣传文本可不随新版本提交而更新，不能把「不提审」当作「不会影响公开页面」。
- 2026-09-10 准备草稿时，1.9.8（build 55）尚在审核，用户决定等其上架后再创建 2.0.0。后续操作须重新读取 ASC 当前状态；若目标版本已被他人修改，先核对差异，避免覆盖人工工作。

## 生成与上传

截图必须从真实 Compose UI 自动生成（脚本数据，不含真实用户数据），不要手工重画界面。一次跑完 iPhone 与 iPad 两套：

```bash
bash marketing/appstore/generate-assets.sh
# 只重新排版已有的最新 UI 帧：
bash marketing/appstore/generate-assets.sh --reuse
```

创建或同步一个**可编辑**的 App Store 版本，运行 GitHub Actions 的 `ios-store-metadata`，输入目标版本号。工作流先确认不会覆盖其他未上架版本；新建版本要求最新版本已上架，更新要求目标就是当前可编辑草稿。它上传名称、副标题、描述、关键词、宣传文本、三个 URL 字段、截图和 App Preview，不上传 binary、不挂 build、不提交审核，并将目标设为手动发布。

`include_release_notes` 默认关闭；用户授权同步完整草稿时打开，一并上传 `release_notes.txt` 和审核备注，供人工核对。正式提交 binary 前仍需按最终代码差异补齐。同步后通过 ASC API 回读本次上传的全部文本及手动发布设置，并校验 iPhone / iPad 媒体。`verify_only` 保持只读媒体检查，不写入任何字段。

正式发版仍走 `ios-release.yml`：`ios` job 上传二进制 → `submit` job 轮询 ASC 处理完成 → deliver 推元数据、挂本次构建，并按输入决定是否提交审核及自动上架。

- 语言目录须与 App Store Connect 里**已启用**的本地化一致（当前：`zh-Hans`、`en-US`）。若 deliver 报某语言不存在，删掉对应目录或先在 ASC 启用该语言。
- 文本均为纯文本（不渲染 Markdown）。名称和副标题各上限 30 字符，描述和版本说明 4000，宣传文本 170，关键词 100。
- App Store 更新说明只写 iPhone / iPad 用户在本版本可获得的变化，不直接复用跨平台发布日志。2.0.0（56）曾因更新说明包含 Android 专属修复被 Guideline 2.3.10 拒绝；中英文均需去除这类内容，桌面托盘、安装命令等更新留在项目发布日志。公开文案检查会拦截 Android / 安卓字样。
- iPhone 截图固定为 1242×2688（`APP_IPHONE_65`，与版本页当前展示槽位一致），放在 `fastlane/screenshots/<语言>/` 根下，每种语言 6 张；文件名 `01-` 到 `06-` 决定展示顺序。
- **iPad 截图**（issue #334）固定为 2048×2732（`APP_IPAD_PRO_3GEN_129`，12.9 英寸 iPad Pro 竖屏），放在**子目录** `fastlane/screenshots/<语言>/ipadPro129/`，同样每种语言 6 张、`01-` 到 `06-` 排序。v1.9.7 起 iOS 包是通用二进制（`TARGETED_DEVICE_FAMILY = 1,2`），**没有 iPad 截图集就不能提交版本**。
  - 为什么放子目录：`fastlane deliver` 扫语言目录时是**非递归**的（`Deliver::Loader::LanguageFolder#file_paths` 只 glob `<语言>/*.png`），只对 `appleTV` / `iMessage` 这两个特殊目录下钻。所以子目录里的文件 deliver 完全看不见——这正是我们要的：deliver 靠**像素尺寸**猜机型，而 2048×2732 同时是 12.9 英寸二代（`APP_IPAD_PRO_129`）和三代（`APP_IPAD_PRO_3GEN_129`）两个槽位的尺寸，deliver 只能靠文件名里是否含 `ipadPro129` / `IPAD_PRO_3GEN_129` 去消歧（`Deliver::AppScreenshot.resolve_ipadpro_conflict_if_needed`）。与其让它猜，不如由 `scripts/sync-appstore-screenshots.rb` 按显示类型显式上传。目录名沿用 fastlane 自己的 `ipadPro129` 写法，看代码的人一眼能对上。
  - 工作流让 deliver 跳过截图，由 `sync-appstore-screenshots.rb` 统一同步两套截图，避免 deliver 清空它不会上传的 iPad 集合。
  - iPad 帧不走 ffmpeg 缩放：渲染器直接按 1024×1366 pt @ `Density(2f)` 出图，落盘即 2048×2732，文字不会被重采样。
- App Preview 用 `marketing/preview/make-preview.sh <lang> --compose` 从当前真实 Compose UI 帧自动生成；每种语言 1 个，上传新视频处理成功后才删除旧视频。模拟器交互录制仅作为可选验收路线。
- `python3 scripts/check-appstore-content.py` 会校验字段长度、三个 URL 字段格式、两套截图各自的数量/尺寸（顶层 6×1242×2688、`ipadPro129/` 6×2048×2732）、视频编码规格，拦住任何**没人上传**的多余子目录，并阻止已知问题功能或内部草稿语句进入商店。
- 只检查文字时使用 `python3 scripts/check-appstore-content.py --metadata-only`，不依赖截图、视频或 ffprobe；`ios-release` 在归档前执行，`ios-store-metadata` 的完整检查也包含同一规则。
- `review_information/notes.txt` 是给审核员的备注（Demo 模式入口说明——2.1a 教训），每个版本都会随提审带上，别删。
- 提审问卷答案（IDFA / 第三方内容）在 `fastlane/Deliverfile` 的 `submission_information`。
- 手动上传也应使用与工作流相同的文本、截图及视频分步流程；单独运行 deliver 不能完成 iPad 和 App Preview 上传。

字段与发布行为参考：[fastlane deliver](https://docs.fastlane.tools/actions/deliver/)、[Apple 产品页面说明](https://developer.apple.com/app-store/product-page/)。
