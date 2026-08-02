# 智能审批（Smart Approval）——意图与实现边界

> 状态：方向认可，已完成方案复审修订，待按本文分期验证与实现。
>
> 本文现作为 `APPROVAL-SYSTEM.md` 的风险评估子设计；统一领域模型、Task Grant、审批状态机、
> 审批人路由与实施顺序以主设计为准。
>
> 核心定位：智能审批不是“AI 判断安全”，而是“行为风险雷达 + 审批权路由”。AI 只提供风险信号，
> daemon 中的硬策略、审批权校验和能力边界才是安全事实源。

## 一、要解决什么

现有审批是三档结构：硬 DENY（`BridgeCommandPolicy` DANGEROUS、Handoff 只读墙、pathScope 越界）
→ 证明安全的 AUTO-ALLOW（封闭白名单）→ 中间地带 ASK 人。盲区主要在 ASK 和跨动作上下文：

- **飞书 bridge**：owner 在 IM 异步语境里看一张上下文有限的卡，容易疲劳式点“允许”；单看无害、
  组合起来可能构成数据外发，例如先读取、再打包、最后上传。
- **跨用户协作接力**：v1 典型对象是经过首次扫码和安全指纹确认的同事、朋友或长期合作伙伴，主要
  风险不是把熟人默认视为恶意攻击者，而是误操作、账号/设备受损、Prompt Injection，以及协作者对
  owner 本机边界了解不足。
- **审批人与执行人重合**：Handoff 中 recipient 持有 Controller Lease，当前也能回答自己 Session 的
  PermissionAsk。对于需要真正升级的高风险动作，仅在 recipient 侧显示警告没有约束力。

如果产品未来允许陌生人协作，应新增真实 shell 沙箱、隔离执行环境或更强的零信任能力；智能审批
不能替代这些设施，也不能被描述成能够阻止一个有意对抗系统的恶意协作者。

## 二、安全能力分层

智能审批必须放在既有安全模型中理解，不能取代其他层：

| 层 | 负责什么 | 是否安全边界 |
|---|---|---|
| 硬策略 | pathScope、结构化写工具拒绝、确定性危险命令 DENY | 是 |
| 行为序列风控 | 识别读取敏感内容 → 打包 → 网络外发等动作链 | 仅规则命中部分是 |
| 智能风险雷达 | 对规则无法判断的语义风险给出等级和理由 | 否 |
| 审批权路由 | 决定 recipient 还是 owner 可以批准当前动作 | 是，必须由 daemon 校验 |
| Handoff History | 威慑、发现、追责和事后复盘 | 否，不是技术阻断 |

因此本方向与 Handoff History 是互补关系：History 解决事后可见，Smart Approval 解决事前注意力和
审批权分配，硬策略负责真正阻止已知红线。

## 三、定位铁律（防漂移边界）

1. **只能升级，不能降级**。评估结果只能增加风险提示、撤销某次 AUTO_TRUSTED 资格或换成更强的
   审批路径。LLM 判“安全”不产生新的 auto-allow，也不能下调静态 DENY。
2. **评估器是被攻击面**。恶意 input 可以包含写给分类器看的 Prompt Injection。评估失败、超时或
   结果不可解析一律为 `UNKNOWN`，绝不默认为安全。
3. **风险标注必须给正确的人**。Handoff 高风险告警应到 owner 侧；若升级为 owner 审批，recipient
   只能看到“等待 owner 审批”，不能批准自己的请求。
4. **UI 可异步，执行不可抢跑**。审批卡可以立即出现，但只要风险结果可能改变审批人，“允许”就必须
   在评估完成前禁用；“拒绝”可以始终立即使用。禁止先让 recipient 批准、再异步尝试升级。
5. **建议模式与强制模式分开**。早期试验可以只做风险标注，此时它不构成当前请求的安全保证；一旦
   启用高风险审批路由，就必须使用完整的等待、超时和审批权校验状态机。
6. **LLM 不是第一道检测器**。确定性的敏感路径、网络外发、压缩/编码、凭证访问和命令序列规则应先
   执行；LLM 只分析规则无法判断的中间地带，降低延迟、费用和被注入面。
7. **隐私最小化**。发给评估模型前必须复用 secret redaction，并限制上下文长度；默认不发送 stdout、
   文件正文、完整 transcript 或明文 token。UI 应说明评估是否调用外部模型。

## 四、评估上下文：不能只看一条命令

文档要解决“单看无害、组合起来危险”的问题，因此评估单元不能只有当前的
`toolName + input + diff`。daemon 应维护结构化、受限长度的 `RiskContext`，至少包括：

- Handoff Brief 或 Bridge 原始请求的摘要；
- 当前 turn 最近若干次工具调用及确定性结果摘要；
- 当前工具名、输入、diff 摘要和目标路径；
- 路径敏感等级，例如 workspace、用户目录、凭证目录、系统目录；
- 是否已经发生读取、压缩、编码、网络访问或凭证相关动作；
- 网络目标域名、Git remote 等可结构化提取的信息；
- 当前累积风险状态和命中的规则 reason code。

