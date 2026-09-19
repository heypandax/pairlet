# 八项修复的验收与交付要求

本文件列出 Claude Code 后续应执行的验证，不是已经通过的测试报告。当前只交付方案。

## 环境与保护

1. 接手先记录 HEAD、分支、工作区／暂存区差异与可用设备；每项用独立补丁或提交范围隔离，保护其他任务的未提交修改。可用 worktree，但不得把未提交依赖静默丢掉；特别是 #389 的诊断变更应先对照。
2. 根目录 AGENTS.md 是当前规则入口。macOS JDK 默认路径不能机械套到 Windows／Linux；使用本机实际 JDK 17 与所需 SDK。测试命令下文使用 POSIX 写法，PowerShell 对应 `./gradlew.bat`；Python 使用本机可用的 `python`／`python3`。
3. 不运行 `:daemon:run` 或手工拉第二个 daemon 做测试。改 daemon 后的本机更新按仓库唯一更新脚本、谱系检查及活动会话确认门；#389 的现场保护见其方案，在隔离环境验证后另行安排本机／生产更新。
4. 不从其他日期的迭代文档继承“已授权部署”“停止 Linux 验证”等历史决定；本轮 #385 明确需要适配其问题环境的验证，#352、#381、#342 明确不在范围。

## 测试分层

先选与实际修改有关的类运行，再做模块和集成检查。不要为了低风险文案或简单布局写镜像测试；需要覆盖状态、权限、数据删除、协议与竞态时，用故障输入证明用户行为和安全边界。

### 任务级测试入口

以下为现有类过滤器；以实施时类名和 Gradle task 为准，可在一次调用中合并相关 `--tests`。UI 类需要可用显示环境，跳过应记录为未执行。

| Issue | 现有定向测试／检查 | 必须补的真实证据 |
|---|---|---|
| #384 | `:mobile:composeApp:desktopTest --tests '*QuestionCardUiTest'` | 手机＋桌面长正文展开／收起，无限高宿主不崩溃 |
| #387 | `:daemon:test --tests '*DshModelServiceTest' --tests '*RequestRouterFetchModelsDshTest'`；路径改动再加 `*DshPathsTest` | 真实 DSH 模型探测前后会话记录及未误删对照 |
| #385 | Linux 原生 `scripts/release-desktop-linux.sh <候选版本>`；实际产物安装 smoke | Debian Testing 的故障包与修复包启动对照、native 栈归因 |
| #386 | `:daemon:test --tests '*ZCodeLauncherTest' --tests '*ZCodeBackendTest'` | Windows 受测 bundle 新建、首条消息和 resume |
| #388 | `:daemon:test --tests '*DshBackendAcpTest' --tests '*DshTranscriptScannerTest' --tests '*DshGenerationReplayTest' --tests '*DshResumeMetaTest'` | Web↔Pairlet 原 ID、cwd、历史及新轮次连续 |
| #389 | `:mobile:composeApp:desktopTest --tests '*PushRegisterTest' --tests '*CollaboratorInboxPushTest'`；`:relay:test --tests '*PushTest' --tests '*CollaboratorPushTest'`；新增协调器／wire 兼容测试 | 专用 iPhone、iOS 生命周期、新旧服务器、实际通知展示；Android 回归 |
| #390 | `:mobile:composeApp:desktopTest --tests '*HtmlPreviewRenderingTest' --tests '*HtmlPreviewPolicyTest'` | iOS 原生附件入口的真实触摸滚动；共用 wrapper 改动时补 Android／桌面 |
| #375 | `:daemon:test --tests '*HandoffTerminalSinkCutTest'` | 有控制的广播延迟与负向泄漏注入、正常 Ubuntu CI |

例：

```bash
./gradlew :daemon:test --tests '*DshModelServiceTest' --tests '*RequestRouterFetchModelsDshTest'
./gradlew :mobile:composeApp:desktopTest --tests '*QuestionCardUiTest'
```

