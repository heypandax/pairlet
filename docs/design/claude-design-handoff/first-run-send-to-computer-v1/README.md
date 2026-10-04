# First Run · Send to Computer v1 — 首启「把设置链接发到电脑」与演示的下一步

日期：2026-10-04（本机时区）。来源：#342 的方向——降低「换到电脑上安装」这一跳的成本，演示结束后给出明确的下一步。用户要求这块界面先出设计再实现。

- 设计项目：cc-pocket Design System 2.0。
- [在线设计板：First Run · Send to Computer v1](https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=First+Run+%C2%B7+Send+to+Computer+v1.dc.html)，共享组件 `FirstRunDevice v2`（由 `FirstRunDevice` 复制而来；v1 组件和旧板 [First Run · Connect + Pair](../first-run-connect-pair/README.md) 未改动）。
- 设计生成：Claude Design，界面显示 Fable 5.1 Max，一次生成约 30 分钟，使用用户的 Claude Design 额度。投递内容见 [DESIGN_BRIEF](DESIGN_BRIEF.md)。
- 范围：首启「连接你的电脑」页的六个状态、演示模式里的三处新元素、电脑上打开的 `pairlet.org/start` 页面。配对页（六个状态）不在范围内，设计板里只把命令名换成了 `pairlet pair`。
- **状态：用户评审不通过（2026-10-04），不按此稿实现。** 评审意见：「发到电脑」不是主流程，这一稿却让它接管了首启页的主按钮、把安装命令和配对入口降到次级，还在演示的消息流里插入卡片，干扰了主路径。重做时首启页的主路径（安装、配对）保持不动，发送入口只作为次级入口出现。下文保留为这一稿的记录。

## 问题

开始配对的人几乎都能配成，流失发生在开始之前。首启页把一条很长的安装命令显示在唯一不能执行它的设备上；「复制」只是把命令放进手机剪贴板，用户还得自己把它带到另一台机器。很多人第一次打开 App 时并不在电脑旁，这一页要求的事当下做不了，也没给别的事可做。另一部分人进演示看一圈就走：演示只有一条被动的「演示模式 · 示例数据」横幅，没有下一步。

## 采用方案：先完成「这一跳」

设计板把推荐方案和一个备选放在一起比较，推荐前者：

- **第一个动作是「把设置链接发到我的电脑」。** 实心按钮调起系统分享（隔空投送到 Mac、发给自己的信息／邮件／备忘录）。按钮下方用 24pt 等宽字显示同一个短网址 `pairlet.org/start`，方便直接在电脑上输入；旁边是复制框。一行小字说明「所有人用的都是同一个链接，不含任何关于你或这部手机的信息」。
- **命令后置但一步可达。** 「查看命令」展开后就是今天那一块原样内容：系统分段、脚本／包管理器切换、命令、前置条件行和 Windows 提示。
- **已发送状态只陈述接下来要做的事。** App 只知道分享面板被用过，不知道是否送达，所以页面不写「已发送」，而是列出电脑上的三步，并把实心按钮换成配对动作。复制链接同样视为已发送。
- **回访时配对领先。** 不是第一次打开时，标题改为「回到电脑旁了？」，实心按钮是「扫码」，「再发一次链接」降为次级入口。
- **演示的出口就是设置的入口。** 横幅上的次级动作、示例回合结束后的一张内联卡片，打开的都是同样三个动作；退出演示落在首启页的「看过演示」状态。

### 备选：保留两步编号

最接近现状：保留「1 安装 / 2 配对」两个编号步骤，把发送链接放进第 1 步。设计板的判断是首次访问时只有第 1 步能做，第 2 步只是承诺，却要占掉首屏约 110px；在 360×640 的小屏上会把信任说明和命令入口挤到首屏之外。推荐方案把配对步骤放到真正可执行的时刻（已发送状态）。备选被保留为退路：如果数据显示用户发了链接却不回来，可以换回这个布局，不需要新部件。

## 首启页的六个状态

