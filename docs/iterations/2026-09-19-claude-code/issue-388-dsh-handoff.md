# #388：DSH 会话双向可见、接管和启动失败

来源：[Issue #388](https://github.com/heypandax/pairlet/issues/388)及启动错误评论。批次 2；建议 P1。状态：分段诊断方案，未独立复现。

## 问题拆分与目标

报告环境：Pairlet／daemon 2.1.0、DSH 0.1.5-rc.1、macOS Apple Silicon、iPhone／iPad。

| 编号 | 用户症状 | 需要证明的链路 |
|---|---|---|
| A | Pairlet 创建的会话落盘，但 DSH Web 看不到 | 创建 ID／cwd → 实际 DSH_HOME → Web profile 的发现、筛选与刷新 |
| B | DSH Web 创建的会话无法在 Pairlet 接管 | 外部历史发现／显式导入 → 同一 ID/cwd → ACP resume → 可继续对话 |
| C | 手机启动 DSH 报 Internal error，随后 turn failed | initialize／new 或 resume／配置写入／首条 prompt 的具体失败边界 |

目标是两端顺序接续同一会话，不复制、偷偷新建或 fork 来模拟成功。正常会话归本单；模型目录探测空会话归 #387。A/B/C 可能有不同原因，分别记录证据和完成状态。

## 已有基线，不重复实现

- #376 已补 DSH V0–V3 发现与历史回放；当前按真实 header/cwd 判断归属，不仅靠有损项目目录名。
- `DshBackend.openSession` 对已有 ID 调用 `session/resume`，否则 `session/new`；resume 回包缺 sessionId 时使用原 resumeId。
- 新会话已有 Ungrouped 提示；ACP 没有已验证的 Web preset group 设置能力。Ungrouped 与“完全不可见”不是同一问题。
- #360 已引入“Pairlet 受管会话／外部发现并显式导入”的产品边界。Web 会话不自动进入受管列表并不直接等于扫描失效；验收需走既有发现入口。

代码入口：

- [DshBackend.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshBackend.kt)、[DshLauncher.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshLauncher.kt)。
- [DshPaths.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshPaths.kt)、[DshTranscriptScanner.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshTranscriptScanner.kt)、[DshTranscript.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshTranscript.kt)。
- [DshBackendAcpTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshBackendAcpTest.kt)、[DshGenerationReplayTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshGenerationReplayTest.kt)、[DshResumeMetaTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshResumeMetaTest.kt)。
- [probe-dsh-acp.py](../../../scripts/probe-dsh-acp.py)及现有会话发现／导入调用链。

## 诊断矩阵

1. 同一测试用户、DSH_HOME、工作目录、DSH 精确版本，分别从 Web 和 Pairlet 新建一个专用会话；记录原 ID、header cwd、实际持久化文件代际、首次成功轮次及界面可见位置。日志用脱敏 ID 映射，不提交真实对话。
2. A 方向：停止写入并释放一端会话后，检查 Web 的所有相关列表和 Ungrouped，执行正常刷新，再检查是否仅重启可见；区分未落盘、存储根不同、Web 缓存与分组过滤。
3. B 方向：经“发现／导入”找到 Web 会话，检查 Pairlet 使用的 ID/cwd 与文件头一致；捕获 ACP initialize、resume、配置回读、prompt 各阶段结果。发现／导入本身不得发送提示或抢占运行会话。
4. C 方向：按 new/resume 两种情况各复现一次；保留 JSON-RPC error code／安全 data 摘要及 stderr 阶段。确认失败是否在初始化后、打开会话、配置设定或发送前，禁止只拼接“turn failed”覆盖前因。
5. 在真实占用会话中测试另一端请求：只要求准确说明占用／受支持的交接，不强杀上游进程，不声称同时写入受支持。

## 条件修复方案

- **发现／映射错误**：修正当前扫描或导入边界，保留 ID、可信 cwd、V3 优先级及路径授权；不复制原会话文件，也不按有损 projectKey 重新制造 ID。
- **ACP 契约漂移**：用受测版本回包固定 fixture，修正 new/resume／握手能力判断。仅采用实际支持的方法；保留模型／effort 设置完成后才释放首条 prompt 的门控，不丢队列中的消息或图片。
- **资源根或启动环境差异**：统一继承实际用户配置，记录来源；不无依据将 Web 与 ACP 数据库合并，不改 Web 私有索引。
- **上游 Web 索引／分组限制**：验证上游支持的刷新／导入路径；若 Pairlet 无法通过受支持接口解决，交付清晰可验证的限制和绕行，将 A 单独标为未解决。不能用现有 Ungrouped 提示替代真正的可见性验收。
- **初始化错误处理**：失败应结束对应等待，给用户可行动的阶段错误，不无限重试、不自动创建替代会话；其他会话不受影响。

## 验收

| 方向／场景 | 通过条件 |
|---|---|
| Web→Pairlet | 发现／导入原 ID，读到完整多轮历史，继续新一轮后 ID 不变 |
| Pairlet→Web | 正常落盘且释放后，在受支持 Web 入口可见并继续同一会话 |
| 两方向再切回 | 新增轮次可回读，没有丢失、重复、fork 或设置串项 |
| new／resume 失败 | 错误阶段准确、待处理消息有终态，不污染其他会话 |
| 活跃会话占用 | 按上游能力拒绝／提示，不并发改写转写、不强杀 |
| 路径／格式 | 空格、中文 cwd；V3 压缩／非压缩、旧格式、缺失文件按现有契约处理 |
| 与 #387 集成 | 模型列表探测不留下垃圾，正常创建／发现仍不受影响 |

先跑 backend／scanner／replay fixture；再用 DSH 0.1.5-rc.1 或明确受影响版本执行双向现场矩阵。若只能验证更新版本，记录差异及支持边界。现有探针会消耗账号并创建测试会话，先读脚本，在专用目录运行并保存脱敏摘要。

## 结果记录（实施后填写）

- A／B／C 各自根因与状态：
  - **A（Pairlet 建的会话在 DSH Web 看不到）：未解决，本机不可判定，未验收。** 只做了上游源码与落盘证据分析。读 dsh 0.1.2-rc.1 源码的结论与“完全不可见”相反：Web 的会话列表来自对 `~/.dsh/sessions` 的目录扫描并逐条读 header（`dsh-session-persistence-jsonl` 的 `listArtifacts` → `dsh-session-query` → `dsh-api-session-controller`），不读 `storages/workspace.json`；`workspace.json` 只管分组与排序，未归属的会话落进 Ungrouped 桶。ACP 建的会话 `origin` 为空，缺 `sessionListMetadata` 时 `blank` 默认 `false`（即“可见”分支），不会被 `sessionVisible` 过滤掉。真正的上游边界是：挂进工作区的唯一入口是 `workspace.attachSession`，只有 Web 的 `session.create({workspaceId})`、`session.fork`、webhook 与一次性 bootstrap 会调用它；acp profile 压根没挂载 `dsh-workspace` 插件，ACP 方法表里也没有任何挂载入口。Pairlet 无法通过受支持接口解决，现有 Ungrouped 提示文案属实，因此未改文案、未写 Web 私有索引。附带一条可验证副作用（可能才是“找不到”的观感来源）：acp bundle 不挂 `dsh-api-session-controller`，ACP 会话永远缺 `sessionListMetadata`，Web 端 `updatedAt` 退回 `header.createdAt`，列表按创建时间而非最后一次对话排序。
  - **B（Web 会话在 Pairlet 接管）：未发现 daemon 侧缺陷，按 #360 边界工作，已补 fixture 证明。** Web 会话的 ID 形如 `session-<uuid>`（ACP 是裸 UUID），落盘布局与 ACP 一致；`DshTranscriptScanner` 按 header 的 `cwd` 归属、按原 ID 定位，`ProjectPaths.canonicalKey` 走 `toRealPath()` 吸收软链与大小写差异，`DshBackend.openSession` 对已有 ID 走 `session/resume` 且 resume 回包缺 `sessionId` 时沿用原 ID——全链路不重造 ID、不复制文件。真实 Web→Pairlet 端到端未验收。
  - **C（Internal error 后 turn failed）：找到并修复三个真实缺陷。** ① `initialize` 的错误回包完全没被路由——`handleErrorResponse` 只认 config／sessionOpen／prompt 三类 id，initialize 失败只剩一行日志，30 秒后握手看门狗再给出“你的 dsh 太旧”这个与错误本身矛盾的诊断；② `session/new` ／ `session/resume` 失败（以及 `session/new` 回包缺 `sessionId`）虽然报了错，却把排在 `promptGate` 后面的首条 prompt 永远留在队列里：没有终态，Conversation 的未消费账本不结算，每次重启都重放——这正是“Internal error 之后 turn failed”的来源；③ 失败之后再发的消息继续无限排队。修复：错误回包按阶段命名（握手／新建／恢复），JSON-RPC `code` 与有界的 `data` 摘要一并带出（裸 `Internal error` 不指向任何原因），所有等待中的 prompt 以 `UserReplay + ⚠️ + isError` 结算终态；不重试、不自动新建替代会话、不触碰其他会话；重启（`attach`）清除失败态。
- DSH 版本、两方向 session ID 连续性证据：本机 `dsh` **不可执行**——`DshLauncher` 的全部解析位置（PATH、`%APPDATA%\npm`、`~/.local/bin`、`~/.npm-global/bin`、`~/.volta/bin`、`~/.bun/bin`）都没有 `dsh*`，daemon config 也没有 `--dsh-bin`，因此**没有跑** `scripts/probe-dsh-acp.py`，没有真实双向现场矩阵，也没有新的 session ID 连续性实测。可用证据只有本机 `~/.dsh` 的历史落盘（`sessions/`、`storages/workspace.json`、`storages/session_projcache/sessions/*.json`，全是明文 JSON）与 `0.1.2-rc.1` 的上游包源码；反馈环境是 macOS + `0.1.5-rc.1`，差三个补丁版本，A 的结论只对 `0.1.2-rc.1` 成立。另需注意：本机 projcache 的行数差异是**按时间线**分裂的（早于 `~/.dsh/profiles/acp/` 创建时间的几条大概率跑在 web profile 下），裸 UUID 不能反证 ACP 出身，只有 `session-` ／ `webhook-` 前缀能证明 Web ／ webhook 出身。ID 连续性只由 fixture 证明：新增的 `a_web_created_session_is_discovered_by_its_own_id_and_cwd` 与既有的 `a resume opens the recorded session instead of creating one`。
- 修改、测试、上游限制及未验收项：改动 [DshBackend.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshBackend.kt)（新增 `openFailure` 状态、`describeError`、`failStartup` ／ `injectStartupFailure` ／ `drainWaitingPrompts` 与三个阶段常量；`handleErrorResponse`、`onSessionOpened`、`watchHandshake`、`sendPrompt` 接上），测试 [DshBackendAcpTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshBackendAcpTest.kt)（+5）与 [DshTranscriptScannerTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/dsh/DshTranscriptScannerTest.kt)（+1）。`./gradlew.bat :daemon:test --tests '*DshBackendAcpTest' --tests '*DshTranscriptScannerTest' --tests '*DshGenerationReplayTest' --tests '*DshResumeMetaTest'` 全绿：29 ／ 9 ／ 13 ／ 7 共 58 项，0 失败 0 跳过。上游限制：ACP 无工作区挂载能力（见 A），也无 rename ／ preset ／ fork。未验收项：A 的 Web 端可见性、B 的真实 Web→Pairlet 端到端、C 在 `0.1.5-rc.1` 上的真实错误回包形态（fixture 用的是受测过的 `-32603` ／ `-32602` 形态，不是 0.1.5 实测回包）、活跃会话占用与两方向再切回。
- daemon 更新、发布状态：未提交、未推送、未更新本机 daemon、未发布——按任务边界只在独立 worktree 内改动与自测。
- 提交状态（2026-09-19 补记）：已提交到 main，提交 `24f5e734`；未推送、未部署、未发布。上文“未提交”指子任务实施当时的状态。
