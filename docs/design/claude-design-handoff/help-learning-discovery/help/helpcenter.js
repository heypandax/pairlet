// FRAMES 5–8 — Help Center Ready, task guide, grounded how-to, context share & location guide
(function () {
  const Y = 2130, Y2 = 3100;

  // ── FRAME 5 · Help Center · Ready (learning-first) ──────────
  const ready = () => `<div class="ph">${sb()}
${abar('帮助中心', `<span class="ib">${ic('search', 20)}</span>`)}
<div class="scr" style="gap:9px;padding-bottom:14px">
  ${composer('你想学会做什么？')}
  <div class="chips" style="margin-top:-3px">${['继续一个已有会话', '看它改了哪些文件'].map(t => chip(t, { tap: 1 })).join('')}</div>
  <div class="sechead" style="margin-top:0"><span class="t-eyebrow">接着学这个</span><span class="t-s mono" style="font-size:11px">你还没用过</span></div>
  <div class="card col gap8" style="border-color:color-mix(in oklab,var(--accent) 38%,var(--hair))">
    <div class="row gap10"><span style="width:32px;height:32px;border-radius:9px;display:grid;place-items:center;background:var(--accent-bg);color:var(--accent);flex:none">${ic('diff', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">看懂 Agent 到底改了什么</b><span class="t-s">按文件看改动、展开逐行 diff，确认后再让它继续。</span></span></div>
    <div class="row gap8"><span class="btn xs b-pri">查看步骤</span><span class="btn xs b-quiet">读完整指南</span></div>
  </div>
  <div class="sechead"><span class="t-eyebrow">按你想做的事</span></div>
  <div class="col gap8">
    ${goal('term', '继续一台电脑上的工作', '恢复会话、接管终端、批准权限')}
    ${goal('clock', '让它按时间替我执行', '预约稍后发送、按天重复')}
    ${goal('compass', '看看我还能做什么', '已体验 5 / 18')}
  </div>
  <div class="sechead"><span class="t-eyebrow">最近新增</span><span class="pill p-new">2</span></div>
  <div class="card row gap10" style="padding:10px 12px"><span class="grow col" style="gap:2px"><b style="font-size:13.5px;font-weight:500">桌面端托盘直接批准权限</b><span class="t-s mono" style="font-size:11px">1.5.1 · 还有 1 条</span></span><span class="btn xs b-quiet">查看介绍</span></div>
  <div class="grow"></div>
  <div class="disc" style="border-top:1px solid var(--hair);height:34px"><span class="row gap8" style="color:var(--sec)">${ic('book', 16)}浏览全部指南</span><span style="color:var(--muted)">${ic('ext', 16)}</span></div>
</div>${hbar()}</div>`;

  F({ label: 'FRAME 05 · 帮助中心 · 已激活', sub: '390×844 · dark · 你想学会做什么？+ one teaching recommendation', thread: '红线 3', x: 80, y: Y, w: 390, h: 844, html: ready() });
  F({ label: 'FRAME 05L · 浅色变体', sub: 'light theme parity', x: 530, y: Y, w: 390, h: 844, theme: 'light', html: ready() });

  // ── FRAME 6 · Learn a task ──────────────────────────────────
  F({
    label: 'FRAME 06 · 学会一件事', sub: 'TaskStepList · 静态步骤 + 位置说明 + 一个直接动作', thread: '红线 2', x: 980, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}
${abar('学会一件事')}
<div class="scr" style="gap:10px">
  <div class="card col gap12" style="border-color:color-mix(in oklab,var(--info) 40%,var(--hair));background:color-mix(in oklab,var(--info) 6%,var(--surface))">
    <div class="col gap5" style="gap:5px">
      <span class="t-eyebrow" style="color:var(--info)">任务指南 · 共 4 步</span>
      <b class="t-h2" style="font-size:18px;letter-spacing:-.025em">继续一个已有会话并查看改动</b>
      <p class="t-s" style="line-height:1.55">离开电脑后接着干：把电脑上跑着的会话拉到手机上，看清它改了哪些文件，再决定放行还是补充要求。</p>
    </div>
  </div>
  <div class="col gap8">
    <div class="step"><span class="n">1</span><span class="grow col" style="gap:2px;padding-bottom:2px"><b class="t-h3" style="font-size:13.5px">打开电脑上已有的会话</b><span class="t-s">项目页 → 会话列表里带绿点的那条</span></span></div>
    <div class="card col gap10" style="border-color:color-mix(in oklab,var(--accent) 40%,var(--hair))">
      <div class="step"><span class="n on">2</span><span class="grow col" style="gap:3px"><b class="t-h3" style="font-size:14px">打开「改动文件」看它做了什么</b><span class="t-s">会话顶部的文件计数就是入口，点开按文件聚合，可展开逐行 diff。</span></span></div>
      <div class="row gap8" style="padding:9px 11px;border-radius:10px;background:var(--raised)">${ic('diff', 16)}<span class="t-s grow" style="color:var(--sec)">显示在哪里：会话页顶部 · <b style="color:var(--text);font-weight:500">2 个文件已改动</b></span></div>
      <div class="row gap8"><span class="btn sm b-pri grow">打开改动</span><span class="btn sm b-quiet">读完整指南</span></div>
    </div>
    <div class="step"><span class="n">3</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">确认改动后批准或补充要求</b><span class="t-s">批准写入后它才会继续下一步</span></span></div>
    <div class="step"><span class="n">4</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">让它在你离开时继续跑</b><span class="t-s">关掉 App 也不会中断电脑上的会话</span></span></div>
  </div>
  ${srcrow('恢复会话与查看改动', '2026-07-24')}
  <div class="grow"></div>
  <div class="row gap8" style="border-top:1px solid var(--hair);padding-top:10px"><span class="t-s grow">这些步骤只是说明，不会自动记录你做到哪一步。</span><span class="btn xs b-quiet">换一个任务</span></div>
</div>${hbar()}</div>`
  });

  // ── FRAME 7 · grounded how-to answer ────────────────────────
  const answer = (notyet) => `<div class="ph">${sb()}
${abar('问问 CC Pocket 助手')}
<div class="scr" style="gap:10px">
  <div class="row gap8" style="justify-content:flex-end;flex-wrap:wrap">${chip('会话页', { on: 1, x: 1 })}${chip('Claude Code', { x: 1 })}${chip('iOS · 1.5.1', { x: 1 })}</div>
  <div class="bub me" style="max-width:80%;margin-left:auto;background:var(--accent-bg);border:1px solid transparent;border-radius:12px;padding:11px 13px;font-size:14px;line-height:1.5">怎么看它改了哪些文件？</div>
  <div class="card col gap11" style="gap:11px">
    <div class="col gap5" style="gap:5px">
      <p style="margin:0;font-size:14.5px;line-height:1.5;font-weight:500">「改动文件」把这次会话里 Agent 动过的文件聚在一起，可以展开逐行 diff。</p>
      ${notyet ? '' : `<p class="t-s" style="line-height:1.55"><b style="color:var(--sec);font-weight:500">什么时候有用：</b>放行下一步之前先确认它改了什么，或只导出某一个文件的改动。</p>`}
    </div>
    <div class="card" style="background:var(--raised);padding:12px;border-color:transparent">
      <div class="step" style="margin-bottom:${notyet ? '0' : '9px'}"><span class="n on">1</span><span class="grow col" style="gap:3px"><b class="t-h3" style="font-size:14px">点会话顶部的文件计数</b><span class="t-s">${notyet ? '位置：会话标题下方的一行文件计数。' : '就在会话标题下方，写着「2 个文件已改动」。'}</span></span></div>
      ${notyet ? '' : `<div class="row gap8" style="padding:9px 11px;border-radius:9px;background:var(--surface);border:1px solid var(--hair)">${ic('diff', 16)}<span class="t-s grow" style="color:var(--text)">2 个文件已改动 · +30 −7</span>${ic('right', 14)}</div>
      <div class="row gap8" style="margin-top:10px"><span class="btn xs b-pri">打开改动</span><span class="btn xs b-quiet">读完整指南</span></div>`}
    </div>
    <div class="row gap8" style="color:var(--muted);opacity:${notyet ? '1' : '.55'}"><span class="step" style="flex:1"><span class="n">2</span><span class="t-s" style="color:${notyet ? 'var(--sec)' : 'var(--muted)'};align-self:center">${notyet ? '换一种更简单的讲法' : '展开逐行 diff，按需求导出'}</span></span>${ic(notyet ? 'up' : 'down', 16)}</div>
    ${notyet ? `<div class="card col gap10" style="background:var(--raised);border-color:transparent;padding:12px">
      <p style="margin:0;font-size:13.5px;line-height:1.5">你现在屏幕上看到的是哪种？</p>
      <div class="col gap6">${['会话正在输出，没看到计数', '我不确定自己在哪个页面'].map(o => `<span class="btn sm b-ghost" style="justify-content:flex-start">${o}</span>`).join('')}</div>
      <p class="t-s" style="border-top:1px solid var(--hair);padding-top:9px">换一种讲法：计数只在它改过文件后才出现，等这一轮说完就会冒出来。</p>
    </div>` : ''}
    ${srcrow('查看本次会话的改动', '2026-07-24')}
    ${notyet ? `<div class="row gap8" style="padding-top:2px"><span class="t-s" style="flex:1">还想换个方式？</span><span class="btn xs b-quiet">${ic('compass', 14)}看完整指南</span></div>` : learned()}
  </div>
  ${notyet ? '' : '<div class="grow"></div>'}
  ${composer('继续追问…')}
</div>${hbar()}</div>`;

  F({ label: 'FRAME 07A · 助手 · 教学式回答', sub: '做什么 → 什么时候有用 → 一步动作 + 显示在哪里 → 来源 → 我会用了', thread: '红线 2', x: 1430, y: Y, w: 390, h: 844, html: answer(false) });
  F({ label: 'FRAME 07B · 「还没明白」分支', sub: 'one clarifying question about what you see, then a simpler retelling', thread: '红线 2', x: 1880, y: Y, w: 390, h: 844, html: answer(true) });
  F({ label: 'FRAME 07L · 浅色变体', sub: 'light theme parity', x: 2330, y: Y, w: 390, h: 844, theme: 'light', html: answer(false) });

  ANN(2820, Y, `<h4>行为标注 · 学会一件事</h4>
<dl><dt>trigger</dt><dd>用户在帮助中心输入任务意图，或在某个界面点「这里怎么用」</dd>
<dt>state</dt><dd>App 已知：当前界面 · 平台 · 所选 Agent · 可见控件</dd>
<dt>next</dt><dd>一次只给一步，并说明「显示在哪里」</dd>
<dt>done</dt><dd>用户自己点「我会用了」→ 能力在 Explore 标记「已体验」；App 不检测真实点击、也不自动推进步骤</dd>
<dt>suppress</dt><dd>学习任务进行中不插入任何主动发现提示</dd></dl>
<p style="margin-top:10px"><b>回答语法 Answer grammar</b><br>1 这个能力做什么<br>2 什么时候有用<br>3 一个下一步动作<br>4 静态「显示在哪里」<br>5 可选的书面步骤（无进度追踪）<br>6 已核验手册来源 + 核验日期<br>7 我会用了 / 还没明白</p>
<p><b>「还没明白」不是诊断</b><br>先问一个关于「你现在看到什么」的澄清问题，然后换更简单的讲法或给更精确的位置说明，不重复整篇文章、不引导报障。</p>
<p><b>L10N</b><br>你想学会做什么？ → What do you want to learn?<br>查看步骤 → See the steps<br>打开改动 → Open changes<br>打开定时发送 → Open scheduled send<br>显示在哪里 → Where to find it<br>我会用了 → I've got it<br>还没明白 → Still unclear<br>为什么值得用 → Why it matters<br>看看我还能做什么 → See what else I can do</p>
<p><b>运行时故障的位置</b><br>离线、重连、配对失效等只在失败现场保留一个就近入口（命名状态 + 一个动作），不进入本旅程、不做模式、不做推荐。</p>`, 250);

  // ── FRAME 8A · context share for better teaching ────────────
  const shareA = () => `<div class="ph">${sb()}
${abar('问问 CC Pocket 助手')}
<div class="scr">
  <div class="card col gap12">
    <div class="col gap4"><b class="t-h2" style="font-size:16px">让回答贴合你现在的屏幕</b><span class="t-s">带上这些信息，助手才能说清按钮在哪。你可以逐条移除。</span></div>
    <div class="col gap8">
      ${[['当前界面', '会话页 · ark-nexus/relay'], ['平台', 'iOS 18.5 · iPhone'], ['所选 Agent', 'Claude Code · Sonnet 4.6'], ['可见控件', '改动文件 · 权限请求 · 发送']].map(([k, v]) => `<div class="row gap10" style="padding:9px 11px;border:1px solid var(--hair);border-radius:10px;background:var(--raised)"><span class="col grow" style="gap:2px"><span class="t-eyebrow" style="font-size:10px">${k}</span><span class="mono" style="font-size:12px;color:var(--text)">${v}</span></span><span class="pill p-info">App 已知</span><span style="color:var(--muted)">${ic('x', 16)}</span></div>`).join('')}
    </div>
    <div class="card col gap8" style="background:transparent;border-color:color-mix(in oklab,var(--danger) 30%,var(--hair));padding:11px 12px">
      <div class="row gap8" style="color:var(--danger)">${ic('eyeoff', 16)}<b class="t-h3" style="font-size:13px">不会读取、也不会发送</b></div>
      <p class="t-s" style="line-height:1.6">仓库名与完整路径 · 会话记录与 prompt 内容 · 源码与文件内容 · token / API key / 配对码 · 原始日志与环境变量</p>
    </div>
    <div class="col gap8"><span class="btn blk b-pri">带上界面信息提问</span><span class="btn blk b-ghost">只发我的问题</span></div>
  </div>
  <div class="grow"></div>
  <div class="t-s" style="text-align:center">只在首次提问前出现一次；之后 chip 直接显示在输入框上方</div>
</div>${hbar()}</div>`;

  // ── FRAME 8B · static location guide ────────────────────────
  const locB = () => `<div class="ph">${sb()}
${abar('操作位置说明')}
<div class="scr">
  <div class="row gap8"><span class="pill p-info">位置说明</span><span class="pill p-mut">静态文字</span><span class="grow"></span><span class="t-s mono" style="font-size:11px">不接管界面</span></div>
  <div class="card col gap10">
    <div class="col gap4"><span class="t-eyebrow">你要找的入口</span><b class="t-h3" style="font-size:14.5px">「改动文件」——本次会话动过的文件</b></div>
    <hr class="hair">
    <div class="col gap6">
      <span class="t-eyebrow">显示在哪里</span>
      <div class="row gap6" style="flex-wrap:wrap;align-items:center">${['会话页', '标题下方一行', '2 个文件已改动'].map((s, i, a) => `<span class="mono" style="font-size:11.5px;color:${i === a.length - 1 ? 'var(--text)' : 'var(--sec)'};padding:4px 8px;border-radius:7px;background:var(--raised)">${s}</span>${i < a.length - 1 ? `<span style="color:var(--muted)">${ic('right', 14)}</span>` : ''}`).join('')}</div>
      <div class="row gap8" style="padding:9px 11px;border-radius:9px;background:var(--surface);border:1px solid var(--hair)">${ic('diff', 16)}<span class="t-s grow" style="color:var(--text)">2 个文件已改动 · +30 −7</span>${ic('right', 14)}</div>
    </div>
  </div>
  <div class="card col gap9" style="gap:9px;border-color:color-mix(in oklab,var(--accent) 38%,var(--hair))">
    <div class="row gap8" style="color:var(--accent)">${ic('diff', 16)}<b class="t-h3" style="font-size:13px">可以直接打开</b></div>
    <span class="btn blk b-pri">打开改动</span>
    <p class="t-s" style="line-height:1.6">这个按钮只做一件事：打开本次会话的改动列表（按文件聚合，可展开逐行 diff）。它不会发送指令、不批准权限，也不改动文件。</p>
  </div>
  <div class="card col gap9" style="gap:9px">
    <div class="row gap8" style="color:var(--sec)">${ic('compass', 16)}<b class="t-h3" style="font-size:13px">手动路径（找不到时）</b></div>
    <div class="col gap6">${[['1', '回到会话页'], ['2', '看标题正下方的文件计数一行'], ['3', '点它，按文件查看；再点文件名展开逐行 diff']].map(([n, t]) => `<div class="row gap8" style="align-items:flex-start"><span class="mono t-s" style="width:12px;flex:none">${n}</span><span class="t-s grow" style="color:var(--sec)">${t}</span></div>`).join('')}</div>
    <p class="t-s" style="border-top:1px solid var(--hair);padding-top:9px">计数只在它改过文件后才出现；这一轮输出结束就会显示。</p>
  </div>
  ${srcrow('查看本次会话的改动', '2026-07-24')}
  <div class="grow"></div>
  <div class="row gap8" style="border-top:1px solid var(--hair);padding-top:10px"><span class="t-s grow">位置说明来自已核验手册，不会替你点击或接管界面。</span><span class="btn xs b-quiet">读完整指南</span></div>
</div>${hbar()}</div>`;

  F({ label: 'FRAME 08A · 上下文共享（教学用）', sub: 'ContextChip preview · current screen, platform, agent, visible controls', x: 80, y: Y2, w: 390, h: 844, html: shareA() });
  F({ label: 'FRAME 08AL · 浅色变体', sub: 'light theme parity', x: 530, y: Y2, w: 390, h: 844, theme: 'light', html: shareA() });
  F({ label: 'FRAME 08B · 操作位置说明', sub: 'LocationHint + DirectActionLink · static breadcrumb, one-shot action, manual fallback', thread: '红线 3', x: 980, y: Y2, w: 390, h: 844, html: locB() });
  F({ label: 'FRAME 08BL · 浅色变体', sub: 'light theme parity', x: 1430, y: Y2, w: 390, h: 844, theme: 'light', html: locB() });
})();
