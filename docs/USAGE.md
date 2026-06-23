# CC Pocket 使用文档

CC Pocket 让手机成为你电脑上 Claude Code 的遥控器：人不在工位，Claude 继续干活，批准权限、跟进进度、补充指令都在手机上完成。

链路一句话：手机 App ↔ 零知识 relay（只转发密文）↔ 你电脑上的 cc-pocket daemon ↔ `claude` CLI。中继看不到任何明文，端到端加密只存在于手机和你的电脑之间（详见 [SECURITY.md](./SECURITY.md)）。

---

## 准备条件

- 一台 Mac（Apple Silicon），已安装并登录 [Claude Code](https://claude.com/claude-code)（终端跑一次 `claude` 完成鉴权即可）；
- 一部手机：iPhone（App Store 搜「CC Pocket」）或 Android（从 [GitHub Releases](https://github.com/heypandax/cc-pocket/releases) 下载 APK 安装）。

## 一、安装电脑端（daemon）

```bash
brew install --cask heypandax/tap/cc-pocket
cc-pocket-daemon service-install --apply   # 注册为登录自启服务，断线自动重连
cc-pocket-daemon pair                      # 打出二维码 + 6 位配对码
```

- 升级：`brew upgrade --cask cc-pocket`；
- 可选（语音输入给 Android／桌面客户端用，iPhone 不需要）：`brew install whisper-cpp`。

## 二、配对手机

手机打开 CC Pocket：**扫描**终端里的二维码，或**手输** 6 位配对码。配对一次永久有效，之后手机在任何网络（蜂窝、异地 Wi-Fi）都能连上，无需与电脑同一局域网。

配对无需注册账号——身份就是设备密钥本身。多台手机可分别配对同一台电脑。

## 三、日常使用

### 目录与会话

- 进入后先选**工作目录**：可按目录**树状层层下钻**（带面包屑回上层），也可一键切回**平铺**的最近列表——两种视图都会记住你的选择；输入即按路径／项目名／进行中会话标题**筛选**，每个项目显示各自的会话数；
- 目录下可以**新建会话**，或**恢复**任意历史会话——从上次离开的地方继续；
- 电脑终端里正在跑的 claude 会话也能看到：默认**只读旁观**实时输出，点「Continue here」可接管为手机控制。

### 对话

- 输出、工具调用、结果实时流式呈现，效果和终端一致；
- 随时点 ■ **打断**当前回合（会话保持存活）；
- `/model <名称>` 切换模型（如 `/model opus`）；输入 `/` 可自动补全当前项目的斜杠命令。

### 权限审批

Claude 要执行敏感操作（跑命令、改文件等）时，手机会弹出审批卡片：看清命令 → **允许** 或 **拒绝**；勾选「本会话总是允许」可以让同类操作本会话内不再询问（可在会话中随时撤销）。

**执行权限模式**（会话内可随时切换）决定 Claude 问多问少：

| 模式 | 行为 |
|---|---|
| `default` | 每个敏感工具都先询问（默认，最稳） |
| `acceptEdits` | 文件编辑自动放行，命令等其他操作仍询问 |
| `plan` | 只读规划——Claude 只能看和想，不执行任何改动 |
| `bypassPermissions` | 全自动，一律不问（信任度最高，谨慎使用） |

**默认模式与默认推理强度**：在 **Settings** 里可以把上面某个模式设为**默认模式**，并设定**默认推理强度（effort）**——之后新建会话、以及从列表直接点开的历史会话都会以这套默认开场（某个会话中途自己改过的，仍按该会话记住的来）。

### 语音输入

点麦克风开始说话（单段上限 90 秒）：

- **iPhone**：系统级实时听写，文字逐词上屏，不经过网络；
- **Android／桌面**：录音发回你的 Mac，由 whisper 本地转写（需先 `brew install whisper-cpp`，没装时手机会提示这条命令）。

### 图片附件

点 ＋ 选图，最多 **4 张**随一条消息发出——发报错截图、设计稿给 Claude 看都很顺手。

### 桌面接力

手机上聊到一半回到电脑？会话结束（关闭或空闲回收）后，直接在项目目录跑 `claude --resume`，手机上的会话就在列表里，原地继续。

## 四、设备管理

Settings 里可以**解除当前手机的配对**；解除后这台手机需重新扫码才能再连。（管理多台已配对设备的功能还在路上。）

## 五、故障排查

| 现象 | 处理 |
|---|---|
| 手机显示离线／连不上 | 确认电脑开机在线；重启服务：`launchctl unload ~/Library/LaunchAgents/dev.ccpocket.daemon.plist && launchctl load ~/Library/LaunchAgents/dev.ccpocket.daemon.plist` |
| daemon 找不到 claude | 终端确认 `claude --version` 正常；自定义路径用 `cc-pocket-daemon run --claude-bin /path/to/claude`（或设 `CC_POCKET_CLAUDE_BIN`） |
| 语音转写报「未安装 whisper」 | 在 Mac 上 `brew install whisper-cpp` 后重试 |
| 想看 daemon 日志 | 停掉服务后前台跑 `cc-pocket-daemon run`，日志直接打在终端 |
| 配对码过期 | 重新跑 `cc-pocket-daemon pair`（每次生成限时一次性码） |

## 六、卸载

```bash
launchctl unload ~/Library/LaunchAgents/dev.ccpocket.daemon.plist
rm ~/Library/LaunchAgents/dev.ccpocket.daemon.plist
brew uninstall --cask cc-pocket
```

手机端直接删 App 即可。

---

疑问与反馈：[GitHub Issues](https://github.com/heypandax/cc-pocket/issues)
