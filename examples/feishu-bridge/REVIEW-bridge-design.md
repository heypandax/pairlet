# feishu bridge 设计评审（Agent Team 综合报告）

> 评审对象：当前工作区未提交的「bridge 客户端 / feishu 机器人」WIP（daemon 侧 `bridge/`、`agent/`、`relay/`、`feishu/`；protocol 新帧；`examples/feishu-bridge/`）。
> 评审方式：四个视角并行——安全（crypto-security-reviewer）／daemon 架构／协议兼容（protocol-wire-compat-reviewer）／产品适配器。
> 评审日期：2026-07-17。**本文档供主会话审核处理，非最终结论。**

## 一句话结论

方向和安全骨架对了，可以继续。提交前至少清掉 **S1（密钥泄露）+ A1（revoke 不停会话）+ P1／P2／P3（resume 死代码／无重连／长轮次丢结果）**——前两个是安全底线，后三个会被第一个照着写 Slack 版的人逐字复制走。`daemon/feishu/` 的接口抽象和 caps facade 可作为紧跟其后的重构，不阻断但别拖太久。

四人对同一批事实高度收敛：`revoke 不停在途会话`被安全与架构两个视角独立命中；「安全靠 daemon 强制、不靠适配器自觉」四人都点赞。

## 做对了的（结构性保证成立，别动）

- **E2E 移植忠实**：`pocket_client.py` 对 `protocol/e2e/` 的 4-DH 顺序、transcript 字段序、nonce／AAD／counter 防重放、send／recv 密钥分离逐项对齐，可放心照抄。
- **双向白名单 fail-closed**：bridge 结构上收不到 `PermissionAsk`、发不了 `verdict`，未知 kind 回落到更严的 BridgeCaps。
- **workdir 匹配无绕过**：`canonicalFile` 解析 symlink 与 `..`，段感知（`/a/bees` 不在 `/a/be` 下）。
- **clean-room**：受限会话 `--setting-sources ""` 剥掉 owner `~/.claude` 里累积的 `permissions.allow`，daemon 成唯一审批权威——这个洞抓得准。
- **协议向后兼容**：新增 6+4 帧全走旧端 `runCatching` 的 unknown-t drop 路径（已核对 v1.3.5 实码），可与旧版 daemon／App／relay 混跑，不腐化状态。

## 必须修（P0 / High）

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| **S1** | **`echo $VAR` 绕过审批泄露环境变量密钥**。`SIDE_EFFECT` 正则只拦 `$(`／`${`，裸 `$VAR` 漏网，而 `echo`／`printf` 在 ALLOW 名单里直接零审批放行 | `BridgeCommandPolicy.kt:297-301,320` | 群里任何人社工一句「echo 打印各环境变量」→ `echo $AWS_SECRET_ACCESS_KEY` 自动放行、值回贴进群；外部适配器完全不脱敏。这是要靠 Bash 闸兜底的场景，不能依赖 Claude 判断。修法：`$` 纳入 SIDE_EFFECT |
| **A1／S2** | **revoke 不终止在途 bridge 会话**（安全+架构双命中）。强制关闭包在 `if (wasGuest)` 里，bridge 只剪钥不 `closeByOrigin` | `DeviceSessions.kt:184-211` | 主人发现异常吊销后，已在执行的那一轮 Claude 继续改文件到 idle reap。「吊销=立即停」不成立。修法：把 force-close 对 bridge 也跑 |
| **P1** | **「掉线 resume」是死代码**。`_convos` 从不驱逐，`resumeId` 分支不可达 | `pocket_client.py:230-232,261,273` | 会话被 idle 回收后该话题永久回 session_gone 直到重启，README:94／132 整段与实现不符 |
| **P2** | **无任何重连**。ws 断开后 `_reader` 静默结束，进程挂着但聋了 | `pocket_client.py:192-199` | launchd `KeepAlive` 救不了没死的进程，之后每条消息 300s 超时 |
| **P3** | **长轮次（>300s）结果静默丢失**。`ask` 用 await+timeout，超时后真正的 `turn.done` 无人接收被丢弃 | `pocket_client.py:251-253` | 恰好打死 issue #91 旗舰场景「MR 自动评审」（常超 5 分钟）。修法：改回调式，`turn.done` 到达即回贴 |
| **P4** | **mention 没验证 @ 的是本 bot**。只判 `any(m.key)` | `feishu_bridge.py:135-137` | 若 scope 是全群消息，@别人也驱动 Claude 烧 owner 用量 |

## 应该修（P1 / Medium）

