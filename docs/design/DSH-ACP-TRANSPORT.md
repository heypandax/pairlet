# dsh 后端换轨：web profile 本地 API → 官方 ACP profile

> 状态：**已实现**（daemon 侧）。全部结论来自 2026-09-10 的实证：dsh 包源码逐包比对 ＋ `scripts/probe-dsh-acp.py` 对 `0.1.2-rc.1` 的实跑，不凭文档推断。

## 1. 故障与根因

**症状**：手机／桌面端给 DeepSeek 发消息，浏览器自动弹出 dsh web 页面，会话里报
`could not reach the dsh local API on 127.0.0.1:<port> after 20 attempts`，`turn failed`。

**根因**：dsh `0.1.2-rc.1` 把 web profile 的本地 API 整个换掉了，daemon 说的还是旧协议。断代点非常干净：

| | ≤ 0.1.1-rc.2（旧 daemon 对着写的） | ≥ 0.1.2-rc.1（用户机器上的） |
|---|---|---|
| 传输实现 | `@deepseek-ai/dsh-host-apiproxy` | `@deepseek-ai/dsh-api-gateway`（Typert Remote） |
| 事件通道 | WS `/api/events.mux`，downlink-only | WS `/api/remote.mux`，双向多路复用 |
| RPC 形状 | `POST /api/session.prompt`（点号） | `POST /api/<namespace>/<method>`（斜杠） |
| 鉴权 | 只有 loopback Host/Origin 栅栏 | 栅栏 **＋ 签名 Cookie**：`requestRejection()` 无 cookie 一律 401，cookie 只能由 `GET /?token=<launchToken>` 换发 |
| 启动横幅 | `dsh web: http://127.0.0.1:PORT` | `dsh web: http://127.0.0.1:PORT/?token=xxx`，并默认拉起浏览器 |

- `dsh-host-apiproxy` 这个 npm 包**停在 0.1.1-rc.2**，之后不再发布——这是断代的硬证据。
- 关键是那道 cookie：`HostConnectionService.requestRejection()` 先过 Host/Origin 栅栏，再校验签名 cookie，两者缺一即 403/401；WS 升级走同一道闸。浏览器能用，是因为它从带 token 的 URL 换到了 cookie；daemon 不知道有 token 这回事，所以连不上。
- 浏览器自动弹出不是新行为（0.1.1 起 `openBrowser` 默认 true），但它正好和这次故障同时被用户看到。

## 2. 裁决：改走官方 ACP profile，不追新 gateway

同一个版本里 dsh 新增了 `dsh --profile acp` —— 标准 Agent Client Protocol v1 stdio server。当初选 web profile 的唯一理由（「rc.6 没有 ACP，SDK 模式没有 cancel／resume／审批回调」）已经作废。

- **跟标准协议走**，不再贴着 dsh 内部实现（Typert Remote 的帧协议是它的私有面，下次还会变）。
- **daemon 早就会说 ACP**（kimi 后端），`DshBackend` 因此和其它后端同构：stdout 一条 JSON-RPC 流，`parse(line)` 就是全部事件路径，不再需要「HTTP 客户端 ＋ 帧回注入」这套特例。
- 代价是 ACP 面确实更窄，见 §5。

## 3. 实证到的 wire 事实（每条都写进了代码注释与测试）

探针：`scripts/probe-dsh-acp.py`（**升级 dsh 后必跑**）。

