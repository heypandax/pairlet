# Windows 托盘浮层（Tray Flyout）· issue #292

> 来源：claude.ai/design「cc-pocket Design System 2.0」项目，2026-08-22 生成（Opus 5 Max）。
> 在线板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Windows+Tray+Flyout+v1.dc.html

## 文件

- `Windows Tray Flyout v1.dc.html` —— 交付板：四状态锚定图（default / calm / idle / attention-heavy，均为 1920×1080 右下角 1120×660 裁切）＋ NEEDS-YOU 行三态（rest / hover / focus）1.5× 细节＋溢出菜单（隐藏此浮层）＋构建注记。
- `WinTrayFlyoutDevice.dc.html` —— 活组件（像素规格源，实现以此为准）。

## 核心规格（实现速查）

- 载体：Windows 11 flyout 语法。**无指针箭头**；角锚定（距右屏边 12px、任务栏上方 12px），**不跟随托盘图标 x 坐标**；宽 360px、高自适应内容。
- 表面：填充 `#16171B`（比 app surface `#141417` 高一阶）；顶部 1px 高光线（白 2%→14%→2% 渐变）；96px 自顶向下 3.5% 白色 wash；边框白 8.5%；阴影=宽软投影＋紧贴触影（比 mac 更重，补偿无真实 acrylic）。窗口圆角 8px、控件圆角 4px（比 mac 更方，Windows 原生感所在）。
- 行高：needs-you 48px（两行：标题＋所请求工具）、running 44px（单行）；一律单行截断，happy path 不滚动。
- 上限：审批 3 条、运行 6 条；超出走「还有 N 条在等 → 打开审批中心」accent 行与页脚 +N。
- 悬停：**行**亮起（fill 白 5.5%＋左侧 2px terracotta inset 边），允许钮暖一阶＋柔和 accent halo；键盘焦点用允许钮 outline，与指针路径视觉区分。
- 可关性：头部 (…) 菜单第一项「隐藏此浮层」（写现有设置开关）＋「通知设置…」＋说明文字（隐藏后托盘图标仍显示待审批数）。
- 动效：入场 250ms 上升 22px＋淡入 ease-out；出场 150ms 纯淡出；点外部 / Esc / 再点托盘图标关闭。
- token：不新增。terracotta `#E2795A` 仅用于紧迫、绿 `#7ECBA0` 运行点、Inter UI、JetBrains Mono 机器名/耗时。

## 边界

- **仅 Windows**；macOS 保持现有指针箭头下拉形态，一行不改。
- 内容模型与 mac 完全一致（TrayPopover 的数据折叠复用），改的只是载体。
- 稿内壁纸/任务栏图标为占位，与实现无关。

## 落地状态

- 实现：见 git 历史（#292 形态实现 commit）。
- Windows 真机目验：挂起，随发版前统一验收波次。
