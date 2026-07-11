# 生物识别锁定门 — App Lock gate + Settings 开关 + 快照遮罩（设计 handoff）

- **在线设计板**（登录 pandaleeng@gmail.com 即看）：
  - 锁定 gate 页（5 态 + 浅色）：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FApp+Lock.html>
  - Settings 开关 + auto-lock + 快照遮罩：<https://claude.ai/design/p/93b56700-6ed2-46c9-bf81-3fd0b1a6340b?file=cc-pocket%2FSettings+App+Lock.html>
- **生成**：2026-07-11，claude.ai/design，模型 Opus 4.8 Medium，两个 Prompt 顺序追加，均一次通过（0 撞限、0 重试）
- **⚠️ 项目已迁回主号**：本设计生成在**主号 pandaleeng@gmail.com 的原始项目 `93b56700`**——它自会话初就持有完整原生设计系统（ia-core / ios-frame / desktop-core 等）+ attachments 批成品，本轮起**恢复为设计线正典**（此前 Workflow/presets/folder-share 三批因主号限额临时借道 b01099485423 的 `c41b194b`，那些成品已各自归档在 `../workflow-view/`、`../presets/`、`../folder-share/`）。FaceID 直接复用 93b56700 原生 `ios-frame.jsx`，无需再喂种子
- **对应 issue／任务**：#109 Face ID / App Lock。**实现待定稿派发**：iOS 走 `LAContext`（`deviceOwnerAuthentication` 带密码回退），Android 走 `BiometricPrompt`（`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`）；开关/阈值存 Keychain / EncryptedSharedPrefs；gate 组件与状态机放 `commonMain`，生物识别与快照遮罩走 expect/actual 薄壳
- **本地打开**：目录下 `python3 -m http.server` 后访问两个 HTML（各引 `ios-frame.jsx` + 自身 app jsx；调色板内联在 app jsx，不依赖 ia-core）

## 文件清单

| 文件 | 内容 |
|---|---|
| `App Lock.html` | 屏 1 入口：锁定 gate（5 态 + 浅色 idle） |
| `app-lock-app.jsx` | 屏 1 组件源：手绘 1.5pt Face ID line glyph、Lockup、AppLockScreen 五态配置（调色板 DARK/LIGHT 内联）——**像素规格主参考** |
| `Settings App Lock.html` | 屏 2 入口：Settings Security 组 + auto-lock + 快照遮罩（4 帧） |
| `settings-lock-app.jsx` | 屏 2 组件源：SecurityGroup、mid-enable verifying switch、Auto-lock options sheet、SwitcherThumb + 复用 Lockup（调色板内联） |
| `ios-frame.jsx` | 共享依赖：iPhone 设备框（两 HTML 引用），主号项目原生文件 |

## 两屏内容

**Prompt 1 · 锁定 gate（`App Lock.html`）**——全屏仪式感屏，克制对标 Pairing：居中 lockup（wordmark 走 text-primary **不上色** + app mark）+ 大号 Face ID line glyph（**唯一陶土焦点**）+ 状态行 "Locked" + subline。五态（各帧 accent 只落一处）：
1. idle — 静息门 + Unlock with Face ID 按钮（陶土焦点在 glyph）
2. authenticating — gate 作背板（lockup 压 muted、glyph 轻脉冲、按钮禁用、**无 spinner**、底色保持主题 base）
3. failed once — 温和 "Not recognized · Try again"（soft-danger，不报红警，glyph 不变，焦点仍在 glyph）
4. multiple failures → passcode fallback — Enter passcode 升为主行动（**陶土焦点移到按钮**）
5. biometry locked out — glyph 置灰、唯一出路是设备密码
   + 浅色 idle 副本（#FAF9F7/#C15F3C）

**Prompt 2 · Settings + 遮罩（`Settings App Lock.html`）**——
- A · Security 组：**紧接「Default permission mode」组之后**（访问控制聚顶）；Require Face ID 行（Face ID 图标 + 副标 + 开关 ON 陶土）+ 仅 ON 时露出的 Auto-lock 子行（值 "Immediately" + chevron）
- B · enabling verifying：拨 ON 即弹 OS Face ID 验一次，开关停在 mid-travel 压暗 pending 态，通过才落定 / 取消回弹 OFF
- C · Auto-lock options bottom sheet：Immediately（默认勾选）/ After 1 minute
- D · app-switcher 遮罩：**全不透明品牌盖板**（主题底色 + **精确复用 gate 的 lockup**，无控件，非毛玻璃），置于 iOS 切换器缩略图中、两侧邻居 app 卡片可见内容作对比——证明 cc-pocket 卡只露品牌门

## 落地状态

**未实现**（本轮到设计定稿归档为止）。gate 组件与状态机 → `commonMain`；LocalAuthentication / BiometricPrompt / 快照遮罩 → expect/actual 薄壳（对齐 UI-DESIGN §9）。app jsx 内联 script 即像素规格与配色。

## 设计约束核验（全部遵守）

- **陶土只点一处**：生成侧总结明确 "accent lands on exactly one focal point per frame — Face ID glyph in states 1–3, passcode button in states 4–5"；wordmark 保持 text-primary mono 不上色；
- **验证中不放 spinner**、底色保持主题 base（不做空白/崩溃感）；
- **失败分级**：温和 retry → passcode 升级 → lockout 死路，三态各有落点；
- **遮罩复用 gate lockup**：切换器里的门 = 每天开锁的门（Frame D 精确复用 app mark + wordmark + Face ID glyph）；不用毛玻璃。

## 占位提醒（brief 原话）

所有带引号文案（"Locked"、"Unlock to open your sessions"、"Require Face ID"、"Auto-lock" 等）均为**占位**，实现按 i18n 校准；「Face ID」字样 + 人脸 glyph 按设备可用生物类型运行时整体替换（Face ID / Touch ID / Fingerprint / 通用 "Unlock"，读 `LAContext.biometryType` / `BiometricManager`）。

## 与 brief 的偏离与待拍板项

- **无结构性偏离**——两个 Prompt 的 LAYOUT／STATES 逐条落全，gate 5 态 + 浅色、Settings 4 帧全到位。
- brief 建议的 variants（lockup 纯 wordmark ⟷ 带 app mark、glyph tinted ⟷ 加陶土 ring）**未展开**——一次成型已选带 app mark + tinted glyph；要比稿可补一轮。
- 平台差异（iPad Touch ID / Android BiometricPrompt / FLAG_SECURE 遮罩 / lockout 语义）brief 正文已详述，实现按其适配。
