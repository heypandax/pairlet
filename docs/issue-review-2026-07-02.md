# Issue 评审与遗留待办（2026-07-02）

对 7-01/7-02 两天落在 main 的 18 个未发版提交（v1.1.9..ac5d454）做了逐 issue 代码评审（6 组并行审查，含 ktor 3.1.3 实证探针）。本文记录评审结论与全部遗留缺口，作为下一步修复的工作清单。

## 一、issue 处置结果

| Issue | 处置 | 评审结论 |
|---|---|---|
| #18 接管冒新会话 | 保持关闭 | 核心已修（条件式 fork）；残留一条 fork 路径 → P1-1 |
| #19 Windows 连接 | 保持关闭 | 主诉求已修；Codex 侧同类缺口 → P2-18 |
| #20 上下文百分比 | 保持关闭 | 核心已修；1M 清单偏差 → P3-19 |
| #21 没输入却触发 | 保持关闭 | ⚠️ 字面复现路径仍在（= P1-1），修完自然消除；如有人重开按此修 |
| #22 目录点不进去 | 保持关闭 | 两半均确认已修 |
| #24 resume 400 | 保持关闭 | 已修（含边界用例）；LAN 侧残留 → P2-9/10/11 |
| #26 token 看板 | **本次关闭** | 四项指标齐全；Codex 用量未纳入 → P3-23 |
| #27 模型没同步 | 保持关闭 | 模型回填已修；侧链/泄漏缺口 → P1-3/4 |
| #28 后台回来闪重连 | 保持关闭 | 已修，状态机严密；小窗口 → P3-25 |
| #29 草稿串会话 | 保持关闭 | 已修；持久性回退 → P3-24 |
| #30 切换模型 | **本次关闭** | 端到端可用；接续未发言即切的边缘 → P1-2 |
| #31 只显示一个 agent | **本次关闭** | 完成（就在设置里、持久化） |
| #32 进不了子文件夹 | 保持关闭 | 两个改动均确认存在 |
| #33 误标接管 | 保持关闭 | 已修（mtime 保留 + 单测实跑通过）；≤20s 残留 → P3-28 |
| #23 安装引导页 | **保持打开（已收窄）** | 引导页完成；剩「一条命令」+ 文档 bug + 三面不一致，已评论至 issue |
| #25 Windows 安装 | **保持打开** | relay_offline 两个根因已定位（= P1-5..8 + pair 诊断），清单已评论至 issue |

## 二、遗留待办（按优先级）

### P1 会话完整性 —— #21 的字面复现路径仍在 main 上

1. **relaunch 旧 fork 启发式**：`daemon/.../conversation/Conversation.kt:207` 仍是 `forkSession = resumeId != sessionId`（always-fork 时代遗留）。接续会话后、第一条消息前切权限模式（「选择全部权限」正是此流程）→ fork 出重复会话。修法：`open()` 时把 fork 决策存成字段（如 `openedWithFork`），relaunch 在 `sessionId == null` 时沿用；或重算 `transcriptRecentlyWritten`。
2. **switchModel / switchEffort 缺回退**：`Conversation.kt:235-243` 未像 `switchMode`（:228）那样回退 `sessionId ?: openedResumeId`。接续后未发言即 `/model` 或 `/effort` → 空白重启、孤立已接续历史（比 fork 更糟）。#30 关闭评论中已向用户声明此边缘。
3. **lastModel 不过滤侧链/合成记录**：`daemon/.../disk/TranscriptScanner.kt:114-127` 接受任意 `type=="assistant"`。尾部是 Task 子 agent turn 时回填子 agent 的模型；`model=="<synthetic>"` 会字面进标题。同一过滤也应加到 `lastContextTokens`（:88-109，会把子 agent 的占用当主线程种子）。
4. **回填模型泄漏进启动参数**：回填写入的 `model` 字段会被 `relaunch()`（:204-209）与 `/clear`（:411）烘进 `AgentSpec(--model …)`，把历史模型钉到新进程/新会话上。修法：拆 `requestedModel`（驱动参数）与 `displayModel`（仅展示）。

