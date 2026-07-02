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

### ~~P1 会话完整性~~ ✅ 已修（e630a86，2026-07-02）

1. ~~relaunch 旧 fork 启发式~~ → `open()` 存 `openedWithFork`，relaunch 在 `sessionId == null` 时沿用；#21 字面复现路径消除。
2. ~~switchModel / switchEffort 缺回退~~ → 均改为 `relaunch(sessionId ?: openedResumeId)`，接续后未发言切模型/effort 不再孤立历史。
3. ~~lastModel 不过滤侧链/合成记录~~ → `lastModel` 跳过 `isSidechain` 与 `<synthetic>`；`lastContextTokens` 跳过 `isSidechain`。附两个单测。
4. ~~回填模型泄漏进启动参数~~ → 新增 `backfilledModel` 仅供展示（`displayModel()`），`AgentSpec` 只用显式 `model`；init/显式切换会清掉回填值。

### ~~P1 daemon 侧 relay 死链~~ ✅ 已修（48170a4，2026-07-02）

5. ~~写超时~~ → `sendOrDie`（10s）包住 dataWriter/ctrlWriter 的所有稳态写。
6. ~~硬断代替优雅关~~ → 心跳判死改为抛 `DeadLinkException` 硬撤整个 session scope（不再往死链写 close 帧）。
7. ~~sawPong 盲区~~ → 判死基线改为 attach 时刻，无论是否见过 Pong 一律 45s 强制；`sawPong` 仅保留日志用途。
8. ~~重建 HttpClient~~ → 每次 `connectOnce` 走 `newClient().use{}`，selector 不再跨网络切换复用。

验证：单测 7/7 过；`update-local-daemon.sh` 后本机单实例连上 relay，`pair` 探针返回真实 ticket（非 relay_offline）。真机换网自愈需发版后观察。

> 注：记忆中「networkChangeLoop + 重建 client（编译过未发版）」与代码不符——仓库全分支 grep 无此实现；手机端实际落地的是 sendOrDie 方案（515ced9）。记忆已修正。

### ~~P2 LAN 侧稳定~~ ✅ 已修（5e40d34，2026-07-02）

9. ~~scheduleClose 无归属校验~~ → `scheduleClose(convoId, owner)` 到期时校验会话仍归属该 sink 才关（顺带消灭 reattach-vs-到期微竞态）。
10. ~~LAN 服务端无僵尸检测~~ → `DaemonServer` 加 ping 15s/timeout 30s；`WsConnection` 写套 10s 超时，卡死即撤链。
11. ~~reaper 看不见 LAN attach~~ → registry 记 LAN 连接计数，reaper 在 `peerOnline || lanConnected` 时不回收。

### ~~P2 Windows / 发版链路~~ ✅ 代码全落（26c205f + 7e87448；仅剩发版动作本身）

12. **scoop 自动 bump 已接入 release.yml**（bump-scoop job，需在仓库 Secrets 配 `SCOOP_BUCKET_TOKEN`——对 heypandax/scoop-bucket 有 contents:write 的 fine-grained PAT；缺失则告警跳过）。⚠️ **发版动作未执行**：版本号已 lockstep 预升 1.2.0，等真机验证通过后按 runbook 建 release + 跑 workflow。
13. ~~pair 诊断重试~~ → 自动等待 60s 骑过退避、失败输出链路状态 + 可能原因 + 每 OS 启动命令；`/pair` 503 带 attached/lastPongAgeMs。
14. ~~Windows 无日志~~ → 计划任务经 `cmd /c … >> %USERPROFILE%\.cc-pocket\logs\daemon.log 2>&1`。
15. ~~status 子命令~~ → daemon/relay/服务注册/claude/codex 五项检查，异常退出码非零（本机实测 ✓）。
16. ~~install.ps1 一键化~~ → `irm … | iex` 下载最新版 + 注册启动服务 + 直接进 pair；引导页 Pair 步骤展示命令。scoop shims 路径项未做（影响极小：post_install 传的是 $dir 版本路径，scoop cleanup 后由 checkver 版本更新覆盖）。
17. ~~`cc-pocket pair` 文档 bug~~ → README（中英）/PairingScreen 均改 `cc-pocket-daemon pair`；mac 引导页收敛为 2 步；官网 Windows 面板同步 irm 一键。
18. ~~Codex cwd 精确比较~~ → 复用 `ProjectPaths.normCwd`（提为 internal），附单测。

