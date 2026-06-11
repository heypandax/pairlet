# v1 设计稿补充（Design Supplement）

> 用途：补齐「实现已有、设计稿缺失」的画板。每节都是独立规格，可直接粘进 Claude Design（项目 cc-pocket）生成对应画板。
> 视觉语言沿用现有 token：`base #0E0F11 · surface #16181B · raised #1E2125 · hair #2A2E33 · tx #ECEDEE · tx2 #9BA1A6 · muted #6B7177 · accent #D97757 · ok #4FB477 · warn #E0A93B · danger #E5604D`；字体 Inter + JetBrains Mono。文案以英文为准（中文对照见 §8）。

---

## 1. 重连横幅（Reconnect Banner）

**场景**：会话激活后传输层断开。应用**不退出当前页面**，顶部出现细横幅，后台指数退避自动重连（1s→2s→…→30s），App 回前台立即重试。

**结构**：全宽细条，置于安全区下、页面内容上方（内容下移，不遮挡头部）。
- 背景：`danger` @ 14% 透明度；高度 ~28px（上下 padding 6px）
- 文案居中：`Connection lost — reconnecting…`（12px / Medium / `danger`）
- 无按钮、不可点；重连成功后横幅消失，内容回弹

**状态**：
- S1 重连中（常驻直到成功）
- S2 成功：横幅移除（无 toast）
- 横幅期间页面照常可读，但发送类操作会在恢复后生效

---

## 2. 观察模式条（Observing Bar）

**场景**：打开了一个**正在电脑终端里运行**的会话——手机只读跟看（tail），不能打字。composer 整体替换为观察条。

**结构**：全宽条，`surface` 背景，padding 12px：
- 左侧文案：`👁 Observing · running in a terminal`（13px / `tx2`），占满剩余宽度
- 右侧主按钮：`Continue here`（accent 实心，标准按钮）——点按接管会话：关闭只读 tail，重新以可控进程恢复，composer 复原

**状态**：观察中（唯一态）。消息流持续滚动更新，头部不显示模式徽章（观察态无权限语义）。

---

## 3. 斜杠命令菜单（Slash Command Autocomplete）

**场景**：composer 输入以 `/` 开头且未出现空格/换行时，输入框上方弹出命令面板，实时过滤。

**结构**：列表面板，`raised` 背景，最大高度 240px 可滚动，行 padding 16/8：
- 每行两层：
  - 第一行：`/命令名`（mono 13px / SemiBold / `accent`）＋ 参数提示（mono 12px / `muted`，如 ` <pr-number>`）＋ 右对齐来源标签（10px / `muted`）：`built-in` / `user` / `project` / `skill`
  - 第二行：命令描述（11px / `tx2`，单行截断）
- 点按行：输入框填入 `/命令名 `（有参数提示时补尾随空格），面板关闭
- 排序：前缀匹配优先，其余包含匹配；录音/转写期间面板不出现

---

## 4. 已配对主屏（Connect Screen）

**场景**：已配对但未连接（冷启动、手动断开后）。是已配对用户的「家」。

**结构**：垂直居中布局，padding 24px：
- 标题：`CC Pocket`（28px / Bold / `tx`）
- 副标题：`Paired · a1b2c3d4e5f6…`（14px / `tx2`，accountId 截 12 位）
- 主按钮：`Connect`（全宽 accent 实心）→ 经加密 relay 连接 daemon
- 次操作：`Unpair`（文字按钮，12px / `muted`）→ 清除配对回到 Pairing
- 状态行：mono 12px / `muted`，居中（`disconnected` / `connecting…` / `failed: <原因>`）
- 折叠入口：`Advanced · direct LAN`（文字按钮，12px / `muted`）→ 展开：daemon ws url 输入框（mono）＋ `Connect direct` 描边按钮

**状态**：S1 静止；S2 连接中（状态行变化，Connect 可重复点）；S3 失败（状态行红字原因）。

> P2 注：未来多电脑（Computers.html）落地后，此屏被电脑列表取代。

---

## 5. 新建会话模式选择弹层（Start-Session Mode Picker）

