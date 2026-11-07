# 推送出站分流

2026-11-07 已在生产切换。发送逻辑、凭据和唯一数据库仍在深圳 relay；本目录部署美国的 HTTPS 出口，不启动第二套 relay。

| 渠道 | 出站路径 |
|---|---|
| Google FCM、Google OAuth | 深圳 `127.0.0.1:18889` → 专用 SSH 隧道 → 美国 `127.0.0.1:18888` tinyproxy |
| Apple APNs（生产与沙盒） | 深圳直连 |
| 华为 OAuth / Push Kit | 深圳直连 |
| 原有 Google Analytics 转发 | 沿用 JVM 默认 HTTPS 代理，现经美国出口 |

香港 `pairlet-relay-tunnel` 的本地 18888 仍保留作人工回退。它没有自动故障切换功能，也不再是 JVM 当前默认出口。香港的旧域、网站、下载和客服入口职责另行处理。

**2026-11-07 18:06 更新**：按用户要求进行香港暂停观察。香港 tinyproxy、旧域/网站/客服入口和下载任务，以及深圳的旧 `pairlet-relay-tunnel` 均已暂停并禁用自启动。上述回退配置仍在，但不再常驻；回退前须先恢复相应服务。详见[香港暂停记录](../../docs/observability/HK-PAUSE-20261107.md)。

## 美国出口

以本目录为 Docker build context；基础镜像按摘要固定，tinyproxy 固定为 `1.11.3-r0`。现网构建目录 `/opt/pairlet-push-egress`，镜像 `pairlet-push-egress:20261107`，容器 `pairlet-push-egress`。

```bash
docker build -t pairlet-push-egress:20261107 .
docker run -d --name pairlet-push-egress --restart unless-stopped \
  --network host --read-only --cap-drop ALL --security-opt no-new-privileges \
  --memory 128m --memory-swap 128m --cpus 0.5 --pids-limit 96 \
  --log-driver local --log-opt max-size=10m --log-opt max-file=3 \
  pairlet-push-egress:20261107
```

只监听回环 18888，CONNECT 仅允许 443。不开放公网代理端口，不在美国保存 APNs / FCM / 华为凭据；TLS 由深圳的客户端与厂商端点建立，代理不解密请求。

美国 SSH 使用专用系统账号 `pairlet-us-egress`，公钥只允许深圳来源 IP；`restrict,port-forwarding,permitopen="127.0.0.1:18888"` 配合独立 sshd Match 配置，将 TCP 转发限制为 local、禁止密码/键盘交互认证、代理转发、PTY、X11 和隧道设备，`MaxSessions 0` 禁止 shell / command / subsystem session。新增或修改 sshd 配置须先 `sshd -t` 再 reload。服务器的现有管理员认证不变。

## 深圳隧道与 JVM

专用系统账号同名，独立密钥位于 `/var/lib/pairlet-us-egress/.ssh/id_ed25519`，在深圳生成且不导出。`known_hosts` 从已认证的美国连接读取服务器公钥并固定，禁止关闭主机校验。

安装 [pairlet-us-egress.service](pairlet-us-egress.service)，在 `/etc/pairlet-us-egress.conf` 配置 `PUSH_EGRESS_HOST=<美国服务器地址>`，启用服务，确认本地 18889 已监听且可实际通过代理完成 TLS。`active` 不能单独证明 SSH 已完成连接。

relay 新增 `/etc/systemd/system/cc-pocket-relay.service.d/60-push-egress.conf`，保留完整既有 `JAVA_TOOL_OPTIONS`（堆、观测等），仅将代理端口改为 18889 并追加下列绕过名单；不能用下面的片段替换完整 JVM 配置：

```text
-Dhttps.proxyHost=127.0.0.1
-Dhttps.proxyPort=18889
-Dhttp.nonProxyHosts=localhost|127.*|[::1]|api.push.apple.com|api.sandbox.push.apple.com|api.development.push.apple.com|oauth-login.cloud.huawei.com|push-api.cloud.huawei.com
```

HTTPS 与 HTTP 共用 `http.nonProxyHosts`；不要改成无效的 `https.nonProxyHosts`。依据：[Java 网络属性](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/doc-files/net-properties.html)。JVM `HttpClient` 的默认 `ProxySelector` 已用同版本 Java 17 实测。新增国内渠道时，要同时核对其认证和发送域名。

## 本轮验证与限制

- 通过代理取得 FCM OAuth HTTP 200，`validate_only=true` 返回 HTTP 200，未向真实手机投递。
- 用生产同版本 Java 17、同一组 JVM 参数验证：Google 两个端点及 Analytics 选择美国代理，APNs 与华为选择 DIRECT；APNs 生产和沙盒协商 HTTP/2。
- 华为现有凭据请求在原香港出口和深圳直连下均返回 HTTP 400、`error=1101`、`sub_error=20003`、`parameter invalid`。属于迁移前已存在的认证问题，路由已切换不等于华为推送可用。不得据此清理用户设备 token，也未更换凭据。
- 美国代理拒绝 CONNECT 到非 443 端口；既有客服、OpenClaw 和容器保持运行。
- 尚未执行真机后台通知展示测试；接口连通、FCM 预校验和手机实际展示是不同验收层级。

## 回退与后续部署

切换备份、精确 JVM 参数、验证回执和服务端执行脚本在深圳 `/root/pairlet-push-cutover-20261107/`（0700）。原 service 和既有 Sentry drop-in 不变；本轮只增加新的出站 drop-in 并重启一次 relay，未复制/回退数据库，也未替换程序。

若需回退，先确认旧 `pairlet-relay-tunnel` active 且本地 18888 能取得 TLS 响应，再移除本轮 `60-push-egress.conf`、`daemon-reload` 并在服务器自持任务中重启 relay。这样恢复原香港出口和原 JVM 选项；数据库始终保留深圳最新数据。切换脚本在首次重启或本机健康检查失败时自动执行上述回退。

后续发布继续使用国内 relay 部署流程，保留本 drop-in 与隧道，不要执行会覆盖生产 service 的旧 `provision-relay-push.sh`。需要更新美国镜像时先验证候选容器，避免直接覆盖正在承载流量的实例。
