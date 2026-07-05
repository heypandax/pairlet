// cc-pocket — "Claude asks you" (AskUserQuestion) · core tokens, icons, atoms
// Loaded first. Declares T + shared atoms and bridges them onto window so
// qm-card.jsx / qm-board.jsx can use them without re-declaring.

const T = {
  base:    '#0E0F11',
  surface: '#16181B',
  raised:  '#1E2125',
  border:  '#2A2E33',
  text:    '#ECEDEE',
  sec:     '#9BA1A6',
  muted:   '#6B7177',
  accent:  '#D97757',
  success: '#4FB477',
  warning: '#E0A93B',
  danger:  '#E5604D',
  mono:    "'JetBrains Mono', ui-monospace, monospace",
  ui:      "'Inter', 'Noto Sans SC', -apple-system, system-ui, sans-serif",
};
// accent tints (terracotta), reused everywhere
const A08 = 'rgba(217,119,87,0.08)';
const A12 = 'rgba(217,119,87,0.12)';
const A18 = 'rgba(217,119,87,0.18)';
const A28 = 'rgba(217,119,87,0.28)';
const A55 = 'rgba(217,119,87,0.55)';

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ d = 'down', c = T.muted, s = 14, w = 1.8 }) => {
  const p = { down: 'M3 6l6 6 6-6', up: 'M3 12l6-6 6 6', left: 'M11 3L5 9l6 6', right: 'M6 3l6 6-6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Check = ({ c = '#0E0F11', s = 13, w = 1.9 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.5 7.3l2.8 2.8L11.5 4" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const Pencil = ({ c = T.sec, s = 13 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M10.8 2.6l2.6 2.6M11.4 2l2 2a1 1 0 010 1.4l-6.7 6.7-3 .7.7-3 6.7-6.7a1 1 0 011.4 0z" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const Plus = ({ c = T.sec, s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>
);
const SendArrow = ({ c = '#0E0F11', s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>
);
// OS glyphs (machines stay monochrome — fleet convention)
const OSmac = (c = T.sec, s = 13) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M12.3 9.5c0-1.8 1.5-2.7 1.6-2.7-.9-1.3-2.2-1.4-2.7-1.5-1.1-.1-2.2.7-2.8.7-.6 0-1.5-.7-2.4-.6-1.2 0-2.4.7-3 1.8-1.3 2.2-.3 5.5.9 7.3.6.9 1.3 1.9 2.2 1.8.9 0 1.2-.6 2.3-.6 1.1 0 1.3.6 2.3.6 1 0 1.6-.9 2.2-1.8.7-1 .9-2 .9-2.1 0 0-1.7-.7-1.7-2.6zM10.8 4.3c.5-.6.8-1.4.7-2.3-.7 0-1.5.5-2 1.1-.4.5-.8 1.3-.7 2.1.8.1 1.5-.4 2-.9z"/></svg>
);
const OSlinux = (c = T.sec, s = 13) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M9 2.5c-1.1 0-1.6 1.1-1.6 2.6 0 1.1-.6 2-1.4 3.2-.7 1.1-1.4 2.2-1.4 3.3 0 .9.7 1.4 1.8 1.7.9.2 1.3.9 2.6.9s1.7-.7 2.6-.9c1.1-.3 1.8-.8 1.8-1.7 0-1.1-.7-2.2-1.4-3.3-.8-1.2-1.4-2.1-1.4-3.2 0-1.5-.5-2.6-1.6-2.6z"/><path d="M7.6 6.4v.01M10.4 6.4v.01"/></svg>
);
const OS = { mac: OSmac, linux: OSlinux };

// ── the question mark badge (terracotta on 12% terracotta) ────
function QIcon({ s = 28 }) {
  return (
    <span style={{
      width: s, height: s, borderRadius: 999, background: A12, flexShrink: 0,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <span style={{ fontFamily: T.ui, fontSize: Math.round(s * 0.56), fontWeight: 700, color: T.accent, lineHeight: 1, transform: 'translateY(-0.5px)' }}>?</span>
    </span>
  );
}

// ── selection controls ────────────────────────────────────────
function Radio({ sel }) {
  return (
    <span style={{
      width: 20, height: 20, borderRadius: 999, flexShrink: 0,
      border: `1.5px solid ${sel ? T.accent : T.border}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      {sel && <span style={{ width: 9, height: 9, borderRadius: 999, background: T.accent }}/>}
    </span>
  );
}
function CheckboxCtrl({ sel }) {
  return (
    <span style={{
      width: 20, height: 20, borderRadius: 6, flexShrink: 0,
      border: `1.5px solid ${sel ? T.accent : T.border}`,
      background: sel ? T.accent : 'transparent',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      {sel && <Check c="#0E0F11" s={13}/>}
    </span>
  );
}

// ── header chip-tab (multi-question switcher) ─────────────────
function ChipTab({ label, active, done, onClick }) {
  const bg = active ? A18 : done ? A08 : 'transparent';
  const bd = active ? A55 : done ? A28 : T.border;
  const col = (active || done) ? T.accent : T.sec;
  return (
    <button onClick={onClick} className="qm-press" style={{
      all: 'unset', boxSizing: 'border-box', cursor: 'pointer',
      display: 'inline-flex', alignItems: 'center', gap: 5, flexShrink: 0,
      height: 27, padding: '0 11px', borderRadius: 999,
      background: bg, border: `1px solid ${bd}`,
    }}>
      {done && <Check c={T.accent} s={10.5} w={2}/>}
      <span style={{ fontFamily: T.ui, fontSize: 12, fontWeight: active ? 600 : 500, color: col, whiteSpace: 'nowrap' }}>{label}</span>
    </button>
  );
}

// ── tiny chip (chosen-answer labels in the answered row) ──────
function TinyChip({ children, muted }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', flexShrink: 0,
      fontFamily: T.ui, fontSize: 11, fontWeight: 500,
      color: muted ? T.muted : T.sec,
      background: T.base, border: `1px solid ${T.border}`, borderRadius: 6,
      padding: '2px 7px', maxWidth: 118,
      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
    }}>{children}</span>
  );
}

// ── filled terracotta pill button (Answer / inbox affordance) ─
function PillBtn({ children, onClick }) {
  return (
    <button onClick={onClick} className="qm-press" style={{
      all: 'unset', boxSizing: 'border-box', cursor: 'pointer', flexShrink: 0,
      fontFamily: T.ui, fontSize: 12.5, fontWeight: 700, color: '#0E0F11',
      background: T.accent, borderRadius: 999, padding: '7px 15px',
    }}>{children}</button>
  );
}

// ── machine chip (monochrome glyph + mono host + status dot) ──
function MachineChip({ name, os = 'mac', status = 'online', mono = 11.5, glyph = 13 }) {
  const dotc = status === 'online' ? T.success : status === 'reconnecting' ? T.warning : T.muted;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
      <span style={{ display: 'flex', flexShrink: 0 }}>{(OS[os] || OSmac)(T.sec, glyph)}</span>
      <span style={{ fontFamily: T.mono, fontSize: mono, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</span>
      <span className={status === 'online' ? 'qm-pulse' : ''} style={{ width: 6, height: 6, borderRadius: 999, background: dotc, flexShrink: 0, boxShadow: status === 'online' ? `0 0 6px ${T.success}88` : 'none' }}/>
    </span>
  );
}

Object.assign(window, {
  T, A08, A12, A18, A28, A55,
  Chevron, Check, Pencil, Plus, SendArrow, OSmac, OSlinux, OS,
  QIcon, Radio, CheckboxCtrl, ChipTab, TinyChip, PillBtn, MachineChip,
});
