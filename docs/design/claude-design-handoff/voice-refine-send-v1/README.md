# Voice Refine Send v1 — 语音说完即发与发送前校对

状态：原型已由 Claude Design 交付（生成结束后页面自动自检，修复了两处板子裁切），**已评审**（2026-10-06，负责人委托 Fable 代为决定，见文末「评审决定」）。daemon 与协议（M1）实施中，手机端（M2）待 M1 合入后按本文与评审决定实施；不再追加设计轮次。

- 日期：2026-10-05 投递，2026-10-06 交付（本机时区 PDT）。来源：维护者反馈语音输入常把几个词识别成同音错词，且「✓ → 落输入框 → 再点发送」要三步。技术方案见 [语音输入 v2：说完即发，发送前由轻量模型校对](../../VOICE-INPUT-REFINE-SEND.md)；投递原文见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 设计项目：cc-pocket Design System 2.0。[在线设计板：Voice Refine Send v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Voice+Refine+Send+v1.dc.html)。
- 文件：`Voice Refine Send v1.dc.html`（板子，推荐结论 + A–G 七区，共 47 个组件实例）、`VoiceRefineSendDevice.dc.html`（活组件：状态机、中英字符串表、外部控制面板都在里面）。两份只引用共享运行时 `support.js`，没有本轮新增的 `.js`。对话记录里只有这两份文件的创建和修改；文件切换器里其余文件的最近编辑时间都早于本轮开始，未见改动既有文件。续跑前项目共 75 页，板子建出后 76 页；第一轮之前的页数没有记录，也无法逐字比对既有文件的历史版本。
- 生成：Claude Design，界面显示 Fable 5.1 Max，使用用户的 Claude Design 额度。归档与验收全程只读，没有发起新的生成；自检修复由页面自动发起。
- 原件（两份最终源码、自检前的源码副本、板子正文、逐节截图、原型操作截图与日志、静态帧测量）在本机 `_local/design/voice-refine-send-v1/`，不入库。

## 生成过程（如实记录）

| 时间（PDT） | 事件 |
|---|---|
| 10-05 20:45 | brief 投递到新对话，界面显示 Fable 5.1 Max |
| 10-05 20:58 | 第一次中断：第一轮读完项目文件后无声结束，没有写文件也没有回复 |
| 10-05 20:59 | 同一对话追加一句「继续并实际创建设计板」，生成重新开始 |
| 10-05 21:04 | 第二次中断：Claude Design 用量上限触发；此时活组件 `VoiceRefineSendDevice.dc.html` 已建出状态面板，板子文件尚未创建 |
| 10-06 01:46 | 用量窗口恢复，点「Resume」续跑同一轮（保留完整上下文，未重发 brief） |
| 10-06 01:47 | 板子文件 `Voice Refine Send v1.dc.html` 建出，项目 75 → 76 页 |
| 10-06 01:52 | 生成结束 |
| 10-06 01:52–01:59 | 页面自动自检（"Checking the design for issues…"），发现两处板子裁切问题并自动修复：Anatomy 第二个窗口 300 → 420px 且改为底部对齐，让录音条整行可见；B 时间条五帧由 `transform:scale(.85)` 改为 `zoom:.85`，说明文字与帧的间距一致。只改了板子文件；组件文件与板子正文前后逐字相同 |

## 问题

今天（#221 之后）口述一条提示要三步：点 ✓ → 文字追加到输入框草稿、键盘弹起 → 读一遍、改掉错词后再点发送。错误几乎都是少数同音字、听错的术语和英文音译（「用功体验」→「用户体验」、「cloud」→「Claude」、「edit」→「effort」），轻量模型能可靠修正。维护者希望 ✓ 直接发送，由发送前的校对保证安全，并让等待可见且短。

## 推荐方案（板子结论）

- **两个控件**：
  - 前导控件：去掉 ✕，换成键盘字形（Apple 听写和 Gboard 语音输入里都表示「改为打字」），ink3、48pt、无文字标签。无障碍名：录音中「Finish and edit / 完成并编辑」，等待中「Edit instead / 改为编辑」。作用：结束并把文字放进输入框，不发送。
  - 尾部 ✓：字形、强调填充、位置都不变，只改名：会发送时「Finish and send / 完成并发送」；不会发送时（无校对器、设置为「放入输入框」）沿用现有「Done / 完成」，外观不变。
  - 录音条里不再有任何破坏性控件。
- **等待怎么呈现**（B 时间条：Android、Claude、校对 5s、三处替换；帧下标注 0.0s / 0.9s / 3.0s / 5.1s / 6.1s）：
  - 点 ✓ 后，✓ 位变成细线圆环（line2）裹着现有的 15pt 转圈，`aria-hidden`、不可点。这时全屏没有强调填充控件，再点一次也不会出事。
  - 药丸去掉红点和波形，只放一句状态加冻结的计时：`Uploading 1 of 1`（Android 音频上传中）→ `Transcribing…` → `Correcting · Claude` → `Sending…`。状态文字是 polite 实时区域。
  - 原文一出现就显示在录音条上方的预览框里：iOS 沿用录音时的实时转写框、原地不动；Android 在转写完成时以 220ms 变形长出。圆角 14，最多 6 行，超出在框内滚动并钉在末尾，不滚动去找屏幕外的改动。
  - 替换到达时只高亮改动片段（强调色字 + accBg 底，保持 200ms 后 800ms 淡回 ink2），其他字不动。
  - `Sending…` 停留 1s（没有替换时 300ms），让人看清改了什么，然后自动发出；录音条以 220ms 收起，消息作为普通 YOU 回合出现，不加任何标记。
  - 成功时通知行保持安静，不显示「Sent · 3 corrections」：打字发送也没有回执，事后也改不了，通知槽要留给失败结局。
  - 等待中点前导控件 = Edit instead：校对结果已到就放校对后的，否则放原文，都不发送。它一直可点，直到发送真正发出。走前导控件路径时，转写期间它是灰的（ink5、`aria-disabled`）。
