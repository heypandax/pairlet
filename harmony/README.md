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

## 预览包下载（开发调试用）

未签名 HAP 挂在 GitHub Release（pre-release，如 `harmony-preview-20260804`），**二进制不进
git**（`harmony/release/` 已 gitignore）。无 DevEco 环境的协作者可用小白调试助手等工具自签
安装；自签指纹与 AGC 项目不一致时 Push Kit 自动关闭，其余功能不受影响。本机构建：
`bash scripts/build-harmony.sh`（macOS，需 DevEco Studio）。普通构建保持 DevEco 的默认
buildMode；需要单独验证 Release 编译时可传
`bash scripts/build-harmony.sh -p buildMode=release`，额外参数会原样透传给 hvigor。

## 正式 GitHub Release（显式启用）

`.github/workflows/release.yml` 的 Harmony job **不跑在 GitHub-hosted runner**：hosted macOS
没有 DevEco Studio，也不能凭空获得 Harmony 签名材料。它只在发版人显式传
`include_harmony=true` 时，调度一台受信任的、单任务 ephemeral/JIT self-hosted Apple Silicon
Mac；普通发版默认值为 `false`，不会排队等这台机器，也不会因为它离线而失败。

### 强制安全边界（首次配置）

1. 在 Settings → Environments 创建 **`harmony-release`**，配置至少一名 required reviewer，
   禁止发起人自审，并把允许部署的 ref 限制为 release tag（`v*`）。下述签名 secrets 必须只放
   在这个 environment，不能保留同名 repository secrets。workflow 已声明该 environment，未审批
   时 job 不会被派发到 runner，也不会取得 secrets。
2. 给 `v*` 建 GitHub tag ruleset，禁止非发布管理员更新或删除已推送的 release tag，避免验证后
   tag 被移动；workflow 会检查 `GITHUB_REF_PROTECTED=true`，未受保护的 tag 直接失败。Harmony
   runner 必须是每个 job 新建、job 后销毁的专用 ephemeral/JIT runner。注册时保留默认标签
   `self-hosted`、`macOS`、`ARM64`，再加唯一标签 `harmony`；只能服务本仓库，不能让其他仓库或
   fork 共用宿主机、账号、work 目录或 DevEco 用户数据。使用 Actions Runner 的
   `config.sh --ephemeral` 或 REST API 生成的 JIT 配置，并在 job 结束后销毁 VM/主机和注册记录。

   GitHub 当前没有向 job 暴露可移植、受支持的“本 runner 确由 `--ephemeral` 注册”证明，因此
   workflow 不能靠仓库内 marker 自证这一点；**ephemeral/JIT provisioner 与销毁检查是仓库外的
   必要发布门禁**，不能用一台长期在线的个人 Mac 替代。
3. runner 上安装 DevEco Studio（当前验证 6.1.1.290）。默认路径是
   `/Applications/DevEco-Studio.app/Contents`；若 runner 安装在别处，在仓库 Actions variables
   增加 `HARMONY_DEVECO_HOME`，值为 DevEco app 内的 `Contents` 绝对路径。
4. runner 镜像预装可执行的 GitHub CLI `gh`，以及 macOS 自带的 `unzip`、`plutil`、`openssl`。
   `gh` 由 job 的 `GITHUB_TOKEN` 临时认证，不在 runner 上持久登录或保存个人 PAT。

### `harmony-release` environment secrets

签名文件以 base64 文本保存为 `harmony-release` environment secrets，job 通过 required reviewer
审批后才还原到
`$RUNNER_TEMP/cc-pocket-harmony-signing/`，权限为 `0700/0600`，结束时（成功或失败）删除。
不要把文件、密码或 signingConfigs 写进仓库，也不要在 runner 镜像或 DevEco 用户目录保留 `.env`。

| Secret | 内容 |
|---|---|
| `HARMONY_P12_BASE64` | `cc-pocket-harmony.p12` 的 base64 |
| `HARMONY_CER_BASE64` | release `.cer` 的 base64 |
| `HARMONY_P7B_BASE64` | release profile `.p7b` 的 base64 |
| `HARMONY_KEYSTORE_PASSWORD` | p12 密钥库密码 |
| `HARMONY_KEY_ALIAS` | 密钥别名 |
| `HARMONY_KEY_PASSWORD` | 密钥密码；与库密码相同时可留空 |

在持有材料的 Mac 上，通过 GitHub Settings 写入 environment secrets；CLI 操作时必须显式指定
environment，例如 `base64 < file | gh secret set SECRET_NAME --env harmony-release`。runner 不得
持久化：工作流的 `always()` 清理只覆盖正常 job 生命周期，不能替代 ephemeral 主机销毁。
当前 DevEco `hap-sign-tool` 的非交互模式只接受命令行密码参数；`pwdInputMode=1` 要求真实 Console，
不能用 CI pipe 安全替代，也不得用会回显输入的伪终端包装。这也是 runner 必须单任务、无其他本机
用户且 job 后整机销毁的原因。

### 每次发布