`RiskContext` 不直接复制 Handoff History 的所有内容，也不为了分类方便收集更多秘密。规则引擎使用
daemon 内的原始数据；传给外部 LLM 的版本必须经过裁剪和脱敏。

首批确定性序列规则建议覆盖：

- 敏感路径读取后进行网络外发；
- 文件读取/收集后进行压缩、编码，再访问网络；
- 修改 Git hooks、`.git/config`、agent hooks 或 shell 启动配置；
- 向非预期 remote push，或使用强制覆盖语义；
- 尝试访问 workspace 外、凭证目录或系统目录；
- 先收集环境变量/认证信息，再调用 curl、wget、scp、rsync 等外发工具。

## 五、两个拦截层，而不是一个 ASK 拦截点

### 5.1 Request-level preflight

Bridge 的 AUTO_TRUSTED 请求可能在 `PermissionAsk` 产生前执行一部分工具，所以仅在 ASK fall-through
评估，无法实现“高风险时剥夺 AUTO_TRUSTED”。外部请求进入 agent 前需要一次 request-level preflight：

- 硬策略先决定请求是否直接拒绝；
- 确定性规则和可选 LLM 生成 request risk；
- 结果只能保留或收紧原 grant；
- HIGH / UNKNOWN 可以把本 turn 的 `AUTO_TRUSTED` 收紧为逐工具 ASK；
- 不允许评估器创建比原请求更高的 grant。

### 5.2 Tool-level assessment

`PermissionBridge.onControlRequest` 的 ASK 路径负责具体工具风险：

- 先执行 handoff write wall、pathScope、危险命令红线等硬策略；
- 再组合 `RiskContext` 评估当前动作；
- 建立 pending ask，并在 daemon 保存允许回答它的 `ApprovalAuthority`；
- LOW / MEDIUM 走原审批人；HIGH / UNKNOWN 走 owner（强制模式）；
- 任一 verdict 都用 transport-derived deviceId 验证，不能信任 frame 自报 actor 或角色。

## 六、审批状态机与权限分离

`ControllerAuthority` 与 `ApprovalAuthority` 必须分离：

- `ControllerAuthority` 决定谁可以发送 prompt、CancelTurn 和回答 AskUserQuestion；
- `ApprovalAuthority` 决定谁可以对某个 PermissionAsk 发送 ALLOW/DENY；
- Handoff 期间 recipient 仍是 Controller，但高风险工具可以由 owner 单独审批；
- daemon 的 pending ask 保存 authority，RequestRouter 按 `askId + transport deviceId` 校验；
- owner 审批工具不意味着 owner 重新取得整个 Session 的 Controller Lease。

强制模式的状态流：

```text
CONTROL_REQUEST
  → HARD_POLICY(DENY / CONTINUE)
  → ASSESSING（卡片可见；Deny 可用；Allow 禁用）
  → LOW/MEDIUM：WAITING_ORIGINAL_APPROVER
  → HIGH/UNKNOWN：WAITING_OWNER
  → ALLOWED / DENIED / TIMED_OUT / WITHDRAWN
```

若评估超时：

- 建议模式：显示 `UNKNOWN`，保持“该模式不提供阻断保证”的诚实文案；
- 强制路由模式：转 owner，不能退回 recipient 自批；
- 总审批超时仍按现有机制安全拒绝，并撤回双方卡片。

## 七、Handoff Bash 的确定性 P0

`BridgeCommandPolicy` 可以复用危险命令识别，但不能原样复用完整 verdict 语义。其 `ALLOW` 在 Bridge
中代表零交互执行，而 Handoff v1 约定 shell 逐次确认。因此映射必须是：

| `BridgeCommandPolicy` 结果 | Bridge | Handoff REVIEW |
|---|---|---|
| DENY | 硬拒绝 | 硬拒绝 |
| ALLOW | 允许按 Bridge 既有策略自动执行 | 仍然 ASK |
| ASK | owner ASK | recipient 或升级后的 owner ASK |

不要把 Bridge 的 owner allow-list 带入 Handoff。建议实现时把共用部分重命名/抽取为更中性的
`CommandRiskFloor`，由不同来源会话映射成自己的最终动作。

此外，“Handoff Bash 不提供始终允许”必须由 daemon 强制，而不只是 App 隐藏按钮：

- `handoffAccess != null && tool == Bash` 时，pending ask 强制 `neverRemember = true`；
- daemon 忽略自定义客户端或旧客户端发送的 `remember=true`；
- 既有 remembered rule 不得在新的 Handoff Grant 下自动放行 Bash；
- 每次 Bash 请求、审批决定和结果继续进入 Handoff History。

## 八、协议与 UI

风险结果是异步产生的，不建议通过重新发送同一个 `PermissionAsk` 更新：当前 App 会按新的接收时间
重新计算 `timeoutSec`，可能错误延长审批窗口。

建议新增 additive frame：

```text
PermissionRiskUpdated(
  convoId,
  askId,
  risk,              // LOW / MEDIUM / HIGH / UNKNOWN
  reason,
  reasonCodes,
  authority,         // ORIGINAL_APPROVER / OWNER
  assessedAt,
  assessorVersion
)
```

要求：

