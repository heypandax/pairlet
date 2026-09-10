# Pairlet 官网部署记录

2026-09-10：根据 Owner 当次授权，官网已直接部署到香港服务器，使用 pairlet.org。DNS 与 SSL 设置通过已登录的 Dia / Cloudflare 页面完成。客户端仍为本地候选，本次没有发布客户端、切换客户端默认 relay、迁移仓库或包管理器，也没有重启本机产品 daemon。

## 运行位置与域名

| 入口 | 运行行为 |
| --- | --- |
| `https://pairlet.org/` | 中文官网；`/en/` 英文官网；`/manual/zh/`、`/manual/en/` 手册 |
| `https://www.pairlet.org/<path>?<query>` | 308 到主域，保留完整 URI |
| `https://relay.pairlet.org/` | 代理原 `127.0.0.1:9000` relay；本次只验证健康接口，未切换客户端默认地址 |
| `https://pairlet.org/dl/` | 复用原下载镜像 `/var/www/cc-pocket-dl`；稳定清单与安装文件未发布或替换 |
| `https://pairlet.org/support/` | 新支持页面，同源 `/support-api` 通过原受限 SSH 隧道访问支持服务 |
| `https://pocket.ark-nexus.cc/` | 原 relay、配对、API、下载和旧手册路由保持原配置 |

香港源站为 `8.218.228.34`，Caddy 2.11.4。Cloudflare 的主域、`www`、`relay` 三条 A 记录都指向该 IP，Proxied、TTL Auto。SSL 模式为 **Full (Strict)**；三个源站证书均由 Let's Encrypt 签发，信任链及 hostname 验证通过。Caddy 已 reload，relay 进程没有重启。

站点目录：

- 当前链接：`/var/www/pairlet-site/current` → `releases/website-20260910-r2`。
- 最初部署：`/var/www/pairlet-site/releases/e92ba5cf-20260910`，保留用于内容回退。
- 旧站点：`/var/www/cc-pocket-site`，未覆盖。
- 站点文件共 66 个；最终包 SHA-256 为 `4735b8a2953a75718973a66f290954b4fa362b8e825f40deb07186d46b95523a`。
- 源内容基于 `e92ba5cf`，最终补充将官网帮助及 README 入口改为新域。逐文件摘要见部署清单；运行路由以 `deploy/Caddyfile` 为准。

## 支持服务兼容更新

既有独立支持主机上的 API 仍是较旧版本，缺少新网页调用的 `/config`。已备份后部署当前 `support/web/server.py` 与 `abuse.py`，仅重启支持 API；原 OpenClaw agent、模型配置、既有知识库和共享密钥保留。

运行环境允许 `https://pairlet.org`、旧域及原 Pages origin；新增 StateDirectory 供现有治理模块使用。`/config`、允许 origin 的 OPTIONS 204、拒绝未知 origin 的 403 都通过；Dia 中一条匿名安装配对问题成功收到回答，服务日志对应 `status=ok`、约 50.8 秒。

原服务没有配置 Turnstile 密钥，本次保持 `turnstile.enabled=false`，没有声称挑战链已验收。支持机器人仍可能回答 CC Pocket 并引用旧手册域名；本次未重建其知识库。

## 验证结果与边界

- 香港及美国节点、本机访问主域 HTTPS 200；Dia 实际检查中文页、英文页和支持问答。
- 66 个公开静态文件通过 HTTPS 获取后与本地清单逐一比对 SHA-256，全数一致。首轮 Python urllib 请求被边缘返回 403，改用普通 curl 完成检查，未为此调整防护配置。
- 英文页、手册、支持页、`/dl/latest.json` 返回 200；www 返回 308 且保留路径和 query。
- 三个源站 TLS 证书使用系统信任库及各自 hostname 验证通过；Cloudflare 页面回读为 Full (Strict)。
- 新旧 relay `/healthz` 均为 200，Caddy 和 relay 服务 active；这不替代客户端配对、WebSocket 或新旧地址组合的端到端验收。
- 支持服务 42 项测试、网站 SEO 39 页 / 36 sitemap URL、本地链接和公开内容检查通过。
- 尚未验证中国大陆实际网络、客户端新地址配对及本次正式客户端升级。现有下载入口仍分发原稳定版本。

详细证据保存在当前 worktree 被忽略的 `docs/plans/pairlet-implementation/deploy-20260910/`，包括 DNS/SSL 页面回读、HTTP/TLS 回执、浏览器截图、站点清单及测试日志。凭据未写入记录或提交。

## 备份与回退

香港备份目录 `/var/backups/pairlet/20260910/` 保存原 `Caddyfile.before`、两个站点包及对应清单。原 Caddyfile SHA-256 为 `1cc8fa35503b6bec4594df8b72d163a2af2cab6866226f18a2570f6eb74a9789`。

只回退本次最后一轮静态内容时，在香港服务器原子切回首轮目录：

```bash
ln -s releases/e92ba5cf-20260910 /var/www/pairlet-site/current.rollback
mv -Tf /var/www/pairlet-site/current.rollback /var/www/pairlet-site/current
```

若需撤销新域路由，先核对线上配置自本次之后没有其他修改，再从备份恢复 Caddyfile，执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` 并 reload。旧域原配置块应保持原样，不重启 relay，不覆盖下载镜像。撤销 DNS 时仅处理本次新增的三个记录。

独立支持主机的 `/var/backups/pairlet/20260910/` 保存 `support-api/`、`support.env`、`support.service`。若回退 API，应同时恢复代码与 env，移除本次新增的 `cc-pocket-support-api.service.d/pairlet.conf`，daemon-reload 后仅重启支持 API；保留状态数据。该回退会失去新网页所需的 `/config`，应与网站支持入口一起评估，不能只回退一半。
