// cc-pocket — Choose a directory (v1: flat daemon-pushed list)
// Browse-filesystem moved to P2 (needs daemon support) — see annotation below the phone.

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
const Folder = ({ c = T.sec, s = 17 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M2 5.4a1.6 1.6 0 011.6-1.6h3.1l1.5 1.6h6.2A1.6 1.6 0 0116 7v6.4a1.6 1.6 0 01-1.6 1.6H3.6A1.6 1.6 0 012 13.4z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/>
  </svg>
);
const Branch = ({ c = T.muted, s = 11 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
    <circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
  </svg>
);
const SearchIcon = ({ c = T.muted, s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <circle cx="7" cy="7" r="4.4" stroke={c} strokeWidth="1.5"/>
    <path d="M10.4 10.4L14 14" stroke={c} strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);
const Spinner = ({ s = 17, c = T.accent }) => {
  const r = (s - 3) / 2, C = 2 * Math.PI * r;
  return (
    <svg className="cc-spin" width={s} height={s} viewBox={`0 0 ${s} ${s}`}>
      <circle cx={s/2} cy={s/2} r={r} stroke="rgba(255,255,255,0.16)" strokeWidth="2" fill="none"/>
      <circle cx={s/2} cy={s/2} r={r} stroke={c} strokeWidth="2" fill="none" strokeLinecap="round" strokeDasharray={`${C*0.28} ${C}`}/>
    </svg>
  );
};

// ── data (what the daemon pushes) ─────────────────────────────
const OPEN_SESSIONS = [
  { title: 'Refactor auth module', project: 'cc-pocket', branch: 'main', running: true },
  { title: 'Add relay websocket client', project: 'cc-dashboard', branch: 'feat/relay', running: false },
];
const PROJECTS = [
  { name: 'cc-pocket', path: '~/proj/app/cc-pocket', history: true },
  { name: 'cc-dashboard', path: '~/proj/app/cc-dashboard', history: true },
  { name: 'analyse', path: '~/proj/app/analyse', history: false },
  { name: 'ReleaseAdmin', path: '~/proj/app/ReleaseAdmin', history: true },
  { name: 'nanobanana', path: '~/proj/app/nanobanana', history: false },
  { name: 'api-server', path: '~/work/api-server', history: true },
];

// ── primitives ────────────────────────────────────────────────
function Row({ children, onClick, last }) {
  const [p, setP] = React.useState(false);
  return (
    <div
      onClick={onClick}
      onPointerDown={() => setP(true)} onPointerUp={() => setP(false)} onPointerLeave={() => setP(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 11, minHeight: 56, padding: '9px 14px',
        cursor: 'pointer', background: p ? T.raised : 'transparent',
        borderBottom: last ? 'none' : `1px solid ${T.border}`,
      }}
    >{children}</div>
  );
}
const SectionLabel = ({ children }) => (
  <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.6, color: T.muted, textTransform: 'uppercase', padding: '0 4px 8px' }}>{children}</div>
);
const Card = ({ children, style }) => (
  <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden', ...style }}>{children}</div>
);
const HistoryBadge = () => (
  <span style={{
    fontFamily: T.mono, fontSize: 10.5, color: T.accent, flexShrink: 0,
    background: 'rgba(217,119,87,0.13)', border: '1px solid rgba(217,119,87,0.30)',
    borderRadius: 999, padding: '2px 8px',
  }}>history</span>
);

// ── header (no Switch — multi-computer is P2) ────────────────
function Header() {
  return (
    <div style={{ flexShrink: 0, background: T.base, paddingTop: 52 }}>
      <div style={{ display: 'flex', alignItems: 'center', height: 44, padding: '0 16px' }}>
        <span style={{ flex: 1, fontFamily: T.ui, fontSize: 17, fontWeight: 600, color: T.text }}>Choose a directory</span>
        <button style={{ all: 'unset', cursor: 'pointer', fontFamily: T.ui, fontSize: 14, fontWeight: 600, color: T.sec, padding: '8px 2px' }} aria-label="Exit">Exit</button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '2px 16px 10px' }}>
        <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 7px ${T.success}99`, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec, whiteSpace: 'nowrap' }}>Lidapeng-MacBook</span>
      </div>
    </div>
  );
}

// ── filter input ──────────────────────────────────────────────
function Filter({ value, onChange }) {
  return (
    <div style={{ padding: '2px 16px 12px', borderBottom: `1px solid ${T.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 10, padding: '0 12px', height: 38 }}>
        <SearchIcon c={T.muted} s={14}/>
        <input
          value={value} onChange={e => onChange(e.target.value)} placeholder="filter…"
          style={{ all: 'unset', flex: 1, fontFamily: T.mono, fontSize: 13, color: T.text, minWidth: 0 }}
        />
      </div>
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function DirectoryScreen() {
  const [q, setQ] = React.useState('');
  const [refreshing, setRefreshing] = React.useState(false);
  const [pull, setPull] = React.useState(0);
  const scrollRef = React.useRef(null);
  const touch = React.useRef(null);

  const match = (s) => s.toLowerCase().includes(q.trim().toLowerCase());
  const sessions = OPEN_SESSIONS.filter(s => !q.trim() || match(s.title) || match(s.project) || match(s.branch));
  const projects = PROJECTS.filter(p => !q.trim() || match(p.name) || match(p.path));

  // pull-to-refresh
  const onDown = (e) => { if (scrollRef.current && scrollRef.current.scrollTop <= 0) touch.current = e.clientY; };
  const onMove = (e) => {
    if (touch.current == null || refreshing) return;
    const dy = e.clientY - touch.current;
    if (dy > 0 && scrollRef.current.scrollTop <= 0) setPull(Math.min(dy * 0.5, 64));
  };
  const onUp = () => {
    if (pull > 44 && !refreshing) {
      setRefreshing(true); setPull(40);
      setTimeout(() => { setRefreshing(false); setPull(0); }, 1100);
    } else setPull(0);
    touch.current = null;
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <Header/>
      <Filter value={q} onChange={setQ}/>
      <div
        ref={scrollRef} className="cc-scroll"
        onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerLeave={onUp}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 16px 28px', touchAction: 'pan-y' }}
      >
        {/* pull-to-refresh indicator */}
        <div style={{ height: pull, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', transition: touch.current == null ? 'height .2s ease' : 'none' }}>
          {(refreshing || pull > 8) && (
            refreshing
              ? <Spinner s={17}/>
              : <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.muted }}>{pull > 44 ? 'release to refresh' : 'pull to refresh'}</span>
          )}
        </div>

        {/* OPEN SESSIONS */}
        {sessions.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <SectionLabel>Open Sessions</SectionLabel>
            <Card style={{ marginBottom: 24 }}>
              {sessions.map((s, i) => (
                <Row key={s.title} last={i === sessions.length - 1} onClick={() => { location.href = 'Chat.html'; }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontFamily: T.ui, fontSize: 15, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title}</div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginTop: 3, minWidth: 0 }}>
                      <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.project}</span>
                      <span style={{ color: T.border, flexShrink: 0 }}>·</span>
                      <span style={{ flexShrink: 0, display: 'flex' }}><Branch c={T.muted} s={11}/></span>
                      <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.muted, whiteSpace: 'nowrap', flexShrink: 0 }}>{s.branch}</span>
                    </div>
                  </div>
                  <span style={{
                    fontFamily: T.mono, fontSize: 11, flexShrink: 0,
                    color: s.running ? T.accent : T.muted,
                  }}>{s.running ? 'running' : 'idle'}</span>
                  <Chevron d="right" c={T.muted} s={14}/>
                </Row>
              ))}
            </Card>
          </div>
        )}

        {/* PROJECTS */}
        {projects.length > 0 && (
          <div style={{ marginTop: sessions.length ? 0 : 16 }}>
            <SectionLabel>Projects</SectionLabel>
            <Card>
              {projects.map((p, i) => (
                <Row key={p.name} last={i === projects.length - 1} onClick={() => { location.href = 'Sessions.html'; }}>
                  <Folder c={T.sec} s={17}/>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontFamily: T.ui, fontSize: 14.5, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.name}</div>
                    <div style={{ fontFamily: T.mono, fontSize: 11, color: T.muted, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.path}</div>
                  </div>
                  {p.history && <HistoryBadge/>}
                  <Chevron d="right" c={T.muted} s={14}/>
                </Row>
              ))}
            </Card>
          </div>
        )}

        {sessions.length === 0 && projects.length === 0 && (
          <div style={{ padding: '40px 0', textAlign: 'center', fontFamily: T.mono, fontSize: 12, color: T.muted }}>no matches for “{q}”</div>
        )}
      </div>
    </div>
  );
}

