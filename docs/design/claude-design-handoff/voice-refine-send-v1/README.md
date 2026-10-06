# Voice Refine Send v1 — 语音说完即发与发送前校对

状态：**设计板生成中，尚未验收**。本文是占位说明，设计板完成并经可见验收后补齐推荐方案、评审结论、与现状差异和实现落点。未实现，未改任何产品代码。

- 日期：2026-10-05 投递（本机时区）。来源与技术方案见 [语音输入 v2：说完即发，发送前由轻量模型校对](../../VOICE-INPUT-REFINE-SEND.md)。
- 设计项目：cc-pocket Design System 2.0。[在线项目](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=VoiceRefineSendDevice.dc.html)。目标文件：`Voice Refine Send v1.dc.html`（板子）与 `VoiceRefineSendDevice.dc.html`（活组件）。
- 投递原文见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。

## 生成过程（如实记录）

| 时间（PDT） | 事件 |
|---|---|
| 10-05 20:45 | brief 投递到新对话，界面显示 Fable 5.1 Max |
| 10-05 20:58 | 第一轮在读完项目文件后无声结束，没有写文件也没有回复 |
| 10-05 20:59 | 同一对话追加一句「继续并实际创建设计板」，生成重新开始 |
| 10-05 21:04 | Claude Design 用量上限触发，生成中断；此时活组件 `VoiceRefineSendDevice.dc.html` 已建出状态面板，板子文件尚未创建 |
| 10-06 01:55 | 用量窗口恢复，点「Resume」续跑同一轮（保留完整上下文，未重发 brief） |

## 投递之后已确定的工程事实（板子验收时以此为准）

- Claude 路径：`sonnet` + `low` + 替换列表，✓ 之后约 4–6 秒；`haiku` 两轮复测仍为 44–100 秒，不作默认。
- Codex 路径：常驻 `app-server` 的替换列表三次实测 10.0–12.3 秒，超过 8 秒预算；本轮 Codex 会话按「无校对器」回落到输入框。brief 中「Codex 约 3–10 秒」的表述已被实测推翻，板子里 Codex 作为校对器的画面只作远期参考。