DSH 探针需先读脚本，`python3 scripts/probe-dsh-acp.py <专用测试目录>` 会真实创建会话并调用模型；不能当作无副作用的版本查询。ZCode 先使用 `python3 scripts/probe-zcode-app-server.py --self-test` 及适配实际 bundle 的默认只读探测，证明启动后再按受支持参数进行 `--active` 隔离验证。探针原始帧留本地忽略目录，提交仅保留脱敏摘要与必要 fixture。

### 模块和集成

- 单件收尾：在环境齐备时运行 `bash scripts/check-all.sh --affected <实际基线>`。注意它会计入工作区其他修改，记录本次实际涵盖模块，不能把其他任务失败归到当前补丁。
- 集成批次：根据修改运行 daemon、mobile 等模块；#389 改 protocol 要运行完整受影响的序列化、daemon、relay、客户端测试。
- 交付前在支持环境执行一次 `bash scripts/check-all.sh`，必要时补 `:mobile:composeApp:compileKotlinDesktop` 与 Android 构建。该脚本默认不证明 iOS 能编译。
- iOS 原生改动在 macOS／Xcode 上构建；Apple Silicon 且已启动对应模拟器时可用 `CHECK_IOS=1 bash scripts/check-all.sh` 补仓库现有模拟器测试。真实 APNs、系统权限与触摸体验仍需各方案指定的专用真机验收。
- Linux 打包需 Linux 原生主机；Windows launcher 行为需 Windows。fixture 可以跨平台测解析，不能冒充对应平台集成验收。

## 失败处理

把结果分成：补丁引入的失败、当前基线已有失败、环境不满足、未执行。附 SHA、命令、用例和最小错误摘要。缺少报告不能等同“已知 flaky”；#375 的存在也不能让其他测试失败自动豁免。

测试通过后不无理由扩跑。只有代码变化、新失败、明确未解决的竞态才追加验证；#375 的有界重复是诊断例外，必须固定样本数并记录失败。

## 文档与仓库检查

- 每项将方案底部“结果记录”填为真实完成情况，修正失效路径／最终决策；保留未验收清单。
- 提交前按根目录规则运行 `python3 scripts/check-repository-content.py` 与 `git diff --cached --check`。该脚本只检查 index，未暂存的新文件需要额外检查链接、内容边界和敏感信息；不为跑检查而暂存其他人的修改。
- 原始日志、core dump、用户附件、token、真实配置及探针回执放忽略目录或仓库外；不提交到本目录。
- 不自动评论、关闭 Issue、改标签或发布 Release。交付可供用户决定的状态和证据。

## 最终交付表模板

| Issue | 根因与最终改动 | 提交／候选产物 | 实际验证 | 未执行／阻塞条件 | 发布需求 | 关闭建议 |
|---|---|---|---|---|---|---|
| #384 | 待填写 | 待填写 | 待填写 | 待填写 | 客户端 | 待验收后判断 |
| #387 | 待填写 | 待填写 | 待填写 | 待填写 | daemon | 待验收后判断 |
| #385 | 待填写 | 待填写 | 待填写 | 待填写 | Linux 桌面包 | 待验收后判断 |
| #386 | 待填写 | 待填写 | 待填写 | 待填写 | daemon | 待验收后判断 |
| #388 | A/B/C 分列 | 待填写 | 待填写 | 待填写 | 依最终修改 | 待验收后判断 |
| #389 | 登记与其他断点分列 | 待填写 | 待填写 | 待填写 | 兼容 relay、客户端；daemon 依证据 | 待分层验收后判断 |
| #390 | 待填写 | 待填写 | 待填写 | 待填写 | 客户端 | 待验收后判断 |
| #375 | 待填写 | 待填写 | 待填写 | 待填写 | 预期仅测试，无产品发布 | 依据竞态与 CI 证据判断 |
