// FRAMES 11–12 — desktop Help panel / Settings › Help, public help web
(function () {
  const DY = 5780, WY = 6800;

  const side = (helpOn) => `<div class="side">
<div class="sh"><span class="dot ok"></span><b style="font-size:13.5px;font-weight:600;flex:1">MacBook Pro</b><span style="color:var(--muted)">${ic('down', 14)}</span></div>
<div class="sl">
  <span class="t-eyebrow" style="padding:6px 10px">项目</span>
  ${['ark-nexus/relay', 'cc-pocket-daemon', 'site-marketing'].map((n, i) => `<span class="snav ${i === 0 ? 'on' : ''}">${ic('folder', 16)}<span class="grow mono" style="font-size:12.5px">${n}</span>${i === 0 ? '<span class="dot ok"></span>' : ''}</span>`).join('')}
  <span class="t-eyebrow" style="padding:14px 10px 6px">会话</span>
  ${['更新配对流程文档', '修复中继重连', '梳理 diff 视图'].map((n, i) => `<span class="snav ${i === 0 ? 'on' : ''}"><span class="grow" style="font-size:13px">${n}</span></span>`).join('')}
</div>
<div class="sf">
  <span class="snav">${ic('bell', 16)}<span class="grow">待审批</span><span class="pill p-warn">2</span></span>
  <span class="snav ${helpOn ? 'hl' : ''}">${ic('help', 16)}<span class="grow">帮助中心</span><span class="mono" style="font-size:11px;opacity:.7">⌘/</span></span>
  <span class="snav">${ic('cpu', 16)}<span class="grow">设置</span></span>
</div></div>`;

  const conv = () => `<div class="main">
<div class="tbar"><b style="font-size:14px;font-weight:600;flex:1">ark-nexus/relay · 更新配对流程文档</b><span class="pill p-ok">Claude Code · Sonnet 4.6</span><span style="color:var(--muted)">${ic('term', 18)}</span></div>
<div class="body">
  <div class="conv">
    <div class="bub me">把 README 里的配对步骤更新成三段式引导</div>
    <div class="bub">已更新 <span class="mono">README.md</span> 与 <span class="mono">docs/pairing.md</span>，共 4 处改动。要看逐行 diff 吗？</div>
    <div class="card row gap10" style="max-width:520px"><span style="color:var(--accent)">${ic('diff', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">2 个文件已改动</b><span class="t-s mono" style="font-size:11.5px">+30 −7</span></span><span class="btn xs b-ghost">查看 diff</span></div>
    <div class="bub" style="opacity:.55">正在等待下一步指令…</div>
    <div class="grow"></div>
    <div style="max-width:760px;width:100%">${composer('输入指令，⌘↩ 发送 · 长按发送键预约稍后执行')}</div>
  </div>`;

  // ── FRAME 11A · desktop Ready (learning & discovery) ────────
  const panelReady = `<div class="hpanel">
<div class="hh">${ic('help', 20)}<b class="grow" style="font-size:14.5px;font-weight:600">帮助中心</b><span class="mono t-s" style="font-size:11px">Esc 关闭</span><span style="color:var(--muted)">${ic('x', 18)}</span></div>
<div class="hb">
  ${composer('你想学会做什么？', { chips: chip('当前会话 · 更新配对流程文档', { x: 1 }) + chip('Claude Code', { x: 1 }) })}
  <div class="chips" style="margin-top:-3px">${['查看这次会话的改动', '让它稍后再执行'].map(t => chip(t, { tap: 1 })).join('')}</div>
  <p class="t-s">回答基于已核验的 CC Pocket 用户手册。</p>
  <div class="sechead" style="margin-top:0"><span class="t-eyebrow">接着学这个</span></div>
  <div class="card col gap8" style="border-color:color-mix(in oklab,var(--accent) 38%,var(--hair))">
    <div class="row gap10"><span style="width:32px;height:32px;border-radius:9px;display:grid;place-items:center;background:var(--accent-bg);color:var(--accent);flex:none">${ic('diff', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">看懂 Agent 到底改了什么</b><span class="t-s">这次会话已有 2 个文件改动，正好用来练一遍。</span></span></div>
    <div class="row gap8"><span class="btn xs b-pri">查看步骤</span><span class="btn xs b-quiet">读完整指南</span></div>
  </div>
  <div class="sechead"><span class="t-eyebrow">按你想做的事</span></div>
  <div class="col gap8">${goal('term', '继续一台电脑上的工作', '恢复会话、接管终端、批准权限')}${goal('clock', '让它按时间替我执行', '预约稍后发送、按天重复')}${goal('compass', '看看我还能做什么', '已体验 7 / 18')}</div>
  <div class="sechead"><span class="t-eyebrow">最近新增</span><span class="pill p-new">2</span></div>
  <div class="card row gap10" style="padding:10px 12px"><span class="grow col" style="gap:2px"><b style="font-size:13px;font-weight:500">桌面端托盘直接批准权限</b><span class="t-s mono" style="font-size:11px">1.5.1 · 还有 1 条</span></span><span class="btn xs b-quiet">查看介绍</span></div>
  <div class="grow"></div>
  <div class="disc"><span class="row gap8" style="color:var(--sec)">${ic('book', 16)}浏览全部指南</span><span style="color:var(--muted)">${ic('ext', 16)}</span></div>
</div></div>`;

  // ── FRAME 11B · desktop task-guide panel ────────────────────
  const panelGuide = `<div class="hpanel">
<div class="hh">${ic('book', 20)}<b class="grow" style="font-size:14.5px;font-weight:600">任务指南</b><span class="mono t-s" style="font-size:11px">Esc 关闭</span><span style="color:var(--muted)">${ic('x', 18)}</span></div>
<div class="hb">
  <div class="card col gap12" style="border-color:color-mix(in oklab,var(--info) 40%,var(--hair));background:color-mix(in oklab,var(--info) 6%,var(--surface))">
    <div class="col gap5" style="gap:5px">
      <span class="t-eyebrow" style="color:var(--info)">任务指南 · 共 4 步</span>
      <b class="t-h2" style="font-size:17px">继续一个已有会话并查看改动</b>
      <p class="t-s" style="line-height:1.55">左侧就是你当前的会话。下面是写好的步骤说明，按顺序自己做就行。</p>
    </div>
  </div>
  <div class="col gap8">
    <div class="step"><span class="n">1</span><span class="grow col" style="gap:2px;padding-bottom:2px"><b class="t-h3" style="font-size:13.5px">打开电脑上已有的会话</b><span class="t-s">侧栏「会话」里带绿点的那条</span></span></div>
    <div class="card col gap10" style="border-color:color-mix(in oklab,var(--accent) 40%,var(--hair))">
      <div class="step"><span class="n on">2</span><span class="grow col" style="gap:3px"><b class="t-h3" style="font-size:14px">点开「2 个文件已改动」</b><span class="t-s">按文件聚合后，点文件名展开逐行 diff。</span></span></div>
      <div class="row gap8" style="padding:9px 11px;border-radius:10px;background:var(--raised)">${ic('diff', 16)}<span class="t-s grow" style="color:var(--sec)">显示在哪里：会话正文上方 · <b style="color:var(--text);font-weight:500">2 个文件已改动</b></span></div>
      <div class="row gap8"><span class="btn sm b-pri grow">打开改动</span><span class="btn sm b-quiet">读完整指南</span></div>
    </div>
    <div class="step"><span class="n">3</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">确认后批准写入</b><span class="t-s">托盘里也能直接放行</span></span></div>
    <div class="step"><span class="n">4</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">让它在你离开时继续跑</b><span class="t-s">关掉面板也不会中断会话</span></span></div>
  </div>
  ${srcrow('恢复会话与查看改动', '2026-07-24')}
  ${learned()}
  <div class="grow"></div>
  <p class="t-s">「打开改动」只是一次打开左侧已有的改动列表。面板不跟踪你做到哪一步，也不接管界面。</p>
</div></div>`;

  F({ label: 'FRAME 11A · 桌面 · 帮助面板 · 已激活', sub: '1440×900 · dark · learning & discovery in a 440px panel', thread: '红线 3', x: 80, y: DY, w: 1440, h: 900, html: `<div class="dt">${side(true)}${conv()}${panelReady}</div></div></div>` });
  F({ label: 'FRAME 11B · 桌面 · 任务指南面板', sub: 'TaskStepList beside the conversation · static steps + one 打开改动 action', thread: '红线 2', x: 1600, y: DY, w: 1440, h: 900, html: `<div class="dt">${side(true)}${conv()}${panelGuide}</div></div></div>` });

  // ── FRAME 11C · Settings › Help ─────────────────────────────
  const setrow = (t, s, on) => `<span class="snav ${on ? 'on' : ''}" style="height:36px"><span class="grow" style="font-size:13.5px">${t}</span>${s ? `<span class="pill p-mut">${s}</span>` : ''}</span>`;
  const path = (n, t, s, total) => `<div class="card col gap9" style="gap:9px;padding:13px">
<div class="row gap10"><span style="width:30px;height:30px;border-radius:9px;display:grid;place-items:center;background:var(--raised);color:var(--accent);flex:none">${ic(n, 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${t}</b><span class="t-s">${s}</span></span></div>
<div class="row gap10"><span class="t-s mono grow" style="font-size:11px;align-self:center">共 ${total} 步 · 书面指南</span><span class="btn xs b-ghost">查看步骤</span></div></div>`;

  F({
    label: 'FRAME 11C · 设置 › 帮助（任务指南与能力目录）', sub: 'Settings › Help — written task guides + capability catalog, replaces 3 external rows', x: 3120, y: DY, w: 1440, h: 900,
    html: `<div class="dt">
<div class="side" style="width:230px">
  <div class="sh">${ic('back', 18)}<b style="font-size:13.5px;font-weight:600;flex:1">设置</b></div>
  <div class="sl">${['通用', '电脑与配对', 'Agent 与模型', '通知', '外观', '隐私与安全'].map(t => setrow(t)).join('')}${setrow('帮助中心', '', true)}${setrow('关于', '1.5.1')}</div>
</div>
<div class="main">
  <div class="tbar"><b style="font-size:14px;font-weight:600;flex:1">帮助中心</b><span class="pill p-ok">已体验 7 / 18</span></div>
  <div class="body"><div class="conv" style="padding:24px 32px;gap:14px">
    <div style="max-width:760px;width:100%;display:flex;flex-direction:column;gap:14px">
      ${composer('你想学会做什么？')}
      <p class="t-s" style="margin-top:-6px">回答基于已核验的 CC Pocket 用户手册。设置内与侧栏面板使用同一批组件，仅版式更宽。</p>
      <div class="sechead"><span class="t-eyebrow">任务指南</span><span class="t-s mono" style="font-size:11px">按你想做的事排列 · 书面步骤与链接</span></div>
      <div class="grid3">
        ${path('term', '继续一台电脑上的工作', '恢复会话、接管终端、批准权限', 4)}
        ${path('diff', '看懂它改了什么', '改动文件、逐行 diff、导出', 3)}
        ${path('clock', '让它按时间替我执行', '预约发送、按天重复、后台任务', 3)}
      </div>
      <div class="sechead"><span class="t-eyebrow">能力目录</span><span class="t-s mono" style="font-size:11px">18 项 · 按结果分组</span></div>
      <div class="grid4">${[['zap', '离开电脑也能继续', '4 项'], ['send', '更快地告诉 Agent', '3 项'], ['clock', '安排它稍后执行', '2 项'], ['diff', '看懂它改了什么', '3 项'], ['cpu', '让多个 Agent 协作', '2 项'], ['layers', '在多台电脑间工作', '2 项'], ['lock', '安全地分享能力', '1 项'], ['compass', '选择 Agent 与模型', '1 项']].map(([i, t, n]) => `<div class="card row gap8" style="padding:11px 12px"><span style="color:var(--sec)">${ic(i, 16)}</span><span class="grow col" style="gap:1px"><span style="font-size:13px;font-weight:500">${t}</span><span class="t-s mono" style="font-size:10.5px">${n}</span></span></div>`).join('')}</div>
      <div class="card" style="padding:2px 14px">
        <div class="lrow"><span class="li">${ic('book', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">浏览全部指南</b><span class="t-s mono" style="font-size:11.5px">pocket.ark-nexus.cc/manual/ · 在系统浏览器打开</span></span>${ic('ext', 16)}</div>
        <div class="lrow"><span class="li">${ic('cpu', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">这台设备的信息</b><span class="t-s mono" style="font-size:11.5px">macOS 15.5 · App 1.5.1 · daemon 1.5.0 · 提问时可选择附带</span></span><span class="pill p-info">App 已知</span></div>
      </div>
      <p class="t-s">被替换的三行：帮助与支持 · 用户手册 · 故障排查 → 合并为「帮助中心」一行；连接故障留在连接状态区就近处理。</p>
    </div>
  </div></div>
</div></div>`
  });

  ANN(4620, DY, `<h4>桌面行为 Desktop</h4>
<dl><dt>入口</dt><dd>侧栏页脚 Help / ⌘/ / 设置 › 帮助 —— 都先打开右侧面板，不直接跳浏览器</dd>
<dt>面板</dt><dd>440px，会话与所选 Agent 保持可见并自动成为可移除的 chip</dd>
<dt>指南</dt><dd>只给书面步骤与位置说明；不高亮控件、不跟踪进度、不代替用户点击</dd>
<dt>直接动作</dt><dd>「打开改动」这类按钮只做一次导航，打开已存在的界面就结束</dd>
<dt>键盘</dt><dd>⌘/ 打开 · Esc 关闭面板，已输入文本不清空</dd>
<dt>持久</dt><dd>面板内已输入文本与「已体验」标记保留；不保存任何「当前步骤」</dd></dl>`, 250);

  // ── FRAME 12 · public help web ──────────────────────────────
  const wnav = `<div class="wnav"><span class="row gap8" style="margin-right:8px">${ic('mark', 20)}<b style="font-size:15px;font-weight:600;letter-spacing:-.02em">CC Pocket</b></span><a>功能</a><a>下载</a><a>手册</a><a class="on">帮助</a><span class="grow"></span><span class="chip mono">EN / 中文</span></div>`;

  const tasks = [['term', '怎么继续电脑上已有的会话？', '恢复会话与无分叉接管'], ['diff', '怎么看它改了哪些文件？', '改动文件与逐行 diff'], ['clock', '怎么让它稍后再执行？', '长按发送与按天重复'], ['clip', '怎么把文件或录屏发给它？', '图片、文件、录屏作为上下文'], ['shield', '权限请求该怎么处理？', '批准、拒绝与超时的含义'], ['compass', '该选哪个 Agent 和模型？', 'Claude / Codex / OpenCode 与推理强度']];
  const cats = [['zap', '离开电脑也能继续'], ['send', '更快地告诉 Agent'], ['clock', '安排它稍后执行'], ['diff', '看懂它改了什么'], ['layers', '在多台电脑间工作'], ['lock', '安全地分享能力']];

  const webBody = (fallback) => `<div class="wbody"><div class="wwrap">
  <div class="col gap10">
    <h1 class="t-h1" style="font-size:34px">你想学会做什么？</h1>
    <p class="t-b" style="font-size:15px">用一句话说出你想完成的事。回答基于已核验的 CC Pocket 用户手册；这个页面不会连接你的电脑，也不会索取密钥、令牌、配对码或日志。</p>
  </div>
  ${fallback
      ? `<div class="banner col gap12">
      <div class="row gap10" style="align-items:flex-start"><span style="color:var(--warning);margin-top:1px">${ic('book', 20)}</span><span class="grow col" style="gap:3px"><b class="t-h2" style="font-size:16px">助手暂时不可用，你的问题已保留</b><span class="t-s">已按同一套任务分类在手册里检索，下面是最贴近的已核验指南。</span></span><span class="btn sm b-ghost">${ic('refresh', 16)}重试</span></div>
      <div class="card row gap10" style="padding:12px 13px"><span class="t-eyebrow" style="flex:none">你的问题</span><span class="grow" style="font-size:14px">怎么看 Agent 改了哪些文件？</span></div>
      <div class="card" style="padding:2px 14px">${[['查看本次会话的改动', '2026-07-24'], ['展开逐行 diff 与导出文件', '2026-07-20'], ['确认改动后再批准写入', '2026-07-18']].map(([t, d]) => `<div class="lrow"><span class="li">${ic('book', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${t}</b><span class="t-s mono" style="font-size:11.5px">最近核验 ${d}</span></span>${ic('right', 16)}</div>`).join('')}</div>
      <div class="row gap8" style="padding-top:2px"><span class="t-s grow">也可以直接按你想做的事浏览下面的分类。</span></div>
    </div>`
      : `<div class="composer" style="padding:16px">
      <div class="ph-txt" style="font-size:15.5px">例如：我想让它在我下班后再跑这条指令</div>
      <div class="cbar" style="margin-top:22px"><span class="t-s mono" style="font-size:11px">${ic('lock', 14)} 不要粘贴令牌、配对码或私有路径</span><span class="btn sm b-pri">开始学</span></div>
    </div>
    <div class="chips">${['怎么继续已有的会话？', '怎么看它改了什么？', '有哪些我还没用过的能力？'].map(t => chip(t, { tap: 1 })).join('')}</div>`}
  <div class="col gap10">
    <div class="sechead"><span class="t-eyebrow">常见的「怎么做」· 直接进入分步指南</span></div>
    <div class="grid2">${tasks.map(([i, t, s]) => `<div class="card row gap10" style="padding:12px 13px"><span style="color:var(--accent)">${ic(i, 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${t}</b><span class="t-s">${s}</span></span>${ic('right', 16)}</div>`).join('')}</div>
  </div>
  <div class="col gap10">
    <div class="sechead"><span class="t-eyebrow">按结果浏览能力</span></div>
    <div class="grid3">${cats.map(([i, t]) => `<div class="card row gap8" style="padding:11px 12px"><span style="color:var(--sec)">${ic(i, 16)}</span><span class="grow" style="font-size:13.5px;font-weight:500">${t}</span></div>`).join('')}</div>
  </div>
  <div class="row gap10" style="padding-top:4px;border-top:1px solid var(--hair);margin-top:2px">
    <span class="row gap8 grow" style="color:var(--sec);font-size:13.5px">${ic('book', 16)}完整用户手册</span>
    <span class="row gap8" style="color:var(--muted);font-size:13px">${ic('download', 16)}还没装 CC Pocket？看安装指南</span>
  </div>
</div></div>`;

  F({ label: 'FRAME 12A · 公开帮助页 · 桌面 1440', sub: 'task-first learning page · 你想学会做什么？', x: 80, y: WY, w: 1440, h: 960, html: `<div class="web" style="height:100%">${wnav}${webBody(false)}</div>` });

  F({
    label: 'FRAME 12B · 公开帮助页 · 移动 390', sub: 'composer + How do I… in the first viewport', x: 1600, y: WY, w: 390, h: 900,
    html: `<div class="web" style="height:100%">
<div class="wnav" style="padding:0 16px;gap:12px"><span class="row gap8 grow">${ic('mark', 18)}<b style="font-size:14.5px;font-weight:600">CC Pocket</b></span><span class="chip mono" style="height:26px">中文</span>${ic('search', 18)}</div>
<div class="wbody" style="padding:22px 16px"><div class="wwrap" style="gap:16px">
  <div class="col gap8"><h1 class="t-h1" style="font-size:24px">你想学会做什么？</h1><p class="t-s" style="line-height:1.6">回答基于已核验的用户手册。本页不会连接你的电脑，也不索取密钥或日志。</p></div>
  <div class="composer" style="padding:13px"><div class="ph-txt">说出你想完成的事…</div><div class="cbar" style="margin-top:16px"><span class="t-s mono" style="font-size:10.5px">${ic('lock', 12)} 勿粘贴令牌或配对码</span><span class="btn xs b-pri">开始学</span></div></div>
  <div class="chips">${['怎么继续已有的会话？', '怎么看它改了什么？', '我还没用过哪些能力？'].map(t => chip(t, { tap: 1 })).join('')}</div>
  <div class="col gap8"><span class="t-eyebrow">常见的「怎么做」</span><div class="card" style="padding:2px 13px">${tasks.slice(0, 4).map(([i, t, s]) => `<div class="lrow"><span class="li">${ic(i, 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${t}</b><span class="t-s" style="font-size:11.5px">${s}</span></span>${ic('right', 16)}</div>`).join('')}</div></div>
  <div class="col gap8"><span class="t-eyebrow">按结果浏览</span><div class="chips">${cats.map(([, t]) => chip(t, { tap: 1 })).join('')}</div></div>
  <div class="row gap8" style="border-top:1px solid var(--hair);padding-top:12px;color:var(--sec);font-size:13px">${ic('book', 16)}完整用户手册${ic('ext', 14)}</div>
</div></div></div>`
  });

  F({ label: 'FRAME 12C · 公开帮助页 · 助手不可用回退', sub: 'question preserved → goal-based search → verified guides', x: 2090, y: WY, w: 1440, h: 1120, html: `<div class="web" style="height:100%">${wnav}${webBody(true)}</div>` });

  ANN(3580, WY, `<h4>公开页规则 Public web</h4>
<dl><dt>framing</dt><dd>任务优先：「怎么做…」+ 能力目录，不做营销 hero、不做四宫格路径卡</dd>
<dt>context</dt><dd>空上下文起步：页面不知道 App 状态，也绝不声称能访问你的电脑</dd>
<dt>fallback</dt><dd>助手不可用：保留已输入问题 → 按同一套任务分类检索手册 → 给出已核验指南 → 重试；不呈现为服务故障页</dd>
<dt>secondary</dt><dd>完整手册与安装指南是次要出口；连接故障不在本页做诊断台</dd></dl>
<p style="margin-top:10px"><b>L10N</b><br>你想学会做什么？ → What do you want to learn?<br>开始学 → Start learning<br>常见的「怎么做」 → Common how-tos<br>按结果浏览能力 → Browse by outcome<br>完整用户手册 → Full user manual<br>助手暂时不可用，你的问题已保留 → Assistant is unavailable · your question is kept</p>`, 250);
})();