| 状态 | 内容 |
|---|---|
| 01 first | 首次访问。实心按钮「发送」，下方短网址、复制框、无标识说明；随后一组发丝线分隔的行：查看命令、已经装好了直接输入数字码、改为浏览演示；再往下是信任说明和「完整设置指南 · 支持」 |
| 02 sent | 分享面板用过或链接被复制之后。「在电脑上」三步（打开链接、运行页面上的命令、运行 `pairlet pair`），实心按钮变为「我在电脑旁了，扫码」，「再发一次链接」在下一行 |
| 03 return | 非首次访问。配对领先：标题提问、正文给出命令、实心按钮「扫码」；其次是「改为输入数字码」「再发一次链接」 |
| 04 after-demo | 刚退出演示。与 first 相同，只把说明行换成「你已经看过它如何工作。现在连接你自己的电脑。」 |
| 05 command-open | 命令展开，Windows 下前置条件行换成注意提示；多一句说明随后要运行 `pairlet pair` |
| 06 compact | 沿用现有行为：滚动后标题折叠，实心按钮停靠在底部。first 状态下停靠的是「发送」，sent 和 return 状态下是配对动作 |

规则：每个状态只有一个实心按钮；其余都是 56pt 的次级行。

## 演示里的三处新元素

演示沿用现有的项目、会话、聊天界面，只新增：

1. **横幅动作**：「演示模式 · 示例数据」右侧多一个强调色文字动作「正式设置」（13pt 文字，44pt 点击区，不是实心按钮）。
2. **底部面板**：标题「正式设置」，三个动作依次是「把设置链接发到我的电脑」（实心，下方带可输入的短网址）、「我在电脑旁了，现在配对」、「继续浏览」。点遮罩等同「继续浏览」。
3. **下一步卡片**：用户在演示里发过一条消息、示例回合结束后，在消息流里（最后一个回合下方、输入区上方）出现一张内联卡片：「刚才是示例数据。在你自己的代码上，它的工作方式完全一样。」带同样三个动作和关闭按钮。每次演示只出现一次，关闭后不再出现。

演示规则：卡片只在示例回复结束且输入区空闲后出现，不打断、不遮挡输入区；没有电脑也能把演示完整走完（审核会这样走）。退出演示落在 04 after-demo。

## `pairlet.org/start` 页面

用户在电脑上打开的页面，是工具页而不是营销页：单列 640px，1280×800 下不需要滚动。按 UA 检测系统并提供分段切换；安装命令带复制按钮；包管理器方式收在一个次级开关后；前置条件行（Windows 下为注意提示）；然后并排两步「2 运行 `pairlet pair`」「3 用手机上的 Pairlet 扫描二维码」；一行信任说明和 GitHub 链接。静态页面，不设 cookie，链接不带任何参数。设计板另给了 390 宽（误在手机上打开）和 Windows 加包管理器展开两帧。

## 证明帧

- 浅色：沿用 Entry Flow 的浅色 token，强调色加深以保证对比。
- 小屏 360×640：标题降到 29px，实心按钮约在距顶 300px，短网址、说明和无标识说明都在 440px 以内，首个动作不需要滚动。
- 平板 820×1180：同一单列，480px 宽居中。
- 简体中文：六个状态、演示三帧和 390 宽的网页都给了中文帧。最长的实心按钮文案「把设置链接发到我的电脑」比英文窄；行高不固定，长文案会撑高行而不是被截断。

## 规格要点

| 部分 | 规格 |
|---|---|
| 实心按钮 | 52pt 高，文字 17px |
| 短网址 | 24pt JetBrains Mono；右侧 44pt 复制框；上方 12.5pt 说明「或在电脑上直接输入」 |
| 次级行 | 56pt，标题加一行说明，「查看命令」的箭头随展开旋转，面板在列表内展开 |
| 横幅动作 | 强调色文字 13pt，44pt 点击区 |
| 下一步卡片 | surface 底、较重的发丝线边框（与提问卡同级），内联在消息流中 |

精确尺寸、颜色和状态切换逻辑在组件源码里；源码不进仓库（见下方「来源」）。

## 文案（英文 / 简体中文）

设计板注明以下之外的文案沿用现有字符串：标题、字标、系统分段、脚本／包管理器标签、命令块、前置条件行和 Windows 提示、「已经装好了？直接输入数字码」、「改为浏览演示 · 不需要电脑」、信任说明、「完整设置指南 · 支持」、「演示模式 · 示例数据」，以及配对页的全部文案。

