# 桌面分屏（issue #311）

主内容区同时放下多个会话：最左是聚焦会话，右边每个会话各占一列，每列都能独立滚动、独立输入、独立处理审批。上限 3 列（`MAX_SPLIT_PANES`）。

## 为什么不是「第二条连接」

daemon 对每台设备只保持一条 E2E 会话，第二次握手会让第一条链路失聪（见 `FleetCoordinator` 类注释）。所以同一台机器上的多个列**必须共用一条链路**，靠 `convoId` 分流，不能各开一个 `PocketRepository`。

好在下面两层本来就是多会话的，这次改动没碰它们：

- 协议层：`Messages.kt` 里收发两个方向的帧普遍带 `convoId`。
- daemon：`session/SessionRegistry.kt` 就是 `convoId -> Conversation` 的表，带按设备分流的 sink 与重连重挂。
- 客户端本来也在后台留着别的会话：切走时只有空闲会话才发 `CloseSession`，正在跑的留着继续跑。

缺的只是客户端把「当前会话」这一份状态变成多份。

## 结构

| 位置 | 职责 |
|---|---|
| `data/ChatTranscript.kt` | 从 `PocketRepository` 原样抽出的会话流构建（thinking 块、replay 回声去重、子 agent 卡片合并、history 回放映射）。repository 持有一份，每个分屏各持有一份，**只有这一份实现** |
| `data/SplitPanes.kt` | `SidePane`（一列的状态）与 `SidePanes`（帧路由、开/关/发/重挂） |
| `desktop/SidePaneModel.kt` | `DesktopModel by base`，会话相关成员答自本列，其余透传给外壳 |
| `desktop/SplitPaneView.kt` | 一列的渲染。用**真正的 `ChatPane`**，不是第二套聊天 UI |
| `desktop/DesktopApp.kt` | 主内容区按列等分排布 |

## 两个挂点，其余分支一行没动

`PocketRepository.handle` 里 30 多个帧分支都写着 `if (f.convoId == convoId.value)`，一个都没改。加的是：

1. `sidePanes.route(f)`：把属于某列的帧镜像进那一列。**从不消费帧**，原有分支照常执行，所以跨会话的审批收件箱、额度统计这类「与具体聊天无关」的记账全都不受影响。
2. `acceptsSessionLive` 开头一行 `if (sidePanes.claimsSessionLive(f)) return false`：分屏的 open 回来的是同一种 `SessionLive`，而聚焦侧的接受规则最后一条是「这里还什么都没开」的兜底。没有这行，在主区为空时开一列会把会话开进主区。

手机永远不开分屏，`route` 的第一行是一个普通整数比较（`openCount`，**不是快照状态**）。用快照 list 判空会推进全局快照、在别处不会发生的时刻冲掉待发的 apply 通知——实测会让组合器草稿在用户眼皮底下变空。

## 一列有什么、没什么

有：自己的会话流、自己的输入框、自己的审批卡、自己的重连重挂（按 `lastEventSeq` 只要增量）、open 8 秒不落地就报失败并给重试。

没有：改模型 / 改模式、compact/clear、Changes、Git、rewind/fork、附件。这些动词都作用于**聚焦会话**，放在侧栏里点下去会悄悄改到另一个会话——分屏最不能有的就是这种错。所以列头把它们换成两个属于列自己的动词：`聚焦` 和 `关闭此栏`（`DesktopModel.paneScoped`）。点「聚焦」这列就变成聚焦会话，全套能力当场解锁。

## 晋升为什么不做交换

「聚焦」= 一次普通的 `openSession(resumeId)`，daemon 用它已经持有的会话重挂，不 fork 不重启。让出主区的那个会话**不会**自动搬进空出来的列：`openSession` 在切换时本来就会回收空闲会话，同一口气再把它开成一列，等于让同一个会话的 `CloseSession` 与 `OpenSession` 同时在飞。把它送回列里只差一个手势（侧栏「在分屏中打开」），而那个手势是有序的。

同理，晋升时那一列走 `detach` 而不是 `close`——`close` 会发 `CloseSession`，和紧接着的重挂抢同一个会话。

## 拖拽分屏（issue #336 修订）

侧栏会话行按住拖到聊天区，悬停列**对半**分出两个放置区：左半 = 新列开在该列左边，右半 = 开在右边。相邻两个半区（A 列右半、B 列左半）表示同一落点「A 与 B 之间」，符合直觉。旧的三分区（左 30% / 中 40% / 右 30%）已移除：焦点列的左边缘兑现不了自己（高亮在左、列开在右），中间的「落下 = 切换会话」与分屏意图混在一个手势里。切换会话回归点击。

「开在左边」按渲染位置解，不动会话生命周期：`SidePanes.focusedSlot` 记焦点列排第几，拖左 = 新列插到位置 0、焦点列右移一格。新会话仍走旁路通道打开，无 CloseSession/OpenSession 竞态。晋升（聚焦某列）时焦点走到那一列的位置上，而不是把它的会话拽到焦点原来的位置。

## 已知边界

- 只做同一台机器。跨机器的列要等卫星会话流（`fleet.md` brief ③）。
- 等宽纵向等分，没有拖拽改宽、没有瓦片管理。拖拽只决定新列出现在哪。
- 手机端不做，行为逐字节不变。
