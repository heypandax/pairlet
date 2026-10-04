# Read Aloud v1 — 朗读从一个按钮变成全 App 的小播放器

日期：2026-10-04（本机时区）。来源：用户试用 iOS 朗读后的反馈——离开界面就停、没有倍率、只在文件查看器里可用，希望做成对其他文本也适用的通用组件，并要求先补设计。

- 设计项目：cc-pocket Design System 2.0。
- [在线设计板：Read Aloud v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Read+Aloud+v1.dc.html)，共享组件 `ReadAloudPhone`；聊天输入区用的是现有 `ChatComposerV2`，未改动任何已有文件。
- 设计生成：Claude Design，界面显示 Fable 5.1 Max，一次生成约 34 分钟，使用用户的 Claude Design 额度。投递内容见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 范围：全局迷你播放条、展开的播放器、各处入口、原文跟读、八个状态、设置页、锁屏元数据；手机为主，平板和桌面只给出同一播放条的摆放位置。
- **状态：用户评审不通过（2026-10-04），不按此稿实现。** 评审意见：朗读不是主流程，这一稿却把播放条放进输入区上方的停靠区、在每条回复上常驻入口，干扰了聊天的主路径。重做时需要安静得多的控制方式。下文保留为这一稿的记录。 仓库工作区里另有一份未提交的 iOS 朗读在制品（文件查看器里的一个开关按钮），它是本稿要取代的现状，差距见文末。

## 问题

- 离开查看器朗读就停。听，恰恰是做别的事的时候才做的。
- 没有倍率。
- 只在一个界面、只对 .md 文件可用；同样的能力应该能读 App 里任何长文本。

## 采用方案：播放条放进交互停靠区

一个播放器，同一时间只读一项；只要在读或暂停，每个界面上都有它，否则不出现。

- **位置**：在输入区、提问卡和底部安全区的上方，一切可滚动内容的下方——也就是聊天页现有的交互停靠区的最上面一层。没有输入区的界面（查看器、会话列表、设置）里，同一条播放条贴在底部安全区上。键盘弹起时整个停靠区一起上移，不遮挡任何东西。
- **没采用的备选**：放在导航头部下方的一条。聊天页顶部已经有标题、上下文展开、置顶状态块和演示横幅，再加一个置顶区域会让每个界面都少一截正文，而且离手指最远。
- **一行一项**：播放／暂停、标题、段落计数、倍率、停止。开始读别的内容会替换当前项，播放条用 2.5 秒显示「已切换 · …」，计数重新开始；没有队列，被替换的不保留。
- **标题是回去的路**：点标题回到原文并滚到正在读的段落；点计数或空白处打开展开的播放器。
- **不加新颜色**：surface 底、发丝线、一个强调色；没有波形、封面、渐变，唯一的动效是图标切换和段落标记移动。

## 播放条

| 部分 | 规格 |
|---|---|
| 高度 | 52pt，200% 字号时 60pt；始终不高于收起的输入区（111pt） |
| 外观 | surface 底，朝向内容的一侧 1px 发丝线，通栏，无圆角无阴影 |
| 控件 | 播放／暂停 44pt（永不截断）、标题（最先截断）、计数（点开播放器）、倍率（44pt 点击区内的 30pt 胶囊，点一下换一档）、停止 44pt（永不截断） |
| 倍率 | 0.75×、1×、1.25×、1.5×、2×；播放条、播放器和设置页共用同一个值并记住 |
| 200% 字号 | 标题先省略；计数和倍率随字号放大；播放／暂停和停止不缩小也不消失 |

## 展开的播放器「正在朗读」

底部面板：标题和来源（文档名加项目路径，或会话标题加「回复 · Claude · 时间」）；上一段／播放暂停／下一段（48pt 点击区，播放圆环 64pt，首尾段时两端变暗）；4pt 的进度线，按「第几段／共几段」填充并标注，不是可拖动的时间轴；五档倍率的分段行；当前语音（按文本语言自动选择，仅显示，不可选）；「打开原文」。刻意不做：时间轴、波形、封面、语音选择、队列、睡眠定时、文稿视图。

## 入口

