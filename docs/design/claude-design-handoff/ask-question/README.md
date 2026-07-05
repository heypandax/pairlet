# 提问卡（AskUserQuestion）设计交付

来源：claude.ai/design cc-pocket 项目 · `Ask Question.html`（2026-07-05，Opus 4.8 Max 生成，自动化流程投递 brief 并导出）。
在线查看：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FAsk+Question.html>
Brief 存档：`~/Desktop/Brain/60_Outbox/2026-07-05-cc-pocket-提问卡设计提示词.md`

## 文件

| 文件 | 内容 |
|---|---|
| `qm-core.jsx` | token（A08/A12/A18/A28/A55 accent 色阶）、图标（QIcon/Radio/CheckboxCtrl/Chevron/Check/Pencil）、原子（ChipTab/TinyChip/PillBtn/MachineChip） |
| `qm-card.jsx` | 提问卡本体 + OptionRow/OtherRow/Freeform/ReplyLink/CardFooter + AnsweredRow（可展开）+ WithdrawnNotice + InboxRow |
| `qm-board.jsx` | 展示板：Chat 全屏上下文 + 7 状态 + 键盘态，简中演示数据 |
| `Ask Question.html` | 板的 HTML 外壳 |

## 落地状态

已按此稿实现于 `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/QuestionCard.kt`（2026-07-05，含 UI 测试 `QuestionCardUiTest`）。协议/daemon 侧见 `AskQuestions.kt` / `PermissionBridge.kt`。**尚未落地**：跨机收件箱行 InboxRow（fleet 收件箱当前以通用行显示提问）、桌面端提问卡（桌面走通用审批流）。
