// cc-pocket · Changed files v2 — review-doc interactions
(function () {
  'use strict';

  // ── per-screen state toolbars ───────────────────────────────
  document.querySelectorAll('.seg[data-target]').forEach(function (seg) {
    var stage = document.getElementById(seg.dataset.target);
    var btns = [].slice.call(seg.querySelectorAll('button'));
    btns.forEach(function (b) {
      b.addEventListener('click', function () {
        btns.forEach(function (x) { x.classList.toggle('on', x === b); });
        setState(stage, b.dataset.state);
      });
    });
    // normalize initial state (hide nested variant elements that lack .hidden)
    var on = seg.querySelector('button.on') || btns[0];
    if (stage && on) setState(stage, on.dataset.state);
  });

  function setState(stage, state) {
    if (!stage) return;
    stage.dataset.state = state;
    stage.querySelectorAll('[data-variant]').forEach(function (v) {
      var states = v.dataset.variant.split(',');
      v.classList.toggle('hidden', states.indexOf(state) === -1);
    });
    requestAnimationFrame(fitAll);
  }

  // ── hunk collapse (click header) ────────────────────────────
  document.addEventListener('click', function (e) {
    var hd = e.target.closest('.hunk-hd');
    if (hd && !e.target.closest('.copy-hunk')) {
      hd.parentElement.classList.toggle('collapsed');
      requestAnimationFrame(fitAll);
      return;
    }
    var sep = e.target.closest('.dl-sep');
    if (sep) {
      sep.classList.toggle('open');
      requestAnimationFrame(fitAll);
      return;
    }
  });

  // ── desktop file selection (highlight; live header only in default overlay) ──
  function selectDesktopFile(row) {
    var ovl = row.closest('.ovl');
    if (!ovl) return;
    ovl.querySelectorAll('.dfr').forEach(function (r) { r.classList.remove('sel'); });
    row.classList.add('sel');
    if (ovl.hasAttribute('data-live-header')) {
      var path = ovl.querySelector('.ovl-fhd .path');
      var stat = ovl.querySelector('.ovl-fhd .stat');
      var chip = ovl.querySelector('.ovl-fhd .stchip');
      if (path && row.dataset.path) path.textContent = row.dataset.path;
      if (stat && row.dataset.stat) stat.innerHTML = row.dataset.stat;
      if (chip && row.dataset.status) { chip.textContent = row.dataset.status; chip.className = 'stchip ' + row.dataset.status; }
    }
  }
  document.querySelectorAll('.ovl-list').forEach(function (list) {
    list.addEventListener('click', function (e) {
      var row = e.target.closest('.dfr');
      if (row) selectDesktopFile(row);
    });
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
