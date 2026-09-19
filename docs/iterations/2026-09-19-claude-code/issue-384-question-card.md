# #384：回放提问卡支持完整阅读

来源：[Issue #384](https://github.com/heypandax/pairlet/issues/384)。批次 1；建议 P2。状态：方案，未实施。

## 目标与已知事实

长问题默认可折叠，但用户能展开阅读完整正文，再收起继续浏览会话。未作答回放与已作答回放均能读全；实时问题的回答提交链路保持原有语义。

当前 `QuestionsUnansweredRow` 将正文设为 `maxLines = 3`、`TextOverflow.Ellipsis`，没有展开状态和操作入口。组件位于 commonMain。该源码缺口已确认；Issue 截图的准确平台／版本及手机实际表现仍待验证。

入口：

- [QuestionCard.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/QuestionCard.kt)：未作答与已回答回放、现有实时卡片。
- [QuestionCardUiTest.kt](../../../mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/ui/QuestionCardUiTest.kt)：含 LazyColumn／无限高度宿主回归。
- [TightText.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/theme/TightText.kt)：同排小字对齐规则。

## 实施方案

1. 先找所有回放调用点及消息稳定 key，确认正文完整到达组件。用超过三行的中英文样例重现；记录短文本、长单词／路径、多段落三种布局。
2. 建议默认保持三行摘要；正文真实发生溢出时显示文字按钮“展开”，展开后显示全文及“收起”。溢出判断依据实际文本布局，不以字符数估算；宽度、字体或正文变化时重新计算。
3. 展开状态按回放消息身份保存；列表复用、切换会话后不能把上一条的状态套到另一条。先使用现有稳定消息 key，不为局部显示偏好新增协议字段或数据库。
4. 展开内容自然参与外层聊天列表滚动。不要给 LazyColumn item 增加无限高的内层纵向滚动或错误的 `weight`。极长正文必须仍可到达结尾并可收起。
5. 已回答折叠行沿用现有交互，只核对展开后的问题／答案是否完整；确有同类截断才修正。不要重做实时问答提交、多选或审批交互。
6. 新增中英文资源，沿用已有卡片色彩与字号；同排按钮文字、徽章及不同字号文本遵守 `tightCenter`。可访问语义能区分展开／收起，桌面键盘及触屏均能触发。

## 验收

- 短问题完整显示，不出现无意义的展开按钮。
- 长问题默认摘要；展开后能读到最后一段，收起恢复摘要，没有重新发送答案或请求。
- 中英文、长路径、窗口缩放、手机横竖屏下无重叠、裁切或列表崩溃。
- 快速切换两条长问题和两个会话，展开状态不串项。
- 未作答／已回答回放都能完整阅读，实时待回答卡仍能正常回答。
- 桌面与手机各做目验；确实修改状态／无限高度布局时，在现有 UI 测试中添加对应行为回归，避免只断言实现中的 maxLines 数值。

测试入口与环境要求见 [VALIDATION.md](VALIDATION.md)。不涉及 daemon、relay 或转写格式；随客户端发布后才具备用户验收条件。

## 结果记录（实施后填写）

- 根因与最终交互：`QuestionsUnansweredRow` 把回放正文写死在 `maxLines = 3` + `TextOverflow.Ellipsis`，没有任何展开入口，长问题只能读到前三行。改为默认仍是三行摘要，由 `onTextLayout` 的 `hasVisualOverflow` 判断真实溢出后才追加一个「展开」文字按钮（`Role.Button` + 动作标签），展开后 `maxLines = Int.MAX_VALUE` 显示全文并换成「收起」；短问题不出现任何按钮。展开状态用 `rememberSaveable(text)`，复用聊天列表既有的每条消息稳定 key（`ChatRow.Original` 的 `m:<sourceKey>`，由 LazyColumn 的 saveable 作用域承载），没有新增协议字段或数据库；正文本身作为第二重输入，槽位被别的问题复用时自动回到折叠。展开内容为自然高度，参与外层聊天列表滚动，不加内层纵向滚动也不加 `weight`。展开时徽章改为顶部对齐，避免长文里徽章悬在段落中间。三处 `Text` 全部保留 `tightCenter`。
- 修改／提交：未提交（按任务要求只改工作树）。`mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/QuestionCard.kt`、`mobile/composeApp/src/commonMain/composeResources/values/strings.xml`（新增 `questions_unanswered_expand` / `questions_unanswered_collapse`）、`mobile/composeApp/src/commonMain/composeResources/values-zh/strings.xml`（展开／收起）、`mobile/composeApp/src/desktopTest/kotlin/dev/ccpocket/app/ui/QuestionCardUiTest.kt`（新增三条行为回归）。已回答折叠行核对后未发现截断：展开区的问题与答案两处 `Text` 都没有 `maxLines`，长文会自然换行，故未改动。
- 实际验证的平台、用例及结果：桌面 JVM 测试 `./gradlew.bat :mobile:composeApp:desktopTest --tests '*QuestionCardUiTest'`，BUILD SUCCESSFUL，9 个用例全过（`tests="9" skipped="0" failures="0" errors="0"`）。新增用例：长中文提问在无限高 `verticalScroll` 宿主里默认折叠、展开后正文高度翻倍以上、收起回到原高度；短提问不出现展开／收起入口；`LazyColumn` 中两条长提问只有被点的那条展开，另一条高度不变且仍带自己的入口。断言走的是可见正文高度与入口存废，不是 `maxLines` 数值。
- 未执行项／发布状态：手机与桌面目验未执行（本机无设备／未启动 App）；窗口缩放、手机横竖屏、长路径与英文长词的目视检查同样未执行。未跑 `check-all.sh` 全量（按任务范围只跑指定用例，整个 desktopTest 源集已随编译通过）。未提交、未推送、未发布，发布后才具备用户验收条件。
- 提交状态（2026-09-19 补记）：已提交到 main，提交 `a1225145`；未推送、未部署、未发布。上文“未提交”指子任务实施当时的状态。

## 2.1.1 发布补记（2026-09-19）

已发布，已关闭；[GitHub 状态回执](https://github.com/heypandax/pairlet/issues/384#issuecomment-5744959823) 已写回并核对。完整构建、生产部署和剩余验收范围见 [2.1.1 发布记录](RELEASE-2.1.1.md)。上文未推送／未发布等描述保留为当时的实施快照。
