# CC Pocket 用户手册设计交接

## 在线设计

- Claude Design 项目：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=CC+Pocket+Manual.html>
- 访问范围：沿用当前 Claude 工作区权限，不额外公开。
- 落地状态：已实现到移动端、桌面端、官网与 README；公开手册由结构化内容生成。

## 文件

- `CC Pocket Manual.html`：完整设计画板，共 9 个 frame。
- `manual/manual.css`：画板样式、主题、响应式和组件状态。
- `manual/manual.js`：复制反馈、主题切换等演示交互。

直接在本目录启动静态服务器即可查看，例如：

```bash
python3 -m http.server 8000
```

然后访问 `http://localhost:8000/CC%20Pocket%20Manual.html`。

## 已确认的信息架构

1. 手机 App：`Settings` 中在 Token usage / Scheduled tasks 后加入 `Help & manual` 和 `Troubleshooting`。
2. 桌面 App：`Settings > Help` 提供搜索、热门任务、打开完整手册和 `Copy link for AI`。
3. Web 手册：首页按用户任务组织，不按功能模块或代码模块组织。
4. 文章页：首屏先给 Short answer，再给文字步骤；截图只用于确认界面位置。
5. 搜索：支持中英别名匹配、键盘操作、空结果和离线索引降级。
6. 上下文入口：首次配对、电脑离线、daemon 过旧等状态直接链接到精确文章。
7. AI 入口：每篇文章使用公开稳定 URL；`Copy for AI` 复制 URL 和一条供应商无关的检索指令。
8. URL 规划：`https://heypandax.github.io/cc-pocket/manual/{locale}/{slug}/`。

Frame 9 是额外的组件、token、断点和 URL 规则交接板，因此总数为 9。

## 截图方案

采用混合方案：

- Claude Design 负责手册、App 入口、搜索、文章结构和响应式版式。
- 最终教程截图由真实 Compose UI / 模拟器生成，避免设计稿与线上产品漂移。
- 原生截图 registry 使用稳定 scene ID；标注数字、箭头和说明文字由手册 HTML 覆盖，不烘焙进 PNG。
- marketing 视频渲染管线可复用 scene registry 和设备外框，但不作为教程截图的事实来源。

首篇样板文章预留三个 scene：

- `manual.schedule-send.long-press`
- `manual.schedule-send.open-sheet`
- `manual.schedule-send.created`

## 事实校验

最终稿已按仓库当前实现校正：

- 定时选项为 `30m / 1h / 3h / 8h / Custom time…`，可选 `Repeat daily`。
- 定时任务只通过 `Settings > Scheduled tasks` 查看状态和移除。
- 会话从创建起绑定一个 agent backend，不宣称会话中途切换 agent。
- IM 示例使用已存在的 Feishu bridge，不使用 Telegram。
- daemon 主升级命令为 `cc-pocket-daemon update`。
- 未把规划中的文章数量表达为已经上线的内容量。

## 实现状态

1. `site/manual/` 已包含 locale 路由、文章元数据、离线搜索索引和 8 个核心任务的双语文档。
2. Android / iOS Settings 已添加「帮助与手册」和「排查连接问题」入口。
3. Desktop Settings 已添加 Help tab，以及打开手册、热门主题和复制给 AI 的动作。
4. 官网、README、`llms.txt` 与 `sitemap.xml` 已加入手册入口和 AI 可发现链接。
5. 后续可根据站内搜索词、零结果查询和复制给 AI 的使用率，继续扩写文章并加入错误页上下文帮助。
