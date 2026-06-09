// cc-pocket — Session list for a working directory

const T = {
  base: '#0E0F11', surface: '#16181B', raised: '#1E2125', border: '#2A2E33',
  text: '#ECEDEE', sec: '#9BA1A6', muted: '#6B7177',
  accent: '#D97757', success: '#4FB477', warning: '#E0A93B', danger: '#E5604D',
  mono: "'JetBrains Mono', ui-monospace, monospace",
  ui: "'Inter', -apple-system, system-ui, sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ d = 'right', c = T.muted, s = 14, w = 2 }) => {
  const p = { left: 'M11 3L5 9l6 6', right: 'M6 3l6 6-6 6', down: 'M3 6l6 6 6-6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Branch = ({ c = T.muted, s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
    <circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
  </svg>
);
const Bubble = ({ c = T.muted, s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M2.2 4.4A2 2 0 014.2 2.4h7.6a2 2 0 012 2v4a2 2 0 01-2 2H6.4l-3 2.4a.4.4 0 01-.65-.32V10.4H4.2A2 2 0 012.2 8.4z" stroke={c} strokeWidth="1.3" strokeLinejoin="round"/>
  </svg>
);
const Plus = ({ c = '#0E0F11', s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="2.2" strokeLinecap="round"/></svg>
);
const Gear = ({ c = T.sec, s = 19 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <circle cx="10" cy="10" r="2.6" stroke={c} strokeWidth="1.5"/>
    <path d="M10 1.6v2.2M10 16.2v2.2M3.6 3.6l1.6 1.6M14.8 14.8l1.6 1.6M1.6 10h2.2M16.2 10h2.2M3.6 16.4l1.6-1.6M14.8 5.2l1.6-1.6" stroke={c} strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ── pressable wrapper ─────────────────────────────────────────
function Pressable({ children, onClick, style, pressBg = T.raised, baseBg }) {
  const [p, setP] = React.useState(false);
  return (
    <div
      onClick={onClick}
      onPointerDown={() => setP(true)}
      onPointerUp={() => setP(false)}
      onPointerLeave={() => setP(false)}
      style={{ cursor: 'pointer', background: p ? pressBg : baseBg, ...style }}
    >{children}</div>
  );
}

// ── header ────────────────────────────────────────────────────
function Header() {
  return (
    <div style={{ flexShrink: 0, background: T.base, paddingTop: 52, borderBottom: `1px solid ${T.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px 0 4px', height: 44 }}>
        <button style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{ flex: 1, fontFamily: T.ui, fontSize: 17, fontWeight: 600, color: T.text }}>Sessions</span>
        <button style={iconBtn} aria-label="Settings"><Gear c={T.sec} s={19}/></button>
      </div>
      {/* connection bar — tap to switch directory */}
      <Pressable onClick={() => {}} baseBg={T.base} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 16px 10px' }}>
        <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 7px ${T.success}99`, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.sec, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flex: 1, minWidth: 0 }}>
          Lidapeng-MacBook&nbsp;<span style={{ color: T.border }}>·</span>&nbsp;<span style={{ color: T.text }}>~/proj/app/cc-pocket</span>&nbsp;<span style={{ color: T.border }}>·</span>&nbsp;⑂&nbsp;main
        </span>
        <Chevron d="right" c={T.muted} s={13} w={1.8}/>
      </Pressable>
    </div>
  );
}
const iconBtn = { all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 };

// ── new session row ───────────────────────────────────────────
function NewSession() {
  return (
    <Pressable
      onClick={() => { location.href = 'Chat.html'; }}
      baseBg={'rgba(217,119,87,0.10)'} pressBg={'rgba(217,119,87,0.18)'}
      style={{
        display: 'flex', alignItems: 'center', gap: 13, padding: '0 14px', minHeight: 60,
        border: `1px solid ${T.accent}66`, borderRadius: 14,
      }}
    >
      <span style={{ width: 36, height: 36, borderRadius: 10, background: T.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <Plus c="#0E0F11" s={18}/>
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: T.ui, fontSize: 16, fontWeight: 600, color: T.accent }}>New session</div>
        <div style={{ fontFamily: T.mono, fontSize: 11, color: T.muted, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Start Claude in ~/proj/app/cc-pocket</div>
      </div>
      <Chevron d="right" c={T.accent} s={15} w={2}/>
    </Pressable>
  );
}

// ── session card ──────────────────────────────────────────────
function SessionCard({ s }) {
  return (
    <Pressable
      onClick={() => { location.href = 'Chat.html'; }}
      baseBg={T.surface} pressBg={T.raised}
      style={{ border: `1px solid ${T.border}`, borderRadius: 12, padding: '13px 14px' }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontFamily: T.ui, fontSize: 15.5, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{s.title}</span>
        {s.active && (
          <span style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
            <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.accent }}/>
            <span style={{ fontFamily: T.mono, fontSize: 10, color: T.accent, letterSpacing: 0.3 }}>active</span>
          </span>
        )}
      </div>
      <div style={{ fontFamily: T.ui, fontSize: 13.5, lineHeight: '19px', color: T.sec, marginTop: 3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.preview}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 9, fontFamily: T.mono, fontSize: 11.5, color: T.muted }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0, whiteSpace: 'nowrap' }}><Bubble c={T.muted} s={12}/>{s.msgs}</span>
        <span style={{ color: T.border, flexShrink: 0 }}>·</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}><Branch c={T.muted} s={11}/><span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.branch}</span></span>
        <span style={{ color: T.border, flexShrink: 0 }}>·</span>
        <span style={{ flexShrink: 0, whiteSpace: 'nowrap' }}>{s.time}</span>
      </div>
    </Pressable>
  );
}

const SESSIONS = [
  { title: 'Refactor auth module', preview: 'add a unit test for the stream parser', msgs: 12, branch: 'main', time: '2h ago', active: true },
  { title: 'Fix stream parser test', preview: 'the parser drops the last token on EOF', msgs: 6, branch: 'fix/parser', time: '5h ago' },
  { title: 'Add relay websocket client', preview: 'scaffold the Ktor WS client with reconnect', msgs: 23, branch: 'feat/relay', time: 'yesterday' },
  { title: 'Wire up pairing flow', preview: 'generate a 6-digit pairing code on the daemon', msgs: 4, branch: 'main', time: '2d ago' },
];

// ── screen ────────────────────────────────────────────────────
function SessionsScreen() {
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <Header/>
      <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '16px 16px 28px' }}>
        <NewSession/>
        <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.6, color: T.muted, textTransform: 'uppercase', padding: '20px 2px 10px' }}>
          Recent
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {SESSIONS.map((s, i) => <SessionCard key={i} s={s}/>)}
        </div>
      </div>
    </div>
  );
}

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
        <IOSDevice dark><SessionsScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
