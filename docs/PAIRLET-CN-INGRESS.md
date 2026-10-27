# 国内 relay 核心与旧域兼容

更新日期：2026-10-27。当日 23:52（UTC+8）完成生产核心迁移。**本次没有构建或发布 App/daemon，也没有修改其默认地址或配对存储。**

## 当前拓扑

- `relay.pairlet.cn`：DNS A 指向备案 ECS，TTL 10 分钟；国内 Caddy 终止受信 TLS，反代本机 `127.0.0.1:9000`。
- `pocket.ark-nexus.cc`、`relay.pairlet.org`：继续由香港 Caddy 接入，relay 请求经香港 `127.0.0.1:19001` 的 SSH 隧道转至国内 `127.0.0.1:9000`。没有 HTTP 跳转，也没有修改旧域 DNS。
- 唯一运行的 `cc-pocket-relay` 核心及 SQLite 位于国内 ECS。香港旧核心已停止、禁用，并通过 `90-retired-primary.conf` 和 `/etc/cc-pocket-relay/retired-primary` 阻止意外启动。
- 香港官网、客服 `/support-api/`、支持页、下载镜像等路由保留。新旧入口都覆盖调用方提交的 `X-Forwarded-For`，再经回环或隧道直接到核心。
- 国内无法直连部分 Google 服务。国内 `pairlet-relay-tunnel` 现仅将 `127.0.0.1:18888` 转发至香港同端口的 tinyproxy，供 JVM HTTPS 出站使用。香港 tinyproxy 只监听回环、仅允许 CONNECT 443；不向公网开放代理。应用级 TLS 继续校验目标服务证书。
- 国内 JVM 保留原堆配置并增加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18888`。因此香港仍是兼容入口与出站依赖，不得关机或宣称数据全部在内地处理。

具体 IP、测试身份及原始回执仅放忽略目录 `_local/icp-cn-20261021/`；不写入版本库。

## 迁移与验收证据

1. 直接复制现网已部署的程序和完整配置，未从当前分支构建。迁移前后程序目录和推送/分析配置目录哈希一致。
2. 先对旧 SQLite 做在线备份并检查完整性；最终停止香港核心、checkpoint 后复制完整数据库。新服务器启动前数据库 SHA-256 完全一致，`integrity_check=ok`。
3. 最终复制 892 个账号、1,261 条设备记录，包含本次新增的一个隔离测试身份及设备；原账号、设备密钥、凭据和配对记录完整保留。
4. 香港核心 23:52:19 停止，23:52:40 三域健康检查通过并完成切换；约 21 秒窗口内连接需要重连，不声称零断线。
5. 用隔离测试身份在切换前配对，切换后复用原设备凭据，验证旧域 daemon→新域 device、新域 daemon→`relay.pairlet.org` device 的认证和双向加密二进制转发。没有启动第二个本机 daemon，没有读取真实用户密钥。这证明凭据延续及 relay 路由，不替代真机 App 完整 E2E 握手、前后台和通知验收。
6. 三域受信 HTTPS `/healthz` 均正常；切换后国内核心观察到 254 条已建立连接，未发生服务自动重启。一次本机新域握手超时，随后重试认证与双向转发成功；尚未进行持续网络质量测量。
7. Apple、FCM、Google OAuth、Google Analytics 经出站代理都能完成 TLS 并取得 HTTP 响应；FCM/Huawei sender 初始化成功。未给真实用户发测试通知，实际手机通知到达仍需后续验收。

## 运维配置与防误部署

- 国内入口模板：[Caddyfile.cn](../deploy/Caddyfile.cn)；香港入口模板：[Caddyfile](../deploy/Caddyfile)。不要互换。
- 后续核心部署脚本 [redeploy-relay.sh](../scripts/redeploy-relay.sh) 必须设置 `RELAY_HOST_CN`，默认密钥登录，可用 `RELAY_SSH_JUMP`；不再回退使用 `RELAY_HOST_HK`。脚本会检查目标核心运行且没有退役标记，再上传。
- 国内保留原 `/etc/cc-pocket-relay/`（推送、分析配置）、systemd 单元及 Sentry drop-in；不要用旧模板覆盖掉 HTTPS 出站参数。
- 香港 `pairlet-cn-tunnel` 服务以专用账号连国内 `pairlet-hk-ingress`，仅准许本地转发至国内回环 9000，禁止 shell/PTY/代理转发。国内保留的反向方向隧道仅承担 HTTPS 出站。
- 两端备份目录均为 `/root/pairlet-migration-20261027/`，权限 0700。包含迁移前 Caddy 配置、最终数据库快照等；不得公开下载或提交。
- 切换执行器和日志在可信个人 Mac 的 `~/.cache/pairlet-migration-20261027/`；脱离本地会话运行，失败路径先停新核心，再将最新数据库带回旧机，不用旧快照覆盖已接收的新写入。

## 回退约束

回退必须在维护窗口执行，保持单写者：先停止国内核心并 checkpoint，备份和复制**最新**数据库到香港；完整性与哈希核验后，移除香港退役标记、恢复香港两个 relay upstream 为本机 9000，再启动香港核心。新域必须同时改为指向同一个香港核心的隧道；当前国内旧 19000 转发及香港 SSH 对 9000 的转发授权均已移除，须重新配置受限隧道并验证，不能只恢复历史 Caddy 文件。确认新旧三域认证和数据往返后，再调整自启动。

禁止直接启动香港陈旧数据库，禁止同时运行两套生产核心。`relay.final.db` 是历史备份，不可在已有新写入时直接恢复。

## 备案与后续客户端

- 已获批 App 备案号 `粤ICP备2026165853号-2A`，名称 `Pairlet`，接入域名 `relay.pairlet.cn`。主体号是 `粤ICP备2026165853号`，不可混填。Apple 已保存 App 号，中国区回读为 `Available`，用户已确认商店可见。
- `pairlet.cn` 网站不是本次部署范围；当前商店官网、支持、隐私链接仍为 `pairlet.org`。
- App/daemon 切域留待后续新版：统一处理默认地址、旧配对持久化地址和 QR 导入，保留自托管地址、公钥、设备 ID、凭据与逻辑身份。现在老版本仍使用旧域，但已通过代理接入新核心，不需要重新配对。
- 旧入口退役另定，香港目前不能下线。备案的各平台详情及旧版本过渡口径以接入商获批材料为准，不将技术兼容等同于合规范围已获额外确认。
