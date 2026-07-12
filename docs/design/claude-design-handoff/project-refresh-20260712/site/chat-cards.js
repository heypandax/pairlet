// cc-pocket · Chat cards — review-doc interactions
(function () {
  'use strict';

  // ── per-screen state toolbars ───────────────────────────────
  document.querySelectorAll('.seg[data-target]').forEach(function (seg) {
    var target = seg.dataset.target;
    var btns = [].slice.call(seg.querySelectorAll('button'));
    btns.forEach(function (b) {
      b.addEventListener('click', function () {
        btns.forEach(function (x) { x.classList.toggle('on', x === b); });
        applyState(target, b.dataset.state);
      });
    });
    var on = seg.querySelector('button.on') || btns[0];
    if (on) applyState(target, on.dataset.state);
  });

  function applyState(target, state) {
    if (target === 's1') {
      // swap the in-stream running card treatment
      document.querySelectorAll('#s1 [data-runcard]').forEach(function (card) {
        setTreatment(card, state);
      });
    } else if (target === 's2') {
      var stage = document.getElementById('s2stage');
      if (stage) stage.classList.toggle('freeze', state === 'freeze');
    } else if (target === 's3') {
      var desk = document.getElementById('s3');
      if (desk) desk.classList.toggle('hoveron', state === 'hover');
    }
    requestAnimationFrame(fitAll);
  }

  function setTreatment(card, treat) {
    card.classList.remove('sac--pulse', 'sac--breathe', 'sac--edge');
    card.classList.add('sac--' + treat);
    var dot = card.querySelector('[data-livedot]');
    if (dot) dot.style.display = (treat === 'pulse') ? '' : 'none';
  }

  // ── subagent-card expand / collapse ─────────────────────────
  document.addEventListener('click', function (e) {
    var hd = e.target.closest('.sac.is-expandable .sac-hd');
    if (!hd) return;
    var card = hd.closest('.sac');
    card.classList.toggle('is-open');
    var body = card.querySelector('[data-repbody]');
    if (body) body.classList.toggle('hidden');
    requestAnimationFrame(fitAll);
  });

  // ── fit-scaling: shrink fixed frames to the column width ─────
  function fitAll() {
    document.querySelectorAll('[data-fit]').forEach(function (el) {
      var wrap = el.closest('.fit-wrap');
      if (!wrap) return;
      el.style.transform = 'none';
      var natW = el.offsetWidth, natH = el.offsetHeight;
      var avail = wrap.clientWidth;
      var s = Math.min(1, avail / natW);
      el.style.transform = s < 1 ? 'scale(' + s + ')' : 'none';
      wrap.style.height = (natH * s) + 'px';
    });
  }
  window.addEventListener('resize', fitAll);
  window.addEventListener('load', fitAll);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(fitAll);
  fitAll();
  setTimeout(fitAll, 300);
})();
