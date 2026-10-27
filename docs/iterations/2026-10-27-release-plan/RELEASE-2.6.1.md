# 2.6.1 发布准备与验收清单

更新日期：2026-10-27。状态：集成候选已完成本地构建和静态检查，准备草稿 PR 与候选 CI；尚未合入 main、创建正式标签、上传商店或发布安装包。中国区已由用户确认恢复可见。域名迁移另见 [综合方案](PLAN.md)。

## 候选范围

- [PR #412](https://github.com/heypandax/pairlet/pull/412)，精确来源 `69c30c99d7049e1f8266f13c076d26d422b71dcd`：Codex 用量与现有账号的额度重置卡、手机请求兼容电脑更新。
- [PR #413](https://github.com/heypandax/pairlet/pull/413)，精确来源 `31989d1b746de56a4aa0e766f819e011c86c82c6`：DSH 模型保留 provider，指定模型应用失败保留消息、不改用其他模型发送，回复复制按钮移至末尾。
- 隔离集成分支 `codex/release-2.6.1-prep`；两个 PR 的本地合并提交为 `03d940a7`。草稿 PR 汇总既有功能提交和本次准备改动；合并前核对该分支的实际 head 与检查结果。
- 新增发布前门禁回归与 Homebrew 候选生成；中英文商店说明及审核备注已覆盖实际 2.6.1 功能。
- 国内入口配置和过渡资料独立保存。本版不修改默认 relay、持久化配对、旧更新地址或后台部署。

## 版本与发行材料

| 项目 | 当前候选 |
| --- | --- |
| daemon、移动端、桌面、iOS marketing version、Harmony | 2.6.1，源码门禁通过 |
| Android versionCode | 43，上一正式版为 42 |
| iOS 本地 fallback build | 32；正式上传使用 `ios-release` 的 run number，不能预先把本地值当作 ASC build |
| Homebrew | 保留已发行 2.6.0 的准确双架构 hash；新资产齐备后才推进为 2.6.1 |
| iOS What's New | `fastlane/metadata/en-US/release_notes.txt` 与 `zh-Hans/release_notes.txt` |
| 审核备注 | 保留免配对 Demo 入口、数据披露与诊断说明，补充重置卡和电脑更新的实际行为；未超过 4,000 字符 |

Homebrew 新工具同时核对目标包实际字节与 `SHA256SUMS`，拒绝缺架构、篡改包、单改版本和同版换 hash。完整发布工作流在 `checksums`、`sign-manifest` 两项任务成功后生成候选 artifact；它不自动推送 tap。签名任务成功本身不证明签名密钥已配置，仍需按 [发布手册](../../RELEASE.md) 检查实际输出。

## 已完成的验证

| 检查 | 结果与边界 |
| --- | --- |
| 两个 PR 联合测试 | 初次 6,083 项：6,061 通过、1 失败、21 条件跳过。唯一失败为 worktree 在 `/tmp` 引起的 TempDirsTest；移至正常缓存目录后该类 3/3 通过，源码未改。详细来源见综合方案 |
| 发布工具测试 | 本机 114 项中 86 通过、28 因系统 LibreSSL 跳过；将精确候选脚本和 fixture 复制至个人 Mac 独立临时目录，使用 OpenSSL 3 补跑 28/28 通过。新 Homebrew 用例 13/13 通过 |
| Android 构建 | `:mobile:androidApp:assembleDebug` 通过，使用与 CI 一致的 Firebase 占位配置；不是生产签名包或分析上报验收 |
| iOS 编译 | `:mobile:composeApp:compileKotlinIosSimulatorArm64` 通过；不代表 Xcode 归档、安装或交互验收 |
| App Store 完整材料 | 2 个语言、18 个字段、审核备注、12 张 iPhone 和 12 张 iPad 截图、2 个预览通过；在个人 Mac 临时目录对本次候选精确副本执行，未改个人主仓、未上传 Apple |
| 发布/品牌/内容 | 2.6.1 版本门禁、Homebrew 发行记录、品牌兼容、Harmony 静态契约、仓库内容及暂存格式检查通过 |
| GitHub Actions 语法 | 修改的 `ci.yml` 与 `release.yml` 经 actionlint 通过；配置已有自托管 `harmony` 标签，未将它误报为未知托管 runner |

本机构建使用 Temurin 17；运行脚本测试需 Python 3.10+（本次使用 3.12），签名测试需要支持 Ed25519 的 OpenSSL。原始日志和恢复回执保留在忽略的 `_local/release-plan-20261027/`，不进入仓库。

## 正式发布前仍需通过的检查

- [ ] 草稿 PR 的最终集成 head CI 全部通过；若来源发生变化，重新核对并定向验证受影响部分。
- [ ] 多平台候选预演通过，记录实际提交和 Actions 链接；候选构建只能证明编译、打包与包内配置检查。
- [ ] iPhone 验证首次隐私同意、免配对 Demo、DSH 指定模型失败保留消息、回复复制、Wi-Fi/蜂窝切换及推送。额度卡先验证模拟结果、丢回执和同一幂等键重试，不能为测试消耗真实卡。
- [ ] 电脑更新在受支持的隔离托管安装中验收进度、恢复和失败回退；本机 daemon 的其他会话不能被擅自中断，不起第二个实例。
- [ ] 按拒审检查表核对实际界面、最新年龄问卷、隐私接收方与元数据。实际功能披露保留，不通过删词掩盖服务。
- [ ] 合入 main 后，等待该提交的 CI，通过后创建不可变 `v2.6.1`，从同一标签构建正式资产。
- [ ] 验证 macOS 双架构签名/公证、Android 原签名覆盖升级、Windows/Linux 安装与 daemon 包内版本、最终 hash；回写 Homebrew 发行记录并同步 tap/Scoop。
- [ ] 旧域下载镜像与更新清单确认已同步 2.6.1；安装脚本、历史包和旧连接入口保持可达。
- [ ] iOS 记录真实 build，依次核实上传处理、审核提交、批准和可下载，不把 VALID 当作上架完成。

## GitHub Release 中文说明草案

2.6.1 完善手机与电脑之间的使用体验：

- Codex 用量详情展示账号可用的额度重置卡，使用前需明确确认。
- 手机设置支持请求更新兼容的已配对电脑，并显示进度与恢复状态。
- DeepSeek Harness 模型选择保留提供方；指定模型无法应用时保留输入，避免发送到其他模型。
- Agent 回复的复制入口移到正文末尾，便于读完后复制。

建议手机与电脑同时更新。额度重置卡取决于现有账号支持情况。旧版连接与更新入口继续可用，新域名迁移将另行安排。本说明仍是草案，正式发布时补充最终安装包和已验证的平台信息。
