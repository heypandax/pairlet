# 桌面端侧栏信息架构 v2＋订阅用量展示 —— claude design 交付（2026-08-24）

- 在线画板：https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=Desktop+Sidebar+IA+%2B+Usage+v1.dc.html（登录即看，45 页画板）
- 生成模型：Fable 5（Max effort）；brief 见 `~/Desktop/Brain/60_Outbox/2026-08-24-cc-pocket-侧栏信息架构与用量展示设计提示词.md`

## 文件清单

| 文件 | 内容 |
|---|---|
| `Desktop Sidebar IA + Usage v1.dc.html` | 主画板：5 个完整窗口＋7 个细节裁切，含推荐方案与三个否决变体 |
| `DesktopShellDevice.dc.html` | 桌面窗口 device 组件（placement/quota/popover 全部做成 props，任意状态组合可调） |

## 设计结论（供实现对齐）

**推荐落点：侧栏 footer「状态行」**（并对侧栏底部做了 IA 重排）：

1. **一区一语法**：「全部项目…」「已归档会话」不再是 footer 家具，改为会话滚动列表的**列表终止行**（列表的安静收尾）；footer 只保留两行——先遥测（用量状态行）、后 meta（帮助＋设置＋版本合一行）。
2. **用量是状态不是导航**：28px 高、mono 数字＋2.5px 细条，读感像状态栏 segment；点击只开详情弹层、绝不导航，不与会话争视觉。
3. **告警只用颜色升级**：muted → amber → danger，几何完全不变；warning 不回流侧栏，blocked 在同一槽位加重置时间，不加横幅。
4. **缺席是一等状态**：API-key 账号／无 daemon／无快照时整行消失、hairline 自然合拢，无空洞。

三个探索过并否决的落点（画板里各带 amber 告警态）：机器切换头部双环（**作用域错**——用量属账号不属机器）、主面板右上 chip（**海拔错**——会话级表面放了全局遥测）、仅设置弹层＋低量才现身（**无环境信号**）。

弹层 v2：双窗口行（剩余百分比条＋mono 倒计时）＋「x 分钟前更新」＋手动刷新；blocked 变体带 danger 条与「resets at 14:00」。

## 落地状态

**未实现**。目标文件 `desktop/Sidebar.kt`（FooterActions）与 `desktop/QuotaBar.kt` 当前是订阅额度功能会话的未提交 WIP——按 2026-08-24 约定，额度代码归属（补记录 commit / revert）定夺前本设计不落码。定夺后按本目录 jsx/html 像素规格实现，改动跑 `bash scripts/check-all.sh`。
