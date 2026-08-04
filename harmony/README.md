# harmony/ — cc-pocket 鸿蒙客户端（路线 A：ArkTS 原生重写）

手机 App 的 HarmonyOS NEXT 移植。daemon / relay / protocol 不动，本目录是一个独立的
HarmonyOS 工程（stage 模型，API 12+），按 `protocol/` 的 wire 规范重新实现客户端。

## 功能范围（真机验证：华为 Pura X 折叠屏 · HarmonyOS NEXT API 24 · 端到端闭环通过）

**连接与安全**
- 配对：扫码（Scan Kit 系统扫码 UI，识别 `ccpocket://pair?...`，无需相机权限）/ 6 位码
  （POST /v1/pair/code → /v1/pair/redeem）
- relay WebSocket + DeviceHello/Attached + 4-DH E2E 握手 + AES-256-GCM transport
  （软件 P-256 + CryptoFramework GCM/HMAC；配对页自检：RFC 5869 / P-256 2G / ECDH 互验 /
  GCM 篡改拒绝 / 全链路握手 / Asset Store 往返）
- 应用层 pocket/ping 心跳（鸿蒙 webSocket API 不暴露协议层 ping）
- 断线指数退避重连（2s→30s），回前台立即重连；会话自动重开（仅在握有具体 sessionId 时 resume，避免 fork）

**聊天**
- 项目列表 → 会话列表 → 聊天；历史回放（TranscriptMerge 锚点合并）、流式 chunk、
  工具卡、思考块、错误行
- Markdown 渲染（标题/列表/代码围栏/**粗体**/`行内码`/斜体）
- 权限审批卡（允许一次 / 始终允许 / 拒绝）+ AskUserQuestion 单选 / 多选问答卡
- 回合中排队发送（daemon/CLI 排队 mid-turn prompt）、活动反馈（思考中/执行工具/回复中）、
  「运行中」呼吸 chip
- 顶栏 ⋮ 快捷操作：切换模型 / 思考深度 / 权限模式 / 改动文件 / 压缩上下文 / 简化 / 清空会话；
  模型目录走 daemon `models.list`（网关 id 优先）、思考深度走 `supportedEfforts`
- 输入栏模型 chip、上下文占用圈（点开看确切 token 数 + 压缩入口）、「回到最新」悬浮 pill、
  自动跟随滚动

**附件**
- 图片：相册选择 → 压缩（≤1024px / ~90KB）→ 内联 `SendPrompt.images`，历史气泡内渲染
- 文件：文档选择 → 768KB 分块 `file.chunk` 流式上传 → 落盘工作区 inbox → 随 prompt 以
  `@path` 引用发出；20s 回执守卫（老 daemon 不认帧会提示升级）
- 改动文件：`files.list` → 列表（操作符/增删行数）→ `file.read` → 全文查看器

**多电脑与设置**
- 多设备绑定：绑定列表持久化（旧单绑定自动迁移）、项目页顶栏 ▾ 切换、设置页解配
- 设置页：主题（深/浅/跟随系统，token 逐值对齐 Android Palette）、新会话默认权限模式、
  本会话"始终允许"规则管理、账号、关于
- 权限模式切换：询问 / 自动（Claude 原生 auto）/ 自动编辑 / 计划 / 绕过权限（下回合生效）
- 系统返回键应用内导航；折叠屏内容限宽居中（CONTENT_MAX=860vp）

**安全加固**
- 机密存储：设备私钥 / relay 凭证 / 首连票据迁入 **Asset Store**（系统加密、TEE 保护、
  不随明文 preferences 进云备份）；写后读回校验防设备不对称；Asset Store 不可用时回落明文
- Push Kit 注册链路（`getToken` + `pocket/push.register`，platform="huawei"），
  未配 AGC 凭据时静默降级（不影响使用）

## 架构决策（与 Kotlin 端的对应关系）