1. `initialize` → `{protocolVersion:1, agentCapabilities:{sessionCapabilities:{close,list,resume}, promptCapabilities:{image:false}}}`。**图片不支持**，所以 prompt 仍是纯文本。
2. `session/new {cwd, mcpServers}` → `{sessionId, configOptions}`；`session/resume {sessionId, cwd, mcpServers}` → `{configOptions}`（不回 sessionId，用我们发的那个），且**不重放任何历史 update**。
3. **一个会话同时只允许一条 prompt**：中途再发被 `-32602 "a prompt is already in flight for this session"` 拒绝 → FIFO 队列必须放在 daemon 侧。
4. **没有 `user_message_chunk`**：prompt 的响应本身就是消费回执 → `UserReplay` 在 turn settle 时合成（否则 #122 的未消费账本永不结算，每次重启都会重跑旧 prompt）。
5. `session/set_config_option` 的参数名是 **`configId`**（写成 `optionId` 是 zod 报错），model 的 value 是不透明的 `["<provider>","<model>"]` JSON 串（裸 id 被 `unknown model option` 拒），返回**完整的 configOptions 新状态**。
6. `session/request_permission` 只带 `toolCall.toolCallId`，**没有名字也没有入参** → 审批卡片的正文只能从本轮更早的 `tool_call` update 里补（`rawInput` 在那里，且是完整对象，不像 kimi 要累积）。选项只有 `allow-once` / `reject-once`，**没有 always** → `remember` 无处可映射。
7. `usage_update {used, size}` 是**活跃通道上唯一**的上下文占用与窗口来源（#320 的头部读数靠它）。
8. `session/cancel` 是通知（无 id），在飞的 prompt 随后以 `stopReason:"cancelled"` 结算。
9. **老会话可跨 profile 恢复**：web profile 建的 `session-<uuid>` 会话，ACP `session/resume` 能正常加载。
10. **磁盘格式没变**：`~/.dsh/sessions/--<key>--/<id>/session.jsonl.zstd`，header ＋ seq 记录一如既往 → `DshTranscriptScanner` / `DshTranscriptReplay` / `DshUsageScanner` / resume meta 全部原样可用。

## 4. 实现落点

| 文件 | 变化 |
|---|---|
| `DshLauncher.kt` | argv → `dsh --profile acp`；**stdin 不再重定向到 /dev/null**（ACP 的上行就是 stdin，指向 /dev/null 等于开机即断连）；`launchHint()` 把「dsh 太老／Node 太老」翻译成人话 |
| `DshBackend.kt` | 换轨重写：initialize → session/new｜resume → `session/update` 翻译 → prompt FIFO ＋ 配置链 ＋ 审批桥 |
| `DshConfigOptions.kt` | 新增：`configOptions` 翻译 ＋ **裸 model id ↔ dsh 不透明 value 的对接**（两侧同一份目录，永不字符串拼装） |
| `DshCatalog.kt` | 新增：活跃会话把自己的目录发布出来，取代 `DshHosts` |
| `DshModelService.kt` | 目录来源改为「活跃会话的 configOptions」，无会话时**临时起一个 acp 进程 ＋ 临时目录建一个 scratch 会话**再杀掉（ACP 面没有 host 级目录方法，只能这样问；临时目录保证这条 scratch 会话不会污染用户项目的会话列表） |
| `DshAsk.kt` | 裁到只剩**磁盘回放**那一半；live 那一半（`/api/respond` 的应答构造）随通道一起删除 |
| 删除 | `DshApiClient.kt`、`DshHosts.kt`、`DshAskLedger.kt`、`scripts/probe-dsh-api.py` 及其测试 |
| 测试 | 新增 `DshBackendAcpTest`（15 例）、`DshConfigOptionsTest`（6 例）；`DshModelServiceTest` / `RequestRouterFetchModelsDshTest` 改写到新接缝 |
| 实弹测试 | 新增 `DshBackendLiveIT`——**对真 dsh 二进制**跑完整链路（argv／stdin 接线／握手／建会话／切模型／真回合）。默认关闭，`CC_POCKET_DSH_LIVE=1 ./gradlew :daemon:test --tests '*DshBackendLiveIT'` 手动跑，dsh 升级时和探针一起跑 |

## 5. 明确的能力损失（ACP 面没有的东西）

dsh 自己写明：「session deletion、fork、`session/load`、modes、commands、plans、terminals、client filesystem operations、elicitation 均不在这个自动化面内」。因此：

