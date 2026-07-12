# 附件体系 — composer 附件流／消息流呈现／桌面拖拽（设计 handoff）

- **在线设计板**（登录即看）：
  - 手机 composer 附件流：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FFile+Attachment.html>
  - 消息流附件 + 播放器 + 浅色：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FSent+Attachments.html>
  - 桌面拖拽 + hover：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FDesktop+Attachments.html>
- **生成**：2026-07-11，claude.ai/design，cc-pocket 设计项目追加模式，三个 Prompt 顺序投递（模型 Opus 4.8 Medium；Prompt 1 中途撞会话限额一次，Resume 续跑完成，无内容损失）
- **对应 issue／任务**：#90 文件上传（任务 17）＋ #98 视频附件（任务 18）——**实现尚未开始**，等主会话按任务派发
- **本地打开**：目录下起 `python3 -m http.server` 后浏览器访问对应 HTML（jsx 经 Babel 运行时编译，`file://` 直开会被 CORS 拦）

## 文件清单

| 文件 | 内容 |
|---|---|
| `File Attachment.html` | 屏 1 入口：手机 composer 附件流（attach sheet 开 + 三 chip 条） |
| `file-attach.jsx` | 屏 1 组件源：Glyph 图标族／FileChip（进度环）／AttachSheet／middleTrunc（保扩展名截断）——**屏 2 复用其导出** |
| `file-attach-app.jsx` | 屏 1 页面装配 + 注释面板 |
| `Sent Attachments.html` | 屏 2 入口：消息流附件呈现 + 全屏播放器 + 浅色锁版（三台手机一屏） |
| `sent-attach.jsx` | 屏 2 组件源：theme-aware 文件 chip／视频缩略图／图片缩略图／播放器浮层（一份源码驱动暗浅两主题） |
| `sent-attach-app.jsx` | 屏 2 页面装配（dark／player／light 三列）+ 注释面板 |
| `Desktop Attachments.html` | 屏 3 入口：桌面 chat pane 两变体（drag-over／working） |
| `desktop-attach.jsx` | 屏 3 组件源：drop overlay／dense chip（2.5px 线性进度条）／hover 显隐 ×·↻ |
| `desktop-attach-app.jsx` | 屏 3 页面装配 + 注释面板 |
| `ios-frame.jsx` | 既有依赖：iPhone 设备框（屏 1／2 引用） |
| `ia-core.jsx` | 既有依赖：暗色 token 全集（屏 1／2 引用） |
| `desktop-core.jsx` | 既有依赖：桌面窗口框 + 300px 侧栏 + ChatPane 语汇（屏 3 引用） |

## 三屏内容

1. **屏 1 · 手机 composer 附件流**（暗色，iPhone 框）——attach sheet 锚在 composer 上方（Photo·File·Video 三选项一排，1.5pt 线性图标，terracotta 按压色，caption“Files are copied into this session's workspace · up to 200 MB”）；输入框上方三 chip 并排：PDF 上传中 64%（terracotta 进度环）／CSV 排队（虚线环）／代码文件失败（danger 色 + retry）；条上 caption“uploading 2 of 3…”；发送按钮等待态 spinner；“+”在 sheet 打开时旋转成“×”
2. **屏 2 · 消息流附件 + 播放器 + 浅色**——三台手机一屏：①暗色聊天流（用户轮叠文件 chip「2.4 MB · in workspace」+ `@inbox/report.pdf` mono 引用行、16:9 视频缩略图（居中播放键 + 0:42 时长丸）、图片缩略图延续现款；assistant 回复引用同一路径成闭环；末轮 failed 变体 danger hairline + tap to retry）②全屏播放器（scrim 压暗、左上 ×、播放/暂停、mono 计时 scrubber 可拖拽）③浅色锁版（#FAF9F7 底／#FFFFFF 卡面／#C15F3C 强调／#E4E1DB hairline，token 与 brief 精确一致）
3. **屏 3 · 桌面两变体**——A·drag-over：整个 chat pane 虚线 terracotta 边界 + 压暗遮罩 +“Drop to add to this session's workspace”+“images · files · videos — up to 200 MB”，侧栏不参与投放；B·working：已发送轮（dense 单行文件 chip + 视频缩略图，`@inbox/…` terracotta mono 路径）+ composer 两 pending chip（server.log 上传中带 2.5px 底边线性进度条／trace.txt 失败 danger hairline + retry）+“uploading 1 of 2 — send waits”提示；hover chip 才显 ×（取消）/ ↻（重试），13px mono，无重阴影

## 落地状态

**未实现**（本轮只到设计定稿归档）。实现按任务 17（#90 文件上传）→ 任务 18（#98 视频，被 17 阻塞）另行派发；jsx 即像素规格（token 色阶、尺寸、间距都在里面），落 Compose 时以此为准。

## 占位文案提醒（brief 原话）

稿内“200 MB 上限”“in workspace”措辞、`@inbox/...` 提法**均为占位**，实现时按 #90 的最终协议决策校准。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——三个 Prompt 的 LAYOUT／STATES 要求逐条落全（含浅色锁版四个 token 值精确一致）。
- 设计师主动加分项：“+”→“×”旋转过渡、queued 虚线环、middleTrunc 保扩展名、桌面 hover 显隐修了 `all:unset` 干掉 `opacity:0` 的 bug（`!important` 兜底）。
- **待拍板 1**：手机 chip 条三并排在 402pt 宽下文件名只剩约 8 字符（扩展名保留）；设计师给了备选——条改横向滚动、露 2.5 个 chip 换更长文件名。默认按稿内三并排。
- **待拍板 2**：桌面 `desktop-attach.jsx` 的 DocG 文件图标是独立拷贝，未复用 `file-attach.jsx` 的导出（屏 2 是复用的）；实现时应统一成一份图标源，避免两处漂移。
