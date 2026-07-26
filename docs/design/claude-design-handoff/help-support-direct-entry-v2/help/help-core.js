// CC Pocket Help Center — doc scaffolding + shared builders
(function () {
  const SPRITE = `<svg class="sprite" aria-hidden="true"><defs>
<symbol id="i-help" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M9.6 9.2a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .9-1 1.6v.4"/><path d="M12 16.8h.01"/></symbol>
<symbol id="i-back" viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></symbol>
<symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="M16 16l4 4"/></symbol>
<symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2.5"/><path d="M15 6.5A2.5 2.5 0 0 0 12.5 4h-6A2.5 2.5 0 0 0 4 6.5v6A2.5 2.5 0 0 0 6.5 15"/></symbol>
<symbol id="i-check" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7"/></symbol>
<symbol id="i-checkc" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8.2 12.3l2.6 2.6 5-5.4"/></symbol>
<symbol id="i-circle" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/></symbol>
<symbol id="i-right" viewBox="0 0 24 24"><path d="M9.5 5l7 7-7 7"/></symbol>
<symbol id="i-down" viewBox="0 0 24 24"><path d="M5 9.5l7 7 7-7"/></symbol>
<symbol id="i-up" viewBox="0 0 24 24"><path d="M5 14.5l7-7 7 7"/></symbol>
<symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 4.6L21 19.4H3L12 4.6z"/><path d="M12 10v4"/><path d="M12 16.6h.01"/></symbol>
<symbol id="i-monitor" viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="12" rx="2.5"/><path d="M9 20h6M12 16.5V20"/></symbol>
<symbol id="i-monitoroff" viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="12" rx="2.5"/><path d="M9 20h6M12 16.5V20"/><path d="M4 3.5l16 14"/></symbol>
<symbol id="i-phone" viewBox="0 0 24 24"><rect x="6.5" y="2.5" width="11" height="19" rx="3"/><path d="M10.5 18.6h3"/></symbol>
<symbol id="i-qr" viewBox="0 0 24 24"><rect x="3.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="14.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="3.5" y="14.5" width="6" height="6" rx="1.5"/><path d="M14.5 14.5h3v3M20.5 17.5v3h-3"/></symbol>
<symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></symbol>
<symbol id="i-diff" viewBox="0 0 24 24"><path d="M6 3.5v9M6 20.5v-2.5"/><circle cx="6" cy="15.5" r="2.5"/><path d="M18 20.5v-9M18 3.5V6"/><circle cx="18" cy="8.5" r="2.5"/></symbol>
<symbol id="i-layers" viewBox="0 0 24 24"><path d="M12 3.5l8 4.2-8 4.2-8-4.2 8-4.2z"/><path d="M4 13.2l8 4.2 8-4.2"/></symbol>
<symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 3.4l7 2.6v5.4c0 4.2-2.9 7.5-7 8.6-4.1-1.1-7-4.4-7-8.6V6l7-2.6z"/><path d="M9.2 12.2l2 2 3.6-3.9"/></symbol>
<symbol id="i-ext" viewBox="0 0 24 24"><path d="M14 5h5v5"/><path d="M19 5l-7.5 7.5"/><path d="M18 14.5v3A2.5 2.5 0 0 1 15.5 20h-9A2.5 2.5 0 0 1 4 17.5v-9A2.5 2.5 0 0 1 6.5 6h3"/></symbol>
<symbol id="i-x" viewBox="0 0 24 24"><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/></symbol>
<symbol id="i-refresh" viewBox="0 0 24 24"><path d="M19.5 12a7.5 7.5 0 1 1-2.4-5.5"/><path d="M19.8 4.5v4h-4"/></symbol>
<symbol id="i-send" viewBox="0 0 24 24"><path d="M4.5 12h14"/><path d="M12.5 6l6 6-6 6"/></symbol>
<symbol id="i-clip" viewBox="0 0 24 24"><path d="M12.5 7.5l-5.6 5.6a3.2 3.2 0 0 0 4.5 4.5l6.6-6.6a4.3 4.3 0 0 0-6-6L5 11.5"/></symbol>
<symbol id="i-mic" viewBox="0 0 24 24"><rect x="9.5" y="3" width="5" height="10" rx="2.5"/><path d="M6 11.5a6 6 0 0 0 12 0M12 17.5V21"/></symbol>
<symbol id="i-term" viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="15" rx="2.5"/><path d="M7.5 10l2.5 2-2.5 2M12.5 15.5h4"/></symbol>
<symbol id="i-github" viewBox="0 0 24 24"><path d="M9.2 20.5v-2.9c-2.6.5-3.3-1.2-3.6-2-.2-.5-.9-1.5-1.5-1.8-.5-.3.3-.5 1-.3.7.2 1.2.9 1.5 1.4.7 1.1 2 .8 2.7.5.1-.7.4-1.2.8-1.6-2.6-.4-4.4-1.7-4.4-4.4 0-1.2.4-2.2 1.1-2.9-.2-.7-.3-1.9.1-2.7 0 0 1 .2 2.3 1.2a7.9 7.9 0 0 1 4 0C14.5 3.5 15.5 3.3 15.5 3.3c.4.8.3 2 .1 2.7.7.7 1.1 1.7 1.1 2.9 0 2.7-1.8 4-4.4 4.4.5.5.8 1.3.8 2.1v5.1"/></symbol>
<symbol id="i-bell" viewBox="0 0 24 24"><path d="M6.5 10.5a5.5 5.5 0 0 1 11 0c0 3 .8 4.3 1.5 5H5c.7-.7 1.5-2 1.5-5z"/><path d="M10 18.5a2.2 2.2 0 0 0 4 0"/></symbol>
<symbol id="i-folder" viewBox="0 0 24 24"><path d="M3.5 7.5A2 2 0 0 1 5.5 5.5h3.2l2 2.3h7.8a2 2 0 0 1 2 2v7.7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-10z"/></symbol>
<symbol id="i-cpu" viewBox="0 0 24 24"><rect x="6.5" y="6.5" width="11" height="11" rx="2.5"/><path d="M10 3.5v3M14 3.5v3M10 17.5v3M14 17.5v3M3.5 10h3M3.5 14h3M17.5 10h3M17.5 14h3"/></symbol>
<symbol id="i-eyeoff" viewBox="0 0 24 24"><path d="M4 4l16 16"/><path d="M9.5 6.4A8.4 8.4 0 0 1 12 6c4.6 0 8 4 9 6-.4.8-1.3 2.2-2.8 3.5M6.4 8.4C5 9.6 4.3 10.9 3 12c1 2 4.4 6 9 6 .9 0 1.7-.1 2.5-.4"/><path d="M10.3 10.4a2.4 2.4 0 0 0 3.3 3.3"/></symbol>
<symbol id="i-lock" viewBox="0 0 24 24"><rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/></symbol>
<symbol id="i-book" viewBox="0 0 24 24"><path d="M4 5.5A2 2 0 0 1 6 3.5h13v15H6a2 2 0 0 0-2 2v-15z"/><path d="M19 18.5v2H6"/></symbol>
<symbol id="i-compass" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M14.8 9.2l-1.5 4.1-4.1 1.5 1.5-4.1 4.1-1.5z"/></symbol>
<symbol id="i-zap" viewBox="0 0 24 24"><path d="M13.2 3.5L6 13.2h4.4l-.6 7.3 7.2-9.7h-4.4l.6-7.3z"/></symbol>
<symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 6v12M6 12h12"/></symbol>
<symbol id="i-globe" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/><path d="M12 3.5c2.2 2.4 3.3 5.3 3.3 8.5s-1.1 6.1-3.3 8.5c-2.2-2.4-3.3-5.3-3.3-8.5S9.8 5.9 12 3.5z"/></symbol>
<symbol id="i-flag" viewBox="0 0 24 24"><path d="M6 20.5V4.5h11l-2 3.5 2 3.5H6"/></symbol>
<symbol id="i-download" viewBox="0 0 24 24"><path d="M12 3.5v11"/><path d="M8 11l4 3.5 4-3.5"/><path d="M4.5 17.5v1.5a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-1.5"/></symbol>
<symbol id="i-mark" viewBox="0 0 24 24"><rect x="3.5" y="5.5" width="17" height="13" rx="3.5"/><path d="M8.5 10.5l2.4 2-2.4 2M13 14.5h3"/></symbol>
</defs></svg>`;

  const root = document.body;
  root.insertAdjacentHTML('afterbegin', SPRITE);

  function el(tag, cls, html) { const n = document.createElement(tag); if (cls) n.className = cls; if (html != null) n.innerHTML = html; return n; }

  // Frame factory. o = {label, sub, thread, x, y, w, h, theme, cls, html}
  window.F = function (o) {
    const f = el('div', 'frame ' + (o.cls || ''), o.html || '');
    f.style.cssText = `left:${o.x}px;top:${o.y}px;width:${o.w}px;` + (o.h ? `height:${o.h}px;` : '');
    f.dataset.theme = o.theme || 'dark';
    if (o.label) f.dataset.screenLabel = o.label;
    const lb = el('div', 'flabel', `${o.label || ''}${o.thread ? ` <u>${o.thread}</u>` : ''}<i>${o.sub || ''}</i>`);
    lb.style.cssText = `left:${o.x}px;top:${o.y - 50}px`;
    root.appendChild(lb);
    root.appendChild(f);
    return f;
  };
  window.ANN = function (x, y, html, w) {
    const n = el('div', 'ann', html); n.style.cssText = `left:${x}px;top:${y}px;` + (w ? `width:${w}px` : '');
    root.appendChild(n); return n;
  };
  window.RT = function (x, y, text) {
    const n = el('div', 'rowtitle', text); n.style.cssText = `left:${x}px;top:${y}px`; root.appendChild(n); return n;
  };
  window.ic = (n, s) => `<svg class="ic ${s ? 'ic-' + s : ''}"><use href="#i-${n}"/></svg>`;

  // ── shared UI builders ─────────────────────────────────────────
  window.sb = (t = '9:41') => `<div class="sb"><span>${t}</span><span class="rt mono" style="font-size:11px">LTE ${ic('cpu', 14)} 84%</span></div>`;
  window.hbar = () => `<div class="hbar"><span></span></div>`;
  window.abar = (title, right = '') => `<div class="abar"><span class="ib">${ic('back', 22)}</span><h2>${title}</h2>${right}</div>`;
  window.cmd = (c, state = '', wrap = '') => `<div class="cmd ${wrap}"><code>${c}</code><span class="cp ${state === 'done' ? 'done' : ''}">${state === 'done' ? ic('check', 14) + '已复制' : ic('copy', 14) + '复制'}</span></div>`;
  window.composer = (placeholder, o = {}) => `<div class="composer ${o.focus ? 'focus' : ''}">${o.chips ? `<div class="chips">${o.chips}</div>` : ''}<div class="ph-txt ${o.typed ? 'typed' : ''}">${placeholder}</div><div class="cbar"><div class="row gap12" style="color:var(--muted)">${ic('mic', 18)}${ic('clip', 18)}</div><div class="send ${o.typed ? '' : 'off'}">${ic('send', 18)}</div></div></div>`;
  window.chip = (t, o = {}) => `<span class="chip ${o.on ? 'on' : ''} ${o.tap ? 'tap' : ''}">${o.icon ? ic(o.icon, 12) : ''}${t}${o.x ? `<span class="x">${ic('x', 12)}</span>` : ''}</span>`;
  window.goal = (icon, t, s) => `<div class="card row gap12" style="padding:10px 12px"><span class="li" style="width:32px;height:32px;border-radius:9px;display:grid;place-items:center;background:var(--raised);color:var(--accent);flex:none">${ic(icon, 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3">${t}</b><span class="t-s">${s}</span></span><span style="color:var(--muted)">${ic('right', 16)}</span></div>`;
  window.srcrow = (title, date) => `<div class="src">${ic('book', 14)}<span class="grow"><b>${title}</b> · 最近核验 ${date}</span>${ic('ext', 14)}</div>`;
  window.learned = () => `<div class="row gap8" style="padding-top:2px"><span class="t-s" style="flex:1">这样讲清楚了吗？</span><span class="btn xs b-ghost">我会用了</span><span class="btn xs b-ghost">还没明白</span></div>`;
})();
