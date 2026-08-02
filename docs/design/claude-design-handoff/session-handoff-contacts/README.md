# 协作联系人（Collaborator Link ＋ 直接选人）— 设计 handoff

- **在线设计板**（登录 b01099485423@gmail.com 即看）：
  <https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=Handoff+Contacts.html>
- **生成**：2026-08-02，cc-pocket 正典项目追加，Opus 5 Medium 一次通过（自查修了三处 clipping）。
- **依据**：`docs/design/SESSION-HANDOFF.md` 更新版 §4.1–§4.3——二维码只用于首次建联
  （Collaborator Link），之后发起接力直接选联系人，daemon 经既有 E2E Link 投递 offer ＋
  不含正文的 push；每次接力的访问由独立 Handoff Grant 动态授予。
- **Brief 存档**：`~/Desktop/Brain/60_Outbox/2026-08-02-cc-pocket-协作联系人ContactPicker设计提示词.md`。
- **本地打开**：本目录 `python3 -m http.server` 后访问 `Handoff Contacts.html`
  （共享样式在 `./session-handoff/handoff.css`，增量样式在 `./session-handoff-contacts/contacts.css`）。

## 文件清单

| 文件 | 内容 |
|---|---|
| `Handoff Contacts.html` | 一张画布，9 帧（含子状态 14 个画面） |
| `session-handoff-contacts/contacts.css` | 增量样式（联系人行、方向 glyph、指纹块、offer 卡） |
| `session-handoff/handoff.css` | 与 Session Handoff 画布共享的基础样式（副本，便于独立预览） |

## 帧清单

| 帧 | 内容 |
|---|---|
| 1 / 1b | 选人器（草稿 sheet 二级页）：搜索 ＋ RECENT／ALL 分组 ＋ 方向 glyph（⇄/→）＋ "no daemon" 弱标 ＋ 底部唯一 QR 入口「Connect a new colleague…」；1b 空态（一句话解释一次性扫码 ＋ 主按钮） |
| 2 | 草稿 sheet 选中态：联系人 chip（头像＋label＋方向）＋ change；主按钮改为 **"Send to Frank"**（不再是 Create invite） |
| 3 / 3b | 建联·发起侧：QR ＋ 短码 ＋ 诚实注记「只建立信任连接，不共享任何会话/目录/代码」＋ waiting for scan；3b 成功态（绿 Connected chip ＋ Back to handoff 回到被打断的草稿） |
| 4 | 建联·接收侧确认：设备标签 ＋ **安全指纹块**（mono 词组，当面/电话核对）＋ 方向说明行 ＋ Confirm/Cancel |
| 5 / 5b | Settings ▸ Collaborators 管理页：联系人行（方向 glyph ＋ mono 元信息）＋ removed 终态分组；5b 空态 |
| 6 | 联系人详情：指纹（可 re-verify）＋ 方向行 ＋「补齐反向连接」（无 daemon 时禁用带 mono 提示）＋ 与此人的接力历史 ＋ Remove（唯一 danger） |
| 7a / 7b | 接收侧 offer：无正文 push → 应用内 offer 卡（项目 basename mono ＋ kind chip ＋ 倒计时 ＋ View 主按钮/Decline）；7a 首页叠卡、7b 通知落点；接受预览复用原稿不重设计 |
| 8 | WAITING banner 改版：第二行 mono "offer sent · Frank notified · expires …"；动作变 **Resend notify** ＋ Recall（不再以 copy-invite 为主路径） |
| 9a / 9b | 桌面：草稿 modal 的联系人 dropdown（搜索＋recent＋all＋connect new，anchored popover 语法）；Settings ▸ Collaborators 双列 pane |

## 设计稿的关键裁定（聊天总结）

- Expiry pill 重标注为 **"Offer expires · access, not the link"**——过期的是 Grant，不是联系人连接。
- WAITING 行只写 **"Frank notified"**（push 被服务受理），**绝不渲染 seen/online**；「Connected」绿是完成态不是在线态。
- 双向连接渲染为一行 ＋ "Both ways" chip；底层权限仍有向。
- **已移除联系人落入终态分组而非消失**——历史 handoff 还引用它们。

## 落地状态

- 仅设计定稿；实现待排（工程增量：protocol `Collaborator*` 模型与消息、daemon CollaboratorStore/COLLABORATOR 凭证/offer 投递与 push、双端选人器与联系人管理 UI、WAITING banner 改版）。实现前须过 crypto review（建联 ticket 与指纹校验是新安全面）。
