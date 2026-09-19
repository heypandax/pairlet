# #385：Linux deb 启动段错误

来源：[Issue #385](https://github.com/heypandax/pairlet/issues/385)。批次 2；建议 P1。状态：诊断与条件修复方案，根因未确认。

## 用户结果与已知事实

Debian Testing x86_64 上安装 2.1.0 deb 后，执行 `/opt/cc-pocket/bin/CC Pocket` 直接段错误。目标是正式安装包能启动桌面窗口、进入基本界面并正常退出；开发态 Gradle 运行成功不足以验收。

当前 Linux 脚本验证 deb/rpm 内的 launcher、bundled JVM、desktop 文件和菜单注册脚本，未证明安装后的 GUI 能启动。#379 的“产出 Linux 包”已完成，不能据此关闭运行时故障。

缺少：Debian Testing 日期／版本、内核与 libc、显卡／驱动、Wayland 或 X11、实际包名和 SHA256、崩溃栈。JVM、Skiko、JavaFX、系统库均只是排查分支，不是已确认根因。

## 代码与工具入口

- [release-desktop-linux.sh](../../../scripts/release-desktop-linux.sh)：本机原生架构打包、payload 验证。
- [composeApp/build.gradle.kts](../../../mobile/composeApp/build.gradle.kts)：Compose Desktop、JavaFX 平台依赖、JVM 参数、jpackage 模块。
- [release.yml](../../../.github/workflows/release.yml)：`linux-desktop` 构建／上传；含只重建 Linux 桌面包的入口，但本轮不触发发布。
- [HtmlPreview.desktop.kt](../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/ui/HtmlPreview.desktop.kt)：仅当栈指向 JavaFX／WebKit 时进一步检查，不先认定它是启动原因。

## 诊断与修复顺序

1. 在隔离 Debian Testing x86_64 环境记录包及系统版本，对照用户反馈。验证下载 SHA256 和包架构，再安装该版本；保留安装／启动日志与时间，不用重新构建的未知包替代故障包。
2. 直接启动安装目录中的 launcher，记录退出码、stderr；检查 `hs_err_pid*.log`、系统 coredump。对 launcher 及崩溃栈涉及的本地库检查 ELF 架构、动态依赖与缺失项。必要时用 debugger 获取 native backtrace，原始 core 留本地，不提交仓库。
3. 分离故障层：launcher 是否成功进入 JVM；包内 JVM 自身是否可运行；进入 Compose 前后哪个 native 库首先加载失败；窗口系统初始化是否相关。对比同一环境中 app image 与 deb 安装结果，开发态只用于缩小差异。
4. 一次只改变一个变量：图形会话、显示服务、渲染后端或库依赖。软件渲染／Xvfb 只能作为诊断对照；变成默认兼容策略前需证明用户环境可用及性能影响。
5. 根据证据选择补丁：
   - 打包漏文件／架构错配：修正依赖 classifier 或打包输入，加针对性 payload 检查。
   - 系统运行库缺失：在包元数据声明所需依赖；不要要求用户猜测安装一串无依据依赖。
   - bundled JVM／native ABI：选最小且受支持的 runtime 或依赖修正，验证相邻平台；不默认升级整个 Compose 工具链。
   - 图形后端缺陷：用最小应用确认，实施有条件的 fallback／修复；不无条件禁用所有平台硬件加速。
   - 若属于上游包／驱动问题：给出准确复现、受影响范围及可验证绕行，不仅添加“请升级系统”提示。
6. 为根因增加安装包启动 smoke；有显示环境时验证窗口出现并存活、可退出。仅断言进程运行几秒或 headless 失败已消失，不足以证明 GUI 可用。

## 验收与发布条件

| 项目 | 要求 |
|---|---|
| 报告环境 | Debian Testing x86_64，原问题可复现，修复包不再段错误 |
| 实际安装 | deb 安装、菜单启动、命令启动、正常退出、再次启动 |
| 最小功能 | 无需配对即可打开界面／Demo；文件预览如受改动影响也要验证 |
| 交付范围 | 修改公共打包逻辑时核对 rpm 和已有 arm64 构建；不能将未验证平台写成通过 |
| 数据兼容 | 已有设置与本地数据仍可读，不靠清空用户目录解决崩溃 |
| 回归门禁 | 崩溃对应检查应在故障产物上失败、修复产物上通过；避免只验证包结构 |

当前 Windows 工作机不足以完成 Debian GUI 验收。接手者应优先使用已有隔离 Linux 环境；无环境时可完成源码定位与测试设计，但明确标记用户现场未验收。生成测试包不等于获准替换 GitHub Release 资产。

## 结果记录（实施后填写）

- 发行版、显示环境、故障包 SHA256 与栈摘要：待填写。
- 根因／排除项及最小补丁：待填写。
- deb/rpm/架构验证与未验证项：待填写。
- 提交、候选包 SHA256、发布建议：待填写。
