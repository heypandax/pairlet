# App Store 元数据（fastlane deliver）

每次发版前改这里的 `*/release_notes.txt`（即 App Store「此版本的新增功能」）。`ios-release.yml` 现在是全自动闭环：`ios` job 上传二进制 → `submit` job（ubuntu，省钱）轮询 ASC 处理完成 → 一次 deliver 推元数据 + 挂本次构建 + 提交审核（`submit_for_review` 输入可关；`automatic_release` 控制过审后自动上架）。

- 语言目录须与 App Store Connect 里**已启用**的本地化一致（当前：`zh-Hans`、`en-US`）。若 deliver 报某语言不存在，删掉对应目录或先在 ASC 启用该语言。
- 每份上限 4000 字符；纯文本（不渲染 Markdown）。
- `review_information/notes.txt` 是给审核员的备注（Demo 模式入口说明——2.1a 教训），每个版本都会随提审带上，别删。
- 提审问卷答案（IDFA / 第三方内容）在 `fastlane/Deliverfile` 的 `submission_information`。
- 本地手动推送（需 .p8）：
  `fastlane deliver --app_version <版本> --api_key_path <key.json>`
