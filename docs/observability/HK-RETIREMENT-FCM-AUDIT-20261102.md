# 香港停机依赖与 Google 推送核查

核查日期：2026-11-02，约 13:08—13:20（UTC+8）。范围为当前香港入口机和国内 ECS，通过已有个人 Mac SSH 通道只读查询；没有连接已于 7 月停用的旧机器，没有部署、重启、改 DNS 或向手机发送通知。

## 停机结论

国内 relay 核心和数据库已迁移，但香港仍有生产依赖，不能直接关机。这里的“新服务器”按当前 Pairlet 国内 ECS 核对，不是公司局域网 Ubuntu。

| 香港现存职责 | 实测依据 | 下线前处理 |
|---|---|---|
| `pocket.ark-nexus.cc`、`relay.pairlet.org` 旧客户端入口 | Caddy → 本机 19001 → `pairlet-cn-tunnel` → 国内核心；两个旧域及新域 `/healthz` 都返回 200/ok | 在目标入口配置原域 TLS 和代理，再切 DNS；保留旧配对地址兼容，不能仅让用户改新域 |
| `pairlet.org`、`www.pairlet.org` 官网，旧域帮助/手册 | Caddy 静态站点和 www 跳转仍在香港；站点目录 `/var/www/pairlet-site/current`、`/var/www/cc-pocket-site` | 迁移静态资产、全部相关路由、TLS 与域名解析；若落国内，先核对域名接入条件 |
| `/dl/` 下载镜像 | `cc-pocket-mirror-sync.timer` 运行；最近任务成功，镜像 v2.6.1；公开 `latest.json` 返回 200 | 迁移 `/var/www/cc-pocket-dl`、同步脚本与定时器，验证目标机访问 GitHub 和下载完整性 |
| `/support-api/` 客服转发 | `cc-pocket-support-tunnel` active，本机 10966 监听，Caddy 转发到该端口 | 迁移受限 SSH 转发及反代规则；客服后端在别处，本次未执行真实客服问答 |
| HTTPS 出站代理 | 香港 tinyproxy 监听回环 18888；国内 `pairlet-relay-tunnel` active，JVM `JAVA_TOOL_OPTIONS` 设置 `https.proxyHost/Port` | 提供替代 Google 出口并验证后再切换；把 tinyproxy 搬到无法直连 Google 的国内机本身不能解决问题 |
| 香港旧 relay 与旧数据库 | `cc-pocket-relay` inactive/disabled | 保留必要备份，不重启旧核心，不用旧数据库覆盖国内数据库 |

香港业务监听端口为 80/443、10966、19001、18888，另有 SSH、DNS 和 Caddy 管理端口。未发现运行中的 Docker 服务、其他用户 service 文件或 root cron 作业；系统计时器、阿里云维护/监控服务不作为业务迁移项。结论限于已核对的主机、systemd、监听端口、配置与目录。

国内核心自 10 月 27 日 23:52 启动，`NRestarts=0`，当前 Caddy 仅承接 `relay.pairlet.cn`，没有上述官网/镜像目录。不能把“国内核心健康”视为全部迁完。

附带观察：公司 Mac 直接请求官网收到 403；香港本机通过 `--resolve` 验证原域 TLS 访问源站为 200。只证明源站存在，不据此认定所有访问网络正常。客服随手探测 `/health` 返回 404，该路径未确认是健康接口，不能据此判断客服故障。

## Google 推送实现与实测

FCM 已实现，不是独立驻留香港的“Google 推送进程”：发送逻辑属于国内 relay，香港提供网络出口。

