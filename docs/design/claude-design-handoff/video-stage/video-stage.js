/* Video Stage 1.0 — renders app-screen mocks, terminal, and the CC Pocket
   glyph into [data-mock] / [data-glyph] placeholders. Content mirrors the
   real screens the render pipeline captures. */

/* ── CC Pocket glyph (favicon): chevron + phone slab, terracotta ── */
function glyph(size, color){
  color = color || '#D97757';
  return `<svg width="${size}" height="${size}" viewBox="0 0 48 48" fill="none">
    <path d="M14 15 L23 24 L14 33" stroke="${color}" stroke-width="5.4" stroke-linecap="round" stroke-linejoin="round"/>
    <rect x="28.5" y="14.5" width="9" height="19" rx="3.2" fill="${color}"/>
  </svg>`;
}

/* icon glyphs (stroke) */
const I = {
  bolt:'<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h7l-1 8 10-12h-7l1-8z"/></svg>',
  ask:'<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.1 9a3 3 0 1 1 5.8 1c0 2-3 2.5-3 4"/><circle cx="12" cy="18" r="0.6" fill="currentColor" stroke="none"/></svg>',
  shield:'<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 9.5C8 21.2 5 17.5 5 13V6l7-3z"/><path d="M9.2 12l2 2 3.6-4"/></svg>',
  laptop:'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 20h20"/></svg>',
  send:'<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#0E0F11" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12h14M12 6l6 6-6 6"/></svg>',
  store:'<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0E0F11" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m0 0l4-4m-4 4l-4-4"/><path d="M5 17v2a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-2"/></svg>',
  gh:'<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.89 1.53 2.34 1.09 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02a9.5 9.5 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.69-4.57 4.94.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2z"/></svg>',
};

/* ═══════════════ APP SCREEN MOCKS ═══════════════ */
const MOCKS = {
  /* 1 · sessions list */
  sessions: () => `
    <div class="app-hd">
      <div class="row"><span class="title">会话</span><span class="cnt">3 active</span></div>
    </div>
    <div class="app-body">
      <div class="sess-new"><span class="pl">+</span><div><div class="nt">开始新会话</div><div class="st">cc-pocket · main</div></div></div>
      <div class="sess-card">
        <div class="hd"><span class="st">修复 relay 断连</span><span class="badge run">running</span></div>
        <div class="pv">已定位 maxFrame 上限，正在回归测试…</div>
        <div class="mt">RelayServer.kt · 2m ago</div>
      </div>
      <div class="sess-card">
        <div class="hd"><span class="st">重构 onboarding 流程</span><span class="badge wait">待确认</span></div>
        <div class="pv">需要你确认部署目标</div>
        <div class="mt">onboarding-app.jsx · 6m ago</div>
      </div>
      <div class="sess-card">
        <div class="hd"><span class="st">补全配对页文案</span><span class="badge done">done</span></div>
        <div class="pv">已更新 5 处中文文案</div>
        <div class="mt">Pairing.html · 1h ago</div>
      </div>
    </div>`,

  /* 2 · streaming chat */
  chat: () => `
    <div class="app-hd">
      <div class="row"><span class="pulse"></span><span class="title">修复 relay 断连</span></div>
      <div class="ctx">~/proj/cc-pocket · main · opus-4</div>
    </div>
    <div class="app-body">
      <div class="user-rail">日志里有 FrameTooBigException，帮我查一下断连原因</div>
      <div class="botline"><span class="b">⏺</span><span>relay 的 maxFrame 只有 256KB，大会话历史帧一发就被踢断。我把上限提到 4MB。</span></div>
      <div class="tool-row"><span class="b" style="color:var(--aacc)">⏺</span><span class="nm">Update</span><span class="tg">RelayServer.kt</span><span class="meta">+2 −0</span></div>
      <div class="tool-row"><span class="b" style="color:var(--aacc)">⏺</span><span class="nm">Bash</span><span class="tg">./gradlew :relay:test</span><span class="meta">24 ✓</span></div>
      <div class="streamline"><span class="spin"></span><span>Redeploying relay…<span class="caret"></span></span></div>
    </div>
    <div class="app-cmp"><div class="field">回复…</div><div class="send">${I.send}</div></div>`,

  /* 3 · question card */
  question: () => `
    <div class="app-hd">
      <div class="row"><span class="title">重构 onboarding 流程</span></div>
      <div class="ctx">~/proj/cc-pocket · main</div>
    </div>
    <div class="app-body">
      <div class="qcard">
        <div class="qhd"><span class="ic">${I.ask}</span><span class="lb">Claude 提问</span></div>
        <div class="qq">部署到哪个目标？我准备重新构建 relay。</div>
        <div class="qopt sel"><span class="rd"></span><span>Staging（预发）</span><span class="ky">1</span></div>
        <div class="qopt"><span class="rd"></span><span>Production（生产）</span><span class="ky">2</span></div>
        <div class="qopt"><span class="rd"></span><span>先只跑本地验证</span><span class="ky">3</span></div>
      </div>
    </div>
    <div class="app-cmp"><div class="field">或输入其他指示…</div><div class="send">${I.send}</div></div>`,

  /* 4 · diff viewer */
  diff: () => `
    <div class="app-hd">
      <div class="row diffhd"><span class="dot" style="background:var(--asuccess)"></span><span class="st">改动</span><span class="ct">2 files · +73 −9</span></div>
      <div class="ctx">~/proj/cc-pocket · main</div>
    </div>
    <div class="app-body">
      <div class="filerow"><span class="fl a">A</span><span class="fn"><span class="p">src/net/</span>useReconnect.kt</span><span class="st"><span class="add">+42</span></span></div>
      <div class="filerow"><span class="fl m">M</span><span class="fn"><span class="p">src/net/relay/</span>WsClient.kt</span><span class="st"><span class="add">+31</span> <span class="del">−9</span></span></div>
      <div class="diffblk">
        <div class="dh"><span>WsClient.kt</span><span>@@ −12,4 +12,6 @@</span></div>
        <pre><span class="dl"><span class="g">12 </span> fun reconnect() {</span><span class="dl del"><span class="g">13 </span>-  val max = 256_000</span><span class="dl add"><span class="g">13 </span>+  val max = 4_000_000</span><span class="dl add"><span class="g">14 </span>+  if (frame.size > max)</span><span class="dl add"><span class="g">15 </span>+    log.warn("frame over cap")</span><span class="dl"><span class="g">16 </span> }</span></pre>
      </div>
    </div>`,

  /* 5 · usage */
  usage: () => {
    const bars=[38,52,44,61,49,72,58,66,80,54,70,63];
    const bh=bars.map((h,i)=>`<div class="bar ${i>=8?'on':''}" style="height:${h}%"></div>`).join('');
    return `
    <div class="app-hd">
      <div class="row"><span class="title">用量</span><span class="cnt">本月</span></div>
    </div>
    <div class="app-body">
      <div class="usehero"><div class="big"><span class="cur">$</span>23.80</div><div class="cap">this month · 4.2M tokens</div></div>
      <div class="usebars">${bh}</div>
      <div style="margin-top:4px">
        <div class="userow" style="border-top:0"><span class="mdl">opus-4</span><span class="tk">2.8M</span><span class="cst">$18.20</span></div>
        <div class="userow"><span class="mdl">sonnet-4</span><span class="tk">1.1M</span><span class="cst">$4.90</span></div>
        <div class="userow"><span class="mdl">haiku-4</span><span class="tk">0.3M</span><span class="cst">$0.70</span></div>
      </div>
    </div>`;
  },
};

