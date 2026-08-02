// Deterministic QR-code placeholder — finder patterns + seeded module fill.
(function () {
  function build(el) {
    const N = 25, q = 0, cell = 100 / N;
    let seed = 0x7f4a; const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
    const on = (r, c) => {
      // finder patterns (7x7) at three corners
      const f = (br, bc) => r >= br && r < br + 7 && c >= bc && c < bc + 7 &&
        !(r > br && r < br + 6 && c > bc && c < bc + 6 && !(r > br + 1 && r < br + 5 && c > bc + 1 && c < bc + 5));
      if (r < 8 && c < 8) return f(0, 0);
      if (r < 8 && c >= N - 8) return f(0, N - 7);
      if (r >= N - 8 && c < 8) return f(N - 7, 0);
      // alignment block bottom-right
      if (r >= N - 9 && r < N - 4 && c >= N - 9 && c < N - 4)
        return !(r > N - 9 && r < N - 5 && c > N - 9 && c < N - 5 && !(r === N - 7 && c === N - 7));
      // timing rows
      if (r === 6 || c === 6) return (r + c) % 2 === 0;
      return rnd() > 0.52;
    };
    let d = '';
    for (let r = 0; r < N; r++) for (let c = 0; c < N; c++) if (on(r, c))
      d += `M${((c + q) * cell).toFixed(2)} ${((r + q) * cell).toFixed(2)}h${cell.toFixed(2)}v${cell.toFixed(2)}h-${cell.toFixed(2)}z`;
    el.innerHTML = `<svg viewBox="0 0 100 100" shape-rendering="crispEdges" aria-label="Invite QR code"><path d="${d}" fill="#0E0F11"/></svg>`;
  }
  const go = () => document.querySelectorAll('.qrbox').forEach(build);
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', go) : go();
})();
