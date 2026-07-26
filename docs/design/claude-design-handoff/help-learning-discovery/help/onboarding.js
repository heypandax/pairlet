// FRAMES 2–4 — first launch, guided setup, connected + first-success checklist
(function () {
  const Y = 1160, ANNY = 1160;

  // ── FRAME 2 · stage choice ──────────────────────────────────
  const stageCard = (icon, t, s, primary) => `<div class="card row gap12" style="padding:16px;${primary ? 'border-color:var(--accent);background:var(--accent-bg)' : ''}">
<span style="width:40px;height:40px;border-radius:11px;display:grid;place-items:center;flex:none;background:${primary ? 'var(--accent)' : 'var(--raised)'};color:${primary ? '#0E0F11' : 'var(--sec)'}">${ic(icon, 20)}</span>
<span class="grow col" style="gap:3px"><b style="font-size:15.5px;font-weight:600;letter-spacing:-.02em">${t}</b><span class="t-s">${s}</span></span>
<span style="color:${primary ? 'var(--accent)' : 'var(--muted)'}">${ic('right', 18)}</span></div>`;

  F({
    label: 'FRAME 02 · 首次启动 · 阶段选择', sub: '390×844 · dark · stage choice replaces QR-first', thread: '红线 1', x: 80, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}
<div class="scr" style="gap:0">
  <div style="height:26px"></div>
  <span style="width:44px;height:44px;border-radius:12px;display:grid;place-items:center;background:var(--accent-bg);color:var(--accent)">${ic('mark', 22)}</span>
  <h1 class="t-h1" style="margin:20px 0 10px">连接你的电脑</h1>
  <p class="t-b">编码 Agent 运行在你的电脑上，手机是它的遥控器。先确认电脑端的状态，我们再决定下一步。</p>
  <div style="height:24px"></div>
  <div class="col gap10">${stageCard('qr', '电脑端已经安装', '扫码或输入配对码', true)}${stageCard('monitor', '还没有安装', '用两步完成电脑端设置')}</div>
  <div class="grow"></div>
  <span class="btn blk b-quiet" style="margin-bottom:12px">先体验 Demo</span>
  <div class="row gap8" style="color:var(--muted)">${ic('shield', 16)}<span class="t-s" style="flex:1">无需账号 · 端到端中继无法读取会话内容</span></div>
</div>${hbar()}</div>`
  });

  // ── FRAME 3A · install ──────────────────────────────────────
  const setupHead = (n, pct) => `<div class="col gap10" style="padding:14px 16px 13px;border-bottom:1px solid var(--hair)">
<div class="row"><span class="ib" style="width:30px;height:30px;display:grid;place-items:center;color:var(--sec);margin-left:-6px">${ic('back', 20)}</span><span class="grow t-eyebrow" style="text-align:center">开始使用 · 第 ${n} 步 / 3</span><span style="width:24px"></span></div>
<div class="prog"><i style="width:${pct}%"></i></div></div>`;

  const seg = (act) => `<div class="row" style="gap:0;padding:3px;border:1px solid var(--hair);border-radius:11px;background:var(--surface)">${['macOS', 'Windows', 'Linux'].map(o => `<span style="flex:1;height:34px;display:grid;place-items:center;border-radius:8px;font:${o === act ? '600' : '500'} 13px/1 'Inter',system-ui;${o === act ? 'background:var(--accent-bg);color:var(--accent)' : 'color:var(--sec)'}">${o}</span>`).join('')}</div>`;

  F({
    label: 'FRAME 03A · 引导设置 · 安装电脑端', sub: 'Step 1 of 3 · OS segmented + one real command', thread: '红线 1', x: 560, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}${setupHead(1, 33)}
<div class="scr">
  <h2 class="t-h2" style="font-size:20px;margin-top:4px">在电脑上安装 CC Pocket 守护进程</h2>
  <p class="t-b">在你要用来跑 Claude Code、Codex 或 OpenCode 的那台电脑上执行。手机不需要任何操作。</p>
  ${seg('macOS')}
  ${cmd('brew install --cask heypandax/tap/cc-pocket', 'done', 'wrap')}
  <div class="row gap8" style="color:var(--muted);align-items:flex-start">${ic('term', 16)}<span class="t-s" style="flex:1">在电脑的「终端」里运行（macOS：Command + 空格 搜索 Terminal）。安装约需 1 分钟。</span></div>
  <div class="grow"></div>
  <div class="col gap8">
    <span class="btn blk b-pri">我已运行安装命令</span>
    <span class="row gap6" style="justify-content:center;color:var(--sec);font-size:13px">${ic('help', 16)}安装命令报错了？</span>
  </div>
</div>${hbar()}</div>`
  });

  // ── FRAME 3B · pair ─────────────────────────────────────────
  F({
    label: 'FRAME 03B · 引导设置 · 生成配对码', sub: 'Step 2 of 3 · pair command + QR / six-digit fallback', thread: '红线 1', x: 1010, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}${setupHead(2, 66)}
<div class="scr">
  <h2 class="t-h2" style="font-size:20px;margin-top:4px">在电脑上生成配对码</h2>
  <p class="t-b">回到同一个终端窗口运行下面的命令，它会显示一个二维码和一组六位码。</p>
  ${cmd('cc-pocket-daemon pair')}
  <div class="row gap8" style="color:var(--muted);align-items:flex-start">${ic('clock', 16)}<span class="t-s" style="flex:1">二维码与六位码都是<b style="color:var(--sec);font-weight:500">临时</b>的，过期后重新运行该命令即可生成新的。</span></div>
  <div class="qr" style="height:150px;margin-top:2px">
    <span class="corner" style="left:16px;top:16px;border-right:0;border-bottom:0"></span><span class="corner" style="right:16px;top:16px;border-left:0;border-bottom:0"></span>
    <span class="corner" style="left:16px;bottom:16px;border-right:0;border-top:0"></span><span class="corner" style="right:16px;bottom:16px;border-left:0;border-top:0"></span>
    <span class="col gap8" style="align-items:center;color:var(--muted)">${ic('qr', 22)}<span class="t-s mono" style="font-size:11px">对准电脑屏幕上的二维码</span></span>
  </div>
  <div class="grow"></div>
  <div class="col gap8">
    <span class="btn blk b-pri">${ic('qr', 18)}打开扫码</span>
    <span class="btn blk b-ghost">输入六位码</span>
  </div>
</div>${hbar()}</div>`
  });

  // ── FRAME 4A · connected ────────────────────────────────────
  F({
    label: 'FRAME 04A · 连接成功', sub: 'Step 3 of 3 · identity + immediate next action', thread: '红线 1', x: 1460, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}${setupHead(3, 100)}
<div class="scr">
  <div style="height:36px"></div>
  <div class="col gap14" style="align-items:center;text-align:center">
    <span style="width:52px;height:52px;border-radius:14px;display:grid;place-items:center;background:color-mix(in oklab,var(--success) 14%,transparent);color:var(--success)">${ic('checkc', 24)}</span>
    <div class="col gap6" style="align-items:center">
      <h2 class="t-h1" style="font-size:24px">连接成功</h2>
      <span class="row gap6"><span class="dot ok"></span><span class="mono" style="font-size:12.5px;color:var(--sec)">MacBook Pro · macOS 15.5</span></span>
    </div>
  </div>
  <div class="card col gap8" style="margin-top:20px">
    <div class="row gap8" style="color:var(--sec)">${ic('shield', 16)}<span class="t-h3" style="font-size:13.5px">无需账号</span></div>
    <p class="t-s">配对只存在于这台手机与这台电脑之间。端到端中继只转发密文，无法读取你的会话、代码或命令输出。</p>
  </div>
  <div class="grow"></div>
  <div class="col gap8">
    <span class="btn blk b-pri">打开第一个项目</span>
    <span class="btn blk b-ghost">恢复已有会话 · 3</span>
    <span class="t-s" style="text-align:center">「恢复已有会话」仅在电脑上存在历史会话时出现</span>
  </div>
</div>${hbar()}</div>`
  });

  // ── FRAME 4B · projects with checklist ──────────────────────
  const proj = (name, meta, dot) => `<div class="lrow"><span class="li">${ic('folder', 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${name}</b><span class="t-s mono" style="font-size:11.5px">${meta}</span></span><span class="dot ${dot}"></span></div>`;

  F({
    label: 'FRAME 04B · Projects 首次成功清单', sub: 'FirstSuccessChecklist · compact, dismissible, removed after first success', thread: '红线 1', x: 1910, y: Y, w: 390, h: 844,
    html: `<div class="ph">${sb()}
<div class="abar"><h2 style="margin-left:2px">项目</h2><span class="ib">${ic('help', 22)}</span><span class="ib">${ic('plus', 22)}</span></div>
<div class="scr">
  <div class="card col gap12" style="border-color:color-mix(in oklab,var(--accent) 40%,var(--hair))">
    <div class="row gap8"><span class="t-eyebrow" style="color:var(--accent)">开始使用 · 2/3</span><span class="grow"></span><span style="color:var(--muted)">${ic('x', 16)}</span></div>
    <div class="col gap7" style="gap:7px">
      <div class="row gap8"><span style="color:var(--success)">${ic('checkc', 16)}</span><span class="t-s" style="color:var(--sec);text-decoration:line-through">安装电脑端</span></div>
      <div class="row gap8"><span style="color:var(--success)">${ic('checkc', 16)}</span><span class="t-s" style="color:var(--sec);text-decoration:line-through">完成配对</span></div>
      <div class="row gap8"><span style="color:var(--muted)">${ic('circle', 16)}</span><span style="font-size:13px;color:var(--text)">开始第一次工作</span></div>
    </div>
    <span class="btn sm b-pri" style="align-self:flex-start">发送第一条指令</span>
  </div>
  <div class="sechead"><span class="t-eyebrow">最近</span><span class="t-s mono">MacBook Pro</span></div>
  <div class="card" style="padding:2px 13px">
    ${proj('ark-nexus/relay', 'Claude Code · 空闲', 'idle')}
    ${proj('cc-pocket-daemon', 'Codex · 空闲', 'idle')}
    ${proj('site-marketing', 'OpenCode · 空闲', 'idle')}
  </div>
  <div class="grow"></div>
  <div class="t-s" style="text-align:center">首次成功 = 发出第一条指令 / 恢复一个会话 / 安全批准第一个权限请求</div>
</div>${hbar()}</div>`
  });

  // ── annotations ─────────────────────────────────────────────
  ANN(2400, ANNY, `<h4>行为标注 · 首次安装</h4>
<dl><dt>trigger</dt><dd>未配对启动、或从设置进入配对</dd>
<dt>state</dt><dd>App 已知：pairing=none / daemon=unknown</dd>
<dt>next</dt><dd>一次只给一个动作；不把扫码器、六位码、安装命令、局域网高级设置、Demo 并列成同级控件</dd>
<dt>done</dt><dd>pairing.success → 进入第 3 步；首次成功会话 → 清单移除</dd>
<dt>suppress</dt><dd>首次成功后清单永久移除，不留常驻仪表盘</dd></dl>
<p style="margin-top:10px"><b>返回行为</b><br>后退保留已选系统与已完成步骤；已复制状态、已输入的六位码不丢失。</p>
<p><b>L10N · FirstSuccessChecklist</b><br>开始使用 · 2/3 → Get started · 2/3<br>安装电脑端 → Install on computer<br>完成配对 → Pair devices<br>开始第一次工作 → Do your first task</p>
<p><b>可访问性</b><br>命令块朗读为「安装命令，可复制」；进度头部朗读为「第 1 步，共 3 步」；触控目标 ≥44pt。</p>`, 250);
})();
