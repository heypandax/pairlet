# App Store 拒审案例与提审前检查

维护日期：2026-09-12。适用 App：CC Pairlet（原 CC Pocket），App ID `6778773969`，Bundle ID `com.panda.ccpocket`。

修改商店文案、审核备注、隐私披露、首次启动流程、年龄分级，或准备 iOS 提审前，先按本文复核。本文记录已发生的问题及处理证据；案例中的历史回复只适用于当时的功能和数据流，不能直接当作下一版本的声明。

证据范围：2026-09-12 回读 ASC 的 2.0.0 与 1.9.5 审核消息，并核对仓库历史提交。ASC 列表仅展示过去 180 天内最新 10 项已完成提交，早期 1.0 案例依据 Git 中明确记录的拒审原因；不声称已穷尽全部历史审核。日期优先采用 Apple 消息正文的 Review date，消息列表时间可能因时区不同显示为前一天。

## 已确认的人工拒审场景

| 编号 | 版本 / 时间 | 条款 | 触发场景 | 防复发重点 |
| --- | --- | --- | --- | --- |
| R1 | 2.0.0（56），2026-09-12 | 2.3.10 | What's New 混入 Android 专属修复 | 商店文案单独编辑，覆盖全部本地化 |
| R2 | 1.0（1），修复提交于 2026-06-16 | 2.1(a) | 审核员没有电脑 daemon，无法越过配对页 | 保留可发现、可操作的免配对 Demo |
| R3 | 1.9.5（50），2026-08-29 / 08-31 | 5.0 Legal | 中国大陆商店的元数据被判定关联 OpenAI / ChatGPT | 准确说明工具关系，审查全部文案及实际地区功能 |
| R4 | 1.9.5（50），2026-08-31 / 09-02 | 5.1.1(i)、5.1.2(i) | AI 数据去向、接收方和发送前同意不清楚 | 产品内披露与同意、隐私政策、审核备注保持一致 |
| R5 | 1.9.5（50），2026-08-31 | 2.3.6 | 有聊天界面，年龄问卷未选择 Messaging and Chat | 按实际功能填写并回读 ASC 问卷 |

### R1：更新说明出现 Android 引用

