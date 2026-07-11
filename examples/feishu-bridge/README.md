# feishu-bridge — a cc-pocket 外部触发适配器（issue #91 参考实现）

把「飞书群里 @机器人 → 驱动本机 Claude Code → 结果回贴」做成一个**受限 bridge 客户端**：常驻守护、会话可追问、**危险动作照样弹主人手机审批**，取代裸奔的 `claude -p --dangerously-skip-permissions` bash 机器人。

> 定位：**参考 / PoC**，不是生产级。目标是把 bridge 凭证的签发与 wire 接口用法示范清楚。Slack / Telegram / 钉钉 适配器同构，只换事件源与回贴 API，`pocket_client.py` 可直接复用。

## 它能做什么 / 不能做什么（安全边界由 daemon 强制，不靠适配器自觉）

- 能：在**签发时白名单的 workdir 下** `session.open`、发 `prompt`、收自己会话的流式回复与 `turn.done`。
- 不能：发 `verdict`（审批）、切模式、跑 shell、读任意文件、枚举别人的会话。
- 权限提示（危险命令 / 写文件 / rm……）**只路由到主人的手机 / 桌面**；bridge 收不到、也应答不了。无人审批则**超时安全拒绝**（约 120s），本轮以“操作被拒绝”收尾，不挂起。

## 一次性准备

### 1. 签发 bridge 凭证（在装了 daemon 的本机终端跑）

```bash
cc-pocket-daemon pair --headless \
  --name feishu-bot \
  --workdir /Users/you/bots/mr-review     # 可重复；ONLY 这些目录能开会话
# 可选：--max-sessions 2 --open-per-min 6 --prompt-per-min 20
```

它打印一段 JSON 凭证（单次有效、~2 分钟过期）。把它存成本目录的 `bridge-credential.json`：

```bash
cc-pocket-daemon pair --headless --name feishu-bot --workdir /abs/dir > /tmp/cred.json
# 手动把终端里那段 JSON 存成 examples/feishu-bridge/bridge-credential.json
```

> 提示：`--workdir` 指向一个**专用、低敏感度的 checkout**。群里任何人的一句话都会被当作 prompt，agent 在默认模式下能读到这些根目录下的内容（见文末“残余风险”）。
>
> `bridge-credential.json` 含一次性 ticket（2 分钟过期）。存盘后设 `chmod 600 bridge-credential.json`；适配器首启 redeem 后写的 `.pocket-device.json`（含设备私钥 + bearer 凭证）已自动 `0600` 原子创建。两者都已在 `.gitignore` 里。

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

首启会 redeem 掉 ticket（deviceId + 凭证缓存到 `.pocket-device.json`，0600）。若本轮触发了危险动作，你的手机会弹审批；批了才继续。

## 接飞书

在[飞书开放平台](https://open.feishu.cn)建应用，开启：

- 事件订阅：`im.message.receive_v1`（**长连接**模式，无需公网回调）
- 权限：`im:message`、`im:message:send_as_bot`
- 把机器人拉进目标群

然后：

```bash
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
export POCKET_CREDENTIAL=bridge-credential.json
export POCKET_WORKDIR=/Users/you/bots/mr-review   # 该 bridge 允许的某个 workdir
python feishu_bridge.py
```

群里 @机器人 说话即可；同一话题（thread）里的追问会**续用同一个 cc-pocket 会话**（`key = chat_id:root_id` → 复用 convo，掉线后凭 sessionId `resume`）。

## 文件

| 文件 | 作用 |
|---|---|
| `pocket_client.py` | 可复用的 cc-pocket bridge 客户端：redeem + E2E（P-256 ECDH / HKDF-SHA256 / AES-256-GCM，忠实移植 `protocol/e2e/`）+ open/prompt/turn.done。**接口示范的核心。** |
| `feishu_bridge.py` | 飞书事件环：长连接订阅 → 过滤 @ → `PocketBridge.ask()` → 回贴。 |
| `requirements.txt` | 依赖；`lark-oapi` 只有飞书侧需要，`--selftest` 不需要。 |

## 掉线语义（v1）

bridge 掉线时**不排队** `turn.done`——会话在 daemon 侧继续/按 idle 回收，回来后凭 `key→sessionId` `resume` 追问即可（at-most-once）。超 TTL 未回执的 pending，适配器回贴“超时请重试”。

## 残余风险（须知悉）

最终回复会被贴回群：allow-list workdir 内的内容**可被注入式 prompt 摘抄给群**。缓解＝专用低敏 workdir + 在该目录 `.claude/settings` 里加 deny 规则 + 不把敏感库设成 workdir。这是把「IM 陌生人指挥 claude」从裸奔改成“有人在环”后仍存在的、须显式接受的一条。
