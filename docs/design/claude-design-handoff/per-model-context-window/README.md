# Per-model context window（issue #171）

设计稿在线地址（登录 claude.ai 即看）：
<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FPer-model+Context+Window.html>

composer 线程第 4 单，紧接 #170 的占用环（`context-statusline/`）——那个环点进去就是 Session Info，而本单把「改分母」的入口放在了同一张表上。

## 文件清单

| 文件 | 说明 |
|---|---|
| `per-model-window.jsx` | **像素规格源**：色阶、尺寸、间距、三个方案与五个状态都在这里 |
| `Per-model Context Window.html` | 板子的 wrapper |
| `ios-frame.jsx` | 设备框，`per-model-window.jsx` 依赖它渲染 |
| `screenshots/*-pmw*.png` | 设计侧自查截图（5 张） |

## 定案：Option B —— 拆开写与审

三个方案里选的 B：

- **写**在 **Session Info sheet**。那是唯一同时把「具体模型」和「错的那个分母」摆在屏幕上的界面，而且它就在那根要被修正的 bar 底下。
- **审**在 **Settings**。它是全局页面、可以在没有会话时打开，因而**没有「当前模型」可写**——所以它只保留兜底控件，外加一张只读可删的清单。

被否掉的：**A**（模型选择器持有写入口）——那是个用来快速切换的面，挂上数字输入会撑胀它，而且你要设的那个错数字在那儿根本看不见；**C**（Session Info 连兜底一起管）——兜底是全局值，必须在没有会话时也够得着，塞进只能从活跃会话打开的 sheet 里就把它锁死了。

## 核心冲突怎么解的

设计 brief 点名不许绕开的那条：per-model 静默压过兜底，用户给 `deepseek-chat` 设了 256K，之后在 Settings 点 `[200K]`，那个会话**毫无变化**——设置看起来就是坏的。（这正是此前发过的一个 bug 的手感。）

两道防线：

1. **列出来**：每个有自有值的模型都在兜底控件下方的清单里可见，当前正在跑的那个还带 amber「覆盖了兜底值」标——覆盖关系从不是秘密。
2. **当场说破**：用户一动兜底控件（点段位或改自定义值），若存在 per-model 条目，hint 立刻换成 amber 提示，点名它够不到的模型（「这不会影响 deepseek-chat 和另外 2 个模型」）。不是静默 no-op，而是当场解释。

## 落地状态

已实现并合入本分支，`mobile/composeApp` 手机端：

- `ui/Settings.kt` —— 兜底区措辞改为「上下文窗口 · 兜底」、冲突 amber 提示、新增「按模型的窗口」清单（空态 / 条目行 / 44dp 删除）
- `ui/SessionSheets.kt` —— `PerModelWindowRow`，继承 / 自有 / 编辑三态，接在既有 `ContextBar` 下方
- `data/PocketRepository.kt` —— 新增 `contextWindowKeyOf()`，把 private 的键归一化开放给 UI（避免在 UI 层重复实现 trim+lowercase）
- 双语文案 18 条（`values/` + `values-zh/`）
- `desktopTest/ui/PerModelWindowUiTest.kt` —— 8 例，全绿

**桌面端未动**（brief 明确排除）。协议零改动，纯客户端设置。

## 与设计稿的偏离（3 处，都是有意的）

1. **没做「not run recently」陈旧标**。设计稿给 `mixtral-8x22b` 画了这个标记，但仓里**没有任何「模型最近是否用过」的记录**（`byModel` / `modelUsage` 全仓零命中）。与其编一个信号出来，不如不标——设计真正要求的是「陈旧条目可见可删」，这一条是满足的。要补的话得先有 per-model 最近使用时间，那是另一笔。
2. **amber 用 `Tok.warn` 而非设计稿的 `#E0A93B` / 文字 `#EAD9B0`**。项目已有 `contextColor()` 色阶（#170 的环也复用它），跟着调色板走能随主题切换；代价是提示文字比稿子略艳一点。
3. **数字用千分位而非缩写**。`groupDigits()` 输出 `262,144` 而不是既有 `formatTokens()` 的 `262k`——这两个界面讲的就是用户亲手输入的那个精确数字，缩写会让「我填的到底是多少」变模糊。

## 未做的目验

真机（Pandaa iPhone）视觉尚未过目：编辑态键盘弹起时 sheet 的避让、清单行在长模型 id 下的省略表现、amber 提示在 OLED 上的观感。装机：`bash scripts/install-pandaa.sh`。