| key | English | 简体中文 |
|---|---|---|
| `first_run.lead` | Your phone drives the AI coding agent that runs on your computer. Setup starts there. | 手机操控的是运行在你电脑上的 AI 编程代理。设置从电脑开始。 |
| `first_run.lead_after_demo` | You’ve seen how it works. Now connect your own computer. | 你已经看过它如何工作。现在连接你自己的电脑。 |
| `first_run.title_return` | Back at your computer? | 回到电脑旁了？ |
| `first_run.lead_return` | Run `pairlet pair` on the computer. It shows a QR code and a six-digit code. | 在电脑上运行 `pairlet pair`，它会显示二维码和六位数字码。 |
| `first_run.cta_send` | Send the setup link to my computer | 把设置链接发到我的电脑 |
| `first_run.or_type` | or type it on your computer | 或在电脑上直接输入 |
| `first_run.url` | `pairlet.org/start` · not localised | `pairlet.org/start` |
| `first_run.copy_link · a11y` | Copy link | 复制链接 |
| `first_run.no_identifier` | Same link for everyone. It carries nothing about you or this phone. | 所有人用的都是同一个链接，不含任何关于你或这部手机的信息。 |
| `first_run.show_command` | Show the command | 查看命令 |
| `first_run.hide_command` | Hide the command | 收起命令 |
| `first_run.show_command_sub` | What the link will ask you to run | 链接会让你运行的内容 |
| `first_run.then_pair` | Then run `pairlet pair`. It shows a six-digit code and a QR. | 然后运行 `pairlet pair`，它会显示六位数字码和二维码。 |
| `sent.on_computer · label` | On the computer | 在电脑上 |
| `sent.step_1` | Open `pairlet.org/start` | 打开 `pairlet.org/start` |
| `sent.step_2` | Run the command it shows | 运行页面上显示的命令 |
| `sent.step_3` | Run `pairlet pair`. It shows a QR code. | 运行 `pairlet pair`，它会显示二维码。 |
| `sent.cta_scan` | I’m at my computer — scan the code | 我在电脑旁了，扫码 |
| `return.cta_scan_short` | Scan the code | 扫码 |
| `route.send_again` | Send the link again | 再发一次链接 |
| `route.send_again_sub` | or type pairlet.org/start on the computer | 或在电脑上输入 pairlet.org/start |
| `route.send_again_sub_return` | Not installed yet? pairlet.org/start | 还没安装？pairlet.org/start |
| `route.enter_code_return` | Enter the code instead | 改为输入数字码 |
| `share.text · the message itself` | Set up Pairlet on this computer: https://pairlet.org/start | 在这台电脑上设置 Pairlet：https://pairlet.org/start |
| `share.chooser_title · Android only` | Send the setup link | 发送设置链接 |
| `demo.setup_real` | Set up for real | 正式设置 |
| `demo.sheet_body` | Pairlet runs on your own computer. Get the setup link there first. | Pairlet 运行在你自己的电脑上。先把设置链接发过去。 |
| `demo.pair_now` | I’m at my computer — pair now | 我在电脑旁了，现在配对 |
| `demo.keep_exploring` | Keep exploring | 继续浏览 |
| `demo.or_type_url` | or type pairlet.org/start on the computer | 或在电脑上输入 pairlet.org/start |
| `demo.next_step_card` | That was sample data. On your own code it works the same way. | 刚才是示例数据。在你自己的代码上，它的工作方式完全一样。 |
| `demo.dismiss · a11y` | Dismiss | 关闭 |
| `demo.leave · if not already a string` | Leave demo | 退出演示 |
| `start.title` | Set up Pairlet on this computer | 在这台电脑上设置 Pairlet |
| `start.lead` | Three steps. The app on your phone does the rest. | 三步完成，其余交给手机上的应用。 |
| `start.step_install` | Install | 安装 |
| `start.detected` | Detected: {os} | 已检测到：{os} |
| `start.copy / copied` | Copy / Copied | 复制 / 已复制 |
| `start.pkg_question` | Prefer a package manager? | 想用包管理器？ |
| `start.pkg_show / pkg_hide` | Show it / Hide | 显示 / 收起 |
| `start.prereq` | Needs the Claude Code CLI on this computer | 这台电脑需要安装 Claude Code CLI |
| `start.step_run` | Run `pairlet pair` | 运行 `pairlet pair` |
| `start.step_run_body` | It prints a six-digit code and a QR. | 它会打印一个六位数字码和一个二维码。 |
| `start.step_scan` | Scan the QR with the Pairlet app on your phone | 用手机上的 Pairlet 应用扫描二维码 |
| `start.step_scan_body` | Or enter the six-digit code in the app. | 或在应用中输入六位数字码。 |
| `start.trust` | Open source and end-to-end encrypted. Read the code on GitHub. | 开源，端到端加密。代码见 GitHub。 |

