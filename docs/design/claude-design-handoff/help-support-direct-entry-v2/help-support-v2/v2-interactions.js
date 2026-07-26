// Prototype wiring — external transition, task expand, composer → chat, retry
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const f = (id) => document.getElementById(id);

  function setChat(frame, html) {
    const ph = $('.ph', frame);
    ph.querySelectorAll('.scr,.dock').forEach(n => n.remove());
    $('.hbar', ph).insertAdjacentHTML('beforebegin', html);
  }
  const chatWait = () => `<div class="scr" style="gap:12px;padding-bottom:8px">${v2wait()}<div class="grow"></div></div><div class="dock">${v2comp('接着问…', { send: 'ask' })}</div>`;
  const chatAnswer = () => `<div class="scr" style="gap:12px;padding-bottom:8px">${v2answer(V2_ANSWER)}<div class="grow"></div></div><div class="dock">${v2comp('接着问…', { send: 'ask' })}</div>`;

  function flash(el) {
    if (!el) return;
    el.style.transition = 'box-shadow .2s ease';
    el.style.boxShadow = '0 0 0 3px #D97757';
    setTimeout(() => { el.style.boxShadow = ''; }, 1600);
  }

  // ── external transition sheet (native App row) ──────────────
  function openWebSheet(frame) {
    const o = document.createElement('div');
    o.className = 'ovl';
    o.innerHTML = `<div class="sheet">
<div class="row gap10"><span style="color:var(--accent)">${ic('ext', 20)}</span><b class="t-h3" style="flex:1">将打开公开网页</b><span class="tap" data-act="close-sheet" style="color:var(--muted)">${ic('x', 18)}</span></div>
<div class="cmd wrap"><code>pocket.ark-nexus.cc/support/?mode=chat&amp;source=app</code></div>
<p class="t-s">在系统浏览器中打开，公开可访问、无需登录。<b style="color:var(--text)">不会附带你的会话内容、路径、日志或当前模型。</b></p>
<div class="row gap8"><span class="btn sm b-quiet grow tap" data-act="close-sheet">取消</span><span class="btn sm b-pri grow tap" data-act="goto-web">打开</span></div></div>`;
    frame.appendChild(o);
  }
  function landedNote(frame) {
    const o = $('.ovl', frame);
    if (!o) return;
    o.innerHTML = `<div class="sheet"><div class="row gap10"><span style="color:var(--success)">${ic('checkc', 20)}</span><b class="t-h3" style="flex:1">已在系统浏览器打开</b><span class="tap" data-act="close-sheet" style="color:var(--muted)">${ic('x', 18)}</span></div>
<p class="t-s">落地页即对话工作区 —— 见右侧 <b style="color:var(--text)">FRAME 2</b>。返回走浏览器原生返回，App 内会话未受影响。</p></div>`;
    flash(f('f2'));
  }

  // ── task row expand (App) ───────────────────────────────────
  const TASK_STEPS = [
    ['打开那条会话', '点会话正文上方的「N 个文件已改动」', '点文件名展开逐行 diff'],
    ['在会话里点终端图标', '选择「接管」而不是「新建」', '输入命令；Agent 会看到同一个终端'],
    ['收到工具请求时先读摘要', '展开要写入的具体内容', '批准、拒绝，或让它换一种做法'],
    ['写好提示词后长按发送键', '选择时间或按天重复', '在「预约」列表里随时改期或取消'],
    ['在会话头部点模型 chip', '选 Agent（Claude / Codex / OpenCode）', '再选模型与推理强度']
  ];
  function toggleTask(row, i) {
    if (row.classList.contains('open')) { row.outerHTML = v2task(V2_TASKS[i], { act: 'task:' + i, icon: 'down' }); return; }
    row.classList.add('open');
    row.innerHTML = `<div class="row gap11" style="gap:11px;width:100%"><span class="li">${ic(V2_TASKS[i][0], 16)}</span><span class="grow col" style="gap:2px"><b class="t-h3" style="font-size:13.5px">${V2_TASKS[i][1]}</b><span class="t-s mono" style="font-size:10.5px">${V2_TASKS[i][2]}</span></span><span style="color:var(--accent)">${ic('up', 16)}</span></div>
<div class="col gap8" style="width:100%">${TASK_STEPS[i].map((s, n) => `<div class="step"><span class="n">${n + 1}</span><span class="grow" style="font-size:13px;line-height:1.5;color:var(--sec);padding-top:2px">${s}</span></div>`).join('')}
<a class="srclink" tabindex="0">${ic('book', 15)}<span class="grow">手册 · <b class="u">${V2_TASKS[i][1]}</b> · 最近核验 2026-07-24</span>${ic('ext', 14)}</a></div>`;
  }

  // ── event delegation ────────────────────────────────────────
  function handle(e) {
    const t = e.target.closest('[data-act]');
    if (!t) return;
    const act = t.dataset.act, frame = t.closest('.frame');
    e.preventDefault();

    if (act === 'open-web') return openWebSheet(frame);
    if (act === 'close-sheet') { const o = $('.ovl', frame); if (o) o.remove(); return; }
    if (act === 'goto-web') return landedNote(frame);

    if (act.startsWith('task:')) return toggleTask(t.closest('.trow'), +act.split(':')[1]);

    if (act === 'ask') {
      // phone surfaces
      if (frame.id === 'f2' || frame.id === 'f3') {
        setChat(frame, chatWait());
        setTimeout(() => setChat(frame, chatAnswer()), 1500);
        return;
      }
      // desktop home / conversation
      if (frame.id === 'f5' || frame.id === 'f7') {
        const web = $('.web', frame);
        $('[data-body]', web).outerHTML = frame.dataset.wait || f('f5').dataset.wait;
        setTimeout(() => { $('[data-body]', web).outerHTML = frame.dataset.conv || f('f5').dataset.conv; }, 1500);
        return;
      }
    }

    if (act === 'retry') {
      const card = t.closest('.card');
      if (!card) return;
      const keep = card.innerHTML;
      card.innerHTML = `<div class="wait"><span class="spin"></span><span class="grow">正在重试 · 检索已核验手册</span></div>`;
      setTimeout(() => {
        card.innerHTML = `<div class="col gap10"><div class="row gap10"><span style="color:var(--success)">${ic('checkc', 18)}</span><b class="t-h3" style="flex:1">重试成功 · 已给出回答</b><span class="btn xs b-quiet tap" data-act="undo">回到失败状态</span></div>
<p class="t-s">${V2_ANSWER.lead}</p><a class="srclink" tabindex="0">${ic('book', 15)}<span class="grow">手册 · <b class="u">${V2_ANSWER.src[0]}</b> · 最近核验 ${V2_ANSWER.src[1]}</span>${ic('ext', 14)}</a></div>`;
        card.dataset.keep = keep;
      }, 1400);
      return;
    }
    if (act === 'undo') { const card = t.closest('.card'); if (card && card.dataset.keep) card.innerHTML = card.dataset.keep; return; }
  }

  document.addEventListener('click', handle);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const t = e.target.closest && e.target.closest('[data-act]');
    if (t) handle(e);
  });

  // composer focus affordance: clicking a composer marks it typed/focused
  document.addEventListener('click', (e) => {
    const c = e.target.closest('.v2c');
    if (!c || e.target.closest('.go')) return;
    c.classList.add('focus');
    const ph = $('.phtxt', c);
    if (ph && !ph.classList.contains('typed')) { ph.classList.add('typed'); ph.textContent = V2_ANSWER.q; }
    $('.go', c).classList.remove('off');
    $('.go', c).dataset.act = 'ask';
  });

  // reset control
  const btn = document.createElement('button');
  btn.textContent = '重置原型状态 Reset prototype';
  btn.style.cssText = 'position:absolute;left:1860px;top:52px;height:30px;padding:0 13px;border-radius:9px;border:1px solid #2A2E33;background:#16181B;color:#9BA1A6;font:500 12px/1 Inter,system-ui;cursor:pointer;z-index:50';
  btn.onclick = () => location.reload();
  document.body.appendChild(btn);
})();
