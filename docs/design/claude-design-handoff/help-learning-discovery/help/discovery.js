// FRAMES 9–10 — Explore capability library + progressive-discovery board
(function () {
  const dcard = (group, icon, t, val, avail, state) => `<div class="card row gap10" style="padding:7px 12px;align-items:flex-start">
<span style="width:28px;height:28px;border-radius:8px;display:grid;place-items:center;flex:none;background:var(--raised);color:var(--accent);margin-top:${group ? '12px' : '1px'}">${ic(icon, 16)}</span>
<span class="grow col" style="gap:2px;min-width:0">
  ${group ? `<span class="t-eyebrow" style="font-size:10px">${group}</span>` : ''}
  <span class="row gap8"><b class="t-h3" style="font-size:14px">${t}</b>${state ? `<span class="pill ${state[1]}">${state[0]}</span>` : ''}</span>
  <span class="t-s" style="line-height:1.4">${val}</span>
  <span class="row gap8" style="margin-top:1px"><span class="pill ${avail[1]}">${avail[0]}</span><span class="grow"></span><span class="btn xs ${avail[1] === 'p-mut' ? 'b-quiet' : 'b-ghost'}" style="height:28px">查看介绍</span></span>
</span></div>`;

  // ── FRAME 9 · Explore ───────────────────────────────────────
  F({
    label: 'FRAME 09 · 发现更多能力 (Explore)', sub: '390×844 · organized by outcome · why it matters + prerequisite + tried state', thread: '红线 3', x: 1880, y: 3100, w: 390, h: 844,
    html: `<div class="ph">${sb()}
${abar('发现更多能力')}
<div class="scr" style="gap:7px;padding:14px 16px 10px">
  <div class="row gap10" style="height:44px;padding:0 12px;border:1px solid var(--hair);border-radius:12px;background:var(--surface);color:var(--muted);flex:none">${ic('search', 18)}<span style="font-size:14px">搜索你想做的事，例如「改动」</span></div>
  <div class="row gap10" style="flex:none"><span class="t-s" style="flex:1">已体验 5 / 18</span><div class="prog" style="width:110px;align-self:center"><i style="width:28%"></i></div></div>
  ${dcard('离开电脑也能继续', 'term', '无分叉接管终端会话', '把电脑上跑着的会话直接接过来，不新建分支。', ['可用', 'p-ok'])}
  ${dcard('看懂它改了什么', 'diff', '查看本次会话的全部改动', '放行前先确认它动了哪些文件，可展开逐行 diff。', ['可用', 'p-ok'], ['已体验', 'p-ok'])}
  ${dcard('安排它稍后执行', 'clock', '长按发送，预约稍后执行', '不必守着屏幕：选 30 分钟、1/3/8 小时或自定义时间。', ['可用', 'p-ok'], ['新', 'p-new'])}
  ${dcard('更快地告诉 Agent', 'clip', '发送文件或录屏', '日志、图片、录屏都能当上下文，省掉描述。', ['可用', 'p-ok'])}
  ${dcard('在多台电脑间工作', 'layers', '多台电脑集中审批', '一个收件箱处理所有电脑的审批，不用来回切换。', ['需要 2 台以上电脑', 'p-mut'])}
  ${dcard('让多个 Agent 协作', 'cpu', '让多个 Agent 组成 Workflow', '把大任务拆成子任务并行推进。', ['需要 daemon 1.5.0+', 'p-warn'])}
</div></div>`
  });

  // ── FRAME 10 · progressive-discovery board ──────────────────
  const crop = (title, h, inner, theme) => `<div class="col gap8" style="flex:1;min-width:0">
<div class="row gap8"><span class="t-eyebrow" style="color:#7A8086">${title}</span></div>
<div class="frame" style="position:relative;left:0;top:0;width:100%;height:${h}px;border:1px solid ${theme === 'light' ? '#E7E4DF' : '#2A2E33'};border-radius:12px;overflow:hidden" data-theme="${theme || 'dark'}">${inner}</div></div>`;

  const tipbox = (t, s, cta = '查看步骤') => `<div class="tip" style="position:relative"><span style="color:var(--accent);margin-top:1px">${ic('zap', 16)}</span><span class="grow col" style="gap:6px"><span class="col" style="gap:2px"><b class="t-h3" style="font-size:13px">${t}</b><span class="t-s">${s}</span></span><span class="row gap8"><span class="btn xs b-pri">${cta}</span><span class="btn xs b-quiet">知道了</span></span></span></div>`;

  const v1 = `<div class="col" style="height:100%;background:var(--base)">
<div class="grow col gap10" style="padding:14px;overflow:hidden;justify-content:flex-end">
  <div class="bub" style="max-width:86%;font-size:12.5px">已更新 <span class="mono">README.md</span>，共 2 处改动。</div>
</div>
<div style="padding:0 12px 10px">${tipbox('长按发送可预约稍后执行', '选好时间就不用守着屏幕，它会替你按时发出。', '打开定时发送')}</div>
<div style="padding:0 12px 12px">${composer('继续输入指令…', { typed: 1 })}</div></div>`;

  const v2 = `<div class="col gap12" style="height:100%;background:var(--base);padding:14px">
<div class="row gap8"><span class="t-eyebrow">本次会话</span><span class="grow"></span><span class="pill p-ok">3 个文件已改</span></div>
<div class="card col gap10" style="border-color:color-mix(in oklab,var(--accent) 34%,var(--hair))">
  <div class="row gap10"><span style="width:30px;height:30px;border-radius:9px;display:grid;place-items:center;background:var(--accent-bg);color:var(--accent);flex:none">${ic('diff', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">看懂它改了什么</b><span class="t-s">展开逐行 diff，确认每一处再让它继续。</span></span></div>
  <div class="row gap8"><span class="btn xs b-pri">打开改动</span><span class="btn xs b-quiet">以后再说</span></div>
</div>
<div class="card" style="padding:2px 12px">
  ${['README.md', 'src/pair.ts', 'src/relay.ts'].map((f, i) => `<div class="lrow" style="min-height:40px;padding:9px 0"><span class="mono grow" style="font-size:11.5px;color:var(--text)">${f}</span><span class="mono" style="font-size:11px;color:var(--success)">+${[6, 24, 11][i]}</span><span class="mono" style="font-size:11px;color:var(--danger)">−${[2, 5, 1][i]}</span></div>`).join('')}
</div></div>`;

  const v3 = `<div class="col" style="height:100%;background:var(--base)">
<div class="abar" style="height:48px"><span class="row gap8 grow">${ic('term', 18)}<b style="font-size:14px;font-weight:600">电脑上有会话在跑</b></span><span class="dot ok"></span></div>
<div class="grow col gap8" style="padding:14px;overflow:hidden">
  <div class="card row gap10" style="padding:11px 12px"><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">修复中继重连</b><span class="t-s mono" style="font-size:11px">Claude Code · 运行中 12 分钟</span></span><span class="dot ok"></span></div>
  ${tipbox('可以直接接管这个会话', '不新建分支、不打断它，接过来继续对话就行。')}
</div></div>`;

  const v4 = `<div class="col" style="height:100%;background:var(--base)">
<div class="abar" style="height:48px"><span class="row gap8 grow"><span class="dot ok"></span><b style="font-size:14px;font-weight:600">Mac mini</b><span style="color:var(--muted)">${ic('down', 14)}</span></span><span class="ib">${ic('help', 20)}</span></div>
<div style="padding:8px 12px 0">
  <div class="tip" style="position:relative">${ic('layers', 16)}<span class="grow col" style="gap:6px"><span class="col" style="gap:2px"><b class="t-h3" style="font-size:13px">这些审批可以集中处理</b><span class="t-s">你已连续批准 3 次：用一个收件箱看两台电脑的请求。</span></span><span class="row gap8"><span class="btn xs b-pri">查看步骤</span><span class="btn xs b-quiet">知道了</span></span></span></div>
</div>
<div class="grow col gap8" style="padding:14px">${['relay-core', 'daemon-ci'].map(n => `<div class="card row gap10" style="padding:11px 12px">${ic('folder', 16)}<span class="grow mono" style="font-size:12px">${n}</span><span class="pill p-warn">待审批</span></div>`).join('')}</div></div>`;

  const v5 = `<div class="col gap12" style="height:100%;background:var(--base);padding:14px">
<div class="row gap8"><span class="t-eyebrow">帮助中心 · 最近新增</span></div>
<div class="card col gap10">
  <div class="row gap8"><span class="pill p-new">1.5.1</span><span class="grow"></span><span style="color:var(--muted)">${ic('x', 16)}</span></div>
  ${[['桌面端托盘直接批准权限', '不用切回窗口也能放行。'], ['定时任务可按天重复', '每天固定时间跑同一条指令。']].map(([t, s]) => `<div class="row gap10" style="align-items:flex-start"><span class="col grow" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${t}</b><span class="t-s">${s}</span></span><span class="btn xs b-quiet">查看介绍</span></div>`).join('')}
  <p class="t-s" style="border-top:1px solid var(--hair);padding-top:9px">最多三条 · 不做启动弹窗 · 打开后角标即清除</p>
</div></div>`;

  const v6 = `<div class="col gap10" style="height:100%;background:var(--base);padding:14px">
<div class="row gap8"><span class="t-eyebrow">Explore · 状态留存</span></div>
${dcard('', 'clock', '长按发送，预约稍后执行', '被忽略两次后不再主动出现，但永久留在这里。', ['可用', 'p-ok'], ['已忽略 2 次', 'p-mut'])}
${dcard('', 'term', '无分叉接管终端会话', '你已经用过一次，状态可见但不喧哗。', ['可用', 'p-ok'], ['已体验', 'p-ok'])}
${dcard('', 'cpu', '让多个 Agent 组成 Workflow', '当前 daemon 版本不支持，先给出前置条件。', ['需要 daemon 1.5.0+', 'p-warn'], ['不可用', 'p-mut'])}</div>`;

  const rules = [
    ['1 · 长按发送 → 预约', '首次成功发送并收到回复后', '同一会话最多 1 条；流式输出、权限请求、弹窗刚关闭时不出现', '每 App 会话 1 次', '「知道了」= 忽略 1 次；2 次后不再主动出现', 'Composer → 长按发送面板'],
    ['2 · 首次改动 → diff 视图', '首次出现文件改动且这一轮输出结束后', '与 1 号提示互斥；用过 diff 后永久不再提示', '每次会话 1 次', '「以后再说」不影响 Explore 可见性', '会话 → 改动文件 → 逐行 diff'],
    ['3 · 终端在跑 → 无分叉接管', '打开项目时检测到电脑上有活跃会话', '仅在会话列表页内联显示；不覆盖会话卡的主操作', '每 App 会话 1 次', '忽略后仅保留在 Explore', '会话列表 → 接管会话'],
    ['4 · 反复审批 → 集中审批', '同一天连续批准 3 次，且已配对 2 台以上电脑', '不在权限请求弹出时显示；不遮挡批准按钮', '生命周期 1 次', '忽略即永久停止主动出现', '电脑切换器 / 集中审批收件箱'],
    ['5 · What\u2019s new 摘要', 'App 升级后首次打开帮助中心', '最多 3 条；不做启动弹窗；不与 New 角标、tooltip、toast 叠加', '每版本 1 次', '打开或使用该功能后角标清除', '对应功能的实际界面'],
    ['6 · Explore 状态', '常驻，无触发', '不需要抑制：Explore 是所有提示的永久归宿', '常驻', '被忽略与已体验状态可见但克制', '功能深链 + 完整指南']
  ];

  const board = `<div class="board" style="height:100%;display:flex;flex-direction:column;gap:16px">
<div class="row gap16" style="align-items:flex-start">
  <div style="flex:1"><b style="font-size:15px;font-weight:600">渐进式发现组件板 Progressive discovery</b><p class="mono" style="font-size:11.5px;line-height:1.6;color:#8A9096;margin:6px 0 0">每条提示都由一个「刚好用得上」的行为时刻触发，以内联卡片出现在相关界面里，不做居中弹窗、不做覆盖层高亮。没有由错误触发的提示。</p></div>
  <div class="bx acc" style="width:420px"><p><b>全局抑制规则</b><br>每个 App 会话最多 1 条主动提示 · 功能被使用过即不再提示 · 忽略两次即停止主动出现（仍留在 Explore）· 同一功能不叠加 New 角标 / tooltip / toast · 无障碍与减弱动效设置不影响功能在帮助中心内的可发现性。</p></div>
</div>
<div class="row gap14" style="align-items:stretch">${crop('1 · 长按发送 → 预约稍后执行 · dark', 330, v1)}${crop('2 · 首次改动 → diff 视图 · dark', 330, v2)}${crop('3 · 终端在跑 → 无分叉接管 · dark', 330, v3)}</div>
<div class="row gap14" style="align-items:stretch">${crop('4 · 反复审批 → 集中审批 · dark', 330, v4)}${crop('5 · What\u2019s new 安静卡片 · dark', 330, v5)}${crop('6 · Explore 中的忽略 / 已体验状态 · dark', 330, v6)}</div>
<div class="row gap14" style="align-items:stretch">${crop('1 · 浅色变体', 300, v1, 'light')}${crop('2 · 浅色变体', 300, v2, 'light')}${crop('5 · 浅色变体', 300, v5, 'light')}</div>
<div class="bx" style="padding:14px">
  <table class="tbl"><thead><tr><th style="width:190px">变体</th><th>触发 trigger</th><th>抑制 suppression</th><th class="m" style="width:120px">频次</th><th>忽略行为</th><th>深链目标</th></tr></thead>
  <tbody>${rules.map(r => `<tr><td><b>${r[0]}</b></td><td>${r[1]}</td><td>${r[2]}</td><td class="m">${r[3]}</td><td>${r[4]}</td><td>${r[5]}</td></tr>`).join('')}</tbody></table>
</div></div>`;

  F({ label: 'FRAME 10 · 渐进式发现组件板', sub: 'behavioral moments that reveal hidden capabilities · no error-triggered tips', thread: '红线 3', x: 80, y: 4110, w: 1720, h: 1560, html: board });

  ANN(1860, 4110, `<h4>L10N · 发现层</h4>
<p>发现更多能力 → Explore capabilities<br>已体验 5 / 18 → 5 of 18 tried<br>查看介绍 → What it does<br>查看步骤 → See the steps<br>打开定时发送 → Open scheduled send<br>打开改动 → Open changes<br>知道了 → Got it<br>可用 → Ready<br>需要 2 台以上电脑 → Needs 2+ computers<br>需要 daemon 1.5.0+ → Needs daemon 1.5.0+<br>已忽略 2 次 → Dismissed twice<br>已体验 → Tried<br>不可用 → Unavailable<br>最近新增 → Recently added</p>
<h4 style="margin-top:14px">按结果分组 Outcome taxonomy</h4>
<p>1 离开电脑也能继续<br>2 更快地告诉 Agent<br>3 安排它稍后执行<br>4 看懂它改了什么<br>5 让多个 Agent 协作<br>6 在多台电脑间工作<br>7 安全地分享能力<br>8 选择合适的 Agent 与模型</p>
<p style="margin-top:10px"><b>同一套任务分类</b>同时驱动手册搜索、Explore 目录与助手检索：搜索「怎么看改动」、用句子提问、或点会话里的微提示，都会收敛到同一条学习路径。</p>
<p><b>指标</b>能力「发现→使用」转化率、新学会能力的 7 日复用率，而不是提示曝光量。</p>`, 250);
})();