### ~~P3 上下文 / 显示~~ ✅ 已修（72aaee2 + c74f4e3）

19. ~~ContextWindow 清单~~ → 原生 1M 表（+mythos-5、-sonnet-4-5）+ 别名 exact-map + **观测用量自愈升级**（used>200k ⇒ 1M，live/observe 两处）。注：beta 门控的 sonnet-4-20 同 sonnet-4-5 走自愈，不进静态表。
20. ~~Codex 分母~~ → 手机端 Codex 且窗口 null 时显示原始 token 数（statusline/ContextBar），不再画假百分比。daemon 侧 Codex 窗口表暂缓（app-server schema 会漂移；rollout 的 `model_context_window` 字段已确认存在，将来可接）。
21. ~~ObserveSession~~ → 每次文件变化重发 SessionLive（model/window/used 全带）。
22. ~~switchModel 重播 live()~~ → switchModel/switchEffort 均镜像 switchMode（reemitLive + 立即确认帧）。
23. ~~Usage 纳入 Codex~~ → 摄入 rollout `token_count` 增量（按模型、去重、mtime 跳旧文件）；costUSD 价格表兜底**有意不做**（价格漂移风险 > 收益，成本保持 Claude-only）。
24. ~~contextWindowFor 单测~~ → 6 个用例。

### ~~P3 移动端小项~~ ✅ 已修（3c73889）

25. ~~草稿持久性~~ → 键改 `sessionId ?: convoId ?: workdir`，sid 落地瞬间静默迁移草稿（打字中也不丢）。
26. ~~前台 stale-Ready~~ → 前台且自认连接时主动发一次静默目录刷新（练写路径），死链 ≤10s 被写超时揪出。
27. ~~握手空窗~~ → attach+Noise 前奏套 15s `withTimeout`，超时转 `DeadLinkException`（避免被当作主动断开吞掉）。
28. ~~≤20s 快速重进~~ → daemon 记自关 (sessionId, closedAt) LRU；mtime 不晚于自关时刻即视为无外部写手（observe/fork 双入口）。
29. ~~Windows 树布局边缘~~ → 裸 `C:`/`/` 根 sep 从条目推断且不重复拼接；根级 append 根外项目 leaf；面包屑改 `crumbTargets`（锚定 root 的真实跳转目标）；DirList 首批 6 个单测。
30. ~~杂项~~ → 陈旧注释已改（48170a4）；pre-init reattach 用 `resumeAnchor` 匹配（85fcb32）；`sessionParams` 持久化（TSV，末 100 条）；summary `leafUuid` 重链 + 单测。

## 三、剩余事项（P2/P3 清零后）

1. **发版 v1.2.0**（唯一剩余动作）：版本号已就位；按 runbook（记忆 `cc-pocket-daemon-hosts-and-release`）建 release → 跑 release.yml/ios-release.yml → cask sha 回填 → 配 `SCOOP_BUCKET_TOKEN` 后 scoop 自动 bump 生效。发版兑现全部 issue 评论承诺。
2. 真机验证清单：换网自愈（Mac 切 WiFi 后手机可用、pair 探针非 relay_offline）、接续后未发言切模式/模型不 fork 不丢史、LAN 挂 90s+ 不被回收、Usage 看板出现 gpt-* 条目、Codex 会话上下文显示原始 token。
3. 观察项：`selfClosed` 1.5s slack 在慢盘上是否够；install.ps1 在真实 Windows 上过一遍（Expand-Archive/计划任务权限）。
