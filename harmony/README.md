# harmony/ — cc-pocket 鸿蒙客户端（路线 A：ArkTS 原生重写）

手机 App 的 HarmonyOS NEXT 移植。daemon / relay / protocol 不动，本目录是一个独立的
HarmonyOS 工程（stage 模型，API 12+），按 `protocol/` 的 wire 规范重新实现客户端。

## 功能范围（M1）

- 扫码配对（Scan Kit 系统扫码 UI 识别 `ccpocket://pair?relay=&acct=&dpk=&ticket=`，无需相机权限）
- 6 位码配对（POST /v1/pair/code → /v1/pair/redeem）
- relay WebSocket 连接 + DeviceHello/Attached + 4-DH E2E 握手 + AES-256-GCM transport
- 应用层 pocket/ping 心跳（鸿蒙 webSocket API 不暴露协议层 ping）
- 项目列表 → 会话列表 → 聊天（历史回放 / 流式 chunk / 工具行 / 思考块）
- 权限审批卡（允许一次 / 始终允许 / 拒绝）+ AskUserQuestion 单选问答卡
- 断线指数退避重连（2s→30s），回前台立即重连；会话自动重开（restoreAfterReconnect 最小版）

## 架构决策（与 Kotlin 端的对应关系）

| harmony (ArkTS) | mobile (Kotlin) | 说明 |
|---|---|---|
| `pocket/crypto/E2ECrypto.ets` | `protocol/e2e/E2ECrypto.kt` | P-256 ECDH 用**纯 bigint 软件实现**（Jacobian）；HKDF=HMAC-SHA256 手摇（与 Kotlin 相同）；AES-256-GCM 用 CryptoFramework |
| `pocket/crypto/E2ESession.ets` | `protocol/e2e/E2ESession.kt` | 4-DH + transcript HKDF + 计数器 nonce，逐行对齐 |
| `pocket/protocol/Protocol.ets` | `protocol/` | Envelope{t,id,ts,to,body}，宽松 JSON 解析（天然 ignoreUnknownKeys） |
| `pocket/net/RelayConnection.ets` | `mobile/net/RelayE2EConnection.kt` | outbox 缓冲、握手超时、心跳 |
| `pocket/data/Repository.ets` | `mobile/data/PocketRepository.kt` | M1 子集；数组一律改后重赋（ArkUI V1 观察语义） |
| `pocket/store/Store.ets` | `mobile/pairing/Pairing.kt` 存储部分 | preferences 落盘 |

### 为什么 ECDH 是软件实现

cryptoFramework 的 `AsyKeySpec` 系列（ECCKeyPairSpec / getAsyKeySpecBigInt）字段形状需要
对照本机 SDK 的 d.ts 逐字段核实；软件 P-256 行为完全确定，且 `selfTest()` 用公开向量自证
（RFC 5869 case1、P-256 2G、ECDH 互验、GCM 往返+篡改拒绝），配对页会展示自检结果。

**TODO(hardening)**：
1. 对照 SDK d.ts 核实 `AsyKeySpecItem`/`ECCKeyPairSpec` 后，ECDH 换 cryptoFramework（密钥不出 TEE、抗时序）。
2. 设备私钥 / relay 凭证从 preferences 迁移到 HUKS / Asset Store（对齐 Android Keystore / iOS Keychain）。

## Push Kit（离线推送）

链路：鸿蒙 App `pushService.getToken()` → `pocket/push.register`（platform=`"huawei"`，每次重连重发）
→ relay `HuaweiSender`（AGC REST，`IM` 类目）→ 系统通知。**不需要受限 ACL**，与 DevEco
自动化签名兼容。

启用步骤（一次性，AGC 控制台操作为主）：

1. **AGC 建应用**：AppGallery Connect → 我的项目 → 添加应用，包名 `dev.ccpocket.app`
   （与 `AppScope/app.json5` 的 bundleName 一致）。用 DevEco 自动化签名跑过一次的话，
   AGC 里通常已有这个项目记录，直接复用。
2. **开通 Push Kit**：项目 → 增长 → 推送服务 → 开通。
3. **（建议）申请自分类权益**：推送服务 → 配置 → 自分类权益，申请 IM 类目。
   不申请也能推，但会落入资讯营销通道（限量、可能折叠）。
4. **拿三个值**（项目设置 → 常规）：`Client ID`、`Client Secret`、`App ID`。
5. **App 端**：把 Client ID 填进 `entry/src/main/module.json5` 的
   `metadata.client_id`（替换 `REPLACE_WITH_AGC_CLIENT_ID`），重新打包装机。
   首次启动会弹通知授权。
6. **relay 端**：主机环境变量加三行后重启 relay：
   `CCPOCKET_HUAWEI_APP_ID` / `CCPOCKET_HUAWEI_CLIENT_ID` / `CCPOCKET_HUAWEI_CLIENT_SECRET`。
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

- Push Kit 推送唤醒（M2，含 relay 侧 huawei sender）
- 图片/文件附件、语音输入
- Markdown 渲染 / 代码高亮（聊天文本当前是纯文本）
- 多设备绑定管理、folder-share / bridge 管理面
- Usage 统计、模型/模式切换、调度任务