- 新字段尾追 optional/default，新 enum 对未知值安全回退；
- 老 App 忽略新 frame，不影响既有 ASK，但 daemon 仍是审批权事实源；
- 新 App 用 `askId` 更新现有卡片，不重置 daemon 给出的绝对过期时间；
- `reason` 是简短解释，不展示模型思维过程；
- owner 审批升级需要独立 push，且不能继续用 `pathScope != null` 推断“这是 guest、不要通知 owner”；
- recipient 卡片显示等待 owner，owner 卡片展示任务来源、Handoff、当前命令和必要的动作序列摘要；
- HIGH 与 UNKNOWN 的 UI 要区分：“发现风险”与“无法可靠评估”不是同一件事。

## 九、评估器实现边界

- daemon 侧运行，因为 relay 零知识看不到明文；relay 不参与分类或审批决策。
- 不应把 `claude -p` 写死为唯一实现。cc-pocket 同时支持 Claude、Codex、OpenCode，机器未必具备
  Claude 登录或 API key；应定义可选的 `RiskAssessor` adapter。
- 优先顺序建议为：确定性规则 → 用户配置的模型/API → 可选本地模型；没有可用评估器时返回 UNKNOWN。
- 评估任务必须与正在等待 PermissionAsk 的主 agent 进程隔离，不能尝试在同一个被阻塞的会话里提问。
- 设置严格超时、并发上限和缓存边界；缓存只能复用脱敏后的完全相同输入，不能跨不同风险上下文误用。
- 不记录完整分类输入和模型原始输出；History 只保存风险等级、reason code、评估器版本和最终路由。

## 十、分期

| 期 | 内容 | 完成标准 |
|---|---|---|
| P0 | Handoff 复用危险命令 DENY；ALLOW 在 Handoff 仍映射 ASK；daemon 强制 Bash neverRemember | 不依赖 LLM，拉齐确定性红线和逐次确认 |
| P1 | Handoff History + 确定性行为序列风控 | 能看到动作链并对明确组合风险给出 reason code |
| P2 | LLM 建议模式 + `PermissionRiskUpdated` + App 风险高亮 | 只观察和度量，不宣称阻断；收集误报、漏报、延迟和 UNKNOWN 比例 |
| P3 | `ApprovalAuthority`、owner push、Handoff 高风险/UNKNOWN 转 owner | 评估期间不可抢跑，错误审批人 verdict 被 daemon 拒绝 |
| P4 | Bridge request-level preflight，按风险收紧 AUTO_TRUSTED | 外部请求进入 agent 前决定本 turn grant，且只能收紧不能放宽 |

不要从 P2 直接假设分类器足够可靠。进入 P3 前应准备固定风险样例集，至少包含正常测试/构建、危险命令、
敏感读取、压缩后外发、Prompt Injection、混淆命令和评估器超时，并记录人工基准。

## 十一、必须验收的场景

1. Handoff 的 `git status` 仍逐次 ASK，不因 `BridgeCommandPolicy.ALLOW` 自动执行。
2. Handoff 客户端发送 `remember=true` 也不能形成 Bash allow rule。
3. `rm -rf` 等确定性红线在评估器离线时仍被硬拒绝。
4. recipient 在 ASSESSING 阶段发送 ALLOW，daemon 拒绝该 verdict，命令不执行。
5. HIGH / UNKNOWN 切换到 owner 后，recipient verdict 被拒绝，owner verdict 可以只批准该 ask。
6. owner 批准工具不会夺走 recipient 的 Controller Lease。
7. 先读取敏感路径、再打包、再外发能够利用序列上下文升级风险，而不是三次独立判定。
8. 分类输入中的 token、环境变量值和文件正文不被发送或写入 History。
9. 风险更新不重置审批超时；评估晚于 ask 终态时安全丢弃，不复活旧卡片。
10. 老 App 忽略风险更新后仍可正常显示 ASK；强制模式下，即使老 App UI 不理解新状态，daemon 也拒绝
    错误审批人或过早的 verdict。
11. owner 离线时收到不含命令正文的 push，打开 App 后能获取完整待审批上下文。
12. 评估失败、超时、模型不可用和输出不可解析均进入 UNKNOWN，不会变成 LOW 或 AUTO-ALLOW。

## 十二、实现时必过的审查

- 协议字段或新增 frame → `protocol-wire-compat-reviewer`；daemon 与 App 独立发版，必须向后兼容。
- Handoff Bash 策略、ApprovalAuthority、owner push、AUTO_TRUSTED preflight → `crypto-security-reviewer`。
- 评估器 prompt、脱敏、外部模型数据边界 → 安全复审并加入 Prompt Injection / secret leakage 测试。

## 十三、非目标

- 不把 LLM 结果当成恶意软件检测或形式化安全证明；
- 不自动批准任何原本需要人批准的动作；
- 不因为有 Smart Approval 就移除 pathScope、结构化写入墙、危险命令红线或 History；
- 不在 v1 构建企业 IAM、组织级策略后台、不可篡改审计或通用 shell 沙箱；
- 不承诺能够安全地把 owner 电脑开放给陌生人或有意对抗系统的协作者。