### P1 daemon 侧 relay 死链 —— #25 relay_offline 根因（实证）

5. **写超时**：`daemon/.../relay/RelayClient.kt:145-146` 两个 writer 都是裸 `outgoing.send`。移植手机端 `mobile/.../net/LinkHealth.kt` 的 `sendOrDie`（10s）。
6. **硬断代替优雅关**：`RelayClient.kt:156` 心跳判死后走 `close(CloseReason)`——实证（ktor 3.1.3 探针）：链路写死且**有数据在写**时 `close()` 挂住 25s+ 不返回，daemon 僵尸直到 TCP 超时（约 15 分钟）。改为从心跳协程抛异常/cancel session scope 硬断（探针证明可行）。
7. **sawPong 盲区**：`RelayClient.kt:154` 判死门槛要求见过 Pong，且每次重连清零（:149）——attach 后首个 Pong 前（约 20s/次）永不判死。放宽为「attach 后 45s 无 Pong 也判死」。
8. 可选：每次 `connectOnce` 重建 `HttpClient`（:61，TUN/fake-IP 环境翻转的逃生门）。

> 注：记忆中「networkChangeLoop + 重建 client（编译过未发版）」与代码不符——仓库全分支 grep 无此实现；手机端实际落地的是 sendOrDie 方案（515ced9），daemon 端什么都没有。记忆已修正。

### P2 LAN 侧稳定（#24 评审新发现）

9. **scheduleClose 无归属校验**：`daemon/.../session/SessionRegistry.kt:159` + `WsConnection.kt:75`。僵尸旧 socket 迟到的 `finally` 会对**已被新连接接管**的 convoId 排 30s 关闭 → 活会话被杀。加 sink identity / generation 校验。
10. **LAN 服务端无僵尸检测**：`DaemonServer.kt:24` 裸 `install(WebSockets)` 无 pingPeriod/timeout；`WsConnection.kt:45` 写无超时。僵尸手机 socket 会让 outbox（64）塞满、pump 卡死。
11. **reaper 看不见 LAN attach**：`RelayClient.kt:117-125` 只 gate relay `peerOnline`，LAN 挂着但 agent 空闲 >90s 的会话会被回收——「LAN 会话莫名丢失」的一个来源。

### P2 Windows / 发版链路（#25、#23 评论中已对外承诺）