// ── P2 annotation: the deferred Browse-filesystem UI ──────────
function P2Browse() {
  const [open, setOpen] = React.useState(false);
  return (
    <div style={{ width: '100%', maxWidth: 560, marginTop: 26 }}>
      <button onClick={() => setOpen(o => !o)} style={{
        all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: '100%',
        display: 'flex', alignItems: 'center', gap: 12,
        background: T.surface, border: `1px dashed ${T.border}`,
        borderRadius: open ? '12px 12px 0 0' : 12, padding: '13px 16px',
      }}>
        <span style={{ fontFamily: T.mono, fontSize: 11, color: T.warning, border: `1px solid ${T.warning}55`, borderRadius: 999, padding: '2px 9px', flexShrink: 0 }}>P2</span>
        <span style={{ flex: 1, fontFamily: T.ui, fontSize: 13, fontWeight: 600, color: T.text }}>Browse filesystem — needs daemon support</span>
        <span style={{ display: 'flex', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .2s' }}><Chevron d="down" c={T.muted} s={14}/></span>
      </button>
      {open && (
        <div style={{ border: `1px dashed ${T.border}`, borderTop: 'none', borderRadius: '0 0 12px 12px', padding: '14px 16px 18px', background: 'rgba(22,24,27,0.5)' }}>
          <div style={{ fontFamily: T.ui, fontSize: 12.5, lineHeight: '19px', color: T.sec, marginBottom: 14 }}>
            v1 lists only what the daemon pushes (open sessions + known projects). Free navigation of the computer’s
            filesystem — breadcrumbs, drill-in, “Use this directory” — needs a <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.text }}>list_dir</span> RPC
            on the daemon. Deferred design below.
          </div>
          {/* static slice of the deferred browse UI */}
          <div style={{ background: T.base, border: `1px solid ${T.border}`, borderRadius: 12, padding: '14px 14px 0', maxWidth: 372 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 2, paddingBottom: 10 }}>
              {['~', 'proj', 'app'].map((seg, i) => (
                <React.Fragment key={seg}>
                  {i > 0 && <span style={{ fontFamily: T.mono, fontSize: 12.5, color: T.muted, padding: '0 5px' }}>/</span>}
                  <span style={{ fontFamily: T.mono, fontSize: 12.5, fontWeight: i === 2 ? 600 : 400, color: i === 2 ? T.text : T.sec }}>{seg}</span>
                </React.Fragment>
              ))}
            </div>
            <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 10, overflow: 'hidden' }}>
              {['cc-pocket', 'cc-dashboard', 'analyse'].map((n, i) => (
                <div key={n} style={{ display: 'flex', alignItems: 'center', gap: 10, minHeight: 44, padding: '0 12px', borderBottom: i < 2 ? `1px solid ${T.border}` : 'none' }}>
                  <Folder c={T.sec} s={15}/>
                  <span style={{ flex: 1, fontFamily: T.mono, fontSize: 12.5, color: T.text }}>{n}</span>
                  <Chevron d="right" c={T.muted} s={13}/>
                </div>
              ))}
            </div>
            <div style={{ padding: '12px 0 14px' }}>
              <div style={{ height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', background: T.accent, borderRadius: 10, fontFamily: T.ui, fontSize: 14.5, fontWeight: 700, color: '#0E0F11' }}>Use this directory</div>
              <div style={{ textAlign: 'center', marginTop: 7, fontFamily: T.mono, fontSize: 10.5, color: T.muted }}>~/proj/app</div>
            </div>
          </div>
        </div>
      )}
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
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <div style={{ width: 402 * scale, height: 874 * scale }}>
        <div style={{ width: 402, height: 874, transform: `scale(${scale})`, transformOrigin: 'top left' }}>
          <IOSDevice dark><DirectoryScreen/></IOSDevice>
        </div>
      </div>
      <P2Browse/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