**场景**：Sessions 页点 `＋ New session` 后弹出，先选执行模式再开会话。与 ep-* 的 ModeSheet 同构，差异如下：

- 标题：`New session`；副标题：`Start in which mode?`
- 复用 4 阶梯模式行（无当前态勾选，默认无选中高亮）
- 选 `Full auto` 仍走 BypassConfirm 二次确认
- 底注（shield 图标）：`You can change this anytime from the badge.`
- 与安全不变式的关系：**不选即默认 default**；该弹层是显式覆盖入口，bypass 双重确认保留

---

## 6. 配对屏实现增项（Pairing Additions）

在 Pairing.html 现有画板上补三处：

1. **粘贴链接折叠区**：扫码区下方文字按钮 `can't scan? paste link` ⇄ `hide`；展开后：输入框（placeholder `paste ccpocket://pair link`）＋ `Pair from link` 描边按钮。覆盖另一台设备转发 `ccpocket://pair` 链接的场景（深链打开 App 自动配对）。
2. **状态行**：Connect 按钮上方，mono 12px / `muted` 居中：`pairing…` / `invalid pairing link` / `pairing failed: <原因>`。
3. **Advanced · direct LAN 折叠区**：同 §4，开发者本机调试直连用。

---

## 7. 目录页实现现状（Directory — as built）

> 设计稿的 Browse 文件系统（面包屑钻取）标 P2；v1 画板按以下现状修订：

**结构**：
- 头部：`Choose a directory`（SemiBold / `tx`）＋ 右侧 `Exit` 文字按钮（13px / `muted`，断开回 Connect）
- 筛选框：placeholder `filter…`，对路径做包含匹配
- 列表（支持下拉刷新，旋转指示器）：
  - 分区 `Open Sessions`（有 live 会话的项目）：行 = 会话标题（Medium 14px，无标题时 `session`）＋ 右侧 `running`（`accent`）/`idle`（`muted`）＋ 第二行 mono 11px：项目名 ＋ ` · ⑂ 分支`。**点按直进该运行中会话**
  - 分区 `Projects`（全部项目，live 项目会重复出现以保留「会话列表/新建」路径）：行 = 项目名 ＋ 第二行 `~/路径`（mono 11px / `muted`）＋ 右侧 `history` 徽章（11px / `accent`，有可恢复历史时）
- 空态：列表为空时仅显示筛选框（daemon 未配置目录属异常，状态行已在连接层表达）

---

## 8. 中英双语（Localization）

- 全部 UI 文案已资源化：`mobile/composeApp/src/commonMain/composeResources/values/strings.xml`（en，**设计稿基准**）与 `values-zh/strings.xml`（zh，约 110 条）。跟随系统语言；Android 13+/iOS 支持按 App 切换。
- 设计稿规则：画板继续用英文文案；**中文版画板仅在出 App Store 中国区截图时生成**——把 strings.xml 的 zh 值对照替换即可，无需常驻双语画板。
- 给 Claude Design 的注意项：中文按钮文案普遍更短（「连接」vs `Connect`），但模式描述行更长，4 阶梯行高需按 zh 最长行（`不自动允许任何操作 · 每个敏感工具都询问`）校验不折行。
- daemon 下发内容（权限规则、工具预览、错误详情）保持英文透传，画板中这些位置一律用 mono 英文示例。

---

## 9. 语音「确认即发送」注记（待拍板）

实现现状：录音点 ✓ → `Transcribing…` → 转写成功**直接作为 prompt 发送**（无 S4 回填编辑步）。若维持此决策：
- Voice Input.html 的 S4（Result）画板改名为「(removed in v1) — transcript sends on ✓」，S3 成功箭头直接指向消息流新增的用户气泡；
- S5 错误链路不变（错误 chip ＋ mic 重试，保留音频可重发）；
- 「never auto-sends」的安全表述改为：「✓ is the explicit confirm — cancel (✕) is always one tap away」。

若回retract 到设计方案（S4 回填）：实现侧改 `deliverTranscript` 回填输入框即可，设计稿不动。
