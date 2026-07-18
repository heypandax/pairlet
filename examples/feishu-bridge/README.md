# feishu-bridge — a cc-pocket 外部触发适配器（issue #91 参考实现）

把「飞书群里 @机器人 → 驱动本机 Claude Code → 结果回贴」做成一个**受限 bridge 客户端**：常驻守护、会话可追问、**危险动作照样弹主人手机审批**，取代裸奔的 `claude -p --dangerously-skip-permissions` bash 机器人。

**多个群 → 多个项目**：每个群绑一个项目，绑定在群里完成（`/bind`），你不需要知道 chat_id、也不用编辑映射表。

> 定位：**参考 / PoC**，不是生产级。目标是把 bridge 凭证的签发与 wire 接口用法示范清楚。Slack / Telegram / 钉钉 适配器同构，只换事件源与回贴 API，`pocket_client.py` 与 `routes.py` 可直接复用。

> ⚠️ **飞书用户现在有内置版**：飞书的生产路径已改为 daemon **内置引擎**（无需 python / pip / 脚本路径，桌面端或手机端 Bridges 页直接新建）。这份 python 保留仅为 ① 讲清 wire 协议、② 给别的 IM 写新适配器起步。
>
> **照抄前先看已知缺口**（下列内置引擎都已处理，这份 PoC 没有）：`pocket_client.py` 的掉线 resume 是死代码（`_convos` 从不驱逐，会话被回收后该话题永久回 `session_gone`）、ws 断开后不重连（`_reader` 静默结束、进程聋着）、回贴不查 `resp.success()` 也不重试。真要上生产请补齐这三点，或直接照 `daemon/src/main/kotlin/dev/ccpocket/daemon/feishu/FeishuEngine.kt` 抄。

## 它能做什么 / 不能做什么（安全边界由 daemon 强制，不靠适配器自觉）

- 能：在**签发时白名单的 workdir 下** `session.open`、发 `prompt`、收自己会话的流式回复与 `turn.done`。
- 不能：发 `verdict`（审批）、切模式、跑 shell、读任意文件、枚举别人的会话。
- 权限提示（危险命令 / 写文件 / rm……）**只路由到主人的手机 / 桌面**，并且**一定是 urgent 推送**（bridge 在结构上收不到 ask 帧，推送是你唯一的知情通道）。无人审批则**超时安全拒绝**（约 120s），本轮以「操作被拒绝」收尾，不挂起。

## 诚实边界（用之前先知道）

- **回复明文经飞书云端**：E2E 加密只覆盖 bridge ↔ daemon 这一段；机器人发回群里的文本走飞书自己的服务器，和任何飞书消息一样。别让机器人把敏感内容读进群——`SecretRedactor` 只兜明显的密钥格式，不是保险箱。
- **主人看不到是谁触发的**：你手机上收到的审批卡只说「哪个 bridge、什么操作」，不带是群里哪个人发的指令。群成员即你的信任边界——谁在群里，谁就能驱动这台机器（受 workdir 白名单与逐次审批约束）。
- **群友替不了你作答 AskUserQuestion**：Claude 的选择题只弹到**主人手机**，群里的人答不了；主人不答则超时按「未回答」继续。需要群内交互决策的活儿别交给它。

## 一次性准备

### 1. 签发 bridge 凭证（在装了 daemon 的本机终端跑）

`--workdir` 可重复——**每个项目一条**，这就是这个机器人能碰的全部范围：

```bash
cc-pocket-daemon pair --headless \
  --name feishu-bot \
  --workdir /Users/you/proj/alpha \
  --workdir /Users/you/proj/beta \
  --max-sessions 4 \
  --out examples/feishu-bridge/bridge-credential.json
```

`--out` 直接把凭证写成 0600 文件（不 `--out` 就打印 JSON 让你自己存）。凭证里的 ticket **单次有效、约 2 分钟过期**，所以签发后就接着启动适配器；它首启 redeem 后把设备私钥缓存进 `.pocket-device.json`（0600 原子创建），之后不再需要 ticket。两个文件都已在 `.gitignore` 里。

> `--workdir` 指向**专用、低敏感度的 checkout**。群里任何人的一句话都会被当作 prompt，agent 在默认模式下能读到这些根目录下的内容（见文末「残余风险」）。
>
> 并发上限默认 `--max-sessions 2`，多个群同时说话会撞 `bridge_busy`。按群数量给到 4（上限 8）。

管理凭证：

```bash
cc-pocket-daemon bridges                     # 列出所有 bridge 凭证
cc-pocket-daemon bridges --revoke feishu-bot # 吊销（立刻断链 + 删钥）
```

### 2. 装依赖

```bash
cd examples/feishu-bridge
pip install -r requirements.txt
```

### 3. 只连 cc-pocket 自测（不需要飞书）

