# #385：Linux deb 启动段错误

来源：[Issue #385](https://github.com/heypandax/pairlet/issues/385)。批次 2；建议 P1。状态：已复现启动器 SIGSEGV 并完成候选绕行修复；报告者原机、其他架构与正式发布待验证。

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

本轮已在 Windows 的 Ubuntu WSL2 内建立隔离 Debian Testing 用户空间，完成 deb 安装和 Xvfb 窗口验收。它仍使用 WSL 内核，不能代替报告者原机的显卡／桌面环境验证。生成测试包不等于获准替换 GitHub Release 资产。

## 2026-09-19 复现与候选修复

- 故障包：Release v2.1.0 的 `cc-pocket-desktop-2.1.0-linux-x86_64.deb`；SHA256 `1f0285db859bfcda31ff3b24012131731553fd24a3334d812c81b16e31ade559`。包内 runtime 为 17.0.20.1，classpath 有 118 个 JAR。
- 环境：Ubuntu 24.04 WSL2；另建 Debian Testing（forky/sid，libc6 2.43-5、libX11 1.8.13、Xvfb）。未更改系统 pipe 限制：测试进程临时持有空管道，使同 UID 达到 Linux 的 `pipe-user-pages-soft` 后，新建管道缩为两页（x86_64 为 8192 字节）；结束后全部关闭。
- 对照：Ubuntu 普通管道下原包通过 package smoke；8192 字节管道下原包返回 `-11`。GDB 栈为 `setenv → jvmLauncherStartJvm → main`，故障在 JVM 初始化前。Debian 中通过 dpkg 安装同一原包后也复现 `-11`。
- 归因：符合 [OpenJDK JDK-8380085 的修复](https://github.com/openjdk/jdk/pull/30254)。JDK 17 Linux 启动器对管道只做单次 read/write；长 classpath 的启动数据被部分读取后，未完整的指针表导致原生崩溃。这证明本包存在该故障路径；报告者尚未提供栈，不能断言其现场只有这一个原因。
- 修复：`scripts/compact-linux-classpath.py` 将原有有序 classpath 写入仅含标准 manifest 的 `cc-pocket-classpath.jar`，launcher cfg 只保留这一项。原始依赖 JAR、JVM、主类、JVM 参数和渲染设置不变。Linux `createDistributable` 的后处理保证 deb/rpm/AppImage 共用该结果；manifest 按 JAR 规范转义 URL、折行并保留顺序，重复运行会校验已有结果。
- 回归：`scripts/smoke-linux-launcher.py <app-image 或 deb>` 在上述小管道条件下要求真实 packaged JVM 写出成功标记；原包失败、候选包通过。发布脚本、preview CI 和 AppImage 验证已接入。该检查定位启动器故障，不冒充 GUI 测试。
- GUI 验证：Debian 内安装候选 deb 后，在 8192 字节管道条件下正常启动两次；Xvfb 中配对界面截图可见，发送 `WM_DELETE_WINDOW` 后均以 0 正常退出。Ubuntu WSLg 中也观察到候选窗口。四项 Python 测试在 Windows 与 Ubuntu 通过，覆盖 classpath 顺序、空格／中文／URL 特殊字符、manifest 折行、重复执行、缺文件和不支持路径；shell 语法与仓库内容检查通过。
- 候选包：`cc-pocket-desktop-2.1.0-issue385.1-linux-x86_64.deb`，Debian 包版本 `2.1.0+issue385.1`，SHA256 `d143941e38948413fdf748070ed1d03dbf4ea006f34191287dd3443c1e63dbc4`。它是从原版 deb 提取后，仅应用上述打包修复并更新包版本／md5sums 的诊断重打包；不是当前完整源码重新编译的正式版本，App 内版本仍为 2.1.0。
- 原始日志、截图、实验脚本和候选 deb 留在已忽略的 `_local/issue385/`，不提交大文件或原始 core。未替换 Release 资产、未关闭 Issue。
- 发布安排（2026-09-19 用户确认）：随下一正式版本一起发布；本次仅提交修复与回归检查，不单独发布诊断候选包或替换 v2.1.0 资产。新版本发布前仍需完成下列平台验证。
- 未验证：报告者原机；原生 Debian 内核／硬件渲染；完整 Gradle Linux 构建链；rpm 安装、arm64、AppImage 的实际运行；桌面菜单交互和连接后的完整功能。正式发布前需用 CI 生成包补齐受影响平台验证，并请报告者用候选包确认。
