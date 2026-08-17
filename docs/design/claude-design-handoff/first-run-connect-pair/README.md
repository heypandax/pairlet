# First Run · Connect + Pair handoff（#278 批次 2 A＋B）

新用户首启「连接你的电脑」引导流＋配对屏 2.0 的设计交付。目标：把「先在电脑上装 daemon」翻到首启正面，把配对期每个失败态变成可行动的错误卡。

## Source of truth

- `FirstRunDevice.dc.html` —— 共享设备组件（390×844），交互控件即变体矩阵：
  - `screen`: connect（首启引导）/ pair（配对 2.0）
  - `platform`: macOS / Windows / Linux（安装命令段）
  - `compact`: 首启页滚动折叠态（建模为状态而非滚动动画）
  - `state`: idle / verifying / expired / network / badlink / success（配对屏六态）
- `First Run · Connect + Pair v1.dc.html` —— 板文件：全变体帧＋浅色 proof＋设计注记。

在线板（登录即看）：<https://claude.ai/design/p/eb401868-d618-47f7-b8d4-4641117d566d?file=First+Run+·+Connect+++Pair+v1.dc.html>

## 设计师注记（实现必读）

1. **稿内安装命令与 Windows CLI 句是占位文案**（板上已标注）——实现时换成仓库真实分发命令（见 `OnboardingScreen.kt` 既有三平台命令）。
2. 浅色 danger ink `#A83A26` 为新派生 token（浅色盘此前无 danger），来源于既有暗 `#E97462` / 浅 `#A9482A` 配对。
3. `compact` 是折叠状态（收起标题＋停靠 CTA），不是滚动动画。

## 落地状态

- 归档：2026-08-18。实现分支进行中；配对失败态由失败原因分类驱动（parse / code / redeem / network），与遥测同源。
