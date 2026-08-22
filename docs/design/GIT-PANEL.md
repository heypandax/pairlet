# #280 Git 最小闭环设计（草案 v1）

> 状态：**待用户拍板**。范围刻意收严——本文档只覆盖最小闭环；hunk 级操作、AI commit 草稿等进阶项显式推迟（§8）。#281 worktree 管理在此地基上叠放（另有文档）。

## 1. 命题与定位

「手机上看完 agent 改动后直接收尾提交」是移动端闭环的最后一公里：目前看完 diff 必须回电脑 stage → commit → push。

**定位澄清**：手机今天已经能跑 git 命令（`RunShellCommand`，pocket/shell.run 的快捷终端）。#280 的增量不是「能跑」，而是：

- **结构化**：status / 分支 / ahead-behind 解析成模型渲染，不是终端文本；
- **低摩擦**：读操作零审批（快捷终端里 `git status` 每次都可能弹审批卡）；
- **封闭操作集**：daemon 侧白名单拼 argv，不存在自由文本命令面——这是它能比快捷终端低摩擦的安全前提。

## 2. 功能范围（最小闭环）

| 类别 | 操作 | 说明 |
|---|---|---|
| 看 | status（staged / unstaged / untracked / conflicted）、当前分支、ahead/behind | `--porcelain=v2 --branch` 解析 |
| 看 | 单文件工作区 diff（staged / unstaged 两态） | 复用 App 现有 DiffView 渲染 |
| 暂存 | stage / unstage（**文件级**） | hunk 级显式推迟 |
| 提交 | commit（手输 message） | AI 草稿显式推迟 |
| 同步 | fetch、pull（**仅 --ff-only**，非 ff 报「回电脑处理」）、push（当前分支、**无 force**） | |
| 分支 | 列表、新建、checkout（脏工作区时走二次确认） | |
| 撤销 | revert 单文件（丢弃工作区改动，二次确认） | |
| 冲突 | 只读提示（conflicted 列表＋禁用 commit），不做解决 | |

## 3. 权限模型（本设计的安全核心）

### 3.1 通道：owner-only

git 面板整体是 **owner-only surface**（与快捷终端同款 choke point）：Guest / Bridge / Collaborator 一律拒绝——读也不开放。理由：guest 现有的 files/diff 面是「本会话改了什么」，git status 是「整个仓库状态」，面严格更大；第一版最小面，后续有真实需求再单独评估只读放行。daemon 在路由入口按凭证类型硬拒（对齐 `GuestGuard` 手法），不依赖 UI 隐藏。

### 3.2 操作分级（owner 通道内）

与快捷终端不同：git 面板操作是**封闭集合、用户显式点击发起**（不是 agent 自主动作、没有自由文本），所以不走 `PermissionAsk` 审批卡——所需的是**操作自身的确认语义**：

| 级别 | 操作 | 语义 |
|---|---|---|
| L0 纯读 | status / branches / ahead-behind / diff | 零确认零审批 |
| L1 点击即执行 | stage / unstage / commit / fetch / pull --ff-only / push / 新建分支 / 干净区 checkout | 按钮本身即意图；stage、commit 本地可逆，push 无 force 不破坏远端 |
| L2 二次确认（两段式帧） | revert 单文件、脏工作区 checkout | 先回影响预览（丢弃哪些文件的改动），确认帧才执行 |

不提供的操作（force push、merge/rebase、reset --hard 等）不在集合里，天然不可达——白名单即防线。

### 3.3 注入红线

- 所有 git 调用走 **argv 数组**（ProcessBuilder），绝不经过 shell 字符串拼接；文件名参数原样入数组并以 `--` 分隔符终止选项解析（防以 `-` 开头的文件名注入 flag）。
- workdir 必须是已见会话的 workdir（对齐现有 files 面的锚定原则），拒绝任意路径。

## 4. wire 变更（走 protocol-wire-compat-reviewer）

