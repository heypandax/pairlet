# #387：DSH 模型探测不再污染会话列表

来源：[Issue #387](https://github.com/heypandax/pairlet/issues/387)及移动端复现评论。批次 1；建议 P2。状态：方案，未实施。

## 目标、事实与边界

打开模型列表不应在 DSH Web 中不断留下 `cc-pocket-dsh-models…` 空会话；有活会话时复用其真实模型目录，无活会话时仍能获取用户配置的模型。

当前 `DshModelService`：成功结果缓存 10 分钟，用 Mutex 合并并发请求；缓存外先读 `DshCatalog`，否则在临时 cwd 启动 `dsh --profile acp`，通过 `session/new` 取得 `configOptions`。finally 杀进程并删临时 cwd，没有清理 DSH 持久化的会话。回包里的会话 ID 尚未作为清理凭据保存。此源码路径与反馈吻合，具体 DSH 版本的持久化／删除契约仍需探针确认。

本单仅处理 Pairlet 自己本次探测产生的会话。历史垃圾批量清理不在本轮；`acp` profile 是预期行为；正常会话的发现与接管归 #388。

入口：

- [DshModelService.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshModelService.kt)、[DshCatalog.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshCatalog.kt)。
- [DshPaths.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshPaths.kt)、[DshTranscript.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshTranscript.kt)：DSH_HOME、session ID 编码、header/cwd 与 V3 记录。
- [DshModelServiceTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshModelServiceTest.kt)、[RequestRouterFetchModelsDshTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/server/RequestRouterFetchModelsDshTest.kt)。
- [probe-dsh-acp.py](../../../scripts/probe-dsh-acp.py)：先读脚本；完整探针会创建会话并调用模型，本单优先使用仅握手／模型目录的最小探测。

## 决策顺序

1. 在专用测试目录记录受测 DSH 版本及实际 DSH_HOME；模型探测前后列出新增会话，核对 RPC 返回 ID、记录 header 的 ID/cwd、Web 中显示结果。不要输出凭据。
2. 优先查明当前受支持版本是否有公开、可验证的“不持久化模型目录／临时会话”能力。仅在可读到同一用户真实配置且不复制凭据时采用；不要猜 API 名或退回已废弃的 Web RPC。
3. 若必须 `session/new`，优先使用经探针验证的上游删除能力，在进程结束前清理该 ID。不得假设 ACP 一定提供 delete；记录能力及版本依据。
4. 上游没有删除能力时，可评估严格归属校验的本次会话文件清理：保存成功回包的准确 ID、scratch cwd 和受控生命周期，结束并等待本次进程退出后核对持久化记录，再清理唯一会话目录。此分支只有在确认格式、锁与无并发写入的条件后才能启用。
5. 任何无法证明归属、回包丢失没有 ID、写入未结束、锁状态不明的情况均不按名称猜删。记录可诊断失败并标明该异常路径未解决；如果这种情况仍频繁制造垃圾，需要回到无持久化方案，不能把“正常路径清理成功”称为全面完成。

## 实现要求

- 保留当前成功缓存、失败不缓存、并发合并和活会话复用；清理失败与“模型目录不可用”分开表达，不随意丢掉已取得的目录。
- 将临时进程／会话生命周期封装为可测试单元；ProcessBuilder.start 失败也要释放自己创建的 scratch。超时、取消、读帧异常走统一终止路径，并等待进程真正退出后操作持久化文件。
- 使用配置生效后的 DSH_HOME。项目目录名是有损编码，不能仅靠 projectKey 归属；需核对记录 header 的 cwd 和会话 ID。
- 删除目标必须为已验证会话根之下唯一、本次拥有的目录；拒绝 symlink／junction 越界、ID 路径穿越、同名碰撞或含用户消息的会话。不得删除项目根、全局锁或其他会话。
- 失败日志记录有限原因与阶段，避免原样输出用户转写／凭据；不靠隐藏 Pairlet 列表掩盖 DSH Web 里的垃圾。

## 验收矩阵

| 场景 | 预期 |
|---|---|
| 已有活会话，无有效缓存 | 不创建新探测会话，模型／effort 正确 |
| 无活会话，首次打开及缓存过期后再打开 | 目录正常；本次临时记录最终不留在 DSH Web |
| 缓存命中、8 个并发 fetch | 分别零额外探测、最多一次实际探测 |
| 启动失败、超时、取消、清理失败 | 不残留自己可终止的进程；失败可诊断，不误删；未能清理的异常如实记录 |
| 相邻真实会话、相同前缀 cwd、路径碰撞 | 原始记录逐字节不变 |
| Windows 和 macOS；自定义 DSH_HOME | 路径与目录定位正确；各平台测试证据分开记录 |

用隔离 fixture 注入文件／进程错误，并在真实受支持 DSH 上完成一次前后对照。#388 应在本改动整合后验证正常会话仍可发现与接管。修复属于 daemon，需要依根目录规则更新及随后发布；本方案不启动本机更新。

## 结果记录（实施后填写）

- 受测版本／实际采用的能力与清理分支：本机实测 `dsh 0.1.2-rc.1`（`C:\Users\Panda\AppData\Roaming\nvm\v22.17.0\dsh.cmd`，Node v25.2.1）。读随包发布的 `@deepseek-ai/dsh-acp` 与 `dsh-session-persistence[-jsonl]` 确认：ACP 只注册 `initialize`／`authenticate`／`session/new`／`session/list`／`session/resume`／`session/close`／`session/set_config_option`／`session/prompt`／`session/cancel`，`sessionCapabilities` 仅 `{close, list, resume}`；SDK 虽定义了 `session/delete`，dsh 未注册处理器；`session/new` 的参数只有 `{cwd, additionalDirectories, mcpServers, _meta}`，没有 ephemeral／persist，`_meta` 也从不读取；持久化层自述“无删除或保留期 API”，既没有删除接口，也没有关闭持久化的配置项或环境变量；`newSession` 在回包前就调用 `ensureMaterialized`，空会话先落盘再回答。决策顺序第 2、3 条因此都不成立，走第 4 条：严格归属校验的本次会话文件清理。
- 根因、提交与文件归属保护：探测把 `session/new` 回包里的 `sessionId` 丢掉了，`finally` 只 `destroyForcibly` 进程并删临时 cwd，从不碰 dsh 落盘的那一行。改动集中在新增的 `daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshProbeSession.kt`（进程／scratch／会话三条生命周期）与同目录 `DshProbeSessionCleanup.kt`（归属证明与删除），`DshModelService.kt` 只剩“选哪个可执行文件”；`DshPaths.kt`、`DshBackend.kt`、`DshLauncher.kt`、`DshTranscript*.kt` 一行未动（与并行的 #388 约定的文件归属）。删除只认本次回包的准确 ID：目录必须恰好位于生效 DSH_HOME 会话根下两层，且两层都在 `toRealPath` 之后复核（symlink／junction 越界即拒）；ID 经 `encodeSessionId` 后必须是单段目录名（路径穿越即拒）；记录 header 的 `id` 与 `cwd` 必须与本次逐字一致（`projectKey` 有损且会碰撞，这是唯一判据）；目录里除一代转写与 dsh 自己的 sidecar 外不得有任何东西；进程确认退出后才动文件，未退出即拒。探测侧同时把 `DSH_HOME` 显式钉进子进程环境，并先 `session/close`（其内部会 flush 持久化）再关 stdin，保证读到的是最终文件。
- 正常／异常路径验证及残余限制：`./gradlew.bat :daemon:test --tests '*DshModelServiceTest' --tests '*RequestRouterFetchModelsDshTest' --tests '*DshProbeSessionTest'` 共 37 例全绿（新增 `DshProbeSessionTest` 26 例）；覆盖有活会话不新建探测、缓存命中与 8 并发只探测一次、失败不缓存、启动抛错仍释放 scratch、无回包／子进程不死／清理抛错都不误删且不丢已取得的目录，以及相邻真实会话、`projectKey` 碰撞（`…/a/b` 与 `…/a-b`）、同前缀 cwd、多代转写、子目录、链接、越界 ID 等场景下原记录逐字节不变。真实 DSH 前后对照由新增的 `DshProbeSessionLiveIT`（默认关闭，`CC_POCKET_DSH_LIVE=1` 开启）完成：对真实 `~/.dsh` 跑一次真实探测，会话根内每个文件的大小与 CRC 前后完全一致。这次实测同时纠正了方案里的一个隐含假设——空探测会话不是只有 header：dsh 还会写入 `permission/preset`、`sandbox/mode`、`approval/policy` 三条启动配置记录，因此“只删空会话”落地成一份可验证的白名单，白名单之外的记录一律拒删。残余限制：白名单是 fail-closed 的，未来 dsh 新增一条启动记录会让清理开始拒绝并在日志里报 `unrecognized record`，届时需重跑探针确认其不含用户内容后再加入；历史垃圾（本机 `--…cc-pocket-dsh-models17070857550401876678--` 等）按方案不在本轮批量清理；dsh 的 `storages/session_projcache` 等全局缓存不属于会话目录，本改动不碰。
- 本机更新、发布与反馈验收：未执行。按本次任务边界不 commit、不 push、不部署、不更新本机 daemon；`scripts/update-local-daemon*.sh`、发布，以及 #388 的“正常会话仍可发现与接管”验证留给后续。
- 提交状态（2026-09-19 补记）：已提交到 main，提交 `2e83f6d9`；未推送、未部署、未发布。上文“未提交”指子任务实施当时的状态。

## 2.1.1 发布补记（2026-09-19）

daemon 已发布，已关闭；[GitHub 状态回执](https://github.com/heypandax/pairlet/issues/387#issuecomment-5744960561) 已写回并核对。完整构建、生产部署和剩余验收范围见 [2.1.1 发布记录](RELEASE-2.1.1.md)。上文未推送／未发布等描述保留为当时的实施快照。