**审核记录**：[2.0.0 提交](https://appstoreconnect.apple.com/apps/6778773969/distribution/reviewsubmissions/details/6c1e1945-31c0-4f5e-951e-9b8df246c93e)，Submission ID `6c1e1945-31c0-4f5e-951e-9b8df246c93e`；审核设备 iPhone 17 Pro Max。Apple 明确要求删除 What's New 中的 Android 引用。

问题文案在两个本地化中同时存在：

- 中文：`Android：网关模型列表过长时可以滚动，末尾的模型也能选到。`
- 英文：`Android: long gateway model lists scroll instead of running off the screen.`

**根因**：把跨平台项目发布日志直接放进 iOS 商店更新说明。原文还包含 Windows 托盘、桌面右键、命令安装等内容；Apple 本次点名的是 Android，清理其他桌面专属条目是为了让说明聚焦 iPhone / iPad 的体验，并不表示这些条目也被逐项判违规。

**处理与结果**：2026-09-12 已同步修改仓库和 ASC 的 `en-US` / `zh-Hans` What's New，只保留改名、App 内帮助入口、诊断设置、会话文字显示修复；保存后刷新并逐字回读一致。随后经用户授权发送回复，说明已修正并请求按 Apple 消息中的 Bug Fix Submissions 选项继续处理原提交。发送后消息数由 1 变为 2；当次回读仍为“已拒绝”，不能记成重新进入审核或审核通过。保留原 build 56 和“通过审核后自动发布”设置。

**防复发**：

- [中英文发布说明](../fastlane/metadata/)独立面向 iPhone / iPad 编写；电脑安装、桌面 UI 和其他移动平台更新留在项目发布日志。
- [内容检查脚本](../scripts/check-appstore-content.py)对公开文本拦截 `Android` / `安卓`，忽略英文大小写；这是项目防回归规则，不是对 Apple 全部规则的自动判断。
- [ios-release](../.github/workflows/ios-release.yml)归档前执行文本检查；[ios-store-metadata](../.github/workflows/ios-store-metadata.yml)执行包含同一规则的完整素材检查。2026-09-12 本地已验证新文案通过、原先被拒的两种语言文案均被拦截；工作区改动需提交到实际执行的 ref 才能在远程 CI 生效。

**复提路径**：只改元数据可以继续使用相同 build，不为此默认重跑整个 iOS 构建。Apple 官方说明支持修正元数据后复用原构建；“直接回复即可继续处理”则是本次审核信明确给出的选项，后续要按各自审核信处理。[Apple：回复审核消息](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/reply-to-app-review-messages)

### R2：没有桌面电脑就无法体验 App

**证据**：[修复提交 77485885](https://github.com/heypandax/pairlet/commit/7748588576133d8c7c3e9cdfee144f38a4c5e808)明确记载：1.0（1）因审核员无法越过配对页被 Guideline 2.1(a) 拒绝。原始审核信及 Submission ID 本次未取得。

**处理**：1.0 build 2 增加免配对 Demo，使用本机样例数据展示项目、会话和消息；无需账号、桌面 daemon 或真实 AI 调用。入口与处理逻辑现在仍可在 [PairingScreen](../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/PairingScreen.kt)、[PocketRepository](../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt)、[DemoData](../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/DemoData.kt)中定位。代码存在不代表当前提交包的 Demo 已实测。

**防复发**：

- 首次启动、隐私同意页、配对页改版后，从未配对状态实际走到 Demo，检查项目列表、会话、工具展示及退出路径。
- 在 [审核备注](../fastlane/metadata/review_information/notes.txt)保留准确的入口说明；当前说明为配对页的 `Try Demo - no computer needed`，下一版必须与真实文案和导航一致。
- 对需要真实硬件或联网才能审核的功能另给可操作说明，不用 Demo 样例冒充真实网络能力验证。

### R3：中国大陆商店中的 OpenAI / ChatGPT 关联表述

**审核记录**：[1.9.5 提交](https://appstoreconnect.apple.com/apps/6778773969/distribution/reviewsubmissions/details/0b6b0ab9-888f-4536-a217-8cce0b5b42be)，Submission ID `0b6b0ab9-888f-4536-a217-8cce0b5b42be`。2026-08-29 首次拒审点名元数据中的 `OpenAI`；2026-08-31 再次指出同一问题。Apple 当时要求处理中国大陆地区相关功能和元数据，或选择不在该地区分发。

**历史处理**：先回复说明 App 与用户自装的命令行工具的关系，但下一轮仍被点名。随后[提交 ac12c62e](https://github.com/heypandax/pairlet/commit/ac12c62ece06b5bad650ebe299abf20e986ae9de)删除中英文商店描述中的公司名前缀，仅保留工具名 `Codex`；后续 [65c041ae](https://github.com/heypandax/pairlet/commit/65c041aedb5b9ff85cc2d611e050213e31f4f298)也调整了会话信息标签。2026-08-31 的开发者回复确认移除各语言元数据中的 OpenAI 引用。

**结果边界**：本次回读显示同一提交最终为 1.9.5（51）“已批准”，不是被拒的 build 50。不能据此推导“删掉公司名就保证符合中国区要求”；本文也没有核实当时或现在的全部地区分发设置。

**防复发**：

- 本项目商店文案沿用准确的工具名，不从官网、能力表或历史文案盲目回填 `OpenAI Codex` / `ChatGPT`；检查范围包含所有语言、截图和预览字幕。真实的数据接收方仍必须如实披露，不能为避词删掉必要的隐私说明。
- 产品增加托管 AI、代理访问或客服等能力后，重新核对实际服务、地区分发、文案和审核备注。不得照抄旧回复中“没有任何 AI 功能”“从不联系任何第三方”的笼统断言。
- 地区限制或下架涉及产品范围，按本次用户授权处理；不为了消除审核提示自行关闭中国大陆分发。这是历史个案记录，不是通用法律结论。

### R4：AI 数据披露与发送前同意不足

**审核记录**：同一 [1.9.5 提交](https://appstoreconnect.apple.com/apps/6778773969/distribution/reviewsubmissions/details/0b6b0ab9-888f-4536-a217-8cce0b5b42be)，2026-08-31 指出 5.1.1(i) / 5.1.2(i)，2026-09-02 的 Review date 再次审查 build 50 并表示问题仍需处理；审核设备 iPad Air 11-inch（M3）。

**Apple 要求**：说明发送哪些用户数据、发送给谁，并在发送前取得用户同意；隐私政策也应一致说明收集与使用。只把信息写在隐私政策或服务条款中不足以解决实际发送前的披露与同意问题。如果实际没有相应 AI 服务，应回复澄清并同步到 App Review Information。

**处理过程**：

1. [553414cf](https://github.com/heypandax/pairlet/commit/553414cfdd0d8e4a377c6768c23f899ca1f5fc82)补充语音、用户自装 Agent、Demo 的说明和审核备注；开发者于 2026-08-31 回复。但 build 50 在后续一轮仍被拒，不能把这次文字补充记成问题已解决。
2. [65c041ae](https://github.com/heypandax/pairlet/commit/65c041aedb5b9ff85cc2d611e050213e31f4f298)增加首启数据披露同意页、设置中的隐私政策入口，并把 iOS 语音权限文案从不准确的“本机识别”改为 Apple 语音识别；[f8ee0dd6](https://github.com/heypandax/pairlet/commit/f8ee0dd6c67bb73d9db7593f03052f4e1b94d6be)补充数据保留与删除说明。
3. 本次 ASC 回读确认最终批准的是 1.9.5（51）。历史 Git 记录和审核结果相互支持，但本文不宣称逐项修复都被 Apple 单独确认。

**防复发**：每次改动数据路径时，核对[首启披露页](../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/PrivacyConsentScreen.kt)、[隐私政策](../site/privacy.html)、[审核备注](../fastlane/metadata/review_information/notes.txt)及 ASC App 隐私填报是否一致，并验证同意前后的真实行为。

特别注意历史表述的适用范围：

- 编程会话的 App → 用户电脑 E2E 通道，与用户电脑上的 Agent → 模型提供方是两段路径。
- Apple Speech 不应被描述为代码没有保证的“始终离线、仅本机识别”。
- 2.0 的当前审核备注还列有可选 AI 客服，以及 Firebase / Sentry / 推送等独立服务。不得把“relay 不解密会话”扩写为“App 的所有数据都不会到第三方”。
- 首启同意页的存在本身不证明所有新增数据路径都被覆盖；新增接收方或用途应按实际功能更新披露与同意。

### R5：年龄分级遗漏 Messaging and Chat

**审核记录**：同一 [1.9.5 提交](https://appstoreconnect.apple.com/apps/6778773969/distribution/reviewsubmissions/details/0b6b0ab9-888f-4536-a217-8cce0b5b42be)，2026-08-31 消息同时指出 Guideline 2.3.6：App 有 messaging / chat 功能，必须在年龄问卷选择 `Messaging and Chat = Yes`。

**历史处理**：2026-08-31 开发者回复确认已改为 Yes；最终批准版本为 1.9.5（51）。本次核对了审核消息，未另行读取当前年龄问卷，不把历史回复当作当前表单值。

**防复发**：本产品仍有会话聊天界面，不能因其是“开发工具”“远程终端”而填成没有聊天功能。发版时在 ASC 的 App 信息 → 年龄分级中回读 `Messaging and Chat`，其他问卷项目也按实际功能填写。文案和素材校验脚本不覆盖这个表单。[Apple：设置年龄分级](https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating)

## 已发生的上传 / 提交流程阻塞

以下不是已确认的人工审核条款拒绝，不与上面的五类案例混算。

| 场景 | 历史证据 | 后续要求 |
| --- | --- | --- |
| 1.3.0 What's New 含 `✕`，deliver 报 `attribute value has invalid characters` | [b1d136e7](https://github.com/heypandax/pairlet/commit/b1d136e7fbd00c42fbd94b658afe1bca8d7518cf)记录归档后失败；现有 ios-release 归档前检查该字符 | 用普通文字替换；单独同步元数据时也要检查，不能假定只有完整发布流程会遇到 |
| workflow 硬编码 `marketing_version=1.0.1`，上传到已关闭的版本列车 | 同一 [b1d136e7](https://github.com/heypandax/pairlet/commit/b1d136e7fbd00c42fbd94b658afe1bca8d7518cf)记录两次真实失败 | 默认读取仓库锁步版本；显式 override 前核实目标列车；只有上传新二进制才需要新 build 号 |
| XcodeGen 意外生成 universal 包，iPad 方向声明却只有竖屏 | [ecf881ff](https://github.com/heypandax/pairlet/commit/ecf881ffc339fa2f9b6b0c1631de253b08a8b09b)，2026-06-22 | 当时用 iPhone-only 修复；当前已正式支持 iPad，不能照抄旧修复。核对 [project.yml](../iosApp/project.yml)、[Info.plist](../iosApp/iosApp/Info.plist)与最终归档，并准备 iPad 素材 |
| 1.9.5 被拒提交仍持有版本，却另建空审核草稿 | [e0eb466c](https://github.com/heypandax/pairlet/commit/e0eb466c5259e73501ecd55edaa31fb1d776b2be)，2026-09-01 的临时修复 | 先查版本归属与原 submission，再选择合适的复提方式。不要执行历史临时工作流；当前同名 asc-maintenance 已用于证书维护 |

## 每次提审的必查项

1. **目标与授权**：确认实际 App、版本、build、原 submission、当前审核状态及发布方式。区分保存文案、上传 build、回复审核、提交审核、自动或手动发布；沿用本次明确授权，不把旧版本的一次授权泛化成后续发布权限。
2. **商店文案**：中英本地化逐项对照实际提交构建；不直接复用跨平台 changelog，不混入 Android、桌面专属修复或内部实施细节。R3 的品牌关系与地区问题也要检查。What’s New 的重大功能变化必须准确说明。[Apple：准确元数据](https://developer.apple.com/app-store/review/guidelines/#accurate-metadata)
3. **审核可达性**：按目标构建走首次启动 → 披露同意 → 未配对 → Demo；审核备注写真实可达入口。改过相关流程时要做运行验证，仅找到代码或通过编译不足以验收。
4. **隐私与年龄分级**：按 R4 核对实际数据流和同意行为，按 R5 回读年龄问卷；后端、客服、语音、遥测接入有变化时重新核对，不只改宣传文本。
5. **本地检查**：纯文案先跑文本校验；完整提审还需素材、品牌与版本检查。以下命令均不会上传或提审：

   ```bash
   python3 scripts/check-appstore-content.py --metadata-only
   python3 scripts/check-appstore-content.py
   python3 scripts/check-brand-compatibility.py
   bash scripts/check-release-version.sh
   ```

   完整素材检查依赖 ffprobe。另检查发布说明是否残留 `✕`；当前该字符拦截在 ios-release 中，不属于通用内容脚本。脚本不验证线上页面是否可访问、Demo 是否可用、问卷是否正确或 Apple 是否接受。
6. **线上回读与收尾**：保存后读取 ASC 每种语言的实际文本，确认 build 与发布设置符合目标。若同步素材，确认 iPhone / iPad 截图和预览已处理完成。对审核消息发送、重新提交、审核通过和公开上架分别取证，不能用其中一个状态替代其余状态。

操作入口见[发布手册](RELEASE.md)及[元数据说明](../fastlane/metadata/README.md)。仅修 What's New 时优先只修改对应字段；完整元数据工作流还会同步其他文本、截图、预览，并设为手动发布，不应为了一个文案修复无差别重跑。

## 新案例的录入规则

后续收到拒审，继续更新本文及对应防复发检查，不在多个文档复制完整案例。至少保存：日期、版本/build、条款、submission 链接或明确来源、Apple 指出的具体问题、真实根因、处理动作、验证方法、最后回读状态与证据缺口。把审核意见、开发者解释、代码变化、Apple 最终结果分开写；未取得原文或未通过时明确标注。不要写入密钥、账号凭据或审核员个人联系方式。
