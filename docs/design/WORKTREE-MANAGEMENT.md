# #281 Worktree 管理设计（草案 v1）

> 状态：**待用户拍板**。本设计**显式叠放在 #280 Git 面板地基上**（`docs/design/GIT-PANEL.md`）：执行面（argv 白名单、ExecutableResolver、porcelain 解析、两段式确认 token）全部复用 GitService，实施同一个 worktree B 原地续，不另起炉灶。

## 1. 命题

多机 fleet 与会话分组已有，但**同一仓库内多 worktree 并行**这层缺失：手机上无法辨识哪个会话跑在哪个 worktree，无法新建/清理 worktree。「一个仓库多个分支并行跑 agent」是 power-user 的核心场景。

## 2. 功能范围

| 类别 | 内容 |
|---|---|
| 列出 | 仓库全部 worktree：路径、分支、HEAD、是否 main、脏/净、是否有活跃会话 |
| 新建 | `git worktree add`（现有分支或新建分支），默认路径 `<repo>-worktrees/<branch-slug>`（可配置） |
| 进入 | 「在此 worktree 开会话」＝现有 OpenSession(workdir=该路径)，零新协议 |
| 清理 | 删除 linked worktree：净＋无活跃会话直接删；脏或有会话走两段式确认；main worktree 永远拒绝 |
| 标注 | 目录列表里 linked-worktree 行加轻标注「⎇ 属于 <repo>」 |

**收窄决定**：目录/会话列表的「同仓库家族归并成组」大改列表 UI，第一版不做——归并视图收在 worktree 管理面内部（它本来就按仓库聚合），主列表只加轻标注。后续按反馈再评估。

## 3. 与既有系统的关系

- **识别**：`git worktree list --porcelain` 在任一家族成员的 workdir 下都返回全家族（daemon 对会话 workdir 跑一次即得）。`worktree` 段的 `bare`/`detached`/`prunable` 状态按 porcelain 字段透传。
- **transcript 归属**：不同 worktree 是不同 cwd → `~/.claude/projects` 下不同目录（`ProjectPaths.dirFor`），现有扫描天然各自成项目——**不改** TranscriptScanner 的归属逻辑，标注层只做展示。
- **活跃会话判定**：复用 SessionRegistry / LiveProcesses 的既有判定（`DirectoryEntry.open/executing` 同源），不发明第二套。
- **脏净判定**：复用 #280 GitService 的 status 解析；列表刷新时并行收集、超时降级为 `dirty=null`（未知），删除前置检查时才强制拿到。

## 4. wire 变更（走 protocol-wire-compat-reviewer）

| 帧 | 方向 | 字段（概要） |
|---|---|---|
| `pocket/worktree.list`（ListWorktrees） | →daemon | convoId, workdir |
| `pocket/worktree.listResult`（WorktreeList） | →phone | repoRoot, worktrees[]{path, branch?, head, isMain, dirty?, activeSessionId?, prunable} |
| `pocket/worktree.add`（AddWorktree） | →daemon | convoId, workdir, branch, createBranch: Boolean, path?（null＝默认策略） |
| `pocket/worktree.remove`（RemoveWorktree） | →daemon | convoId, workdir, path, confirmToken?（两段式，同 #280 GitAction 手法） |
| 应答 | →phone | 复用 #280 的 GitActionPreview / GitActionResult 家族（op 枚举扩 worktree 项）或平行小帧——实施期与 #280 帧族一起定稿，原则：**一套两段式确认机制，不出现第二套 token 语义** |

`DirectoryEntry` 增 trailing optional `worktreeOf: String? = null`（main worktree 的路径；null＝普通目录）——旧 daemon 省略、旧 App 忽略，纯展示降级。

## 5. 安全与确认语义（对齐 #280 权限模型）

- 通道：**owner-only**，与 Git 面板同一 choke point，guest/bridge 一律拒。
- 新建（add）：L1 点击即执行——在新目录落盘、不碰既有数据；路径**必须**落在默认策略目录或仓库同级（daemon 校验，拒绝任意路径写入）。
- 删除（remove）：L2 两段式。preview 返回：脏文件数、是否有活跃会话、路径；确认 token 60s 单次。`git worktree remove`（净）/ `--force`（脏，确认后）。有**活跃会话**的 worktree 即使确认也拒绝——先停会话再删（防止拉掉正在跑的 agent 脚下的地毯）。
- main worktree、`.git` 本体：白名单外，不可达。

## 6. 回归门禁

- porcelain worktree 解析单测（bare / detached / prunable / locked 各形态）。
- 删除准入矩阵测试：净×无会话／脏×确认／活会话拒绝／main 拒绝，逐格测。
- 路径策略测试：默认目录生成、越界路径拒绝（`..`、绝对路径逃逸）。
- protocol 过 `protocol-wire-compat-reviewer`。

## 7. 拆件与档位（实施期，worktree B 内 #280 合入后原地续）

| 件 | 档位 | 执行者 |
|---|---|---|
| worktree 命令族并入 GitService＋porcelain 解析 | 高（安全面延伸） | Fable |
| 删除准入编排（会话/脏检查＋token） | 高 | Fable |
| protocol 帧＋DirectoryEntry 扩展 | 高（wire 兼容） | Fable |
| 手机端 worktree 管理面 UI | 中（按稿） | opus5 |
| 桌面端同款＋目录行轻标注 | 中（按稿） | opus5 |
