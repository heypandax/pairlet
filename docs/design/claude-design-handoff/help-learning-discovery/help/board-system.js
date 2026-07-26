// FRAME 13 — component & state board, icon handoff, red threads
(function () {
  const BY = 8020;

  const comps = [
    ['HelpEntry', '帮助入口', 'Projects / Sessions 头部、桌面侧栏页脚、设置一行', 'default · badge(new digest) · pressed · disabled'],
    ['HelpCenterHeader', '帮助中心头部', '返回 + 标题 + 搜索；桌面为面板头 + Esc', 'mobile · panel · settings-pane'],
    ['AskSearchComposer', '「你想学会做什么？」输入框', '手册搜索、Explore 目录与助手检索共用同一任务分类', 'idle · focus · typed · sending · suggestions'],
    ['TaskGuideCard', '任务指南卡', '用任务命名一份书面指南，说明结果与共几步；不追踪进度', 'collapsed · expanded · linked-to-manual'],
    ['TaskStepList', '书面步骤列表', '按顺序编号的静态步骤，可视觉标出关键那一步；无确认、无跳过、无检测', 'static · numbered · with-direct-action'],
    ['LocationHint', '位置说明', '一句「显示在哪里」+ 面包屑或紧凑界面位置卡，纯静态文字', 'inline · breadcrumb · with-fallback-path'],
    ['DirectActionLink', '直接动作', '一次性打开已存在的页面或面板（打开改动 / 打开定时发送），仅在可行时出现', 'available · unavailable(退化为书面步骤)'],
    ['CapabilityCard', '能力卡', '为什么值得用 + 前置条件 + 已体验状态 + 查看介绍', 'untried · tried · new · dismissed-twice'],
    ['FeatureAvailability', '可用性标记', '把前置条件说清楚，而不是隐藏入口', 'ready · needs-desktop · needs-2-computers · needs-newer-daemon · unavailable-for-agent'],
    ['ManualSourceRow', '手册来源行', '文章标题 + 最近核验日期，单行不堆叠', 'single · missing(无已核验来源)'],
    ['LearnedFeedback', '我会用了 / 还没明白', '会用了收尾并标记已体验；还没明白换一种讲法', 'unanswered · got-it · still-unclear'],
    ['ContextualMicroTip', '上下文微提示', '内联卡片，由行为时刻触发，每会话最多一条；不做覆盖层', 'unseen · shown · dismissed-once · suppressed · learned'],
    ['WhatsNewCard', 'What’s new 卡', '升级后安静出现，最多三条', 'unread · read · empty'],
    ['ContextChip / ContextSharePreview', '上下文 chip / 共享预览', '当前界面、平台、所选 Agent、可见控件，逐条可移除', 'included · removed · required · empty(公开页)'],
    ['SetupProgress', '设置进度', '第 n 步 / 3 + 进度条，后退保留状态', 'step1 · step2 · step3 · complete'],
    ['SetupStepCard', '设置步骤卡', '一条真实命令 + 位置说明 + 单一 CTA', 'not tried · copied · confirmed'],
    ['FirstSuccessChecklist', '首次成功清单', 'Projects 顶部，紧凑可关闭；首次成功后移除', '0/3 · 1/3 · 2/3 · done(自动消失) · dismissed'],
    ['CopyableCommand', '可复制命令块', 'JetBrains Mono，仅放真实存在的命令，长命令自动换行', 'idle · copied · wrapped'],
    ['CompactStateFallback', '紧凑状态回退（次要）', '运行时失败只在失败现场命名状态 + 一个动作，不进入学习旅程', 'connecting · reconnecting · relay-unreachable · computer-offline · pairing-invalid · daemon-too-old']
  ];

  const stateSet = (title, en, items) => `<div class="bx" style="flex:1;min-width:0">
<h5>${title} <span class="mono" style="font-size:10.5px;color:#5E646A;font-weight:400">${en}</span></h5>
<div class="chips" style="margin-top:9px">${items.map(([t, k]) => `<span class="pill ${k}">${t}</span>`).join('')}</div></div>`;

  F({
    label: 'FRAME 13 · 组件与状态板', sub: 'System component & state board — learning primitives first, error states as compact fallback', x: 80, y: BY, w: 2560, h: 1420,
    html: `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:16px">
<div class="row gap16" style="align-items:flex-start">
  <div style="flex:1"><b style="font-size:15px;font-weight:600">组件与状态 Components &amp; states</b><p class="mono" style="font-size:11.5px;line-height:1.6;color:#8A9096;margin:6px 0 0">同一批基元覆盖移动全屏路由、桌面右侧面板、设置内页与公开网页；仅版式与密度不同。</p></div>
  <div class="bx acc" style="width:560px"><p><b>不变量</b> 一次只教一步并说明「显示在哪里」· 价值先于机制 · 步骤只是书面说明，不追踪进度也不检测点击 · 直接动作只做一次导航 · 主动提示每会话最多一条 · 运行时故障只作为紧凑回退状态，不成为模式、导航或推荐。</p></div>
</div>
<div class="row gap14" style="align-items:stretch;flex:1;min-height:0">
  <div class="bx" style="flex:1.35;min-width:0;overflow:hidden">
    <table class="tbl"><thead><tr><th style="width:230px">组件</th><th style="width:150px">中文</th><th style="width:250px">职责</th><th>状态</th></tr></thead>
    <tbody>${comps.map(c => `<tr><td class="m"><b>${c[0]}</b></td><td>${c[1]}</td><td>${c[2]}</td><td class="m">${c[3]}</td></tr>`).join('')}</tbody></table>
  </div>
  <div class="col gap12" style="width:740px;flex:none">
    ${stateSet('激活与首次使用', 'activation', [['未配对', 'p-mut'], ['部分完成 2/3', 'p-warn'], ['配对成功', 'p-ok'], ['首次成功会话', 'p-ok']])}
    ${stateSet('任务指南', 'task guide', [['未打开', 'p-mut'], ['已展开', 'p-info'], ['含直接动作', 'p-ok'], ['仅书面步骤', 'p-mut'], ['已核验来源', 'p-ok']])}
    ${stateSet('助手（教学）', 'assistant', [['空闲', 'p-mut'], ['检索中', 'p-info'], ['已给出教学回答', 'p-ok'], ['来源不足', 'p-warn'], ['无已核验答案', 'p-warn'], ['被限流（回退手册检索）', 'p-warn']])}
    ${stateSet('能力发现', 'discovery', [['未体验', 'p-mut'], ['已提示过', 'p-info'], ['忽略 1 次', 'p-mut'], ['已抑制', 'p-warn'], ['已体验', 'p-ok'], ['前置条件不满足', 'p-warn']])}
    ${stateSet('紧凑回退（次要）', 'fallback only', [['正在连接', 'p-mut'], ['正在恢复', 'p-mut'], ['中继不可达', 'p-warn'], ['电脑没有上线', 'p-warn'], ['配对已失效', 'p-bad'], ['daemon 版本过旧', 'p-warn']])}
    <div class="bx"><h5>颜色语义 Color semantics</h5><div class="col gap6" style="margin-top:8px">
      <p><span class="pill" style="background:rgba(217,119,87,.18);color:#D97757">terracotta</span> 主要下一步动作与选中态</p>
      <p><span class="pill p-info">blue</span> 正在阅读的任务指南与关键那一步</p>
      <p><span class="pill p-ok">green</span> 已确认 / 已学会 / 已连接</p>
      <p><span class="pill p-warn">amber</span> 前置条件未满足与紧凑回退状态</p>
      <p><span class="pill p-bad">red</span> 破坏性动作与敏感隐私警示</p>
    </div></div>
  </div>
</div></div>`
  });

  // ── icon handoff ────────────────────────────────────────────
  const icons = [['help', 'HelpCircle', '圆 r=9 + 问号弧'], ['compass', 'Explore', '圆 + 指针菱形'], ['zap', 'MicroTip', '闪电折线'], ['clock', 'ScheduleLater', '圆 + 时针'], ['diff', 'LineDiff', '两条竖线 + 圆节点'], ['term', 'TakeoverTerminal', '终端框 + 提示符'], ['layers', 'Fleet', '两层平行四边形'], ['book', 'Manual', '书脊 + 页'], ['shield', 'SafePermission', '盾 + 勾'], ['checkc', 'StepDone', '圆 + 勾'], ['circle', 'StepNumber', '空心圆'], ['eyeoff', 'NeverRead', '眼睛 + 划线'], ['qr', 'PairCode', '三个定位角'], ['download', 'InstallGuide', '下箭头 + 托盘'], ['mark', 'CCPocketMark', '圆角矩形 + 提示符'], ['monitoroff', 'CompactFallback', '显示器 + 对角划线（仅回退状态）']];

  F({
    label: 'ICON HANDOFF · 1.5px 描边', sub: 'stroke SVG · 24×24 · round caps & joins · ImageVector-friendly', x: 2700, y: BY, w: 860, h: 700,
    html: `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:14px">
<div><b style="font-size:15px;font-weight:600">图标 Icons</b><p class="mono" style="font-size:11.5px;line-height:1.6;color:#8A9096;margin:6px 0 0">viewBox 24×24 · stroke-width 1.5 · stroke-linecap/linejoin round · fill none · 单色 currentColor · 无渐变、无内阴影。几何直接转 Compose ImageVector（path + arcTo）。</p></div>
<div class="bx" style="flex:1;min-height:0;overflow:hidden"><div style="display:grid;grid-template-columns:repeat(4,1fr);gap:12px">
${icons.map(([n, name, geo]) => `<div class="row gap10" style="padding:10px 11px;border:1px solid #232629;border-radius:10px;background:#101113"><span style="color:#D97757">${ic(n, 20)}</span><span class="col" style="gap:2px;min-width:0"><b class="mono" style="font-size:11.5px;color:#ECEDEE;font-weight:500">${name}</b><span class="mono" style="font-size:10.5px;color:#6B7177">${geo}</span></span></div>`).join('')}
</div></div>
<div class="bx"><p><b>尺寸档</b> 14 / 16 / 18 / 20 / 22px，全部沿用现有 <span class="mono">.ic</span> 规范；触控目标独立于图标尺寸，恒 ≥44pt。</p></div></div>`
  });

  // ── red threads ─────────────────────────────────────────────
  const thread = (cls, title, en, steps) => `<div class="bx" style="flex:1;min-width:0">
<div class="row gap8" style="margin-bottom:10px"><span class="thread ${cls}">${title}</span><span class="mono" style="font-size:11px;color:#5E646A">${en}</span></div>
<div class="col gap8">${steps.map(s => `<div class="row gap10" style="align-items:flex-start"><span class="mono" style="font-size:10.5px;color:#5E646A;width:56px;flex:none;padding-top:2px">${s[0]}</span><span class="col" style="gap:2px;min-width:0"><b style="font-size:12.5px;font-weight:500;color:#ECEDEE">${s[1]}</b><span class="mono" style="font-size:11px;line-height:1.5;color:#8A9096">${s[2]}</span></span></div>`).join('')}</div></div>`;

  F({
    label: '红线 · 三条端到端旅程', sub: 'Red threads — install · learn a task · discover a capability (no guided tours)', x: 80, y: BY + 1500, w: 2560, h: 470,
    html: `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:14px">
<div class="row gap14" style="align-items:stretch;flex:1;min-height:0">
${thread('th1', '1 首次安装 → 首次成功会话', 'Install → first successful session', [['FRAME 02', '阶段选择', '你的电脑端准备好了吗 → 已安装 / 还没有 / Demo'], ['FRAME 03A', '安装电脑端', 'brew install --cask heypandax/tap/cc-pocket'], ['FRAME 03B', '生成配对码', 'cc-pocket-daemon pair → 扫码或六位码'], ['FRAME 04A', '连接成功', '机器身份 + 打开第一个项目'], ['FRAME 04B', '首次成功清单', '开始使用 2/3 → 发出第一条指令后移除']])}
${thread('th2', '2 学会一件事', 'Read written steps → open changes → self-report', [['FRAME 06', '任务指南', '继续一个已有会话并查看改动 · 共 4 步，静态'], ['FRAME 07A', '教学式回答', '做什么 → 什么时候有用 → 一步动作 + 显示在哪里'], ['FRAME 07B', '还没明白', '一个澄清问题 → 更简单的讲法，不做诊断'], ['FRAME 11B', '桌面任务指南面板', '书面步骤 + 「打开改动」一次性打开已有改动界面'], ['FRAME 13', '标记已学会', 'LearnedFeedback → Explore 标记已体验']])}
${thread('th3', '3 发现定时发送', 'Discover → inline tip → open existing sheet → learned', [['FRAME 10 · 1', '行为时刻微提示', '首次成功发送后内联卡片：长按发送可预约稍后执行'], ['FRAME 08B', '操作位置说明', '面包屑位置 + 一个直接动作 + 手动路径回退'], ['FRAME 05', '帮助中心推荐', '接着学这个 + 「看看我还能做什么」'], ['FRAME 09', 'Explore 条目', '为什么值得用 + 可用性 + 查看介绍'], ['FRAME 10 · 6', '已体验并可复用', '首次成功预约 → 标记已体验；忽略两次仍永久可达']])}
</div></div>`
  });
})();