- **agent preset（标准／极简／PTC／创造）没有了**——`session/new` 只暴露 model 与 reasoning_effort 两个轴。`ModelsList.agentPresets` 对 dsh 返回空，手机端因此不再显示预设行；`AgentSpec.agentPreset` 被忽略（记一条日志，不假装生效）。
- **重命名会话不再支持**（`renameSession` 返回 false）——dsh 自己按首轮 prompt 生成标题。
- **`/permission` 之类 slash 命令没有了**：权限档仍由 `DSH_PERMISSION_MODE` 在**进程启动时**播种（`dsh-base` 的 `sandbox-policy` 与 `approval/policy` 两行都读它），换档仍走重启。
- **会话标题从 LLM 生成降级为「首条 prompt 截断」**：acp profile 明确关掉了 `session-title-llm`（「ACP 没有标题面」），只留确定性 fallback（实测记录 `session/title.source.kind = "fallback"`）。会话列表仍有标题，只是不再是模型概括的那句。想找回得给 profile 叠一个 `--patch` 覆盖层重新启用那一行——本次没做，留作后续可选项。
- **提问工具（`ask_user_question`）不在 acp profile 的工具集里**，所以新会话不会再产生提问卡；历史会话里的提问回放照旧（这正是 `DshAsk` 保留磁盘半边的原因）。

## 6. 用户侧影响与过渡

- **修复随 daemon 发版生效**：手机／桌面 App 不需要同版；但用户机器上的 daemon 必须升到含本次改动的版本。
- **未升级 daemon 的过渡办法**：把 dsh 钉回最后一个旧版 `npm i -g @deepseek-ai/dsh@0.1.1-rc.2`。
- **升级 daemon 后，dsh 必须 ≥ 0.1.2-rc.1**：旧版没有 acp profile，`--profile acp` 会组出一个没有 app 的 profile，谁也不占 stdio，握手永不回应。为此 backend 有一道 30s 握手看门狗，超时就直说「需要 dsh 0.1.2-rc.1 或更新」。

## 7. 只用 npx 装 dsh 的情况（issue #365）

官方文档给的是 `npx @deepseek-ai/dsh`。这条命令**不会在磁盘上留下任何 `dsh` 可执行文件**——它每次临时解包到 npm 缓存里跑。于是 daemon 的解析链（显式路径 → `$CC_POCKET_DSH_BIN` → PATH ＋ 各种 npm 全局目录）根本没有东西可找，直接报 `dsh executable not found`。这不是漏搜目录，是文件真的不存在。

两条修法，任选其一：

**（A）补一个 launcher 文件**——把 npx 这条命令固化成一个真实可执行文件：

```sh
which npx                       # 先拿到绝对路径，下一步必须用它
mkdir -p ~/.local/bin
cat > ~/.local/bin/dsh <<'EOF'
#!/bin/sh
exec /absolute/path/to/npx --yes @deepseek-ai/dsh@latest "$@"
EOF
chmod +x ~/.local/bin/dsh
```

`npx` 必须写 `which npx` 得到的**绝对路径**：daemon 以后台服务身份运行，拿到的是被清洗过的 PATH，看不到你 shell 里的 Node 环境，写 `exec npx` 会在服务上下文里找不到 npx。`~/.local/bin` 本身已经在 `DshLauncher.fallbackDirs` 里，放好即可被找到。

**（B）固定一个路径到 prefs**——不想在 `~/.local/bin` 放文件，或者 dsh 在别的地方：

```sh
cc-pocket-daemon config --dsh-bin /absolute/path/to/dsh
cc-pocket-daemon config --clear-dsh-bin    # 取消固定，回到自动探测
```

写进 `~/.cc-pocket/prefs.json`，**跨重启与 daemon 自更新都在**——这正是它相对 `run --dsh-bin` 的价值：后者只对手工前台启动的 daemon 有效，服务托管的 daemon 由 launchd/systemd 按 `service-install` 烤进去的 argv 拉起，不会带上你临时敲的 flag。优先级是 `run --dsh-bin` 旗标 ＞ prefs ＞ `$CC_POCKET_DSH_BIN`／PATH 搜索。改完要重启 daemon 才生效。

顺带在同一批修了另一个相邻盲点：解析器原本只认 nvm 的 `~/.nvm/versions/node/vX.Y.Z/bin`（#287），现在也认 fnm 的 `<state>/aliases/default/bin`（`$FNM_DIR`、macOS 的 `~/Library/Application Support/fnm`、Linux 的 `~/.local/share/fnm`、旧版的 `~/.fnm`）。这条对**所有** agent 后端生效，不只 dsh。