/* ═══════════════ TERMINAL (V3 scene 1) ═══════════════ */
function terminal(){
  const L=[
    {t:'user',s:'手机连不上 daemon，日志里有 FrameTooBigException'},
    {t:'gap'},
    {t:'b',s:'我先看 relay 的帧大小限制。'},
    {t:'sgap'},
    {t:'tool',n:'Read',a:'relay/net/RelayServer.kt'},
    {t:'r',s:'Read 182 lines'},
    {t:'sgap'},
    {t:'tool',n:'Update',a:'relay/net/RelayServer.kt'},
    {t:'r',s:'Updated 2 lines'},
    {t:'sgap'},
    {t:'tool',n:'Bash',a:'./gradlew :relay:test'},
    {t:'r',s:'BUILD SUCCESSFUL · 24 passed'},
    {t:'gap'},
    {t:'spin',s:'Redeploying relay… ',d:'(2m 14s)'},
  ];
  const esc=s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;');
  const body=L.map(l=>{
    if(l.t==='gap')return '<div class="gap"></div>';
    if(l.t==='sgap')return '<div class="sgap"></div>';
    if(l.t==='user')return `<div class="ln"><span class="pre dim">&gt; </span><span class="dim">${esc(l.s)}</span></div>`;
    if(l.t==='b')return `<div class="ln"><span class="pre"><span class="acc">⏺</span> </span><span>${esc(l.s)}</span></div>`;
    if(l.t==='tool')return `<div class="ln"><span class="pre"><span class="acc">⏺</span> </span><span><b style="font-weight:600">${esc(l.n)}</b><span class="dim">(${esc(l.a)})</span></span></div>`;
    if(l.t==='r')return `<div class="ln dim"><span class="pre">  ⎿  </span><span>${esc(l.s)}</span></div>`;
    if(l.t==='spin')return `<div class="ln"><span class="pre"><span class="acc">✻</span> </span><span>${esc(l.s)}<span class="dim">${esc(l.d)}</span></span></div>`;
    return '';
  }).join('');
  return `
    <div class="termwin">
      <div class="tbar">
        <div class="lights"><span class="light" style="background:#ff5f57"></span><span class="light" style="background:#febc2e"></span><span class="light" style="background:#28c840"></span></div>
        <div class="tt">cc-pocket — claude — 104×34</div>
      </div>
      <div class="term">
        <div class="lines">${body}</div>
        <div class="inbox"><span class="dim">&gt;&nbsp;</span><span class="cursor"></span></div>
        <div class="caps"><span>? for shortcuts</span><span>claude-opus-4</span></div>
      </div>
    </div>`;
}

/* ═══════════════ mount ═══════════════ */
document.querySelectorAll('[data-glyph]').forEach(el=>{
  const s=parseInt(el.getAttribute('data-glyph'),10)||22;
  el.innerHTML=glyph(s, el.getAttribute('data-glyph-color')||undefined);
});
document.querySelectorAll('[data-mock]').forEach(el=>{
  const k=el.getAttribute('data-mock');
  if(MOCKS[k]) el.innerHTML=MOCKS[k]();
});
document.querySelectorAll('[data-term]').forEach(el=>{ el.innerHTML=terminal(); });
document.querySelectorAll('[data-icon]').forEach(el=>{ const k=el.getAttribute('data-icon'); if(I[k]) el.innerHTML=I[k]; });
