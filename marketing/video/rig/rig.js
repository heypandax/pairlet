/* rig.js — deterministic animation toolkit for promo scenes.
   A scene defines window.SCENE = { frame(tMs) } and renders EVERYTHING as a
   pure function of t. render.py steps t frame by frame and screenshots —
   no CSS animations, no wall-clock, fully reproducible. */
(function () {
  const q = new URLSearchParams(location.search);

  const RIG = {
    DUR: parseInt(q.get('dur') || '5000', 10),   // scene length, injected by render.py
    at(frac) { return RIG.DUR * frac; },          // absolute time at a fraction of the scene
    // 0→1 progress of t across [t0, t1]
    seg(t, t0, t1) { return Math.min(1, Math.max(0, (t - t0) / (t1 - t0))); },
    ease(p) { return p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2; },

    // fade+rise an element in at t0
    showAt(el, t, t0, fade = 280) {
      if (!el) return;
      const p = RIG.ease(RIG.seg(t, t0, t0 + fade));
      el.style.opacity = p;
      el.style.transform = `translateY(${(1 - p) * 14}px)`;
    },
    // hard visibility toggle
    onAt(el, t, t0) { if (el) el.style.opacity = t >= t0 ? 1 : 0; },

    // typewriter: chars-per-second from t0
    type(el, text, t, t0, cps = 28) {
      if (!el) return;
      const n = Math.floor(Math.max(0, t - t0) / 1000 * cps);
      el.textContent = text.slice(0, n);
    },
    // word-stream (assistant output): words appear left→right
    stream(el, text, t, t0, wps = 9) {
      if (!el) return;
      const words = text.split(/(\s+)/);
      const n = Math.floor(Math.max(0, t - t0) / 1000 * wps) * 2;
      el.textContent = words.slice(0, n).join('');
    },
    // blinking block caret (500ms period), only after t0
    caret(el, t, t0 = 0) {
      if (!el) return;
      el.style.opacity = (t >= t0 && Math.floor(t / 500) % 2 === 0) ? 1 : 0;
    },
    // countdown ring: SVG circle stroke sweep across [t0, t1]
    ring(circleEl, t, t0, t1, circumference = 75.4) {
      if (!circleEl) return;
      const p = RIG.seg(t, t0, t1);
      circleEl.setAttribute('stroke-dashoffset', String(circumference * p));
    },
    // press feedback: brief scale-down around tPress
    press(el, t, tPress, dur = 220) {
      if (!el) return;
      const active = t >= tPress && t <= tPress + dur;
      el.style.transform = active ? 'scale(0.94)' : 'scale(1)';
    },
    // scroll a container to y(t) linearly across [t0, t1]
    scroll(el, t, t0, t1, toY) {
      if (!el) return;
      el.scrollTop = RIG.ease(RIG.seg(t, t0, t1)) * toY;
    },

    $(sel) { return document.querySelector(sel); },
    $$(sel) { return [...document.querySelectorAll(sel)]; },
  };

  // inject caption / sub / lang from query so storyboards can override copy
  window.addEventListener('DOMContentLoaded', () => {
    const cap = q.get('caption'), sub = q.get('sub');
    if (cap) { const el = RIG.$('[data-caption]'); if (el) el.innerHTML = cap; }
    if (sub) { const el = RIG.$('[data-sub]'); if (el) el.textContent = sub; }
    document.documentElement.setAttribute('data-lang', q.get('lang') || 'zh');
  });

  window.RIG = RIG;
})();
