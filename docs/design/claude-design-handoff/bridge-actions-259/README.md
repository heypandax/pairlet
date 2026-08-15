# 桥接卡片操作区语义重整（issue #259）

- 在线设计板：https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FBridge+Actions.html
- 文件：`bridge-actions.jsx`（像素规格源码）、`Bridge Actions.html`（画布壳）
- 生成：2026-08-15，Opus 5 Max，追加模式（未重喂设计系统）

## 定稿要点

- **两层结构**：重启／停止／编辑（或 启动／编辑）保留 chip 形态；破坏性动作脱离 chip 物种——文字级 danger 动作，置于**通栏 hairline 分隔线**下的 footer 区，右对齐，手机 44dp／桌面 42dp，三种状态位置恒定。
- **改名**：「撤销」→「解除桥接…」（英文 “Disconnect…”），**省略号是承诺的一部分**（表示会先弹确认）。credential 措辞留在确认弹层里说。
- **视觉**：danger 只作 ink＋13pt 警告三角 glyph，永不作填充（填充 danger 只属于弹层确认钮）；hover 加 10% danger 底色。
- **否决项**：「…」溢出菜单被否——把后果藏深一层，且 self-run 卡菜单里只有一项。
- **state c（自行运行）**：无流程 chips，提示行「此桥接由你自行运行适配器。」在分隔线上方；footer 是全卡唯一控件。
- **桌面端**：同两层同角落，chips 32dp；解除动作**必须补确认弹窗**（居中 dialog，danger 图标＋mono 桥名＋两行后果＋取消/填充 danger 确认），hover 不变成一击即毁；Tab 序 chips→破坏性动作最后，Enter 打开弹窗时焦点在「取消」。

## 落地状态

- 实现：BridgesScreen.kt（手机）＋ BridgesPane.kt（桌面）＋ strings zh/en，见 issue #259 对应 commit。
