# 外部分发迁移候选

仅用于审阅，不被现有发布脚本读取；尚未提交 tap/bucket、安装或注册服务。

CLI 简化使用 `pairlet`，保留 `cc-pocket-daemon`。现有 `packaging/homebrew/Casks/cc-pocket.rb` 和 `packaging/scoop/cc-pocket-daemon.json` 已加入双命令声明，可先发布命令兼容；这里的 Homebrew 包名迁移仍单独待验收，详见 [命令兼容](../../docs/PAIRLET-CLI-COMPATIBILITY.md)。

官方 Formula 的实现草稿另见 [Formula 状态](homebrew/FORMULA-STATUS.md)。按最新决定，等正式 Pairlet 稳定版再送审；目前未提交官方 PR，也未完成 Homebrew 安装、测试和完整审计。

- Homebrew：基于 2026-09-10 读取的线上 **1.9.7**。同次变更移除旧 `Casks/cc-pocket.rb`，加入 `Casks/pairlet.rb` 和 rename map。两个命令指向同一 launcher，服务仍为 `dev.ccpocket.daemon`。发布前替换为验收过的新版本及两个架构的实际 hash，并验证旧安装升级、重试和卸载。
- Scoop：基于线上 **1.9.8**，保持 `cc-pocket-daemon.json` 安装身份、下载、hash、checkver、autoupdate 和服务逻辑，仅增加 `pairlet` 命令别名。`checkver=github` 依赖原 homepage，故仍保留仓库 URL。不得同时发布一个会独立安装及注册服务的 `pairlet.json`；包名迁移仍须 Windows 实证。别名写法依据 [Scoop manifest 文档](https://github.com/ScoopInstaller/Scoop/wiki/App-Manifests#optional-properties)。
- Pages：`python3 scripts/build-legacy-pages-redirects.py` 生成已知路径的 `build/pairlet-pages-redirects/cc-pocket/`，只适用于实际站点仓库 `heypandax/heypandax.github.io` 的同名子目录。新域 HTTPS、静态内容和支持链验收后再单独应用。JavaScript 保留 query/hash；这是 HTML 跳转，不是 HTTP 308。关闭 JavaScript 时保留可点击的对应页面链接。

仓库改名继续单独排期；旧 Release URL、资产名与包内布局不得随以上展示名变更。重新发布前必须重读线上状态，不能直接使用本目录中的历史版本与 hash 作为新版本回执。