| harmony (ArkTS) | mobile (Kotlin) | 说明 |
|---|---|---|
| `pocket/crypto/E2ECrypto.ets` | `protocol/e2e/E2ECrypto.kt` | P-256 ECDH 用**纯 bigint 软件实现**（Jacobian）；HKDF=HMAC-SHA256 手摇（与 Kotlin 相同）；AES-256-GCM 用 CryptoFramework |
| `pocket/crypto/E2ESession.ets` | `protocol/e2e/E2ESession.kt` | 4-DH + transcript HKDF + 计数器 nonce，逐行对齐 |
| `pocket/protocol/Protocol.ets` | `protocol/` | Envelope{t,id,ts,to,body}，宽松 JSON 解析（天然 ignoreUnknownKeys） |
| `pocket/net/RelayConnection.ets` | `mobile/net/RelayE2EConnection.kt` | outbox 缓冲、握手超时、心跳 |
| `pocket/data/Repository.ets` | `mobile/data/PocketRepository.kt` | 状态仓库；数组改后重赋（ArkUI V1 观察语义） |
| `pocket/data/TranscriptMerge.ets` | `mobile/data/TranscriptMerge.kt` | 全量回放锚点合并（防排队气泡丢失/旧消息复制） |
| `pocket/store/Store.ets` + `Vault.ets` | `mobile/pairing/Pairing.kt` 存储部分 | 机密落 Asset Store（`@kit.AssetStoreKit`），非机密落 preferences |
| `pocket/media/Attach.ets` | `mobile/media/*` | 图片压缩（ImageKit）、文件分块读取（fileIo） |
| `pocket/ui/*` | `mobile/ui/*` | ArkUI V1；Theme.ets token 逐值对齐 Android Palette |

### 为什么 ECDH 是软件实现

cryptoFramework 的 `AsyKeySpec` 系列（ECCKeyPairSpec / getAsyKeySpecBigInt）字段形状需要
对照本机 SDK 的 d.ts 逐字段核实；软件 P-256 行为完全确定，且 `selfTest()` 用公开向量自证
（RFC 5869 case1、P-256 2G、ECDH 互验、GCM 往返+篡改拒绝），配对页会展示自检结果。

**TODO(hardening)**：
1. 对照 SDK d.ts 核实 `AsyKeySpecItem`/`ECCKeyPairSpec` 后，ECDH 换 cryptoFramework / HUKS（密钥不出 TEE、抗时序）。
2. ~~设备私钥 / relay 凭证迁移到 Asset Store~~（已完成：`store/Vault.ets`，写后读回校验 + 明文回落）。

## Push Kit（离线推送）

链路：鸿蒙 App `pushService.getToken()` → `pocket/push.register`（platform=`"huawei"`，每次重连重发）
→ relay `HuaweiSender`（HarmonyOS NEXT Push Kit v3 REST，`IM` 自分类）→ 系统通知。**不需要受限 ACL**，
与 DevEco 自动化签名兼容。

> ⚠️ **relay 必须先合入本修复分支并 redeploy**（Ping 回显 + `HuaweiSender` v3 端点修正）。现网
> 1.6.0 relay 的 device 腿不回显应用层 `pocket/pong`，鸿蒙端 10s 收不到 pong 即断连 →
> 每约 30s 掉线循环；且旧 `HuaweiSender` 用的是 HMS Android 旧端点，推送会静默不发。两者都在
> 本分支的 relay 侧修复里，未 redeploy 前鸿蒙端不可用。

启用步骤（一次性，AGC 控制台操作为主）：

1. **AGC 建应用**：AppGallery Connect → 我的项目 → 添加应用，包名 `dev.ccpocket.app`
   （与 `AppScope/app.json5` 的 bundleName 一致）。用 DevEco 自动化签名跑过一次的话，
   AGC 里通常已有这个项目记录，直接复用。
2. **开通 Push Kit**：项目 → 增长 → 推送服务 → 开通。
3. **（建议）申请自分类权益**：推送服务 → 配置 → 自分类权益，申请 IM 类目。
   不申请也能推，但会落入资讯营销通道（限量、可能折叠）。
4. **拿三个值**（项目设置 → 常规）：`Client ID`、`Client Secret`、`项目 ID`（Project ID，
   Push Kit v3 端点 `POST /v3/{projectId}/messages:send` 的路径参数）。
