# 手机端订阅额度入口 · 设计交付

- 在线设计板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Mobile+Quota+Entry+v1.dc.html （项目：cc-pocket Design System 2.0）
- 生成对话：同项目内「Mobile Quota Entry」新对话（2026-08-24，Fable 5 起稿计划 → 配额 90% 触顶 → Opus 5 Max 完成构建）
- Brief 源：`~/Desktop/Brain/60_Outbox/2026-08-24-cc-pocket-手机额度入口设计提示词.md`

## 文件清单

| 文件 | 内容 |
|---|---|
| `Mobile Quota Entry v1.dc.html` | 主稿：三方向 × 各态（normal/warning/no-data）in-situ 402×874，含明细 sheet 衔接图与开放问题 |
| `QuotaEntryDevice.dc.html` | 组件：手机机身框架（复用件） |

## 三方向结论

- **A · 机器状态行尾段**：`7d ▬ 58% left`（最紧窗口单段）。零垂直成本；但只在首页存在，Sessions/Chat 一进去就看不到。
- **B · 底边 docked 细条（推荐 ✅）**：双窗口 + 重置时间，滚动容器外、hairline 顶线、无填充。唯一能延伸到会话列表页的方向，且离此前被否的顶栏图标区最远。成本：两屏各占 48pt 常驻高度；若未来出底部 tab bar，条并入其顶 hairline 而非叠加。
- **C · 机器信息带 + 账号行**：`claude.ai · Max` + 双表；语义最完整（回答「谁的额度」），但首屏折上方吃 ~34pt，多机器时有归属歧义。

三方向 no-data 均完全塌缩为现状布局。共享 token：轨道 rgba(255,255,255,.14) 2pt、normal 填充 #8C8D95、warning #E0A93B、数字 JetBrains Mono（chrome 12.5px / sheet 13.5px）、标签恒为 5h / 7d。

## 设计稿留下的三个开放问题

1. 条上显示一个窗口还是两个（B 稿画的是两个）；
2. B 是否在 Sessions 页持续存在（稿是持续）；
3. 双窗口同时告警的表现。

## 落地状态

- 按 B 方向实现（Home + Sessions 底边条），代码见 `mobile/composeApp` QuotaPill/QuotaRows 相关文件；A 方向的机器状态行紧凑胶囊实现保留在历史（被 B 取代）。
