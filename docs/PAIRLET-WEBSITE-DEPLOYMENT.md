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

- 当前链接：`/var/www/pairlet-site/current` → `releases/website-20260912-bdfab268`（2026-09-12 同步到 main `bdfab268`；上一版 `releases/website-20260910-r2` 保留用于回退）。
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

### 2026-09-12 00:08 CST：官网内容同步到 main（bdfab268）

2.0.0 发版前核对发现线上站点仍是 `website-20260910-r2`：`privacy.html` 缺少 Sentry 段落（App Store 审核备注链接到它），`index.html` 也与 main 不一致。从 main `bdfab268` 打包 `site/` 全部 66 个文件，单次 SSH 会话上传到 `/var/www/pairlet-site/releases/website-20260912-bdfab268`，远端按 `LC_ALL=C` 排序生成 SHA-256 清单，与本地清单哈希 `3ddd1da9a3d8ae3b5a5377960536ee054873c0cbaed3a4eb086600e53bb056df` 比对一致后才原子切换 `current`；上一版目录保留。

公网回读：`/`、`/en/`、`/manual/zh/`、`/manual/en/`、`/support/`、`/privacy.html`、`/dl/latest.json` 均 200，`www` 308 保留完整 URI；`privacy.html`、`index.html`、`en/index.html`、`styles.css`、`app.js` 与仓库逐字节一致，隐私页已含 Sentry 段落。未动 Caddyfile、relay、下载镜像与稳定版本清单。

两处坑：清单两端都要用 `LC_ALL=C sort`，macOS 默认排序规则与 Linux 不同，同一批文件会得出不同的清单哈希（首次上传因此被远端校验拒绝切换，属预期保护）；macOS `tar` 要加 `--no-xattrs`，否则 GNU tar 会对每个文件打印 `LIBARCHIVE.xattr.com.apple.provenance` 警告。香港机有 SSH 防暴破限速，两次连接之间至少间隔 20 秒，上传与切换合并在一次会话里完成。

回退到上一版：

```bash
ln -sfn releases/website-20260910-r2 /var/www/pairlet-site/current.new
mv -Tf /var/www/pairlet-site/current.new /var/www/pairlet-site/current
```

### 2026-09-11 07:36 CST：再次恢复被旧部署配置覆盖的新域路由

公开官网、中英文页、支持页和隐私页再次返回 Cloudflare 525；直连香港源站的 Pairlet TLS 握手也失败。远端 Caddyfile SHA-256 再次为上线前的 `1cc8fa35…`，没有 Pairlet 域名块。站点仍指向 `website-20260910-r2`，relay 本地健康检查正常。常用部署工作区 `~/Desktop/Project/app/cc-pocket` 的 Caddyfile 同样是这份旧配置；这证明存在再次覆盖的风险，但未追溯确认具体是哪次操作导致覆盖。

核对旧域配置逐字节不变后，验证候选配置、备份、原子替换并 reload Caddy。备份为 `/var/backups/pairlet/20260911/website-route-repair-2336/Caddyfile.before`；恢复后摘要为 `d072b9e85994d886e61f3f67c6e43b6b8c2a9cf2d07697b0cb3db18683ecc42c`。relay PID 前后均为 `546725`，未重启 relay，也未替换站点内容、下载文件或稳定版本清单。

常用部署工作区仅同步此前未修改的 `deploy/Caddyfile`，保留其正在进行的可观测性代码和部署脚本改动，不提交该工作区的其他内容。主分支新增 `scripts/check-production-caddy.py`，在 relay 部署、镜像初始化和 CI 中检查原域及三个新域的站点块；校验在任何服务器操作之前执行。旧分支须同步配置和此检查后再部署，检查本身不能保护仍在使用旧版脚本的其他 checkout。

修复后官网、中英文页、支持页、隐私页、支持 API `/config`、新旧 relay 健康接口均返回 200；浏览器已显示完整中文首页。直接连接香港源站、启用系统证书及 hostname 验证也返回 200；www 返回 308 并保留路径和 query。新旧下载清单均为 1.9.8，字节一致（SHA-256 `7dce7e76a6ceab363e2753b0cef35093c1ea54a7e10bf8ffbdf4840d340741e2`）。旧 Caddyfile 回归样本会被新检查拒绝，当前配置检查、两个 shell 脚本语法、workflow actionlint 和 diff 检查通过。没有执行支持 AI 问答或客户端配对验收；App Store 2.0 草稿、发布状态与包管理器未改动。

### 2026-09-10 12:05 UTC：恢复被旧配置覆盖的新域路由

准备 App Store 2.0 元数据时发现新域返回 Cloudflare 525。源站的 Caddyfile 摘要已恢复为下述官网上线前的 `1cc8fa35…`，不含任何 Pairlet host 块；站点 `current` 仍指向 `website-20260910-r2`，Caddy 与 relay 均 active。尚未确认是哪次部署覆盖了配置。

已在核对远端摘要未变化后，将 `deploy/Caddyfile` 中的新域路由恢复到源站。候选配置的旧域前缀与当时线上配置逐字节相同；Caddy validate 通过后 reload，relay PID 前后相同。备份保存在 `/var/backups/pairlet/20260910/appstore-route-repair-120457/Caddyfile.before`，恢复后的 Caddyfile SHA-256 为 `d072b9e85994d886e61f3f67c6e43b6b8c2a9cf2d07697b0cb3db18683ecc42c`。

公开主站、中英文页面、支持页、隐私页、新旧 relay 健康接口和原下载清单均返回 200，www 可重定向到主域。后续从其他分支部署 relay 时，需要先合入当前 `deploy/Caddyfile` 的新域路由，不能用改名前的整份配置覆盖源站。

同次将隐私页的 GitHub Pages 托管说明改为香港自有服务器 + Cloudflare，更新仓库链接，并明确编程会话与可选客服是不同的数据路径。只原子替换 `current/privacy.html`；旧文件备份为同目录的 `privacy.html.before`。公网回读与本地 `site/privacy.html` 逐字节一致，SHA-256 为 `fbd0a4a8a4e7fd8cd296e4dfb27ae5647c8c2f97e805b9fb6b1a21e752f1510b`。该单文件更新之后，最初 66 文件清单中的隐私页摘要已过时，其余静态文件未改。

香港备份目录 `/var/backups/pairlet/20260910/` 保存原 `Caddyfile.before`、两个站点包及对应清单。原 Caddyfile SHA-256 为 `1cc8fa35503b6bec4594df8b72d163a2af2cab6866226f18a2570f6eb74a9789`。

只回退本次最后一轮静态内容时，在香港服务器原子切回首轮目录：

```bash
ln -s releases/e92ba5cf-20260910 /var/www/pairlet-site/current.rollback
mv -Tf /var/www/pairlet-site/current.rollback /var/www/pairlet-site/current
```

若需撤销新域路由，先核对线上配置自本次之后没有其他修改，再从备份恢复 Caddyfile，执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` 并 reload。旧域原配置块应保持原样，不重启 relay，不覆盖下载镜像。撤销 DNS 时仅处理本次新增的三个记录。

独立支持主机的 `/var/backups/pairlet/20260910/` 保存 `support-api/`、`support.env`、`support.service`。若回退 API，应同时恢复代码与 env，移除本次新增的 `cc-pocket-support-api.service.d/pairlet.conf`，daemon-reload 后仅重启支持 API；保留状态数据。该回退会失去新网页所需的 `/config`，应与网站支持入口一起评估，不能只回退一半。