先验证凭证与 E2E 链路通：

```bash
POCKET_CREDENTIAL=bridge-credential.json \
python feishu_bridge.py --selftest "跑一下 git status 并总结"
```

若本轮触发了危险动作，你的手机会弹审批；批了才继续。

## 接飞书

在[飞书开放平台](https://open.feishu.cn)建应用，开启：

- 事件订阅：`im.message.receive_v1`（**长连接**模式，无需公网回调）
- 权限：`im:message`、`im:message:send_as_bot`
- 把机器人拉进目标群

然后：

```bash
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
python feishu_bridge.py
```

### 在群里绑定项目

机器人进群后，**@它** 发命令：

| 命令 | 作用 |
|---|---|
| `/projects` | 列出这张凭证可绑的项目 |
| `/bind <项目>` | 把本群绑到某个项目（仅管理员） |
| `/unbind` | 解绑本群 |
| `/help` | 用法 |

**第一次绑定**：直接 @机器人 发 `/bind alpha`。因为还没设管理员，它会拒绝，并把**你自己的 open_id** 回给你——把它设成 `FEISHU_ADMIN_OPEN_ID` 重启，再 `/bind` 即可。这是唯一一次绕路，之后所有群都能直接绑。

```bash
export FEISHU_ADMIN_OPEN_ID=ou_xxx   # 从上面那条回复里拿
```

绑好之后，群里 @机器人 说话就会在该项目下开会话干活。**未绑定的群一律不响应**——机器人被拉进无关群是无害的。同一话题（thread）里的追问会**续用同一个 cc-pocket 会话**（`key = chat_id:root_id`，掉线后凭 sessionId `resume`）。

项目名就是 workdir 的 basename（`/Users/you/proj/alpha` → `alpha`），群里没人需要看见绝对路径。

### 常驻（不用自己 nohup）

```bash
export FEISHU_APP_ID=cli_xxx FEISHU_APP_SECRET=xxx FEISHU_ADMIN_OPEN_ID=ou_xxx
./install-service.sh          # 装成 launchd agent：开机自启 + 崩溃自拉起
./install-service.sh --uninstall
```

日志：`tail -f ~/Library/Logs/cc-pocket/feishu-bridge.err.log`。macOS only；Linux 见脚本头部注释里的 systemd 等价写法。

> plist 里烘焙了 `FEISHU_APP_SECRET`，所以它是 0600。改了任何 env 都要重跑一次脚本。

## 文件

| 文件 | 作用 |
|---|---|
| `pocket_client.py` | 可复用的 cc-pocket bridge 客户端：redeem + E2E（P-256 ECDH / HKDF-SHA256 / AES-256-GCM，忠实移植 `protocol/e2e/`）+ open/prompt/turn.done。**接口示范的核心。** |
| `routes.py` | 群 → 项目 的映射表 + 持久化。换 IM 时原样复用。 |
| `feishu_bridge.py` | 飞书事件环：长连接订阅 → 过滤 @ → 命令 or `PocketBridge.ask()` → 回贴。 |
| `install-service.sh` | 装成 launchd agent 常驻。 |
| `requirements.txt` | 依赖；`lark-oapi` 只有飞书侧需要，`--selftest` 不需要。 |

## 环境变量

| 变量 | 必需 | 说明 |
|---|---|---|
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 是（飞书侧） | 飞书应用凭证 |
| `FEISHU_ADMIN_OPEN_ID` | 绑定时必需 | 只有这个人能 `/bind`；未设则绑定被拒并回显你的 open_id |
| `POCKET_CREDENTIAL` | 否 | 凭证路径，默认 `bridge-credential.json` |
| `POCKET_ROUTES` | 否 | 映射表路径，默认 `.pocket-routes.json` |
| `POCKET_WORKDIR` | 否 | 仅 `--selftest` 用；实际路由按群走 |

## 掉线语义（v1）

bridge 掉线时**不排队** `turn.done`——会话在 daemon 侧继续/按 idle 回收，回来后凭 `key→sessionId` `resume` 追问即可（at-most-once）。超 TTL 未回执的 pending，适配器回贴「超时请重试」。

## 残余风险（须知悉）

最终回复会被贴回群：allow-list workdir 内的内容**可被注入式 prompt 摘抄给群**。缓解＝专用低敏 workdir + 在该目录 `.claude/settings` 里加 deny 规则 + 不把敏感库设成 workdir。这是把「IM 陌生人指挥 claude」从裸奔改成「有人在环」后仍存在的、须显式接受的一条。

绑定是特权操作（只有 `FEISHU_ADMIN_OPEN_ID` 能做），但**群里任何人都能对已绑定的群说话**。所以别把机器人拉进你不信任的群，或者对该群只绑低敏项目。