5. **App 端**：把 Client ID 填进 `entry/src/main/module.json5` 的
   `metadata.client_id`（替换 `REPLACE_WITH_AGC_CLIENT_ID`），重新打包装机。
   首次启动会弹通知授权。
6. **relay 端**：主机环境变量加三行后重启 relay：
   `CCPOCKET_HUAWEI_PROJECT_ID` / `CCPOCKET_HUAWEI_CLIENT_ID` / `CCPOCKET_HUAWEI_CLIENT_SECRET`。
   启动日志应见 `[push] Huawei sender ready`。
7. **验证**：杀掉 App，让 daemon 产生一次审批/回复，手机应收系统通知；relay 侧
   `journalctl -u cc-pocket-relay` 能看到 huawei 发送记录。

## 构建

前置：DevEco Studio（本机验证：6.1.1.290，SDK API 24 / 6.1.1.125）。首次构建前：

```powershell
# 1. 图标（仓库复用 Android launcher 图标）
copy mobile\composeApp\src\androidMain\res\mipmap-xxxhdpi\ic_launcher.png harmony\AppScope\resources\base\media\app_icon.png
copy mobile\composeApp\src\androidMain\res\mipmap-xxxhdpi\ic_launcher.png harmony\entry\src\main\resources\base\media\icon.png

# 2. 自包含 hvigor wrapper（259MB，已 gitignore）：把 DevEco 的 hvigor 工具链按
#    tools\hvigor\{bin,hvigor,hvigor-ohos-plugin} 结构拷进来（wrapper 按脚本位置
#    的上级目录解析 hvigor home，必须保持这个目录形状）：
mkdir harmony\tools\hvigor
xcopy /E /I "E:\DevEco Studio\tools\hvigor\bin" harmony\tools\hvigor\bin
xcopy /E /I "E:\DevEco Studio\tools\hvigor\hvigor" harmony\tools\hvigor\hvigor
xcopy /E /I "E:\DevEco Studio\tools\hvigor\hvigor-ohos-plugin" harmony\tools\hvigor\hvigor-ohos-plugin
```

```bash
# 命令行构建（已验证：entry-default-unsigned.hap 产出成功）
cd harmony
export DEVECO_SDK_HOME="E:\DevEco Studio\sdk"
export PATH="/e/DevEco Studio/jbr/bin:/e/DevEco Studio/tools/node:/e/DevEco Studio/tools/ohpm/bin:$PATH"
./tools/hvigor/bin/hvigorw.bat assembleHap --mode module -p product=default --no-daemon
# 产物：entry/build/default/outputs/default/entry-default-unsigned.hap
```

注意：PackageHap 是 Java 工具链（jbr 必须在 PATH）；根 oh-package.json5 的
modelVersion 必须与 hvigor/hvigor-config.json5 一致（均 "5.0.0"），否则报
00303024「工程结构需要升级」。

真机安装需要签名：DevEco → File → Project Structure → Signing Configs → 自动化签名，
把生成的 signingConfigs 填回 `harmony/build-profile.json5` 并把 product 的
`"signingConfig": "default"` 取消注释。

## 未覆盖（后续里程碑）

- **Push Kit 实际启用**：代码链路已就绪，需在 AGC 控制台建应用/开通推送/申请自分类权益、
  填 `client_id` + relay 环境变量（见上方「Push Kit」节）。未启用前推送功能静默关闭。
- **会话归档（#202）/ 审批 V2 / Handoff 协作（上游 1.6.0 增量帧）**：协议已宽松兼容（未知帧
  自然忽略），功能本身未实现。
- **HUKS 替换软件 P-256 ECDH**：当前 ECDH 是纯 bigint 软件实现（密钥在内存），
  hardening 项——换 cryptoFramework 后密钥不出 TEE、抗时序。
- 图片/二进制文件查看器（`file.read` 目前只接文本；base64 通道留给后续）。
- 代码高亮（Markdown 已渲染，代码块暂无语法着色）。
- opencode 后端（M1 仅 claude：`frameClientCaps` 诚实声明空 `supportsAgents`，
  开放前须把 agent 从 `SessionSummary` 透传进 `frameOpenSession`）。
- 语音输入、folder-share / bridge 管理面、Usage 统计、调度任务。
