# #363：新建会话模式的保留与归因

需求：[Issue #363](https://github.com/heypandax/pairlet/issues/363)，包含 2026-09-13 “选择模型后模式变成每步确认”的补充；2026-09-14 已读全部正文与 1 条评论。困难／M／高风险。**先复现，定位后修复；不能把相关静态路径当作已证实根因。**

## 已完成与当前事实

- main 的 `1a4b992c` 已固定接受新建动作时的模型／后端选择，排队和失败重试沿用快照；不要重新写这一项。
- 手机 [ConfigureSessionSheet](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/entry/ConfigureSessionSheet.kt) 的模型回调只改 chosenModel。模式按 chosenAgent 记忆；`LaunchedEffect(chosenAgent, autoAvailable, modePresets)` 在 modeTouched=false 时重新 seed。
- 手机 [EntryUi.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/entry/EntryUi.kt) 的 seed 只有 openedAgent 能继承传入默认值；切 Agent 会使用该 Agent 的默认。完整权限确认基于 PermissionMode，不由 danger 展示标记决定。
- 桌面 [Popovers.kt](../../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/Popovers.kt) 已有 `carryModeAcrossAgents`：保存模式值对而不是行下标，能力变化时重新匹配。其跨 Agent 规则与手机不同，属于现有行为，不应顺手强制统一。
- 六后端模式并非同义：Claude 有可选 native Auto；Codex 有 advertised presets；Kimi/DSH 无 accept-edits；ZCode 对应自己的模式；OpenCode 自动模式是当前后端事实。不能按同一位置、标签或枚举强行互换。
- 既有 daemon open 日志已记录 mode／permissionMode／model／effort／origin。早期手机失败和桌面成功只是不同样本，不证明 relay 会丢字段。

## 必须保持的用户契约

1. **同一个 Agent 选择不同模型，不静默改变已选模式。** 模型清单刷新只更新选择候选，不能回写权限默认值。
2. 能力清单晚到／刷新时，当前模式若仍合法就保留；若已不合法，在面板显示原因并要求选择受支持模式，不静默升权或悄悄开局。
3. 点击“开始”时冻结将要发送的模式、nativeMode、模型、Agent、preset、目标电脑与 workdir。确认页展示并提交同一个冻结快照；后台能力／设置变化不能让用户确认 A 实际启动 B。
4. 全权限仍走已有确认。取消、返回、重复点击、双回调都不能启动第二个会话。能力变化使已确认快照失效时退回面板重新确认，不自动改值。
5. 恢复会话、会话中途切模式和跨设备设置同步不在本项范围。跨 Agent 行为维持现有合法策略；要统一它们须另定产品决策。

## 诊断步骤

先扩展既有 `MobileNewSessionUiTest`、`NewSessionModeCarryUiTest`、`EntryUiTest` 和 `NewSessionDefaultsSnapshotTest` 的行为回归，不先做整个新建流程重构。

| 变量 | 最小可区分场景 |
|---|---|
| 模型选择 | 同 Agent A→B；选“跟随默认”；模型列表重排／迟到 |
| 模式来源 | 初始默认；用户手选；native Auto 与普通 DEFAULT 分别处理 |
| 能力刷新 | 相同能力重复到达；新增合法行；当前选项移除；空列表后补齐 |
| 面板生命周期 | 打开／关闭／重开；宽窄布局切换；确认页期间刷新 |
| 起会话 | 双点击；先排队后创建；失败重试；切电脑／目录 |

测试捕获 `onPick/onStart` 参数以及 Repository 生成的 OpenSession，不调用真实 Agent。先分别钉住手机与桌面相同用户动作；需要真实复现时收集创建端版本、选前／选后画面、该端设置和对应 open 日志的**脱敏字段**，不把桌面设置当成手机设置。

最多先做一轮有界诊断：UI 行为回归 → 准确帧与 daemon launch 参数对比 → 尝试当前设备复现。若未找到失败，交付“不复现矩阵／缺少的现场证据”，保持未归因，不为了产出代码删除 modeTouched 或把所有默认都改成 BYPASS_PERMISSIONS。

## 条件修复方案

### 若证明能力重播种覆盖了仍合法的选择

将“首次 seed”和“能力 reconcile”分开。reconcile 先查 `(mode, nativeMode)` 的实际值是否仍在当前 Agent 的合法集合，不按列表下标匹配。合法即原样保留；不合法进入显式未选／不可提交状态，面板说明能力已变化。用户点击模型不应增加、减少或伪造权限授权。

可以提取小型纯函数／reducer（建议 `NewSessionSelection`），输入 Agent、模型选择、ModeChoice、能力 revision 和用户动作；输出选择与 validity。只在证明它能消除该竞态时引入，不以测试方便为由重构全部面板。

### 若证明确认期间快照漂移

保存 `PendingStart`（仅客户端、不可变），FullAccessConfirm 从它读文案，确认后提交它。取消清除此对象但保留编辑选择；新能力使它无效则取消确认、显示原因。Repository 已有的排队／重试快照继续使用，避免出现两份互不相干的默认读取。

### 若 UI／OpenSession 已正确，但 launch 不一致

沿 RequestRouter → SessionRegistry → 目标 backend 的 LaunchSpec 查模式转换。仅修有证据错误的映射或字段丢失，复用既有模式表与权限门。若只是客户端和 daemon 版本不一致，明确版本兼容事实，不通过弱化审批“解决”。涉及 PermissionBridge 或权限转换时必须独立安全评审。

## 文件域与验收

优先文件：ConfigureSessionSheet、EntryUi、Popovers、相关 UI 回归；只有证据指向提交／消费链时才进入 PocketRepository、RequestRouter、SessionRegistry 或某个 backend。与 #380/#320 的 Repository 或 strings 修改串行整合。

- 同 Agent 换模型后的 ModeChoice 值对、用户可见模式和 OpenSession 一致；覆盖六种 Agent 的合法模式集合。
- 能力到达不改变仍合法的默认／手选模式；失效选择不能开始，也不静默转换为更宽权限。
- 全权限确认 A 后实际传 A；取消不提交、双击只提交一次、队列与重试不偷读新的默认。
- 跨 Agent 原有明确策略有对照回归；新建修复不改变恢复／接管和中途模式切换。
- 修复前存在可证实失败，修复后通过。若仅建立了回归但未复现原问题，结论仍是“诊断完成，根因待证”，不是“#363 已解决”。

独立评审重点：nativeMode 丢失、模式下标复用、确认页漂移、把“保留选择”变成无确认升权、模型回调意外写设置。现场未知项留在原 Issue，不用扩大日志范围收集完整提示词或凭据。
