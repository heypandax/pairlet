# #320：先说明未知，再补有依据的历史数据

需求：[Issue #320](https://github.com/heypandax/pairlet/issues/320)。2026-09-14 已读正文与全部 4 条评论；旧版本两次关闭／重开不等于当前全部路径已修。基线 `d7f06c17`。A 为常规／M／中风险，B 为困难／M／中风险，全部交由 Claude Code。

## 已实现，不重做

`07e3c672` 已补 OpenCode 最新可信请求的缓存用量与 ZCode 最后一次请求的输出口径。不能重新按整轮／全部工具调用累计上下文，不能用账单 token 数代替上下文占用。

当前可验证线索：

- [DshBackend.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshBackend.kt) live 模型来自 config options 读回，`usage_update` 的 used/size 提供上下文占用／窗口。不能把 live 已有能力写成“DSH 完全不支持”。
- [DshTranscriptScanner.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/dsh/DshTranscriptScanner.kt) 列表主要恢复标题、首问、cwd 等，正常 SessionSummary 目前没有填 model。历史字段是否可可靠恢复须另查原生格式，不能从当前默认配置推旧会话模型。
- [ZCodeTranscriptScanner.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/zcode/ZCodeTranscriptScanner.kt) 会恢复 model，并有 model_usage 的账单查询；有账单表不代表能拿到最后一次上下文占用。
- 手机 [SessionSheets.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/SessionSheets.kt) 对非 Claude 未知模型已有“未知”，ContextBar 对缺值主要显示 `—`；[ContextGauge.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/ContextGauge.kt) used=null 时隐藏。
- 桌面 [ChatPane.kt](../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/ChatPane.kt) 无 contextUsed 时头部不显示 context。现有空白不告诉用户是未返回数据，还是 0。

## A：当前就能实施的状态说明

不新增猜测数据或后端 capability，先共用一个纯展示模型（建议 `ContextStatusUi`）：

| 现有可信字段 | 展示 |
|---|---|
| used 与正数 window 都有 | 当前已用／窗口及现有比例；真实 used=0 仍是有效数据 |
| 只有 used | 显示已用数量；注明窗口上限未知，不算百分比 |
| 只有 window | 显示窗口上限；注明尚未获得已用量，不当作 0 |
| 两者都无 | “尚未获得上下文数据”；详情说明后端未返回可用值，不能由此判断不支持 |
| 模型未知 | 沿用“未知”；不用 default 或当前设置替代历史模型 |

在手机现有会话信息面板落实上述说明；不要在窄屏输入条强行加大块常驻文案。桌面在已有会话头部／信息入口给同样可发现的状态说明；如需新增最小详情入口，沿现有 Popover 视觉与快捷键模式，不新建整套设置页。

“尚未获得数据”是当前能证明的事实；只有将来收到明确 capability/状态原因，才显示“此后端不支持”“历史记录不含”等更细原因。不要凭 AgentKind 静态写死不支持。手动填写窗口上限若已有入口，继续明确为用户覆盖值，不把它标成运行时证据。

本阶段不改 wire、后端、计费口径、模型切换或历史解析。复用中文／英文资源，Text 与图标／胶囊同排均用 tightCenter；测试用语义和用户行为断言，不按布局实现逐行写测试。

文件域：新增共享状态格式化／视图辅助类、SessionSheets、必要的 ContextGauge 详情接线、桌面 ChatPane 和资源／UI 测试。与 #380 的 ChatPane 修改串行整合。

验收：null 与 0 区分；只知分子／分母不造百分比；六后端相同证据呈现一致；旧 daemon 的缺字段保持可读；字号放大和窄屏不挤压发送／停止按钮。已有真实数据显示和未知模型逻辑不回退。

## B：后端历史补齐的有界诊断

先制作按 **Agent × 新建／恢复／继续一轮／重连** 的能力表，每格记录模型、已用量、窗口、来源、时间和缺失原因。只读本机已安装的 CLI/schema/转录结构；如需真实 provider 新任务，先判断用户是否授权该账号与成本。只保留脱敏的字段级 fixture。

建议先研究 DSH 历史模型，再研究 DSH/ZCode 历史占用：

1. 查明原生转录是否包含明确的 session model／runtime config 或最后请求的 context 证据，区分 header 初值、后续变更与实际生效值。
2. 有完整可信字段时，以最后一个有效运行事件为准；忽略子任务、标题生成等辅助请求，不能把它们的模型／用量覆盖主会话。
3. 只有部分字段时返回已知部分；缺缓存字段不能填 0，缺 window 不能按模型名称猜容量。中途 compact 后应接受较小的新占用，不能取历史最大值。
4. 新 live 证据优先于旧历史；恢复／切会话时清理上一个会话值；重连的旧事件不能回盖当前状态。
5. 如需扩大扫描，从现有流式／缓存机制入手，内存有界并正确按文件变化失效，不能每次打开列表全量解压所有会话。
6. 若原生格式没有所需证据，保留未知并在能力表标出。不要修改用户原生数据库来“补齐”，也不要为本单升级所有 CLI 或添加静态容量表。

B 的文件域按取证结果限定为对应 scanner／history adapter、Conversation 状态接线及聚焦回归；无证据时不改 backend。改 serialized runtime metadata 时再补 wire 兼容专项评审。

## 验收与退出条件

A 可以独立交付为“未知状态说明完成”；B 必须逐 backend 以可复现 fixture 证明新建／历史／live 替换，不能把 A 的说明改进算作数据恢复完成。真实反馈者使用的是旧 Windows 版本，当前开发机成功不等于反馈者验收。#320 整单仍包含未获得证据的残余部分，发布前不自动关闭。
