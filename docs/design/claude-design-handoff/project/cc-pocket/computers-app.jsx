// cc-pocket — Choose a computer (daemon picker)

const T = {
  base: '#0E0F11', surface: '#16181B', raised: '#1E2125', border: '#2A2E33',
  text: '#ECEDEE', sec: '#9BA1A6', muted: '#6B7177',
  accent: '#D97757', success: '#4FB477', warning: '#E0A93B', danger: '#E5604D',
  mono: "'JetBrains Mono', ui-monospace, monospace",
  ui: "'Inter', -apple-system, system-ui, sans-serif",
};

const Chevron = ({ d = 'left', c = T.sec, s = 17, w = 2 }) => {
  const p = { left: 'M11 3L5 9l6 6', right: 'M6 3l6 6-6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Plus = ({ c = T.sec, s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="2" strokeLinecap="round"/></svg>
);
const Check = ({ c = '#0E0F11', s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.5 7.3l2.8 2.8L11.5 4" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
);

// generic, non-trademarked device glyphs (laptop / server / monitor)
const DeviceGlyph = ({ type, c = T.sec, s = 22 }) => {
  if (type === 'laptop') return (
    <svg width={s} height={s} viewBox="0 0 22 22" fill="none">
      <rect x="4" y="4" width="14" height="9.5" rx="1.6" stroke={c} strokeWidth="1.4"/>
      <path d="M2.4 16.6h17.2" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    </svg>
  );
  if (type === 'server') return (
    <svg width={s} height={s} viewBox="0 0 22 22" fill="none">
      <rect x="3.5" y="3.5" width="15" height="6" rx="1.6" stroke={c} strokeWidth="1.4"/>
      <rect x="3.5" y="12" width="15" height="6" rx="1.6" stroke={c} strokeWidth="1.4"/>
      <circle cx="6.6" cy="6.5" r="0.95" fill={c}/>
      <circle cx="6.6" cy="15" r="0.95" fill={c}/>
    </svg>
  );
  return ( // monitor
    <svg width={s} height={s} viewBox="0 0 22 22" fill="none">
      <rect x="3" y="3.5" width="16" height="11" rx="1.6" stroke={c} strokeWidth="1.4"/>
      <path d="M8 18.5h6M11 14.5v4" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    </svg>
  );
};

const COMPUTERS = [
  { host: 'Lidapeng-MacBook', os: 'macOS', device: 'laptop', online: true, status: 'active now', dir: '~/proj/app/cc-pocket', current: true },
  { host: 'devbox-linux', os: 'Linux', device: 'server', online: true, status: '3m ago', dir: '~/src/relay' },
  { host: 'win-desktop', os: 'Windows', device: 'monitor', online: false, status: '2d ago', dir: '~/code/api' },
];

// ── card ──────────────────────────────────────────────────────
function ComputerCard({ c }) {
  const [p, setP] = React.useState(false);
  const tappable = c.online;
  const dotColor = c.online ? T.success : T.muted;
  return (
    <div
      onClick={() => { if (tappable) location.href = c.current ? 'Sessions.html' : 'Directory.html'; }}
      onPointerDown={() => tappable && setP(true)} onPointerUp={() => setP(false)} onPointerLeave={() => setP(false)}
      style={{
        display: 'flex', gap: 13, padding: '14px', borderRadius: 12,
        background: p ? T.raised : T.surface, border: `1px solid ${c.current ? 'rgba(217,119,87,0.45)' : T.border}`,
        cursor: tappable ? 'pointer' : 'default', opacity: c.online ? 1 : 0.5,
        position: 'relative',
      }}
    >
      {/* glyph column */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5, width: 44, flexShrink: 0, paddingTop: 2 }}>
        <div style={{ width: 40, height: 40, borderRadius: 10, background: T.raised, border: `1px solid ${T.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <DeviceGlyph type={c.device} c={c.online ? T.sec : T.muted} s={21}/>
        </div>
        <span style={{ fontFamily: T.mono, fontSize: 9.5, color: T.muted, letterSpacing: 0.2 }}>{c.os}</span>
      </div>

      {/* body */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontFamily: T.ui, fontSize: 16, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{c.host}</span>
          {c.current && (
            <span style={{ width: 18, height: 18, borderRadius: 999, background: T.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              <Check c="#0E0F11" s={11}/>
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 6, flexWrap: 'nowrap' }}>
          <span className={c.online ? 'cc-pulse' : ''} style={{ width: 7, height: 7, borderRadius: 999, background: dotColor, boxShadow: c.online ? `0 0 7px ${T.success}88` : 'none', flexShrink: 0 }}/>
          <span style={{ fontFamily: T.ui, fontSize: 12.5, color: c.online ? T.sec : T.muted, whiteSpace: 'nowrap', flexShrink: 0 }}>{c.online ? 'online' : 'offline'}</span>
          <span style={{ color: T.border, flexShrink: 0 }}>·</span>
          <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.muted, whiteSpace: 'nowrap', flexShrink: 0 }}>{c.status}</span>
        </div>
        <div style={{ marginTop: 9, display: 'flex', alignItems: 'center', gap: 7 }}>
          <span style={{ fontFamily: T.mono, fontSize: 11.5, color: c.online ? T.sec : T.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.dir}</span>
          {tappable && <span style={{ marginLeft: 'auto', flexShrink: 0 }}><Chevron d="right" c={T.muted} s={14}/></span>}
        </div>
      </div>
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function ComputersScreen() {
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <div style={{ flexShrink: 0, paddingTop: 52 }}>
        <div style={{ display: 'flex', alignItems: 'center', height: 44, padding: '0 4px' }}>
          <button onClick={() => history.length > 1 ? history.back() : (location.href = 'Sessions.html')} style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        </div>
      </div>

      <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '8px 16px 24px' }}>
        <div style={{ padding: '0 2px 18px' }}>
          <div style={{ fontFamily: T.ui, fontSize: 26, fontWeight: 700, color: T.text, letterSpacing: -0.4 }}>Choose a computer</div>
          <div style={{ fontFamily: T.ui, fontSize: 14, color: T.sec, marginTop: 6 }}>Pick which computer to drive.</div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {COMPUTERS.map((c, i) => <ComputerCard key={i} c={c}/>)}
        </div>

        {/* pair new — secondary */}
        <button
          onClick={() => { location.href = 'Pairing.html'; }}
          style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: '100%', marginTop: 16,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, minHeight: 50,
            borderRadius: 12, border: `1px dashed ${T.border}`, color: T.sec,
            fontFamily: T.ui, fontSize: 14, fontWeight: 500,
          }}
        >
          <Plus c={T.sec} s={15}/> Pair a new computer
        </button>
      </div>
    </div>
  );
}
const iconBtn = { all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 };

function App() {
  const [scale, setScale] = React.useState(1);
  React.useEffect(() => {
    const fit = () => setScale(Math.min(1, (window.innerHeight - 36) / 874, (window.innerWidth - 36) / 402));
    fit(); window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);
  return (
    <div style={{ width: 402 * scale, height: 874 * scale }}>
      <div style={{ width: 402, height: 874, transform: `scale(${scale})`, transformOrigin: 'top left' }}>
        <IOSDevice dark><ComputersScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