先把审核过的 commit 打成 tag 并推送，再从该 tag 创建目标 GitHub Release。Harmony job 必须以
同一个 tag 作为 workflow ref；从 `main` 或其他分支触发会 fail closed：

```bash
git tag v1.7.7 <reviewed-commit-sha>
git push origin refs/tags/v1.7.7
gh release create v1.7.7 --verify-tag --generate-notes
gh workflow run release.yml --ref v1.7.7 -f version=1.7.7 -f include_harmony=true
bash scripts/harmony-jit-runner.sh   # 起单 job JIT runner 承接 Harmony job（job 结束自动注销）
# 最后在 GitHub UI 批准 harmony-release environment 的部署审核
```

对**已经发过、当时跳过了 Harmony** 的版本补发 HAP：改传 `-f only_harmony=true`（隐含
include_harmony），其余平台 job 全部跳过，已发资产与 Homebrew cask / Scoop 的 sha256 指纹保持
逐字节不变；`SHA256SUMS` 会在 HAP 上传后重算一次以覆盖新资产。注意 dispatch 的 `--ref` 仍必须是
该版本 tag 本身，且该 tag 里的 workflow 已含 `only_harmony` 输入（v1.9.0 及更早的 tag 没有，
只能随后续版本走）。

`scripts/harmony-jit-runner.sh` 用 `generate-jitconfig` 做一次性注册：恰好承接一个 job，
job 结束进程退出、注册记录自动移除，工作目录一次一建、退出即删。它完成的是「JIT 注册＋单任务＋
自动注销」这半边；宿主机 job 后销毁仍是仓库外门禁（见上文），在个人 Mac 上运行时应全程有人值守。

job 在 checkout 前严格校验 `x.y.z`，解析 `refs/tags/v<version>` 的实际 commit，检查 Release 的
`tagName`、`targetCommitish` 与 tag 谱系，再 checkout 已验证 tag；因此不能用功能分支代码覆盖正式
Release 资产。`harmony-release` environment 的部署 ref 规则也必须只允许 `v*` tag。

job 调用 `bash scripts/release-harmony.sh 1.7.7`，构建期间临时把包内 `versionName` 设为
`1.7.7`，把 `versionCode` 确定性映射为 `major*1,000,000 + minor*1,000 + patch`，退出时恢复
tracked `app.json5`。正式脚本在 HAP 的 `assembleHap` 与 App Pack 的 `assembleApp` 两步都
显式传 `-p buildMode=release`；签名只发生在 Release 编译产物上，不能用“已签名”替代
Release buildMode。签名和 `verify-app` 均成功后，上传
`cc-pocket-harmony-1.7.7-signed.hap` 到同一个 `v1.7.7` Release，并由总任务重新生成
`SHA256SUMS`。脚本同时产出的 `.app` 是 AGC 上传包，不作为 GitHub 用户下载资产上传。
签名前会从 HAP 与 `.app` 构建中间产物的 `pack.info` 校验 `bundleName`、`versionName`、
`versionCode`；`verify-app` 后还会把导出证书的 SHA-256 fingerprint 与输入 CER 比对，避免把
错误身份或错误版本包装成正确文件名上传。
workflow 在接触签名材料前先运行 `bash scripts/check-harmony-release.sh`：静态断言
`release` build option 仍存在、普通构建脚本仍透传可选参数，且正式脚本恰好在 HAP 与 APP
两个入口使用同一条 `buildMode=release` 属性。本地不安装 DevEco 也能运行这项检查。

`only_android=true` 或 `only_macos_desktop=true` 仍保持原来的“只发该平台”语义，即使误传
`include_harmony=true` 也会跳过 Harmony。没有 `include_harmony=true` 时，现有各平台 job 行为
完全不变。

启用步骤（一次性，AGC 控制台操作为主）：

1. **AGC 建应用**：AppGallery Connect → 我的项目 → 添加应用，包名 `com.ccpocket.app`
   （与 `AppScope/app.json5` 的 bundleName 一致）。用 DevEco 自动化签名跑过一次的话，
   AGC 里通常已有这个项目记录，直接复用。
2. **开通 Push Kit**：项目 → 增长 → 推送服务（新版控制台进入即默认开通，
   「配置」页签可见「关闭推送服务」即为已开）。
3. **（建议）申请自分类权益**：推送服务 → 配置 → 自分类权益 → 申请，按消息类型勾选
   （本项目已按「工作事项提醒」类目提交，2026-08-06，审核约 5 个工作日）。
   不申请时每设备每日仅 2 条限量通道。
4. **拿三个值**（项目设置 → 常规）：`Client ID`、`Client Secret`、`项目 ID`（Project ID，
   Push Kit v3 端点 `POST /v3/{projectId}/messages:send` 的路径参数）。
5. **App 端**：把**应用级** Client ID（项目设置 → 常规 → 应用 → OAuth 2.0客户端ID，
   与项目级的不是同一个）填进 `entry/src/main/module.json5` 的
   `metadata.client_id`（已填 `6917612935973583734`），重新打包装机。
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
