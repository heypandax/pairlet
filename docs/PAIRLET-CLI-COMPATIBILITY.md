# Pairlet 命令兼容

2026-09-10：当前工作区已加入 `pairlet` 简短命令，保留 `cc-pocket-daemon`。本文件记录源码方案；安装脚本、tap/bucket 和包含启动补齐逻辑的 daemon 尚未发布，不能据此认为线上旧安装已经获得新入口。

```sh
pairlet pair       # 等价于 cc-pocket-daemon pair
pairlet status     # 等价于 cc-pocket-daemon status
pairlet version    # 等价于 cc-pocket-daemon version
pairlet update     # 等价于 cc-pocket-daemon update
```

全部子命令、参数和退出码沿用同一个程序。配对、身份、配置、日志、资产名称、包内目录和服务 ID 保持原值。文档推荐短命令，同时保留旧安装的命令说明。原来的 `pairlet-daemon` 草案改为 `pairlet`。

| 渠道 | 新命令如何提供 | 旧安装如何获得 |
| --- | --- | --- |
| macOS / Linux 官方脚本 | `bin/pairlet` shell 脚本 exec 同目录原稳定启动器 `cc-pocket-daemon` | 更新后的脚本重跑即补齐；旧更新器升级至含此代码的 daemon 后，首次启动补齐 |
| Windows 官方脚本 | `pairlet.cmd` 转发同目录 `cc-pocket-daemon.cmd` | 脚本重跑或新 daemon 首次启动补齐；原 shim 随版本更新，新 shim 无需改写 |
| Homebrew | 现有 `cc-pocket` cask 同时声明两个 binary 入口 | tap 发布后随包升级获得；同版本仅修改配方时，已有安装需要通过 Homebrew reinstall 更新入口 |
| Scoop | 现有 `cc-pocket-daemon.json` 的 bin 同时声明旧 exe 和 `pairlet` alias | bucket 发布后由 Scoop 更新 shim；不创建第二个应用目录或计划任务 |
| Gradle installDist | 同目录 `pairlet` / `pairlet.bat` 转发原启动脚本 | 重新构建 installDist；原 daemon 更新脚本会复制整个 bin 目录 |

Homebrew 安装命令仍为 `brew install --cask heypandax/tap/cc-pocket`，Scoop 仍为 `scoop install cc-pocket-daemon`。包名迁移与 CLI 简化分开发布；`packaging/pairlet-candidates/homebrew/` 中的 `pairlet` cask 和 rename map 仍是待验收草案。不能发布两套各自安装、注册服务的同类包。

脚本及 daemon 启动补齐仅创建缺失别名；已有同名文件或其他软链接保留并提示使用旧命令。Homebrew / Scoop 安装目录仅由包管理器维护，daemon 不在那里补写入口。新增 alias 不是额外后台进程，不触发服务安装或重启。

macOS 原生包实测中，`ProcessHandle` 返回的可能是 PATH 里的命令软链接。`UpdateService.selfExe()` 因此先解析真实程序路径，再判断 managed / Homebrew / Scoop 归属，避免 `pairlet update` 或旧稳定链接被误判为手动安装。本次明确允许这处路径解析改动，兼容清单同步更新其文件摘要；旧资产选择器、安装目录、版本切换及服务重启逻辑均保留。

macOS jpackage 原生启动器不能可靠处理 `pairlet -> cc-pocket-daemon -> 版本内 launcher` 这样的多层相对软链接，会找错 `.cfg` 路径。官方脚本和启动补齐因此使用 shell 转发器；Homebrew 的两个 binary 都直接链接到同一个包内原生程序，避免该问题。

源码验证覆盖安装重试、版本切换与旧版本清理、参数和退出码转发、同名文件及悬空软链接保护、旧 managed 安装首次补齐、包管理器目录不写入。Windows CMD 实际执行用例与 PowerShell 解析检查接入 Windows CI；本机 macOS 验证不代替 Windows runner 或正式渠道升级验收。

## 2026-09-10 本地验证

- daemon 全集：222 个测试类、1,881 个用例，0 失败、2 个平台条件跳过。随后针对原生路径识别和 shell 转发修正，重跑 `CliAliasesTest` / `UpdateServiceTest`：15 个用例，0 失败，Windows CMD 用例在 macOS 跳过。
- `python3 -m unittest discover -s scripts/tests -v`：4 个真实 shell 安装器夹具测试通过，覆盖 macOS/Linux 布局、同版本重装、升级与旧版本清理、参数/退出码、同名文件及悬空链接保护。全部使用独立 HOME、本地下载夹具及 `CC_POCKET_NO_SERVICE=1`。
- Gradle `installDist` 构建完成，新旧入口的帮助、版本查询、配对与服务安装的帮助输出一致；只执行离线查询/帮助，没有启动 daemon。
- macOS arm64 jpackage 构建完成。`python3 scripts/smoke-daemon-cli.py daemon/build/jpackage/cc-pocket-daemon.app` 在独立 HOME 的旧 managed 安装布局中验证首次补齐、真实原生执行、安装归属及脚本字节一致，没有注册服务。该检查已加入 macOS 发布任务，在上传资产前执行。
- 使用本机 Homebrew 实际 Cask DSL 解析：两个 binary 均指向同一版本同一个原生程序；Ruby 语法、Scoop JSON、15 项兼容文件、官网公开内容及 SEO 检查通过。

当前未发布安装脚本、tap/bucket 或新客户端；未部署网站文档。检测到本机仍有运行中的 agent 会话，按 `scripts/update-local-daemon.sh` 的会话保护要求，没有重启或替换当前本机 daemon。