- **丢弃**：在输入框里删掉就够了，不另设丢弃控件。录音条里加丢弃，要么是第三个控件（320pt 放不下），要么让现有控件身兼两义；两者都会带回「一下点掉刚说的话」这个本次要消除的错误，而长按、滑动又不可见。代价是长段口述后要长按删除键。
- **其他结局**（C 区）：凡是不能发送的都落输入框、光标在末尾、键盘弹起、不发送，通知槽一句平静的原因。

| 结局 | 处理 | 通知行（en / zh） |
|---|---|---|
| C1 校对超过 8s | 原文落输入框，Send 可用；原因行保留到首次编辑或发送（板子标注这是对 2.5s 通知行的有意偏离） | Correction took too long — check and send / 校对超时，请检查后发送 |
| C1b 迟到结果 | 输入框没动过：替换照样带高亮换进去，原因行和光标保留；已打字：丢弃并记日志；不发送 | 同上 |
| C2 没有校对器 | ✓ 名为 Done；原文落输入框，Send 照旧。点击前录音条不提示，原因在点后给出 | 例：Claude is signed out on MacBook-Pro, so nothing was corrected — check and send / MacBook-Pro 上的 Claude 未登录，未校对，请检查后发送；未安装、Pairlet 版本旧、无一次性模式各一句（见字符串表） |
| C3 替换未通过校验 | 同 C2；用中性圆点，不用危险方块（识别本身没失败） | Corrections didn’t apply — check and send / 校对结果未采用，请检查后发送 |
| C4 转写失败 | 不变：现有危险提示 + Mic 位置的「Retry voice input」 | Couldn’t transcribe — try again / 转写失败，请重试 |
| C5 没听到 | 不变：现有通知 2.5s，空转写不启动校对 | Didn’t catch any speech / 没有听到语音 |
| C6 回合进行中 | Running 块和 Stop 在录音、转写、校对全程保留；发送像打字发送一样排进进行中的回合 | 无 |
| C7 等待中断连 | 文字在手机上，原文落输入框，上下文行显示电脑离线；发送尚未发出，不会重复 | 沿用现有连接文案；板子写的是占位：{computer} disconnected — your text is here, nothing was sent / 与 {computer} 断开连接，文字已保留，未发送 |
| C8 设置为「放入输入框」 | ✓（名为 Done）照样先校对，校对后的文字带高亮落输入框；前导控件仍是「不等校对，立刻给原文」 | 无 |

- **设置**：Settings › General › 语音输入新增一行「After dictation / 语音输入后」，两个取值：「Correct and send / 校对后发送」（默认）和「Put in the composer / 放入输入框」，各带一句说明 ✓ 会做什么、校对在你自己的电脑上由你自己的 Agent 完成。iOS 放在 Whisper 开关下面；Android 新增这一节，只有这一行。形式是取值行（直接显示当前值的说明）→ 点开二选一的单选页。
- **被否决的备选**（A3）：前导控件写成文字「Edit」。文字标签最诚实、一眼可读，但它会是录音条里唯一带字的控件，两端不再成对；它随语言和字号变宽，在 320pt 和 200% 时挤掉波形；键盘字形在 Apple 听写和 Gboard 里本来就是这个意思。「Edit」改放进无障碍名。

## 可见验收（实际操作原型）

操作方式：在板子 A 区的活组件「A · Prototype」上用外部控制面板切换条件，再点手机里的 Mic、✓、前导控件、输入框和设置行；每一步读取组件 DOM（药丸文字、预览、高亮片段、按钮无障碍名、通知行、输入框、键盘、对话里的 YOU 消息、原型日志），并截图。

环境限制：Chrome 窗口被其他应用遮挡，页面处于隐藏状态，定时器被对齐到 1 秒，实测时长比设计值多 1–2 秒；不到 1 秒的过渡态，截图常拍到下一个状态，这类以 DOM 采样为证。操作日志与截图在 `_local/design/voice-refine-send-v1/shots/proto/`。

| # | brief 要求 | 结果 | 依据 |
|---|---|---|---|
| 1 | Record → ✓ → 原文预览 → 替换高亮 → 收起、消息进对话 | **已操作验证** | ✓ 后 +0.0s `Uploading 1 of 1` → +0.9s `Transcribing…` → +1.8s `Correcting · Claude` 并显示原文 → +6.5s `Sending…`，高亮 `Claude` / `effort level` / `daemon side` → +8.7s 录音条收起，对话多出校对后的 YOU 消息（日志 `voice_send · corrected=true · queued=false`）。等待中全屏没有强调填充控件，✓ 位是不可点的圆环 |
| 2 | Record → 前导控件 → 落输入框、光标在末尾、键盘弹起、不发送 | **已操作验证** | 转写期间前导控件变灰并 `aria-disabled`；随后原文落输入框，光标在末尾、键盘弹起，Send 是唯一强调填充控件；约 6s 后对话里仍只有原来那条 YOU |
| 3 | Record → ✓ → 等待中点前导控件 → 落输入框（已校对就用校对后的）、不发送 | **已操作验证** | 5s 档在 `Correcting` 时点：原文落输入框（`voice_refine · result=cancelled · stage=correcting`）。2s 档在 `Sending…` 停留时点：校对后的文本落输入框、不带高亮（`voice_send · cancelled before issue`）。两次都等约 8s，确认没有发送 |
| 4 | 超过 8s，迟到结果在输入框未动时替换进来 | **未动分支已操作验证**；「已打字则丢弃」分支**未能操作验证** | 9s 档：校对开始约 8s 后原文落输入框并显示 `Correction took too long — check and send`；约 0.9s 后替换带高亮换入，原因行保留，未发送。丢弃分支试了两次，都因定时器对齐使超时与迟到结果同时触发，没能在两者之间打字，只读到源码（输入框没打过字才套用）。另：原型说明「日志会写明套用还是丢弃」，实测套用时日志里没有这一行（源码在 `setState` 的更新函数里调用 `note()`，未生效） |
| 5 | 无校对器结局 | **已操作验证** | 选「none · signed out」：录音中 ✓ 的无障碍名变为 `Done`，外观不变；点后原文落输入框，显示 `Claude is signed out on MacBook-Pro, so nothing was corrected — check and send`，约 6s 后仍在，未发送。「older Pairlet」「no quick pass」各显示对应原因行；后者写成「Claude can’t run a correction pass」，这个原因指的 Agent 本应不是 Claude（原型数据问题） |
| 6 | iOS 实时转写变体开关 | **已操作验证**，发现一处原型缺陷 | iOS：录音中文字逐词出现在录音条上方，易变尾部为灰色，有光标；Android：录音中没有这个框。缺陷：iOS 点 ✓ 后的 `Transcribing…` 阶段预览框消失（DOM 采样 `preview: null`），到 `Correcting` 才带全文重新出现，与板子「点 ✓ 后框原地不动」的说明不符（源码 `showPreview` 在这一阶段为假）。过渡不到 1s，截图没拍到 |
| 7 | 切换设置，看两种取值下 ✓ 的行为 | **已操作验证**，发现一处原型缺陷 | 在设置页里改取值：单选状态、设置行的取值与说明、外部芯片同步变化。「校对后发送」：✓ 名为 `Finish and send`，实际发送。「放入输入框」：✓ 名为 `Done`，经 `Transcribing… → Correcting · Claude → Sending…` 后把校对后的文本带高亮放进输入框，不发送。缺陷：药丸显示了 `Sending…`（设计值 1s），与实际不发送矛盾，也与 C8 的说明（只写到 `Correcting · Claude`）不符 |

