# 推送故障诊断（2.1.2 测试包）

需要从反馈继续补日志、构建并交付给用户时，先按[反馈排障与测试版本交付](FEEDBACK-DIAGNOSTICS.md)选择范围和完成标准。

此改动补充诊断，不宣称已经修复 #389 的设备故障。手机与 relay 分别安装包含本改动的构建后，各自新增日志才生效；只更新手机也能确认 APNs token 获取结果和既有 relay 的登记回执。daemon 无需为本次诊断改动更新。

## 给复现用户的操作

1. 更新测试包，在设置 → 诊断中确认诊断分享已开启（尊重原开关，不自动开启）。
2. 回到通知设置查看状态；失败时点重试，复现一次。不要连续快速切换开关。
3. 在退出 App 前进入设置 → 诊断 → **复制推送诊断**，把文本发回。文本只含本次启动最近 64 条安全技术结果，不含 token、稳定 token 哈希、账号、设备 ID、消息内容或路径。

记录仅保存在进程内，重启会丢失；关闭诊断立即清除并停止记录。保留条数有界，不依赖云端上传是否成功。复制动作不发送数据。

## 查哪里

Sentry iOS 项目同时查 Logs 和 Issues，条件 `error_path:EP-28`。新消息直接包含阶段，例如 `EP-28:push_token:network_failed`，不再只看 `unavailable`。

复制文本里的 `push_trace_id` 对应云端 `diag_trace_id`，可关联同一次启动的原生回调、登记和重试记录。它随机生成、关闭诊断后更换，不是用户或设备标识，也不是 relay wire requestId。多配对在同一启动下可能交错，不能据它把某条记录认定为某个账号。事件自身仍有独立 `diag_event_id`。

| 阶段 | 关键结果 | 含义 |
| --- | --- | --- |
| `configure` | `enabled` / `disabled` | 手机 App 内通知偏好，附当前 relay/direct 路径；与系统权限和每条连接的有效登记意图分开 |
| `push_authorization` | `authorized` / `provisional` / `ephemeral` / `not_determined` / `permission_denied` | iOS 实际权限读数 |
| `push_authorization` | `native_failed` / `network_failed` / `bridge_missing` | 授权请求或桥接失败 |
| `push_token` | `started` | 发起 token 请求（协调器与原生入口分别观察，可能各一条） |
| `push_token` | `token_production` / `token_sandbox` | APNs token 已回调及环境；不记录 token 值 |
| `push_token` | `token_received` / `token_rotated` / `late_callback` | 协调器接收、变化、在阻塞或冷却期间收到回调；不保证能把无 requestId 的 APNs 回调绑定到某一次请求 |
| `push_token` | `network_failed` / `native_failed` / `unsupported` / `timeout` | 原生失败分类或前台等待超时 |
| `push_register` / `push_clear` | `started` / `sent` / `stored` / `cleared` / `confirmed` | 提交意图、已写 socket 的结果、匹配请求的 relay 回执及最终确认；stored 不等于通知已投递 |
| `push_register` / `push_clear` | `ack_timeout` / `send_failed` / `no_route` | 确认超时、发送失败、等待可用连接 |
| `push_register` / `push_clear` | `store_failed` / `not_found` / `bad_request` / `forbidden` | relay 明确返回的失败原因 |
| `push_register` / `push_clear` | `ack_mismatch` / `superseded` / `reconfirming` | 过期或不符回执、请求被取代、补确认 |
| `push_token` / `push_register` / `push_clear` | `retry_exhausted` | 对应阶段重试耗尽；包含次数，错误事件携带最近安全步骤 |
| `push_presentation` | `settings_observed` | 系统提醒、锁屏、通知中心及声音设置（enabled/disabled/unsupported/unknown）；授权成功不代表允许横幅 |
| `push_presentation` | `foreground_presented` / `foreground_suppressed` / `opened` | 前台返回展示选项／当前会话主动隐藏／用户点开；不记录通知内容或会话 ID |
| `dispatch` | `no_token` | relay 没有目标，在请求 APNs 前跳过 |

原生失败保留允许表中的 `native_error_domain`（url/cocoa/posix/mach/other）及有界 `native_error_code` 数字；不收集 NSError.description、userInfo 或任意 domain 字符串。relay SQL 异常可记录 sqlite 类别和数字错误码。Sentry Logs 与 Issues 的数值字段分别在日志属性和 diagnostic context 内。

单次重试及成功事实以 Logs 记录，不受成功 trace 的 1% 采样影响；仍遵守现有日志速率、每日预算和分享开关。原生错误与重试耗尽进入 Issues。云端无记录不能证明未发生；优先结合本地复制结果。

relay 服务器 `journalctl -u cc-pocket-relay` 增加 `[push-register]`：仅输出已有规则允许的截断账号/设备和固定阶段、结果，覆盖保存/清空、数据库失败、旧连接忽略、旧客户端无 ACK、ACK 已写出/发送失败。不输出 token 或异常原文。云端不带这些身份前缀。

## 验证边界

回归覆盖 token 失败与 relay 失败的区分、登记与清空证据、原生字段穿过 Sentry 适配器、脱敏、预算下本地记录及关闭后的清除。真实 APNs 签发与手机展示仍需测试包在受影响设备上复现；本任务不发送测试推送或重启生产服务。

后台横幅由 iOS 展示，App 没有可靠的“用户已看到”回调；前台 presented 仅表示已向系统请求 banner/sound。专注模式、摘要延迟等仍需受影响设备核对，不能从 APNs accepted 推导用户看到提醒。