| 帧 | 方向 | 字段（概要） | 说明 |
|---|---|---|---|
| `pocket/git.status`（FetchGitStatus） | →daemon | convoId, workdir | 应答 GitStatus |
| `pocket/git.statusResult`（GitStatus） | →phone | branch, upstream?, ahead, behind, staged[], unstaged[], untracked[], conflicted[], truncated | 条目封顶防帧超限 |
| `pocket/git.diff`（ReadGitDiff） | →daemon | convoId, workdir, path, staged: Boolean | 应答 GitDiff（unified 文本，渲染复用 DiffView） |
| `pocket/git.action`（GitAction） | →daemon | convoId, workdir, op（枚举）, paths[]?, message?, branch?, confirmToken? | L2 操作首发不带 token |
| `pocket/git.preview`（GitActionPreview） | →phone | op, 影响摘要（文件列表等）, confirmToken | 二次确认凭证，短时效 |
| `pocket/git.result`（GitActionResult) | →phone | op, ok, stdout/stderr 摘要, statusAfter?（顺带回新 status 省一次往返） | |

兼容性：全部是新增帧——旧 daemon 丢弃未知帧（App 探测超时后显示「更新电脑端」，对齐 `ReadFileDiff` 的既有降级文案）；旧 App 不发送。无既有类型改动，兼容面干净。

## 5. daemon 实现要点

- 新 `git/GitService.kt`：git 可执行解析（对齐 `ExecutableResolver` 模式：PATH → fallback dirs，Windows 试 `git.exe`）；子进程执行照抄 `ShellService.execute` 手法（`daemon/shell/ShellService.kt:182`——timeout、drainCapped 封顶、背压 one-in-flight per convo）。
- 解析器：`status --porcelain=v2 --branch`（含 ahead/behind）、`branch --format`。porcelain 输出恒用 `/` 分隔符，Windows 上不做路径变换（既训：Windows 路径坑）。
- L2 两段式：preview 时计算影响（`git status` 交叉 paths），发 confirmToken（UUID，60s 时效，单次），确认帧校验 token 后执行。
- 出口守卫：非 CLAUDE 后端的会话同样可用（git 与 agent 种类无关）——但 workdir 非 git 仓库时 GitStatus 回 `notARepo=true`，App 不显示面板入口。

## 6. App 端

- 入口：会话内现有「文件/diff」区域旁增 Git 标签页（或会话菜单项——形态按稿，真岔路走 /design）。
- 手机端与桌面端同步实现；桌面端 ChatPane/Sidebar 属大 UI 文件，遵守小步 Edit 既训。
- 状态流：进入面板拉 status → 操作后用 `GitActionResult.statusAfter` 原地刷新，不加轮询。

## 7. 回归门禁

- protocol 过 `protocol-wire-compat-reviewer`；GitService 单测覆盖 porcelain 解析（含 rename / 冲突 / detached HEAD / 空仓库）与 L2 token 校验路径。
- 安全红线测试：owner-only 拒绝路径（guest/bridge/collab 凭证各一条）、`--` 终止符在场、白名单外 op 拒绝。
- 与 #285 修复批次共存：不触碰 Conversation / prompt 链路，`BridgePromptFateTest` 无交集但合并态全量照跑。

## 8. 显式推迟（防范围膨胀）

hunk 级 stage/unstage/revert、图片 diff、AI commit message 草稿、stash、log 浏览、merge/rebase 冲突解决、force push——全部不做，录后续 issue 按需求热度排。

## 9. 拆件与档位（实施期，进 worktree B；#281 在其上叠放）

| 件 | 档位 | 执行者 |
|---|---|---|
| protocol 帧族 | 高（wire 兼容） | Fable |
| GitService（argv 白名单＋porcelain 解析＋两段式 token） | 高（安全面） | Fable |
| RequestRouter 接线＋owner-only 守卫 | 高 | Fable |
| 手机端 Git 面板 UI | 中（按稿） | opus5 |
| 桌面端 Git 面板 UI | 中（按稿，小步 Edit） | opus5 |
| porcelain 解析器单测补全 | 中 | opus5 |