其余结局也在活组件里走过，表现与板子一致：校验失败（C3）；无需修改（`Sending…` 短停留、不高亮、发送原文）；转写失败（C4）；没听到（C5，数秒后消失）；等待中断连（C7）；Codex 校对（药丸显示 `Correcting · Codex`）；A3 文字版前导控件（51×48，可用）。

回合进行中（C6、B7）**只读到设计**：活组件的普通输入区照抄了设计系统里旧版的 `ChatComposerV2`，Mic 画在工具条上且只在空闲时出现，「agent streaming」时找不到 Mic，无法在活组件里开始录音。

硬约束核对（47 个静态实例逐一测量，加活组件各状态采样）：

| 约束 | 结果 |
|---|---|
| 主路径（读、回答、审批、发送）不移动、不缩小、不改名 | 头部、上下文行、消息行、Send 槽位与 ChatDeviceV2 一致；录音条只在自己的槽内变化，预览框把输入区向上撑高（E5 时对话区明显变矮）。但板子画的「现有」输入区有几处与生产不符：Mic 位置、回合进行中的 Stop、输入框行数（见下文「差异」），实现须以生产为准。板子没有待审批、待回答时的帧，这部分未能核对 |
| 不新增横幅、停靠条、消息流卡片；已发送消息不加标记 | 符合。新增的只有录音条上方的预览框（brief 给出的起点）和现有通知槽里的原因行；B5、C6 与活组件里发出的 YOU 消息都没有标记 |
| 同屏只有一个强调填充控件 | 符合：47 个实例每个 ≤1（录音时只有 ✓，等待时为 0，落输入框后只有 Send）；活组件各状态采样相同 |
| 触控目标 ≥44pt | 本轮控件：前导控件、✓、圆环 48×48（200% 为 58×58）；药丸高 ≥44（200% 为 54，状态文字换行时 64）；文字版「Edit」51×48。小于 44 的只有输入区工具条上三个既有 chip（高 30，照抄 ChatComposerV2） |
| 320pt 宽、200% 字号不裁切 | 符合：E2、E3 药丸宽 168，控件不出界；E4、E5、E8、F12 没有文字溢出或控件出界；E5、F12 预览在 216pt 封顶并在框内滚动。板子写 200% 时药丸宽 220，实测 230 |
| 中文帧与英文帧对应 | 12 个中文帧对应 A2、B 0.0s、B 0.9s、B 5.1s、C1、C2、C3、C7、C4、D1、D3、E5，录音条、通知行、设置文案一一对应（消息角色标签 YOU / CLAUDE 在中文帧里也未翻译，属消息渲染，不在本轮范围）。缺少：C5「没有听到语音」（F9 标题写了，画面里只有转写失败）、A3「编辑」、B7「校对中 · Codex」、另外两种无校对器原因（只在字符串表里） |
| 只新建本轮文件 | 符合，依据见开头「文件」一条 |

## 评审（先查层级与主路径侵入，再看细节）

通过项：

- 录音条只在原槽位内变化，没有新增行、横幅、卡片或消息标记；等待中全屏零强调填充、✓ 位不可点，连点 ✓ 不会出事（B 区、验收第 1 条）。
- 每个不能发送的结局都不丢字：原文或校对后的文本落输入框、光标在末尾，原因一句话（C1–C3、C7，验收第 3–5 条）。
- 状态以文字为准：药丸每个阶段一句话，转圈和高亮只是陪衬；状态文字是 polite 实时区域，圆环 `aria-hidden`（组件模板）。
- 尺寸：48pt 目标；320pt 与 200% 不裁切；宽度变化只由药丸吸收（E2–E5 测量）。
- 文案平静、无感叹号；三种原因行共用「— check and send / 请检查后发送」结尾；Agent 用产品名（F 区说明）。

疑虑项：

