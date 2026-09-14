# #381：daemon 下载进度与下载链路

需求：[Issue #381](https://github.com/heypandax/pairlet/issues/381)。基线 `d7f06c17`，2026-09-14 核验，无评论或活动 PR。分成 A、B 两阶段：A 可实施；B 的供应商／地域／部署未决定。

## 已核验事实

- daemon [Main.kt / UpdateCmd](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/Main.kt) 输出版本与安装／重启阶段；调用 `UpdateService.apply`。
- [UpdateService.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/update/UpdateService.kt) 下载前只记录 `downloading`，再调用 `ReleaseClient.download`，之后校验、解压和切换版本。
- 共享 [ReleaseClient.kt](../../../protocol/src/jvmMain/kotlin/dev/ccpocket/protocol/update/ReleaseClient.kt) 使用 `HttpResponse.BodyHandlers.ofFile`，没有下载观察回调；HTTP 请求设置了超时，但改变 body handler 后仍需实测整个 body 的超时／取消，不能只凭参数存在断言可靠。
- `latest` 已尝试镜像 manifest，再退 GitHub；一旦选中某个资产 URL，`download` 本身没有资产失败后的第二来源重试。**元数据回退与资产下载回退不是同一步。**
- [install.sh](../../../scripts/install.sh) 已使用 `curl --progress-bar`，不能声称所有安装入口都没有进度；[install.ps1](../../../scripts/install.ps1) 是另一入口。本单先只解决 daemon 自更新。
- [mirror-sync.sh](../../../deploy/mirror-sync.sh) 已校验 release 文件并最后原子替换 latest.json，manifest 保留完整资产表。用户仍在选择 OSS 或 COS；没有用户机器测速证据。

## A：可先实施的 CLI 进度

目标：`cc-pocket-daemon update` 下载期间持续反馈“是否还在推进”，兼容无 Content-Length、终端重定向和后台自动更新。A 不切换下载供应商，不承诺提高带宽。

建议在现有共享 `download` 增加默认为空的观察器，保留旧调用方式：

```kotlin
// 建议接口，保持旧的 download(url, dest) 语义和调用兼容。
data class DownloadProgress(val receivedBytes: Long, val totalBytes: Long?)
fun download(url: String, dest: Path, onProgress: (DownloadProgress) -> Unit = {})
```

实现方式由 Claude Code 按现有 JDK HTTP 客户端选择：在接收流／BodySubscriber 中计数并写文件。不得先整包读入内存；有上限缓冲，正常结束／失败／取消都关闭流和文件句柄。进度观察器是展示附属能力，不应因 UI 回调异常破坏下载文件；明确隔离回调异常并用测试验证。

`UpdateService.apply` 接收默认无操作的阶段／进度回调，并由 UpdateCmd 注入终端渲染器。`UpdateChecker` 的后台自动更新使用安静实现，不能向 daemon 日志写高频回车动画。共享 ReleaseClient 的桌面调用方保持原行为；这是内部 JVM API 扩展，不是新增 wire 消息。

### 显示规则

| 场景 | 呈现 |
|---|---|
| TTY，可信 total > 0 | 百分比、已收/总量；可附平滑速率；限频约 200–500ms |
| 未知长度／chunked | 已下载字节和活动提示；不造百分比或 ETA |
| stdout/stderr 被重定向 | 独立行的低频阶段／进度，无 ANSI／回车残留 |
| 下载完成 | 完成该行并显示“校验中”；下载 100% 不等于安装成功 |
| 暂无新字节 | 显示等待网络；不每个 tick 增加已收数 |
| 失败／取消 | 收尾换行，保留清晰失败阶段；不显示“已更新” |

正文输出保持现有 stdout 契约；动态进度建议写 stderr。大小用字节计数，不用响应压缩前后不一致的值凑比例。若实际收到超过声明长度，改为未知总量并继续按 HTTP 客户端结果判断，不显示超过 100% 的假进度。

### 失败与资源契约

保留 10 分钟整体下载上限；测试不仅覆盖连接慢，也覆盖响应头已到达但 body 停滞。选择 JDK body API 时验证取消是否中止实际下载，不留下无限阻塞线程。总时限到达时关闭连接／输出并失败；失败文件仍位于本次临时目录，不切换 launcher。

不改变已有 checksum 行为：有校验不匹配必须失败。当前旧版本缺 sums 的警告降级是已有兼容政策，A 不静默放宽，也不顺带强制改成另一套发行政策。B 的新镜像流程要求 sums 完整，见下文。

### 文件所有权与验收

范围：ReleaseClient、UpdateService、UpdateCmd、必要的终端渲染辅助类；UpdateChecker 仅按需传安静回调。同步维护被改源文件对应的 `packaging/brand-compatibility.json` 哈希，保留全部旧包名／路径／服务标识。

用本地 HTTP fixture 验证：已知长度、未知长度、重定向、HTTP 404/5xx、body 中断、body 停滞、错误 Content-Length、回调限频、校验失败。验证旧两参数调用可编译、文件与源字节一致、缓冲有界；捕获 TTY 与重定向输出，不能只断言 callback 被调用。自动更新路径不刷动画日志。

不要为验证把正在运行的 daemon 升级到新版本，也不要重启服务。安装切换用临时目录和注入 seam；真实更新须后续有明确授权。Windows 现场进度可在有环境时补，缺现场时如实记录，不新增 Linux 验证。

## B：来源回退和对象存储方案

以下是供应商无关的推荐契约。先实现和测试来源选择的纯逻辑；OSS/COS 的实际接入、购买、上传与 CDN 部署等待用户选择。也可在不换供应商的情况下，先补“资产 URL 失败退到同版本 GitHub”的候选修复。

1. **先冻结 release 身份**：一次更新固定 `(version, assetName, expectedHash)`，全过程不再次查询 latest 换到另一个版本。候选来源是同一资产的镜像和 GitHub URL。
2. **有界回退**：连接失败、超时或明确缺失的镜像资产，转同版本 GitHub；源切换展示“更换下载来源”并重新计数，第一版完整重下，不先引入 Range／分块拼接。用户配置 MIRROR=off 时只用 GitHub。
3. **校验失败停止**：收到完整包但 checksum 不匹配，不静默换源掩盖来源污染；明确报错并保留脱敏诊断。重试旧半包不得绕过验证。
4. **发布契约**：保留当前 `latest.json` 的 version 和完整 assets map、既有资产名与 SHA256SUMS。镜像先下载固定 tag 资产 → 对照权威发布清单校验 → 上传版本化只读对象 → 逐项验证可读 → 最后原子／条件更新 manifest。不能先发布 latest 再慢慢补包。
5. **供应链边界**：新源必须有 HTTPS、发布者写权限与完整校验清单。旧字段消费者继续工作；未知字段可忽略。不能把来源 URL、存储服务错误正文里的临时签名参数或凭据写进普通日志。
6. **回滚**：保留旧源配置和前一版 manifest；切回来源不改客户端身份、安装路径或 service 名。不能改动正在运行的 daemon 来验证云端计划。

### 选型前需要的输入

目标地域、目前失败入口／系统／安装方式、同网络条件下已有镜像与 GitHub 的测量、供应商账号可用性、预算与是否已有自定义域名／CDN。国内可达性不能外推海外，单台开发机的速度不能外推反馈者机器。

### 提速验收

同一资产、相同网络与同类时间窗口，记录连接时间、首字节、总下载时间、失败率和最终 SHA256；样本有限就写有限。进度可见、下载完成、下载更快分别判定。对象存储名称不是提速证据。

### 后续评审重点

元数据与资产版本混用、回退循环、无校验切版本、secret URL 日志、部分文件被安装、manifest 提前可见、旧桌面调用方行为变化。触及校验／发布来源后增加独立安全评审；本次方案不授权部署。