| 位置 | 做法 |
|---|---|
| 文件查看器头部 | 现有的喇叭图标保留，范围扩大到 Markdown 和纯文本文件；该文档正在读时图标点亮，再点为暂停 |
| 聊天里的 Agent 回复 | 「朗读」放在该回合来源行里「复制」的旁边，不增加一行；子 Agent 报告卡的头部行同样处理 |
| 语音备忘结果页 | 转写那一行上的「收听」；任务和摘要不读 |
| 长按段落 | 文档和长回复里的段落菜单在「复制」下多一项「从这里开始朗读」 |

## 跟读

原文在屏幕上时，正在读的段落用 surface 色调标出（6pt 圆角，不用竖线，那是引用块的样子），视图把它保持在距顶约三成处。用户手动滚动后停止跟随，朗读继续；停靠区上方居中出现「回到朗读位置」，样式同「跳到最新」。在别的界面只有播放条显示进度，回到原文后从当前段落恢复跟随。

## 八个状态

| 状态 | 表现 |
|---|---|
| idle | 没有播放条，也不留空位 |
| playing | 暂停图标、标题、计数、倍率、停止；段落标记随计数移动 |
| paused | 播放图标，标题变次级色，计数不动；标记停在将要继续的段落上 |
| interrupted | 「来电中断」或「麦克风占用 · 已中断」替换标题，倍率位置换成「继续」；来电结束后不会自己恢复 |
| finished | 「已读完 · 文件名」和「重新朗读」；六秒内没有操作播放条自行消失 |
| skipped code | 读那一句提示时标题位显示「已跳过代码块」，计数照常前进；代码块和普通段落一样带标记 |
| no voice | 设备上没有该语言的语音：在播放条的位置显示一行说明和「打开语音设置」，可关闭；不静默失败，也不换成别的语言去读 |
| unavailable | 平台没有语音引擎：所有入口都不出现（查看器头部没有喇叭，回复行只有复制，备忘没有收听，设置里没有这一行） |

## 设置

设置 → 通用里多一行「朗读」，显示当前倍率。进去是一个短页面：默认语速（与播放器相同的分段行，改这里也会改正在播放的速度）、「跳过代码块时提示」（默认开）、通往系统语音设置的链接行、一句「朗读在本设备上进行，使用系统语音，不会发送任何内容」。

## 锁屏

锁屏和切到后台后继续朗读。设计板给了两帧系统播放器作参考，只用来固定我们发给系统的元数据：标题与播放条相同；副标题固定为「Pairlet」，不放项目路径；封面用 App 图标；进度用段落序号和总段数表示，禁止拖动；播放、暂停、下一段、上一段对应系统命令和耳机按键。

## 证明帧与大屏

浅色、360×640 小屏（播放条不变，标题更早省略，输入区保持收起高度）、200% 字号两帧。平板（1024×768 横屏）和桌面窗口（1280×800）：播放条属于内容列，与输入区同宽并紧贴其上（桌面最宽 760），不进侧栏和标题栏；切换会话时播放条保持不动。这两帧是画在设计板上的布局参考，不是完整的设备组件。简体中文给了播放条的全部状态、展开的播放器、设置页和语音备忘共 12 帧。

## 文案（英文 / 简体中文）

语音那一行在两种界面语言下都用语音自己的语言书写。未列出的聊天和设置文案沿用现有字符串。