1. **✓ 的外观不随「这次会不会发送」变化。** 无校对器和「放入输入框」时只把无障碍名换成 Done（C2、C8，验收第 5、7 条），视觉用户点之前无从得知。本轮 Codex 会话一律没有校对器，等于 Codex 用户的 ✓ 永远不发送，而且每次都出一行原因。
2. **「放入输入框」仍先跑校对**（C8；G 区「Setting」）：要等 4–6s、花 Agent 额度才拿到文字，与技术方案 §6「设置为放入输入框时直接走现有落输入框路径」冲突。原型在这条路径还显示了 `Sending…`（验收第 7 条）。
3. **原因行保留到首次编辑或发送**（C1；G 区「Notice line timing」），偏离现有 2.5s 通知行，板子自己注明了是有意偏离。现有通知槽 `voiceNotice` 已与新建会话的两条提示共用（各 4s），要给它加「随编辑或发送清除」的寿命。
4. **`Sending…` 停留 1s** 让每次校对后的发送多 1s。技术方案估计 ✓ 后约 4–6s 可发（CLI 墙钟实测 5.7–6.5s），停留再加 1s。板子说这是唯一的可调参数。
5. **板子画的「现有」界面有几处与生产不符**：Mic 在工具条且回合进行中隐藏、Stop 行是整宽文字按钮、输入框 6 行、通知行带圆点等（见下一节）。根源是设计系统里的 `ChatComposerV2` 落后于生产（#238 之后 Mic 已在输入框内）。照图实现会改动主路径，必须以生产为准。
6. **原型缺陷**：「放入输入框」时显示 `Sending…`；iOS 点 ✓ 后预览框闪断；迟到结果的日志行不出现；「no quick pass」原因里的 Agent 名写死为 Claude；源码中迟到结果分支对 `invalid` 也会套用替换（`corrected: x.outcome !== 'none'`，未实测）。这些都在原型实现层，板子文字的意图清楚。
7. **去掉 ✕ 的代价**：误触录音也只能先转写（Android 还要上传，转写占用电脑上的 Whisper），落输入框后再删。brief 与技术方案都已接受这一点。但今天 90s 录音上限等同点 ✓（`beginTicker()` 里调用 `stopVoice()`），新语义下会变成「自动校对并发送」，实现时要改。
8. **未覆盖的状态**：已有草稿或已暂存附件时点 ✓（今天是把转写追加到草稿末尾）；待审批、待回答时口述；等待中离开会话（今天 `abandonVoice()` 静默清空，尚未送达的转写会丢）；附件仍在上传或压缩、`sendPrompt` 拒绝发送时如何回落。

## 与产品现状及工程事实的差异（实现时以现状为准）

1. **Codex 不是本轮的校对器。** 实测常驻 `codex app-server` 的替换列表要 10.0–12.3s，超过 8s 预算，也没过技术方案 §4.3 的 6s 门槛。本轮 Codex 会话按「无校对器」回落输入框；板子里把 Codex 当校对器的画面（B7 的 `Correcting · Codex`、原型的「Codex」档）只作远期参考。设置说明里的「the session’s agent — Claude or Codex — / 本次会话的智能体（Claude 或 Codex）」本轮不成立，要改写；Codex 会话的原因行也没有合适的文案（板子 `no_one_shot` 的「Codex can’t run a correction pass」不准确：Codex 有一次性模式，只是太慢）。
2. **Claude**：用 `sonnet` + `low` + 替换列表，✓ 之后约 4–6s；`haiku` 两轮复测仍为 44–100s，不作默认。板子 B 时间条按「5s 校对 + 1s 停留」画，量级相符。
3. **板子对现有界面的描述与源码不符**（对照 main `0940d459` 的源码）：
   - 录音条（`ui/VoiceComposer.kt` 的 `RecordingBar`）：✕ 是 18dp 的 `XSmallIcon`、`Tok.muted`，无障碍名 `cancel_recording`；红点 8dp（板子 9pt）；波形高 28dp、条宽 2.5dp（板子高 22、条宽 3pt）；计时 12.5sp `Tok.tx2`（板子 13、ink4）；✓ 是 48dp 目标内 44dp 的可见圆（`RoundActionButton(filled = true)`）；转写态的 17dp 转圈在药丸里，✓ 仍显示、可点但无效。这些属于停止前的外观（brief 要求保持），以源码为准。
   - 上传文案是现有的 `file_strip_uploading`：「uploading %1$d of %2$d… / 正在上传 %1$d/%2$d…」，不是板子标「现有」的「Uploading 1 of 1 / 正在上传 1/1」。
   - iOS 实时转写框（`LiveTranscriptField`）只在 `Recording` 时显示，点 ✓ 即消失；样式是 `Tok.base` 底、圆角 12、14.5sp、不限行数、光标 2×16dp。板子写的「点 ✓ 后原地变成预览」「最多 6 行」「Android 也出现」都是新行为，不是现状。
   - 回合进行中录音时的 Stop：今天是录音条下方单独一行、靠右的紧凑圆形按钮（`StopButton` → `RoundActionButton(filled = false)`，内含 12dp 强调色方块），不是板子 B7、C6、E3 的整宽文字按钮。
   - Mic：今天是输入框的 `trailingAction`（`VoiceActionButton`，48dp 目标），空闲、有草稿、上传中、回合进行中都可用（#238），失败或慢网时同一位置变成「Retry voice input」。板子按旧版 `ChatComposerV2` 把 Mic 画在工具条、只在空闲时出现，重试画成工具条上的描边按钮。
   - 输入框：`ComposerField` 最多 4 行（`TextFieldLineLimits.MultiLine(maxHeightInLines = 4)`），板子落地态画成 6 行（155pt）。
   - 通知行（`voiceNotice`）：今天是输入区上方一行 12sp `Tok.tx2` 纯文字、无圆点，由 `showNotice` 定时清除（没听到 2.5s；新建会话的两条提示 4s，共用此槽）。板子的「8pt 圆点 + 13/1.45 ink3」以及原因行常驻都是新设计。
   - 失败提示：今天是 `VoiceErrorChip`（`ComposerRibbon(danger = true)`：淡红底、描边、8dp 方块），内容优先显示 daemon 或系统给的原文（`failed.detail`，daemon 的为英文）；缺转写程序、模型或音频转换器时换成带「Ask Agent to set up / 让 Agent 处理」按钮的 `VoiceSetupChip`。板子 C4 只画了一行红字，并标「不变」，实现保持现状。
   - 设置：语音输入一节今天只在 `NativeDictation.available` 时显示（Android、桌面恒为假；iOS 没有可用识别器时也隐藏），只有一个开关「Transcribe on the computer (Whisper) / 用电脑 Whisper 识别」，说明是「Better for mixed-language speech; audio is sent to your computer after you stop, so there's no live transcript. / 中英混说更准；说完后录音发到电脑识别，没有逐字实时预览。」板子标「现有」的中文名「在电脑上转写（Whisper）」和两种语言的说明都是新写的。通用页的外观、字号今天是页内分段控件，不是板子画的「值 ›」行；语音输入下面还有「实验」一节（语音备忘），板子没画。
   - 字符串是 Compose Multiplatform 资源（`composeResources/values*/strings.xml`，snake_case 键）。板子给「现有」字符串起的新键（`voice_dictate`、`voice_state_transcribing` 等）要换回现有键（`dictate`、`transcribing` 等，见下表）。