12. **发版 + scoop bump**：v1.1.9 后 18 个提交未发布；scoop-bucket 无自动 bump（checkver/autoupdate 无 excavator，是死配置）。发版后同步 `packaging/scoop/cc-pocket-daemon.json` → bucket 仓库，并把 bump 接进 release.yml（参考 cask 回填 70b46ef）。
13. **pair 可操作诊断 + 自动重试**：`Main.kt:167-191` 解码 503 给人话 + 轮询 30-60s 骑过重连退避（退避最长 30s vs 取票窗口 10s，`RelayClient.kt:104-106`/:130）；connection-refused 时 Windows 提示/代跑 `schtasks /Run /TN cc-pocket-daemon`；`PairLoopback.kt` 错误体带链路状态。
14. **Windows 后台 daemon 无日志**：`ServiceInstaller.kt:204-206` vbs `Run …, 0, False` 无重定向且 slf4j-simple 只到 stderr。重定向到 `%USERPROFILE%\.cc-pocket\logs\`。
15. **status / doctor 子命令**：`Main.kt:227-228` 现只有 run/test-client/pair/service-install。
16. **install.ps1 一键化** + 引导页 Pair 步骤展示具体命令（`OnboardingScreen.kt:87/91/96`）；scoop 计划任务指向 shims 路径更稳（`Main.kt:216-224`）。
17. **`cc-pocket pair` 文档 bug**：`README.md:84` 与 `PairingScreen.kt:109` 写的是不存在的 `cc-pocket` 二进制 → 改 `cc-pocket-daemon pair`；顺带对齐 mac 引导页步骤数（cask postflight 已自动 service-install，可去掉第 2 步）。
18. **Codex cwd 精确比较**：`daemon/.../codex/CodexTranscriptScanner.kt:38` 仍是 `cwd != workdir` 字面比较——Windows 上大小写/斜杠差异让 Codex 会话从列表静默消失（#19 同类根因）。把 `ProjectPaths.normCwd` 提为共享工具复用。

### P3 上下文 / 显示

19. **ContextWindow 清单修正**（`protocol/.../ContextWindow.kt:17-21`）：补 `"sonnet-4-20"`（命中 claude-sonnet-4-20250514 而不碰 4-5/4-6）与 `"mythos-5"`；加别名 exact-map（`opus/sonnet→1M、haiku→200k`，裸 substring 不安全）；`sonnet-4-5` 属 beta 门控（未开 beta 的用户分母被放大 5 倍，反向 #20）——可在 `Conversation.live()` 用观测用量自愈升级：`used > 200k ⇒ 1M`。
20. **Codex 上下文分母**：daemon 对 Codex 发 null，手机却拿 Claude 的 200k 兜底给 `gpt-*` 画百分比（`Conversation.kt:129` + `PocketRepository.kt:890`）。daemon 加 Codex 窗口表，或手机端 Codex 且窗口 null 时隐藏分母。
21. **ObserveSession 不带模型/窗口/占用**（`ObserveSession.kt:35`）：直接复用 `lastModel` + `lastContextTokens` + `contextWindowFor`。
22. **switchModel 后不重播 live()**（`Conversation.kt:235-238`）：手机端模型/窗口滞后到下次 init，靠 4s 超时兜底——修完 19 的别名表后补发。
23. **Usage 看板纳入 Codex**：`UsageService.kt` 只扫 `~/.claude/projects`；按模型分布里的 Codex 色是死路径。另：`costUSD` 缺失时可考虑本地价格表兜底。
24. **contextWindowFor 单测**（protocol/commonTest 现只有序列化往返）。

### P3 移动端小项

25. **草稿持久性回退**：按 convoId 键控后，离开聊天→重进/重启 App 基本拿不回草稿（convoId 是 daemon 每次 open 的随机 UUID）。改稳定键（Claude sessionId，回退 workdir）。
26. **前台回来 stale-Ready 窗口**：链路在后台死掉但心跳未判死时，`onAppForeground`（`PocketRepository.kt:662`）不重连，最长约 25s 假 Ready。前台且距上次流量 > PING_INTERVAL 时主动探测。
27. **握手段防死锁空窗**：`RelayE2EConnection.kt:57-76` DeviceHello/Noise 握手在心跳启动前、无本地超时——Attached 与握手完成之间的死链两个看门狗都逃过。`withTimeout` 包住或提早 arm pinger。
28. **≤20s 快速离开重进残留**（#33 尾巴）：刚回复完 20s 内离开再进仍会误入 observe/可 fork。daemon 记一份自己刚关闭的 (sessionId, closedAt) LRU 短路掉。
29. **Windows 树布局边缘**：裸 `C:` 根渲染为空（`DirList.kt:119-143` sep 推断/双写）；多盘符项目被 home 推断过滤；root ≠ home 时面包屑中段跳转错（`App.kt:456-460`）。补 DirList 单测（现无）。
30. **杂项**：`RelayClient.kt:209` 陈旧注释（仍说 cold-fork）；`SessionRegistry.kt:53` pre-first-turn 重连 reattach 匹配不到（sessionId 为 null）会再起一个 Conversation；mode/effort 历史恢复（`sessionParams` 仅内存，可持久化）；`TranscriptPatcher.relinkParent` 不修 summary 的 leafUuid（悬空，非 400）。

## 三、发版备忘

- 全部修复**尚未发版**：v1.1.9（06-25）后 18+ 提交在 main。发版即完成 P2-12 的前半，并兑现各 issue 评论里的「随下个版本发布」。
- 发版 runbook 见记忆 `cc-pocket-daemon-hosts-and-release`（4 处版本号 lockstep、cask sha 回填、relay 单独 redeploy、iOS 加密声明）。
- 本机验证 daemon 改动：`bash scripts/update-local-daemon.sh`（工作区代码 ≠ 设备行为）。