分享消息把说明和链接放在同一行，信息和邮件会把它当作一个整体预览。

## 动作与埋点名

设计板给每个新动作起了稳定的 snake_case 名字。实现时以现有的 `onboarding_cta`、`demo_*` 事件和已注册的 GA4 维度为准，这些名字用作其中的取值，不新增维度（#342 的约定）。

| 名称 | 触发与取值 |
|---|---|
| `first_run_viewed` | on appear · {variant: first \| sent \| return \| after_demo} |
| `send_link_tapped` | filled button, inline or docked · {source: first_run \| demo_sheet \| demo_card \| send_again} |
| `send_link_shared` | share sheet completed · no destination, no content · moves the screen to sent |
| `send_link_cancelled` | share sheet dismissed · nothing changes |
| `copy_link` | URL row or its copy box · also moves the screen to sent |
| `show_command / hide_command` | the disclosure row |
| `platform_switched · channel_switched · copy_command` | inside the disclosure · {os}, {channel} · keep today's names if they exist |
| `scan_code_tapped` | filled button in sent and return · opens the pair screen |
| `send_link_again` | quiet route in sent and return · then the share sheet as above |
| `demo_setup_tapped` | banner action · opens the sheet |
| `demo_sheet_send_link · demo_sheet_pair_now · demo_sheet_keep_exploring` | the three sheet actions · scrim tap counts as keep_exploring |
| `demo_next_step_shown` | card inserted · once per walkthrough |
| `demo_next_step_tapped` | {action: send_link \| pair_now} |
| `demo_next_step_dismissed` | {via: close \| keep_exploring} |
| `demo_left` | any exit from the demo · {to: after_demo \| pair \| share} |
| `start_page_view · start_os_switched · start_copy_command · start_pkg_toggled` | web, pairlet.org/start · {os_detected}, {os}, {channel} · page view only if the site has analytics at all; none of these needs a cookie |

## 界面之外要做的

- **`pairlet.org/start` 静态页**：安装脚本和包管理器命令要与 App 内显示的同源。当前 `site/` 下没有这个路径，需要新增并部署。
- **文本分享调用**：App 目前只有文件分享（`shareFile`），没有文本分享。iOS 用 `UIActivityViewController`，说明文字和 URL 作为两个条目传入，这样隔空投送到 Mac 会直接打开链接；Android 用 `ACTION_SEND` 的 `text/plain`。只在完成回调里记一次「已分享」，不记录去向。
- **本地标记**：区分首次与回访（现有的引导访问计数可用）、「刚退出演示」标记、每次演示一次的卡片标记。没有后端，没有账号。

## 设计师注明的占位与边界

- 三个平台的安装命令、包管理器命令和 Windows CLI 那句话都是占位，实现时换成仓库里的真实命令（见 `OnboardingScreen.kt`）。设计板网页上的 `curl … pairlet.org/install.sh | sh` 也是占位。
- 信任说明和「退出演示」按现有内容画出，文案没有重新指定；原型里「退出演示」放在项目页头部。
- 原型里的分享面板是替身，真实面板属于系统；演示里点发送用的是预置消息。
- 项目页和聊天页之间的会话列表没有画，横幅在那里表现相同。

## 评审时请留意

- 短网址 `pairlet.org/start` 是工作名，定了才能做网页和 App 内文案。
- 现有前置条件行写的是「需要 Claude Code CLI」，本稿原样沿用；是否改成覆盖多种 Agent 的说法不在本稿范围。
- 已发送状态只在本次停留期间成立；下次冷启动按访问次数进入回访状态。
- 备选方案的切换条件依赖埋点：发送后是否回访。

## 来源

设计板源码（`First Run · Send to Computer v1.dc.html`、`FirstRunDevice v2.dc.html`）和 28 张逐帧截图保存在本机被忽略的 `_local/design/first-run-send-to-computer-20261004/`，不进仓库；以在线设计板为准。