4. **协议与数据，以技术方案为准**：
   - 能力：板子要求 daemon 按会话公布 `corrector_available` / `corrector_agent` / `corrector_reason`（signed_out、not_installed、version、no_one_shot）。技术方案是 `DaemonInfo.transcriptRefineAgents`（按 daemon，不带原因）加 `TranscriptRefined.error` 固定码（unavailable、invalid、timeout、failed、superseded）。要显示板子那几种原因，需在方案里补原因字段和对应探测（例如 CLI 是否登录）。
   - 套用：板子写「手机套用替换，电脑只校验」；技术方案是 daemon 校验后返回 `text` 和 `edits`，手机只用 `edits` 做高亮。
   - 去重：板子要求「发送带 capture_id，重连后按 capture_id 查询」。现有 `SendPrompt.promptId` + `PromptAck` + `DaemonInfo.supportsPromptRecovery`（#66）已保证同 id 重发不重复投递，不需要新的查询。
5. **埋点**：今天没有任何语音输入埋点，口述发出的消息只记通用的 `prompt_sent`。板子的事件名单（G 区）含 `capture_id`、原始毫秒数和字数，不符合 [EVENT-CATALOG](../../../observability/EVENT-CATALOG.md) 的固定枚举、不带会话或设备 ID 的约定；实现按技术方案 §7 的分桶维度登记。

## 工程需要什么（板子 G 区，取自源码 `S(...)` / `KV(...)`）

### 字符串

| 板子 key | 用途 | en | zh | 实现对应 |
|---|---|---|---|---|
| `voice_dictate` | Mic 无障碍名 | Dictate（现有） | 语音输入（现有） | 现有 `dictate` |
| `voice_finish_send` | ✓ 会发送时的无障碍名 | Finish and send | 完成并发送 | 新增 |
| `voice_done` | ✓ 不会发送时（无校对器、设置为放入输入框） | Done（现有） | 完成（现有） | 现有 `done` |
| `voice_finish_edit` | 前导控件，录音中 | Finish and edit | 完成并编辑 | 新增 |
| `voice_edit_instead` | 前导控件，等待中 | Edit instead | 改为编辑 | 新增 |
| `voice_edit_word` | 仅 A3 备选 | Edit | 编辑 | 不需要 |
| `voice_timer` | 计时器无障碍名 | Recording time, {m:ss} | 录音时长，{m:ss} | 新增 |
| `voice_preview` | 录音条上方文本框的无障碍名 | Dictated text | 语音转写文字 | 新增 |
| `voice_state_uploading` | 药丸 | Uploading 1 of 1（现有） | 正在上传 1/1（现有） | 现有 `file_strip_uploading`（实为「uploading %1$d of %2$d…」/「正在上传 %1$d/%2$d…」） |
| `voice_state_transcribing` | 药丸 | Transcribing…（现有） | 转写中…（现有） | 现有 `transcribing` |
| `voice_state_correcting` | 药丸 | Correcting · {agent} | 校对中 · {agent} | 新增 |
| `voice_state_sending` | 药丸 | Sending… | 发送中… | 新增 |
| `voice_note_timeout` | 通知行 | Correction took too long — check and send | 校对超时，请检查后发送 | 新增 |
| `voice_note_invalid` | 通知行 | Corrections didn’t apply — check and send | 校对结果未采用，请检查后发送 | 新增 |
| `voice_note_unavailable_signed_out` | 通知行 | {agent} is signed out on {computer}, so nothing was corrected — check and send | {computer} 上的 {agent} 未登录，未校对，请检查后发送 | 新增，需要原因字段 |
| `voice_note_unavailable_not_installed` | 通知行 | {agent} isn’t installed on {computer}, so nothing was corrected — check and send | {computer} 上没有安装 {agent}，未校对，请检查后发送 | 新增，需要原因字段 |
| `voice_note_unavailable_version` | 通知行 | {computer} needs a newer Pairlet to correct dictation — check and send | {computer} 上的 Pairlet 需要更新才能校对，请检查后发送 | 新增 |
| `voice_note_unavailable_no_one_shot` | 通知行 | {agent} can’t run a correction pass — check and send | {agent} 不支持校对，请检查后发送 | 新增；Codex 本轮的原因另定 |
| `voice_note_connection` | 通知行 | existing connection copy（占位：{computer} disconnected — your text is here, nothing was sent） | 现有连接文案（示例：与 {computer} 断开连接，文字已保留，未发送） | 待定：生产没有「文字已保留」的现成文案 |
| `voice_note_no_speech` | 通知行，2.5s | Didn’t catch any speech（现有） | 没有听到语音（现有） | 现有 `voice_no_speech` |
| `voice_ribbon_failed` | 危险提示 | Couldn’t transcribe — try again（现有） | 转写失败，请重试（现有） | 现有 `voice_transcribe_failed` |
| `voice_retry` | 输入框尾部操作 | Retry voice input（现有） | 重试语音转写（现有） | 现有 `retry_voice_input` |
| `settings_voice_section` | 设置分节 | Voice input（iOS 现有） | 语音输入（现有） | 现有 `voice_section`（「VOICE INPUT」/「语音输入」） |
| `settings_whisper` | 开关和说明 | Transcribe on the computer (Whisper)（现有，含说明） | 在电脑上转写（Whisper）（现有） | 现有 `voice_use_whisper` / `voice_use_whisper_sub`，中文名与说明以现有为准 |
| `settings_after_dictation` | 设置行 | After dictation | 语音输入后 | 新增 |
| `settings_after_dictation_send` | 取值 | Correct and send | 校对后发送 | 新增 |
| `settings_after_dictation_send_sub` | 取值说明 | ✓ sends. First, the session’s agent — Claude or Codex — fixes mis-heard words on your computer. Nothing is added. | ✓ 直接发送。发送前，本次会话的智能体（Claude 或 Codex）会在你的电脑上纠正听错的词，不增减内容。 | 新增；本轮要去掉 Codex |
| `settings_after_dictation_compose` | 取值 | Put in the composer | 放入输入框 | 新增 |
| `settings_after_dictation_compose_sub` | 取值说明 | ✓ puts the text in the composer for you to check and send. The same correction runs first, on your computer. | ✓ 把文字放进输入框，由你检查后发送。同样先在你的电脑上校对。 | 新增；后半句取决于决定 1 |