| key | English | 简体中文 |
|---|---|---|
| `read_aloud` | Read aloud | 朗读 |
| `read_aloud_active` | Reading | 朗读中 |
| `listen` | Listen | 收听 |
| `read_from_here` | Read from here | 从这里开始朗读 |
| `player_play · a11y` | Play | 播放 |
| `player_pause · a11y` | Pause | 暂停 |
| `player_stop · a11y on ×` | Stop reading | 停止朗读 |
| `player_open · a11y on counter` | Open the player | 打开播放器 |
| `prev_paragraph · a11y` | Previous paragraph | 上一段 |
| `next_paragraph · a11y` | Next paragraph | 下一段 |
| `counter` | {i} / {n} | {i} / {n} |
| `counter_long` | {i} / {n} paragraphs | 第 {i} 段，共 {n} 段 |
| `now_reading` | Now reading | 正在朗读 |
| `open_source` | Open source | 打开原文 |
| `speed` | Speed | 语速 |
| `speed_value` | 0.75× · 1× · 1.25× · 1.5× · 2× | 0.75× · 1× · 1.25× · 1.5× · 2× |
| `voice_en` | English · system voice | English · system voice |
| `voice_zh` | 中文 · 系统语音 | 中文 · 系统语音 |
| `source_reply_prefix` | Reply · {session title} | 回复 · {会话标题} |
| `source_report_prefix` | Report · {sub-agent name} | 报告 · {子智能体名} |
| `switched_prefix · 2.5 s` | Switched to · | 已切换 · |
| `interrupted_call` | Interrupted by a call | 来电中断 |
| `interrupted_mic` | Interrupted · microphone in use | 麦克风占用 · 已中断 |
| `resume` | Resume | 继续 |
| `finished_prefix` | Finished · | 已读完 · |
| `replay` | Replay | 重新朗读 |
| `code_skipped · bar + spoken line` | Code block skipped | 已跳过代码块 |
| `back_to_reading` | Back to reading position | 回到朗读位置 |
| `no_voice` | No English voice on this device. Add one in the system voice settings, then try again. | 此设备没有中文语音。请在系统语音设置中添加，然后重试。 |
| `open_voice_settings` | Open voice settings | 打开语音设置 |
| `settings_row` | Read aloud | 朗读 |
| `settings_row_sub · on / off` | Says when a code block is skipped / Skips code blocks silently | 跳过代码块时会提示 / 静默跳过代码块 |
| `settings_default_speed` | Default speed | 默认语速 |
| `settings_say_code` | Say when a code block is skipped | 跳过代码块时提示 |
| `settings_say_code_sub` | One spoken line replaces each code block. | 每个代码块用一句话代替。 |
| `settings_on_device` | Reading happens on this device with the system voice. Nothing is sent anywhere. | 朗读在本设备上进行，使用系统语音，不会发送任何内容。 |
| `settings_system_voice` | System voice settings | 系统语音设置 |
| `settings_system_voice_sub` | Better voices can be downloaded there. | 可在那里下载更好的语音。 |
| `settings_general_summary` | Dark · Default text size · Read aloud 1× | 深色 · 默认字号 · 朗读 1× |
| `lock_subtitle` | Pairlet | Pairlet |

## 动作与埋点名

实现时沿用现有遥测的事件与已注册维度的做法，这些名字用作取值来源；是否新增事件由实现时按遥测约定决定。

| 名称 | 触发与取值 |
|---|---|
| `read_aloud_start` | source = document \| reply \| report \| memo · from_paragraph · replaced = previous source, when one was playing |
| `read_aloud_pause` | from = bar \| sheet \| lock_screen \| headset |
| `read_aloud_resume` | from · after_interruption = true when it follows read_aloud_interrupted |
| `read_aloud_stop` | source · paragraph · total · from = bar \| sheet |
| `read_aloud_speed` | value · from = bar \| sheet |
| `read_aloud_next · read_aloud_prev` | from = sheet \| lock_screen \| headset |
| `read_aloud_expand` | bar → sheet |
| `read_aloud_open_source` | screen = viewer \| chat \| memo · from = bar_title \| sheet |
| `read_aloud_interrupted` | reason = call \| microphone · never resumes by itself |
| `read_aloud_finished` | source · total |
| `read_aloud_replay` | source |
| `read_aloud_follow_stopped · read_aloud_follow_resume` | manual scroll stops following; the pill or Open source resumes it |
| `read_aloud_no_voice` | lang = en \| zh · fired when an entry is tapped and no voice exists |
| `read_aloud_open_voice_settings` | from = notice \| settings |
| `read_aloud_setting_speed` | value · Settings page |
| `read_aloud_setting_skip_announce` | on = true \| false |
| `read_aloud_unavailable` | fired once per launch when no speech engine exists; entries hidden |

## 各界面的几何