- **架构·内置适配器分层泄漏（方向反了）**：`daemon/feishu/` 装了完整飞书适配器（lark SDK 长连接+群命令+回贴），且给每个 daemon 用户强加 `lark-oapi-sdk` 依赖（`gradle/libs.versions.toml:58`）。`bridge/BridgeRunners.kt:49` 通用 runner 直接持有 `FeishuEngine`，`relay/BridgeService.kt:122` 硬编码 `RUNNER_KIND_FEISHU`，`protocol/Messages.kt` 把 IM 名字写进 wire 常量——通用基础设施依赖了具体 IM。加外部 Slack 适配器不用动 daemon（wire 通用），但加内置的目前是 feishu 专属通道。修法：抽 `InProcessBridgeEngine` 接口（start／stop／shutdown／running／lastError／ownedConvoIds，FeishuEngine 已恰好是这个形状）+ 按 kind 注册 map。
- **架构·caps 强制被降级成约定**：`FeishuEngine` 直接 `core.router.handle()`（FeishuEngine.kt:256、277）绕过 DeviceSessions 双闸，egress 靠「只挑三种帧」的自律而非 `else -> false` 的结构保证——这套设计最自豪的性质被自己的新形态削弱。修法：给 in-process 引擎一个与 DeviceSessions 对称的薄 facade（send 过 `ingressAllowed`+guard.vet，sink 包 `egressAllowed` 过滤）。
- **架构·in-process bridge 权威双源 + revoke 语义分叉**：外部 bridge 授权真相在 `BridgeRegistry`（bridges.json），in-process 在 `RunnerEntry.bridgeSpec`（runners.json），`BridgeService.list()` 要三源合并、revoke 分两条路。这也是 A1 在架构层的根因。
- **产品·同话题两人并发串音**：两个 `ask` 落同一 convo，第二个 `c.text_parts.clear(); c.done.clear()`（`pocket_client.py:247`）摧毁第一个的等待状态；改 P3 回调式后大半消解。
- **产品·bridge_busy 是一条 30 秒后的空白报错**：daemon 发 `convoId=null` 的 error（`DeviceSessions.kt:331`）客户端无法归因，干等超时→群里看到「⚠️ 出错了：」。根治需 wire 层给 open 加客户端关联 id（值得单记 issue）。
- **产品·`_open` 的 workdir 关联有误绑窗口**：daemon relaunch／heal 时重发 SessionLive（`Conversation.kt:1061、1166`），同项目另一话题的 re-announce 落进 30s 等待窗口会被认成新 convo。一行修复：hook 里加 `body["convoId"] not in self._convos`。
- **架构·createdAt 每次落盘被重写**：`BridgeRegistry.kt:261` 用 `currentTimeMillis()` 而非 `createdAts[id] ?: now`，任意变更刷新所有凭证创建时间。

## 低优 / 收尾建议

- 协议：给 `DaemonInfo` 尾追 `bridgeControl: Boolean = false` 能力位，让管理页对旧 daemon 直接提示「需升级」而非等超时（唯一值得做的版本协商）。
- 协议：relay 线上版本须 ≥0cf88b6（headless 底座），否则铸票链路不通——本项目有「改了没部署」前科，上线 bridge UI 前核对。
- 协议：测试补两条——bridge 帧的 unknown-t drop 重述 + `BridgeInfo` 结构化未知键 skip 断言。
- 产品：事件去重没做（issue #91 原始痛点之一），飞书 at-least-once 重投=重复 prompt，加 message_id LRU。
- 产品：`/bind` 绕路可改用飞书 API 查群 owner（`im.v1.chat` 的 owner_id）免 env 免重启。
- 产品：重签凭证后要删 `.pocket-device.json`（`pocket_client.py:137-139` 见文件就短路，README 没写）。
- 产品：回贴零失败处理（`feishu_bridge.py:125-130` 不查 `resp.success()`、无重试），且同步 HTTP 跑在 asyncio loop 会卡 reader，应 `run_in_executor`。
- README 诚实度补三点：①回复明文经飞书云端（E2E 只覆盖 bridge↔daemon）；②owner 收到的 ask 看不到谁触发；③AskUserQuestion 弹 owner 手机而群友无法作答，超时按未回答继续。
- 凭证身份长期应改用 deviceId 而非 name 作 `origin` 键（revoke 后重建同名会误伤 `closeByOrigin`／计数）。
- 架构：三个 caps 概念关系清楚，不建议合并（显式列举比继承更安全）；`ScopedCaps` 名不副实（是 clamp 不是 capability 集），已改名 `TierClamp`。

## 各视角原始裁定摘要

- **安全**：1 High（S1 密钥泄露）+ 1 Medium（S2＝A1 revoke 不停会话）。E2E 移植、deviceId 不可伪造、双白名单、workdir 匹配、clean-room、超时拒绝 fail-safe 全部逐项验证成立。
- **daemon 架构**：安全内核好、层次清楚；架构债集中在「内置适配器」形态——分层泄漏 + caps 结构性强制被降级 + in-process 权威双源，共 3 条高优 + 若干中低。
- **协议兼容**：COMPATIBLE，可与旧版 daemon／App／relay 混跑；python 手写帧逐字段对上 protocol 定义；建议给 DaemonInfo 加能力位改善混版 UX。
- **产品适配器**：daemon 侧围栏扎实；适配器作为 wire 示范合格，但作为「会话语义参考实现」有 P1／P2／P3 三处 README 承诺与代码脱节 + 群聊边界真 bug。issue #91 核心诉求对齐、不过度设计，漏了事件去重与长轮次投递。