`{agent}` 是会话 Agent 的产品名，`{computer}` 是配对电脑名；设置说明里的 ✓ 是字形本身，两种语言都用。新录音条不再使用现有的 `cancel_recording`（取消录音）。

### 埋点（板子原文）

| 事件 | 维度 |
|---|---|
| `voice_record_start` | platform = ios \| android · live_transcript = true \| false · chat_state = idle \| streaming（若已有同类事件则复用） |
| `voice_done` | action = send \| compose · source = check \| leading · duration_ms · setting = send \| compose · corrector_available = true \| false |
| `voice_transcribe` | result = ok \| failed \| no_speech · engine = apple \| whisper · elapsed_ms · chars |
| `voice_refine` | result = applied \| none \| invalid \| timeout \| unavailable \| cancelled · agent = claude \| codex \| none · elapsed_ms · substitutions · reason（unavailable：signed_out \| not_installed \| version \| no_one_shot；cancelled：user \| connection）· stage（cancelled：transcribing \| correcting \| sending）· late = applied \| dropped（仅 timeout） |
| `voice_send` | corrected = true \| false · substitutions · queued = true \| false · capture_id |
| `voice_edit_landing` | text = raw \| corrected · reason = user \| timeout \| unavailable \| invalid \| connection \| setting · then_sent = true \| false · edited_before_send = true \| false |
| `voice_setting_changed` | value = send \| compose · platform |
| send · stop · retry_voice_input | 现有，不变；列出来是为了让口述发送与打字发送可比 |

实现口径：以技术方案 §7 的三个事件（`voice_done`、`voice_refine`、`voice_sent`）和分桶维度为基础，可吸收板子里 `source`、`reason`、`stage`、`late`、`then_sent` 这类固定枚举；`capture_id` 不上报，`duration_ms`、`elapsed_ms`、`chars`、`substitutions` 改为分桶，平台由公共维度提供。

### 录音条几何（按状态）

| 状态 | 板子规格 |
|---|---|
| 录音 | 行高 48（200% 为 58）：前导 48×48，间距 8；药丸弹性宽、最小高 44（54）、圆角 12、raised 底、1pt 描边、内边距 6/12、间距 8；✓ 为 48 圆、强调填充，内含 14×8、2.2pt 的 onAcc 勾。药丸内：9pt 脉动红点 · 3pt 波形条（间距 3、高 22）· m:ss 等宽 13（18.5）ink4。键盘字形 19×13（23×16），1.6pt 描边，ink3。录音时输入区高 12 + 48 + 26 = 86pt，回合进行中再加 Stop 行 9 + 48 |
| 实时转写 / 预览 | 位于录音条上方，下边距 8：圆角 14、raised 底、1pt 描边、内边距 12/14、15/1.45 ink2（200% 为 22/1.45）；最小高 46（66），最多 6 行 = 155（216），超出在框内滚动、钉在末尾。iOS 从第一个词起出现，易变尾部 ink4，光标 1.5pt 强调色、1.1s 闪烁；Android 在转写完成时以 220ms 变形向上长出 |
| 转写 · 校对 · 发送 | 同一行。前导控件可用（ink3）或不可用（ink5 + `aria-disabled`）。药丸：状态文字 13/1.35 ink3、弹性宽（可换行，药丸随之长高）· 冻结计时。尾部：48 圆、1pt line2 描边、透明，内含现有 15pt 转圈（2pt line2 圈、强调色弧、0.9s 一圈），`aria-hidden`。药丸状态文字为 polite 实时区域，变化时播报 |
| 高亮 | 只高亮改动片段：强调色字 + accBg 底（深色 rgba(226,121,90,.12)，浅色 rgba(169,72,42,.10)），圆角 3，内边距 0/1、外边距 0/−1；保持 200ms 后 800ms ease-out 淡回 ink2 / 透明。落在预览框或输入框里都一样。减少动态效果时不淡出，实色保持 1s |
| Sending… 停留与收起 | 有替换 1000ms，无替换 300ms；停留结束时发出发送，随后用现有 220ms 变形收回普通输入区 |
| 落输入框 | 普通输入区按 ChatComposerV2：输入框圆角 14、最小高 46、最大 155（板子说与预览同为 6 行；生产是 4 行，以生产为准）· 光标在末尾 · 键盘弹起 · 工具条上 Send（强调）与回合进行中的 Stop。输入区上方通知行：8pt ink5 圆点、13/1.45 ink3、内边距 10/20/0 |
| 通知行寿命 | 没听到：2.5s（现有）。原因行（超时、无校对器、校验失败、断连）：保留到首次编辑或发送后清除。不叠加，新的替换旧的 |
| 宽度 | 402 宽：录音时药丸 250；320 宽：药丸 168；200% 字号、402 宽：药丸 220（实测 230），状态文字可换两行。控件不缩，宽度变化全由药丸吸收 |

### UI 之外要改的东西

