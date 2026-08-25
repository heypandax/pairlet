# App Store 元数据与截图（fastlane deliver）

`fastlane/metadata/<locale>/` 是描述、关键词、宣传文本、版本说明的来源，`fastlane/screenshots/<locale>/` 是商店截图来源，`fastlane/previews/<locale>/app-preview.mov` 是 App Preview 来源。当前启用 `zh-Hans`、`en-US`。

截图必须从真实 Compose UI 自动生成（脚本数据，不含真实用户数据），不要手工重画界面：

```bash
bash marketing/appstore/generate-assets.sh
# 只重新排版已有的最新 UI 帧：
bash marketing/appstore/generate-assets.sh --reuse
```

创建或同步一个**可编辑**的 App Store 版本，运行 GitHub Actions 的 `ios-store-metadata`，输入目标版本号。该工作流上传描述、关键词、宣传文本、截图和 App Preview，不上传 binary、不挂 build、不提交审核，也不会提前覆盖 `release_notes.txt`；最后通过 ASC API 反查 6.5 英寸槽位中的实际媒体数量。「此版本的新增功能」必须在正式提交 binary 时按真实代码差异填写。

正式发版仍走 `ios-release.yml`：`ios` job 上传二进制 → `submit` job 轮询 ASC 处理完成 → deliver 推元数据、挂本次构建，并按输入决定是否提交审核及自动上架。

- 语言目录须与 App Store Connect 里**已启用**的本地化一致（当前：`zh-Hans`、`en-US`）。若 deliver 报某语言不存在，删掉对应目录或先在 ASC 启用该语言。
- 文本均为纯文本（不渲染 Markdown）。描述和版本说明上限 4000 字符，宣传文本 170，关键词 100。
- 截图固定为 1242×2688（`APP_IPHONE_65`，与版本页当前展示槽位一致），每种语言 6 张；文件名 `01-` 到 `06-` 决定展示顺序。
- App Preview 用 `marketing/preview/make-preview.sh <lang> --compose` 从当前真实 Compose UI 帧自动生成；每种语言 1 个，上传新视频处理成功后才删除旧视频。模拟器交互录制仅作为可选验收路线。
- `python3 scripts/check-appstore-content.py` 会校验字段长度、截图数量/尺寸，并阻止已知问题功能或内部草稿语句进入商店。
- `review_information/notes.txt` 是给审核员的备注（Demo 模式入口说明——2.1a 教训），每个版本都会随提审带上，别删。
- 提审问卷答案（IDFA / 第三方内容）在 `fastlane/Deliverfile` 的 `submission_information`。
- 本地手动推送（需 .p8）：
  `fastlane deliver --app_version <版本> --api_key_path <key.json> --skip_screenshots false --overwrite_screenshots true`
