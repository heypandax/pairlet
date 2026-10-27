# 国内接入与备案迁移

更新日期：2026-10-27。当前阶段是**App 备案已通过、内部入口验证通过，公网与客户端迁移尚未完成**。不能在新域名公网和端到端验收前发布切换默认地址的客户端。完整发布与过渡安排见 [综合方案](iterations/2026-10-27-release-plan/PLAN.md)。

## 当前事实

- 管局退回原因是旧域名 `ark-nexus.cc` 的注册机构未获批复。阿里云 App 总后台域名及 Android、iOS 平台域名原来同时包含 `relay.pairlet.cn` 与 `pocket.ark-nexus.cc`。
- `pairlet.cn` 在阿里云注册且实名认证成功。网站申报域名为 `pairlet.cn`，App 接入域名为 `relay.pairlet.cn`；10 月 27 日检查尚未取得新域公网可用证据，DNS、TLS 和入口仍需验收。
- App Store Connect 2.6.0 中英官网、支持、隐私政策 URL 已为 `pairlet.org`。无需通过修改商店链接来修复旧后台域名的注册商问题。
- 用户选择迁至 `relay.pairlet.cn`，保留旧入口供存量版本过渡。阿里云总后台域名和两个平台列表已调整为新域；备注如实声明迁移尚未完成，并请求初审确认过渡安排及申报范围。用户确认材料及协议后，于 2026-10-21 11:54（UTC+8）提交。建站调研如实填写“未完成建站”，未订阅建站产品联系。
- 2026-10-27 用户提供工信部通过通知和阿里云 App 列表截图：主体号为 `粤ICP备2026165853号`，**App 备案号为 `粤ICP备2026165853号-2A`**，App 名称 `Pairlet`，使用阿里云服务器的域名为 `relay.pairlet.cn`。截图未展示各平台详情、网站备案号或对旧版本过渡安排的明确答复，不扩大解释批准范围。
- 同日已将该 App 号保存至 App Store Connect，独立回读确认；中国大陆状态从 `ICP Filing Number Missing` 变为 `Available`。未上传新包或更改销售地区；用户随后确认 App Store 已恢复可见；本轮没有独立执行真机下载安装。
- 国内主机为本次备案订单选定的 ECS，Ubuntu 24.04。具体连接地址与订单回执只保留在忽略的 `_local/icp-cn-20261021/`，不将其与项目已停用的历史服务器配置混用。

## 已部署的内部链路

国内 Caddy `127.0.0.1:18080` → 国内 SSH 转发 `127.0.0.1:19000` → 香港现有 relay `127.0.0.1:9000`。

只增加入口，不复制数据库、不新起 relay 进程。现有香港 Caddy、relay 和旧域服务保持运行。新旧客户端未来接入同一进程，才能共享设备身份、配对凭据与在线状态。该设计仍经过香港中转，不能对外描述为数据全部在中国内地处理。

配置来源：

- [内部 Caddy 配置](../deploy/Caddyfile.cn-staging)：当前安装到国内主机 `/etc/caddy/Caddyfile`，只绑定回环地址。
- [正式 Caddy 候选](../deploy/Caddyfile.cn)：存放在国内主机 `/etc/caddy/Caddyfile.production-candidate`，尚未启用；仅含 App relay 域名，不发布网站。
- [隧道服务](../deploy/pairlet-relay-tunnel.service)：国内 `/etc/systemd/system/pairlet-relay-tunnel.service`。源站变量在 `/etc/pairlet-relay-tunnel.conf`；专用私钥仅位于国内 `/var/lib/pairlet-tunnel/.ssh/id_ed25519`。

香港专用账号 `pairlet-cn-tunnel` 限定来源为国内 ECS、只允许本地转发到 `127.0.0.1:9000`，禁止 shell 会话、PTY、代理转发及远程端口转发。源站主机密钥通过已有可信 SSH 通道核对，客户端启用严格主机校验。香港 sshd 修改前的备份在 `/var/backups/pairlet/icp-20261021/sshd_config.before`。

Caddy 覆盖 `X-Forwarded-For` / `X-Real-IP`，随后通过 SSH 直达 relay，避免第二层代理将所有用户合并成同一限流 IP。不能把目标换成香港公开 HTTPS 域而不重新审查客户端 IP 信任规则。

## 已验证及限制

- 国内 Caddy 2.6.2（Ubuntu 安全更新包）配置校验通过，正式候选适配通过，systemd 隧道单元校验通过。
- 隧道在限制生效后重连成功；经国内 Caddy 的 `/healthz` 返回 `ok`。
- `/v1/device` 返回 `101 Switching Protocols`；只验证传输升级，没有使用真实用户凭据，也没有验证登录、配对、E2E 消息或推送。
- 国内新增服务仅监听 `127.0.0.1:18080`、`:19000`、`:2019`，未开放公网 80/443，未添加 DNS。
- 公网旧 relay 与 `relay.pairlet.org` 健康端点、`pairlet.org` 官网/支持/隐私页可达。
- 未修改客户端默认地址、用户持久化配对或本机 daemon；本入口准备未发布切域客户端。App 备案通过的证据已补齐；Apple 备案字段及中国区状态以综合方案中的最新回读结果为准。

## 后续执行顺序

1. App 备案已通过；保留获批号、名称和后台域名记录。具体 iOS/Android 平台详情及旧版本过渡范围仍应以获批详情和接入商答复为准，不能仅隐藏仍实际使用的域名。
2. App Store Connect 的号码保存与中国区 `Available` 回读已完成；用户已确认公开商店恢复；真机下载与升级仍在发版验收中核对。商店恢复与新入口公网验收分别取证。
3. 获批后给 `relay.pairlet.cn` 添加指向备案 ECS 的 A 记录，核对安全组 80/443，启用正式 Caddy 配置并验证受信 HTTPS。已有连接期间 Caddy reload 可能中断 WebSocket，应安排发布窗口。
4. 用隔离测试身份验证新旧入口交叉配对、E2E 收发、推送、重连和限流；公网 TLS 与端到端验收前不切换正式客户端。
5. 客户端变更涉及 `Pairing.DEFAULT_RELAY`、daemon `DEFAULT_RELAY`、旧配对存储中的官方 relay 地址及 QR 导入。精确映射旧官方地址，保留用户自托管地址、账号、公钥、设备 ID 和凭据。仅修改默认常量不会迁移存量配对；也不能只改手机而假设另一侧已经就绪。
6. 核对 App 的手册、客服及隐私链接的实际域名和备案口径；当前 `OpenUrl.kt` 仍有旧域与 GitHub Pages。网站 `pairlet.cn` 是另一项尚未部署的服务，需准备网站内容、备案号展示与验证后再使用。商店链接仅在目标网页可达后调整。
7. 根据实际改动执行兼容测试和项目发布流程，发版后再核对真实网络使用。老入口保持可用，退役另定；不能将本记录当作允许中断其他 daemon 会话的授权。

## 停用本次内部入口

国内执行 `systemctl disable --now caddy pairlet-relay-tunnel` 可关闭本次新增入口（本次部署前该主机无 Web 服务）。保留配置和备份便于恢复，不停止香港 relay。未来如国内 Caddy 承载了其他站点，须重新核对停用范围。

若移除香港隧道账号，只移除该专用账号、对应授权公钥及 `# Pairlet CN tunnel restrictions` 块；先核对之后的修改并运行 `sshd -t`，不能用整个历史备份覆盖新配置。