| 项 | 板子 | 与技术方案对照 |
|---|---|---|
| 电脑公布能力 | daemon 按会话公布 corrector_available、corrector_agent（显示用产品名）、不可用时的 corrector_reason（signed_out / not_installed / version / no_one_shot）。手机在 ✓ 之前据此选无障碍名，转写后据此选结局；旧 daemon 没有这个字段 → unavailable · version | 技术方案：`ClientCaps.supportsTranscriptRefine` + `DaemonInfo.transcriptRefineAgents`（按 daemon），没有原因字段 |
| 校对请求 | 手机 → 电脑 {capture_id, text, locale, agent_hint}；电脑 → 手机 {capture_id, substitutions: [{from, to}], elapsed_ms, agent} 或 {error: invalid \| unavailable}。电脑先校验（每条 from 恰好出现一次、总改动有上限），由手机套用 | 技术方案：`TranscriptRefine(convoId, captureId, text, locale, agentHint)` / `TranscriptRefined(convoId, captureId, ok, text, edits, agent, error)`，daemon 返回校验后的 `text`，`edits` 只用于高亮 |
| 谁发送 | 手机：套用、停留，再发出一条普通发送并带上 capture_id；发出前点 Edit instead 则什么都不上线；排队沿用打字发送的队列 | 一致；去重沿用 `promptId` |
| 超时与迟到 | 8s 从转写可用时起算；超时不取消请求；迟到结果只在输入框仍等于原文时套用，否则丢弃；发送之后到达的结果忽略 | 技术方案 §3 第 4 条写「随后 7s 内」，§4.4 写 daemon 硬超时 12s（实际窗口约 4s），两处要统一 |
| 断连 | 发出前断开：没有发送，原文落输入框并显示连接行。发出后、确认前断开：重连后按 capture_id 查询，daemon 已收到则清掉没改过的输入框副本并显示该回合，改过则保留草稿；不查询绝不重发 | 现有 `promptId` + `PromptAck` + `supportsPromptRecovery` 已覆盖，不需要新查询 |
| 设置 | after_dictation：send（默认）/ compose，按设备保存，两端都有；compose 仍请求校对，只改去向；iOS 的 Whisper 开关独立 | 技术方案：`SecureStore` 键 `voice_after_dictation`；compose 是否校对待决定 |
| iOS 实时转写 | 现有的框；点 ✓ 或键盘字形后易变尾部提交、光标停止，框原地变为预览，布局不变；Apple 的最终结果原地替换 | 现状点 ✓ 后该框即隐藏，要改 |
| 范围外 | Mic 位置、语音备忘、桌面输入区、模型选择、消息渲染（含排队消息）、停止前的录音外观、首次使用引导 | — |

## 实现落点（评审通过后参考）

按 [技术方案](../../VOICE-INPUT-REFINE-SEND.md) 的 M0–M3 分期推进，下面只列与这块板子相关的落点。

- **协议**（`protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Messages.kt`）：按技术方案 §5 追加 `TranscriptRefine`、`TranscriptRefined`、`TextEdit`、`ClientCaps.supportsTranscriptRefine`、`DaemonInfo.transcriptRefineAgents`；若采用板子的分原因提示，再加不可用原因。交 `protocol-wire-compat-reviewer` 评审，并刷新 `packaging/brand-compatibility.json` 中 `Messages.kt` 的冻结哈希。
- **daemon**：`transcribe/TranscriptRefineService` 加 Claude 适配器（复用 `ClaudeMemoSummarizer` 的隔离参数，sonnet/low），在 `RequestRouter` 里路由；校验器写单测。Codex 适配器本轮不接（未过门槛）。
- **手机数据层**（`data/VoiceState.kt`、`data/PocketRepository.kt`）：
  - `VoiceState` 加 `Refining(raw, agent, sinceMs)`，并带上「已套用、停留中」的标记，供药丸显示 `Sending…`。
  - ✓（`stopVoice()`）：转写可用后，按设置、`transcriptRefineAgents` 和连接状态决定进入 `Refining`，还是走现有 `deliverTranscript()` → `pendingVoiceText` 落输入框。8s 预算计时；`onTranscriptRefined` 成功后套用、停留，再经现有 `sendPrompt` 发出（排队、附件、`promptId` 去重都沿用）；`sendPrompt` 返回 false 时回落输入框。
  - 前导控件：新增「结束并落输入框」动作，替代录音条上 `cancelVoice()` 的入口；`Refining` 中点它 = 取消自动发送、发 `AudioCancel` 取消校对、落输入框。
  - `voiceNotice` / `showNotice` 增加「随首次编辑或发送清除」的寿命，原因行按不可用原因选文案。
  - 迟到结果：输入框仍逐字等于原文时才套用。
  - 90s 上限（`beginTicker()` 里调用 `stopVoice()`）改为落输入框，不触发自动发送。
  - 离开会话（`abandonVoice()`）时，把已有的原文存进该会话的草稿，不发送。
  - 设置：`SecureStore` 键 `voice_after_dictation`（`send` / `compose`，默认 `send`）。
- **手机 UI**（`ui/VoiceComposer.kt`、`ui/App.kt`、`ui/VoiceIcons.kt`）：
  - `RecordingBar`：✕ 换成键盘字形按钮（新增字形）；等待态尾部改为不可点的细线圆环加转圈，药丸只放状态文字与冻结计时。药丸里的状态文字与计时、设置行里的取值与箭头同排时，按 AGENTS.md 一律用 `tightCenter`。
  - 预览：把 `LiveTranscriptField` 扩展为「录音中实时显示、点 ✓ 后保留为预览、Android 在转写完成后出现」，6 行上限、钉在末尾，改动片段用 `AnnotatedString` 着色（`Tok.accent` 字色，底色取其 0.12 透明度）。`App.kt` 里录音条的显示条件（今天是 `Recording || Transcribing`）要覆盖新的等待状态；回合进行中的 Stop 行沿用现有 `StopButton`。
  - 落输入框后的高亮：现有 `ComposerField`（`BasicTextField` + `TextFieldState`）不支持片段着色，需要另做，先验证可行性；代价高时可以只在预览里高亮。
  - Mic 位置、输入框 4 行上限、工具条、失败提示和 `VoiceSetupChip` 都保持现状。
- **设置**（`ui/Settings.kt` 的 `GeneralPage`）：语音输入一节在手机上始终显示，Whisper 开关仍以 `NativeDictation.available` 为条件；新增「语音输入后」，形式待定（见决定 6）。
- **字符串**：按上表在 `composeResources/values/strings.xml` 与 `values-zh/strings.xml` 增加新键，现有的复用现有键。
- **埋点**：`telemetry/Telemetry.kt` 的 `TelEvent` / `TelKey` 与 `docs/observability/EVENT-CATALOG.md` 一并登记。
- **测试**：daemon 校验器与服务单测。手机端仿照 `desktopTest` 里的 `VoiceTranscriptWaitTest`，覆盖校对成功、超时与迟到、无校对器、校验失败、断连、前导控件转向、90s 上限；`desktopTest` 渲染录音条各状态，断言尺寸、强调填充数量和无障碍名。

