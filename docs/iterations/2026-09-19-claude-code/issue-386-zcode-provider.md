# #386：Windows ZCode 找不到内置 provider 配置

来源：[Issue #386](https://github.com/heypandax/pairlet/issues/386)。批次 2；建议 P1。状态：诊断与条件修复方案，根因未确认。

## 目标与事实

Windows 上安装在非默认目录的受支持 ZCode，通过 Pairlet 能启动会话并发出第一条消息，后续恢复原会话也正常。

反馈为 Pairlet 2.1.0，ZCode CLI 已启动但 exit 1，报找不到 `resources/glm/provider/zcode-builtin.json` 及另一个落在盘符根下的 `config/provider/zcode-builtin.json`。ZCode 精确版本、工作目录、原 App 是否能对话未知。错误来自上游 CLI；与 #353 的 executable not found 不同，也不能先认定新版本回归。

当前 launcher 能从注册表／安装布局查找入口，优先现有 wrapper，必要时用 bundle Electron 加 `ELECTRON_RUN_AS_NODE=1` 启动 `zcode.cjs app-server --stdio`，进程 cwd 是用户工作区。哪种入口实际触发失败仍须现场证据。

入口：

- [ZCodeLauncher.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeLauncher.kt)、[ZCodeBackend.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeBackend.kt)。
- [ZCodePaths.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodePaths.kt)、[ZCodeProviderCatalog.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeProviderCatalog.kt)：仅在配置来源有关时修改。
- [ZCodeLauncherTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/zcode/ZCodeLauncherTest.kt)、[ZCodeBackendTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/zcode/ZCodeBackendTest.kt)。
- [probe-zcode-app-server.py](../../../scripts/probe-zcode-app-server.py)：默认只探测，`--active` 会创建临时会话并调用模型；先确认其启动器支持受测 Windows bundle，不能把脚本默认 macOS 目标当成跨平台契约。

## 排查与实现

1. 记录 ZCode 精确版本／安装方式，确认上游桌面 App 自身可否正常对话；读取 daemon 选中的入口、工作区、退出阶段，脱敏保存。缺反馈者环境时先测本机实际版本，明确不能代表其版本。
2. 在受影响 bundle 内只读定位 `zcode-builtin.json` 与报错文案，核对官方 wrapper 传入的 argv、必要环境变量、资源定位逻辑。比较官方启动路径与 daemon 启动路径；不要扫描／打印 provider 文件内的秘密值。
3. 分清三个分支：文件确实缺失；文件存在但基准路径错误；多个安装版本被混用。盘符根路径可能来自 cwd 或 env，只有执行记录能确定，不据报错字符串猜设变量。
4. 优先复用官方机器入口及其已验证环境；若必须 CJS／Electron fallback，将 bundle 根目录和用户工作区分别建模。只增加确证必要的 env／参数，保证执行用户任务的 cwd 不变。
5. 若是新旧版本布局差异，使用可验证能力／文件布局判断，保留旧版本 fallback，并为错误配置给明确诊断。上游缺文件时报告包不完整或不支持版本，不能伪造空 JSON 配置掩盖问题。
6. 对缺文件、初始化失败、进程 exit 的信息保留阶段与安全错误类别；不将 API key、provider 内容或整份环境写进日志。进程失败要结束当前 pending turn，不能让 UI 永久等待。

## 禁止作为修复捷径

- 不硬编码反馈者安装盘符／个人路径；不复制用户 provider 到全局目录。
- 不把工作目录改成 ZCode 安装目录来让相对路径恰好命中，这会让工具执行到错误项目。
- 不改写上游安装包内容或 credential 数据库；不无依据迁移用户配置。
- 不仅因本机版本成功就判定反馈失效，不将 #353 的已完成状态当作本单答案。

## 验收

- 精确受影响版本：官方 App 可用时，Pairlet 新建会话、第一条消息和恢复同一会话均成功；回读 session ID、cwd、模型正确。
- 自定义安装位置、空格／中文工作区，以及与安装目录不同盘符的项目都能按真实 cwd 执行。
- 默认安装、已有 PATH wrapper、CJS fallback、新旧支持布局不串版本；macOS 路径不受 Windows 修复影响。
- provider 文件缺失／不可读／格式不支持时，给出准确错误，退出清楚，无无界重启。
- 使用 fixture 验证路径／参数选择；Windows 真实 bundle 探测证明启动语义，至少一次隔离会话 roundtrip 证明能使用。

## 结果记录（实施后填写）

- 受测版本、官方入口与失败原因：受测的是本机 Windows 11 上的官方 ZCode 3.11.2（注册表 `DisplayIcon` 指向 `C:\Program Files\ZCode`，内置 CLI runtime 自报 `0.16.5`），**不能代表反馈者版本**——整份 bundle（`resources/app.asar` 与 `resources/glm/zcode.cjs`）都搜不到 `zcode-builtin.json` 这个字符串，本机也复现不出 exit 1。官方入口顺序从 3.11.2 的 `app.asar` 里读出（`resolveDefaultZCodeAgentCommand`）：Electron 运行时（`process.execPath` + `resources/glm/zcode.cjs` + `app-server --stdio` + `ELECTRON_RUN_AS_NODE=1`，cwd 为工作区）优先，部署版原生 `zcode-agent(.exe)` 只是兜底；官方 `spawnArgs` 与 daemon 现有的 `app-server --stdio` 一致。实测差异落在宿主：经 bundle 自带 Electron 启动时 CLI 读到 `process.resourcesPath=C:\Program Files\ZCode\resources`，换成任何非 Electron 宿主（plain node，也就是可选单文件 `zcode-agent.exe` 那一类）该值为 `undefined`；而 runtime 的资源查找（`findZCodeAgentRuntimeBinary` / `findZCodeAgentRuntimeNodeBundle`）正是从这个值起算，缺失时退化到 `~/.zcode/server/agents/...` 与 `process.cwd()` 相对的 legacy 根——与反馈里那对「相对 `resources/glm/...` 加落在盘符根下」的路径同形。因此把根因判为「入口选错导致资源基准路径丢失」，属高置信假设，未经反馈者环境证实。
- 启动契约变化、兼容分支与提交：`ZCodeLauncher.resolveExecutable` 在选中 wrapper 之后多一道同目录判定——该目录若同时有 `zcode.cjs`、且这个 bundle 自带的 Electron 能解析出来，就改用「Electron + zcode.cjs」，与官方顺序对齐；原因是 `ExecutableResolver` 的排序正好相反（原生二进制优先，且 `zcode-agent.exe` 排在 `exeNames` 第一位），于是同时带可选原生 artefact 的 bundle 会被从官方桌面永远不会选的入口拉起。判定逐目录进行：`~/.zcode/server/agents/glm` 这类没有 Electron 的部署仍走原生 `zcode-agent`（反馈 #195），macOS 的 `Resources/app/bin` wrapper 与 PATH 上的 wrapper 都在别的目录、不受影响。argv、cwd（仍是用户工作区）与环境变量都没有新增。改动只在工作树，未提交。
- Windows 真测／fixture 回归／未取得的反馈者证据：`:daemon:test --tests '*ZCodeLauncherTest' --tests '*ZCodeBackendTest'` 在 Windows 上 40 项全绿（13 + 27）；此前其中 2 项因 fixture 写死 macOS 布局和 POSIX 权限，在 Windows 必定失败，已改成按宿主构造。新增 fixture 覆盖「同目录完整 bundle 取 node 入口」「无 Electron 的 server agent 目录保留原生二进制」「没有 node 入口的 wrapper 目录不动」。探针 `scripts/probe-zcode-app-server.py`：`--self-test` 通过（原断言写死 POSIX 字面量，在 Windows 不可能成立）；对本机 bundle 的只读探测，以及临时目录里的一次隔离 `--active` roundtrip 全部 PASS，argv 即 `ZCode.exe <zcode.cjs> app-server --stdio`，证据日志里 `apiKey` 已脱敏。未取得的：反馈者的 ZCode 精确版本与安装方式、daemon 当时选中的入口、失败进程的 stderr 原文，以及其官方 App 自身能否正常对话。
- daemon 更新和发布状态：未更新本机 daemon、未提交、未部署、未发布。
- 提交状态（2026-09-19 补记）：已提交到 main，提交 `7e1573af`；未推送、未部署、未发布。上文“未提交”指子任务实施当时的状态。

## 2.1.1 发布补记（2026-09-19）

已发布，待原版本验收；[GitHub 状态回执](https://github.com/heypandax/pairlet/issues/386#issuecomment-5744961840) 已写回并核对。完整构建、生产部署和剩余验收范围见 [2.1.1 发布记录](RELEASE-2.1.1.md)。上文未推送／未发布等描述保留为当时的实施快照。
