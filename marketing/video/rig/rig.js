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

    // cubic-bezier evaluator (CSS timing semantics) — Video Stage V4 ships exact curves
    bezier(p1x, p1y, p2x, p2y) {
      const sx = t => 3*p1x*t*(1-t)*(1-t) + 3*p2x*t*t*(1-t) + t*t*t;
      const sy = t => 3*p1y*t*(1-t)*(1-t) + 3*p2y*t*t*(1-t) + t*t*t;
      return x => {
        if (x <= 0) return 0; if (x >= 1) return 1;
        let lo = 0, hi = 1, mid;
        for (let i = 0; i < 24; i++) { mid = (lo+hi)/2; if (sx(mid) < x) lo = mid; else hi = mid; }
        return sy(mid);
      };
    },

    // Video Stage V4 element envelopes: enter(el, t, cut, {dy, dur, delay, curve, scale})
    enter(el, t, cut, o) {
      if (!el) return;
      const p = (o.curve || RIG.KICK)(RIG.seg(t, cut + (o.delay||0), cut + (o.delay||0) + o.dur));
      el.style.opacity = p;
      const dy = (1 - p) * (o.dy || 0);
      el.style.transform = `translateY(${dy}px)` + (o.scale ? ` scale(${o.scale + (1-o.scale)*p})` : '');
    },
    // keyword highlighter chip: wipe background-size 0→100% (L→R)
    wipe(el, t, t0, dur = 240) {
      if (!el) return;
      el.style.backgroundSize = `${RIG.seg(t, t0, t0 + dur) * 100}% 46%`;
    },

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

  RIG.KICK  = RIG.bezier(0.22, 1,   0.36, 1);   // kicker / subline
  RIG.SHELL = RIG.bezier(0.16, 1,   0.30, 1);   // phone / terminal / panels
  RIG.DROP  = RIG.bezier(0.22, 1.4, 0.36, 1);   // push banner (overshoot)
  window.RIG = RIG;
})();