## 评审决定（2026-10-06）

负责人把这块板子的评审交给了协调者（Fable）。结论：采用板子的方向，按下面的决定落地，不再跑设计轮次。与板子不同的地方以这里为准。

### 总规则：让「✓ 会不会发送」看得见

板子让 ✓ 的外观在两种情况下完全相同，只改无障碍名（疑虑 1）。改为用前导控件区分两种录音条：

| 录音条 | 什么时候出现 | 前导控件 | ✓ |
|---|---|---|---|
| 发送条（本轮新设计） | 这一次 ✓ 确实会发送 | 键盘字形「完成并编辑」；等待中「改为编辑」 | 「完成并发送」，校对后发出 |
| 现有条（与今天逐项相同） | 这一次 ✓ 不会发送 | ✕「取消录音」，照旧丢弃 | 「完成」，文字落输入框 |

「这一次会发送」要同时满足：设置为「校对后发送」；本会话有可用的校对器；连接正常；输入框是空的，也没有暂存或上传中的附件。任何一条不满足就是现有条，行为、文案、提示与今天完全一样，不出现「未校对」之类的原因行。这样 Codex 会话、旧版 daemon、选了「放入输入框」的人，看到和用到的都是今天的录音条，没有回归，也没有每次一行的噪音。

### 逐条决定

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| 1 | 「放入输入框」时是否仍先校对 | 不校对，等同今天的行为（现有条） | 选这个值的人要的是马上拿到文字；不等待、不花额度。板子 C8 与原型里的 `Sending…` 一并作废 |
| 2 | 原因行的寿命 | 发送条回落输入框时的原因行保留到第一次编辑、发送或离开会话；「没有听到语音」仍是 2.5 秒 | 它说的是「这段字没有发出去」，一闪而过容易让人以为已经发了。结构性原因（没有校对器）按总规则根本不出原因行 |
| 3 | Codex 会话 | 校对器跟着用户在用的 Agent 走：先看会话的 Agent，再看手机的默认 Agent，都没有适配器就没有校对器，不按 daemon 偏好兜底。本轮只有 Claude 适配器 | Codex 常驻进程实测 10–12 秒，超出预算。默认用 Codex 的人不应该被悄悄消耗 Claude 额度；默认用 Claude、临时进了 Codex 会话的人仍由 Claude 校对，药丸如实显示「校对中 · Claude」 |
| 4 | `Sending…` 停留 | 有替换时停留 700 毫秒展示高亮；没有替换时立即发送 | 高亮是让人知道改了什么，值得留一拍；1 秒偏长。没有改动就没有可看的 |
| 5 | 已有草稿或附件时点 ✓ | 属于总规则里的「不会发送」：现有条，口述追加到草稿，照今天的做法 | 手写了一半的消息、几天前恢复出来的旧草稿、还在上传的附件，都不该被一次口述自动带着发出去 |
| 6 | 设置的形式 | 页内分段控件加一句说明，与外观、字号一致；不做单独的单选页 | 少一层页面，沿用现有模式。说明文字不点名 Claude 或 Codex，写「你的编码 Agent」 |
| 7 | 迟到结果的窗口 | 8 秒预算到期后，再接受到 daemon 12 秒硬超时为止的结果（约 4 秒），且输入框文字必须仍与原文逐字相同 | 12 秒之后 daemon 已经放弃，窗口写得再长也没有结果可等。技术方案相应统一 |
| 8 | 是否为原型缺陷再跑一轮设计 | 不跑 | 缺陷都在原型实现层，意图清楚；规格见下 |

### 补充规则（板子没有覆盖的状态）

- **90 秒录音上限**：今天等同点 ✓。在发送条下改为等同前导控件：结束并把文字放进输入框，不自动发送。
- **等待中离开会话**：取消校对；已经拿到的原文追加进被离开会话的草稿，不发送，不丢字。
- **发送被拒**（会话降级等现有的拒发情形）：文字落输入框，原因行「未发送，请检查后发送」。
- **迟到结果套用进输入框**：输入框里不做片段高亮（现有输入框不支持片段着色，不为此改主路径控件）；原因行改为「已校对，请检查后发送」，让人知道文字变过。高亮只出现在录音条上方的预览框里。
- **本轮的原因行只有六句**（daemon 不提供「未登录／未安装」等细分原因，板子里那四句细分文案不做）：

| 情形 | en | zh |
|---|---|---|
| 校对超时 | Correction took too long — check and send | 校对超时，请检查后发送 |
| 迟到结果已套用 | Corrected — check and send | 已校对，请检查后发送 |
| 替换未通过校验 | Corrections didn’t apply — check and send | 校对结果未采用，请检查后发送 |
| 这一次校对不可用或失败 | Couldn’t correct this time — check and send | 这次没能校对，请检查后发送 |
| 等待中断连 | Disconnected — your text is here, nothing was sent | 连接已断开，文字已保留，未发送 |
| 发送被拒 | Not sent — check and send | 未发送，请检查后发送 |

### 实现规格对板子的修正

- 普通输入区、Mic 的位置与可用时机、回合进行中的 Stop、输入框 4 行上限、失败提示和安装引导，全部以生产为准，不照板子重画。
- iOS 点 ✓ 后，实时转写框原地保留为预览，不闪断；Android 在转写完成时出现预览。预览最多 6 行，超出在框内滚动并停在末尾。
- 现有条不显示 `Correcting` 或 `Sending…`。
- 埋点按技术方案第 7 节的三个事件与分桶维度登记，不带 `capture_id`、原始毫秒数或字数。
- 协议与数据以技术方案为准：daemon 返回校验后的 `text`，`edits` 只用于高亮；去重沿用现有 `promptId`。

### 评审时未能确认、留给真机验收的

- 「已打字则丢弃迟到结果」在原型里没能操作到，只读到源码；实现要有自动化用例。
- 回合进行中开始录音，原型里做不到，只看了静态帧。
- 板子没有待审批、待回答时的帧；这两种情况沿用今天的规则（发送排进进行中的回合）。
