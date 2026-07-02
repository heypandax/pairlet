# App Store 元数据（fastlane deliver）

每次发版前改这里的 `*/release_notes.txt`（即 App Store「此版本的新增功能」），`ios-release.yml` 会在上传二进制后自动把它推到 App Store Connect 的对应版本（版本记录不存在时会顺手创建）。

- 语言目录须与 App Store Connect 里**已启用**的本地化一致（当前：`zh-Hans`、`en-US`）。若 deliver 报某语言不存在，删掉对应目录或先在 ASC 启用该语言。
- 每份上限 4000 字符；纯文本（不渲染 Markdown）。
- 只放 `release_notes.txt` 时 deliver 只更新 What's New，不会碰名称 / 描述 / 截图。
- 本地手动推送（需 .p8）：
  `fastlane deliver --app_version <版本> --api_key_path <key.json>`