| 界面 | 规格 |
|---|---|
| Bar | Full width · 52 pt tall (60 at 200 %) · 1 px hairline on the content-facing edge · surface fill · padding 4 left, 6 right · every control 44 × 44 (52 at 200 %) · speed chip 30 pt visual inside a 44 pt target. |
| File viewer | Bottom edge on the home-indicator area: offset 26 pt in these frames, 34 pt on device. Document scroll view gets a 52 pt bottom inset. |
| Chat | Top of the interaction dock, directly above the composer. Bottom offset = composer 111 pt + home 26 pt = 137 pt at 100 %, 167 pt at 200 %. Transcript inset grows by the bar height. |
| Chat · question card | Bar, then the card with its 8 pt margin, then the composer note and the home area. Bar never overlaps the card. |
| Sessions · Settings · Read aloud page | Same as the viewer: on the safe area, list inset 52 pt. |
| Voice memo result | Above the target-session row and the send button, which keep their size and position. |
| Small phone 360 × 640 | Unchanged bar. Title ellipsizes at ~140 pt. Composer keeps its collapsed height. |
| Tablet · desktop | Spans the content column at the composer’s width (desktop max 760), directly above the composer. Never in the sidebar or title bar. Same 52 pt and 44 pt targets. |

## 界面之外要做的

| 部分 | 内容 |
|---|---|
| Background audio | Audio session in a playback category with background audio enabled (iOS); a foreground media service with a MediaSession and notification (Android). Speech continues when the app is backgrounded or the phone is locked. |
| Lock-screen metadata | Now-playing info: title (bar string), subtitle "Pairlet", app mark as artwork, elapsed = paragraph index, duration = total paragraphs, seeking disabled. Remote commands: play, pause, next, previous → paragraph steps. |
| Interruptions | Session interruption began → interrupted (reason call); own microphone capture (dictation, voice-memo recording) → interrupted (reason microphone). On interruption end: stay interrupted, show Resume. Never auto-resume. |
| Text pipeline | Markdown → prose units (heading, paragraph, list item, code block). Code block → one spoken line when the setting is on, otherwise silent, but the unit still counts. Links read as their text. |
| Voice choice | Language detected from the text, not the UI locale. Pick the system default voice for that language; if none is installed, raise the no-voice state instead of falling back to another language. |
| Speed | One persisted value shared by the chip, the sheet and Settings; applied to the utterance rate on the next unit. |
| Engine check | Probe the speech engine at launch and when the app returns to the foreground; absent → hide all entries (state unavailable). |
| Progress to surfaces | The player publishes {source id, paragraph index}; viewer, chat and memo pages mark the unit and scroll it to 30 % from the top while following. |

## 设计师注明的边界

- 设计项目里没有 Design System 2.0 版的文件查看器设备，聊天、设置、备忘设备也不能在不修改的前提下加入口，所以 `ReadAloudPhone` 按现有设备的标记和 token 把这些界面重新搭了出来；`ChatComposerV2` 是原样引入的。
- 平板和桌面两帧是布局参考。
- 设计师建议在冻结文案前，先和工程过一遍「界面之外」那张表（后台音频会话、锁屏元数据、打断处理）。

## 与现有在制品的差距

工作区里未提交的 iOS 朗读（`Speaker*.kt`、`ReadAloud.kt`、`markdownSpeechText`）与本稿相比：

- 朗读的生命周期挂在查看器的按钮上，离开界面即停止；本稿需要一个应用级播放器，由它发布「来源、段落序号」给各界面。
- 文本按约 1500 字切块，不是按段落单元；本稿的计数、上一段下一段、跟读和锁屏进度都以段落为单位。
- 没有语速、暂停继续、打断处理和后台播放；后台播放要开启 iOS 的音频后台模式，并在审核备注里说明。
- 语言由整篇文本加上已插入的「代码块已跳过」提示一起判断，中文系统读带代码块的英文文档会被切到中文语音；本稿要求按文本本身判断，且没有对应语音时进入 no voice 状态。
- 只接了 `.md`；本稿的入口还有纯文本文件、聊天回复、子 Agent 报告、语音备忘和「从这里开始朗读」。
- Android 与桌面端目前是占位实现，入口自动隐藏，对应本稿的 unavailable 状态。

## 评审时请留意

- 锁屏和后台继续朗读是按建议值写进 brief 的，尚未得到用户确认；它决定是否要申请音频后台模式。
- 先做 iOS，Android 与桌面端随后，也是按建议值写入的。
- 「从这里开始朗读」放在段落长按菜单里，要与现有的链接长按菜单和文本选择一起核对手势是否冲突。

## 来源

设计板源码（`Read Aloud v1.dc.html`、`ReadAloudPhone.dc.html`）和 51 张逐帧截图保存在本机被忽略的 `_local/design/read-aloud-20261004/`，不进仓库；以在线设计板为准。