- Android：`PushController.android.kt` 获取/刷新 FCM token；`CcPocketMessagingService.kt` 处理前台消息，后台 notification 交由系统展示。Manifest 注册 messaging service、默认通知渠道及通知权限。
- relay：`FcmSender.kt` 使用 service-account 私钥签署 JWT、交换 OAuth token，再调用 FCM HTTP v1。凭据位于 `/etc/cc-pocket-relay/fcm.json`，由 `CCPOCKET_FCM_CREDENTIALS` 指定，关键字段齐全。
- 数据库只做聚合查询：未撤销、非 headless、非 collaborator 的 FCM 登记有 **172 条，121 个不同 token**；有登记不保证当前仍有效，更不代表 172 台独立手机。最新 FCM 登记为 11 月 2 日 07:10，相关设备最近在线为 10:06。
- 国内直连 `oauth2.googleapis.com` 和 `fcm.googleapis.com` 在限定 5 秒内未取得 HTTP 响应；经本机 18888 代理可以完成 TLS 并取得 HTTP 响应。
- 使用现有 service account 在服务器内签名，经代理取得 OAuth **HTTP 200**；从数据库内读取一条近期在线设备 token，以 `validate_only=true` 校验通知请求，FCM 返回 **HTTP 200**。没有投递到手机，没有打印或导出私钥、OAuth token、设备 token。

此校验验证当前凭据、权限和代理通路；它绕过正在运行的 relay 业务触发逻辑，不能代替真实任务触发与手机通知展示验收。

## 日志能证明什么

| 查询范围 | FCM 记录 | 解释 |
|---|---|---|
| 香港留存日志，9 月 1 日至迁移 | 12 次 sender 初始化；20 次 HTTP 404 与对应失效 token 清理；6 次发送异常 | 确实调用过 FCM，有失效 token 和失败；不是成功率统计 |
| 国内，10 月 27 日 23:52 至 11 月 2 日 13:09 | 1 次 sender 初始化；10 月 28 日 23:14 有 1 次 FCM 超时 | 日志没有成功记录，但不能认定成功次数为零 |

直接检查线上 `relay-0.0.1-SNAPSHOT.jar` 的 `StorePushService` 类：存在失败/失效 token 日志，没有当前源码中的 `sent platform=` 和 `retry attempt=` 标记。线上旧版本与本地源码并不完全一致；不能用本地当前源码推断线上会记录成功，也不能用失败记录数量推算成功率。

现有服务端证据无法回答“哪一台 Pixel 已经显示过通知”。即使未来记录 FCM accepted，也只代表 Google 接收请求；设备送达、系统展示、用户点开需要各自证据。[Firebase 投递指标说明](https://firebase.google.com/docs/cloud-messaging/understand-delivery)。

## 如何配置与验收

现网已配置好 Firebase 发送身份，不需要因迁服务器重新建 Firebase 项目或重新生成全体设备 token。

1. Android 构建使用同一 Firebase 项目的 `google-services.json`，包名为 `com.panda.ccpocket`。生产 CI 从 `GOOGLE_SERVICES_JSON` secret 恢复到 `mobile/androidApp/google-services.json`。不要用 `.template` 占位配置打真实推送测试包，也不要把服务器 service-account 私钥放进 APK。
2. 服务端沿用安全保存的 service-account 配置和权限，保持 `oauth2.googleapis.com`、`fcm.googleapis.com` 可达。当前国内 JVM 的通用 HTTPS 代理也影响使用默认 HttpClient 的 APNs 等请求；不能仅因 Apple 可直连就直接关闭旧代理。
3. Pixel 安装正式配置的 App，保持兼容的 Google Play services 和可访问 Google 的网络；允许系统通知，并在 Pairlet 设置中打开通知，连接电脑完成 token 登记。Android 13+ 还需要运行时通知权限。[Firebase Android 配置说明](https://firebase.google.com/docs/cloud-messaging/android/get-started)。
4. 对确认归属的测试手机，将 App 退到后台/锁屏，触发一次任务完成和一次待审批，分别核对请求、FCM 接收、手机展示及点击进入会话。前台正在查看同一会话时，代码可能主动不展示通知；不要据此判定 FCM 故障。实际手机测试本次未执行。

服务端认证参考：[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)。现有 `scripts/provision-relay-push.sh` 仍硬编码香港目标、会覆盖 service unit 并重启，不适用于当前拓扑，不应直接执行。要调整凭据部署流程，应先迁移至国内目标并保留代理/Sentry 等现有 drop-in。

建议顺序：先准备替代出站通路，再迁官网/镜像/客服和旧域入口；逐项验证后切 DNS，等待缓存与存量长连接完成过渡；最后确认国内无香港依赖再停机。全程维持国内唯一数据库写入核心。真实生产切换尚未执行。
