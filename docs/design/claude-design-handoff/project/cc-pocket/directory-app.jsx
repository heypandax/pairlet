// cc-pocket — Directory picker (choose the working directory Claude runs in)

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

const plural = (n) => `${n} session${n === 1 ? '' : 's'}`;

// ── sessions pill ─────────────────────────────────────────────
const SessionsPill = ({ n }) => (
  <span style={{
    fontFamily: T.mono, fontSize: 10.5, color: T.accent, flexShrink: 0,
    background: 'rgba(217,119,87,0.13)', border: '1px solid rgba(217,119,87,0.30)',
    borderRadius: 999, padding: '2px 8px', whiteSpace: 'nowrap',
  }}>{plural(n)}</span>
);

// ── pressable row ─────────────────────────────────────────────
function Row({ children, onClick, last }) {
  const [p, setP] = React.useState(false);
  return (
    <div
      onClick={onClick}
      onPointerDown={() => setP(true)} onPointerUp={() => setP(false)} onPointerLeave={() => setP(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 11, minHeight: 54, padding: '0 14px',
        cursor: 'pointer', background: p ? T.raised : 'transparent',
        borderBottom: last ? 'none' : `1px solid ${T.border}`,
      }}
    >{children}</div>
  );
}

const SectionLabel = ({ children }) => (
  <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.6, color: T.muted, textTransform: 'uppercase', padding: '0 4px 8px' }}>{children}</div>
);

// ── fake filesystem ───────────────────────────────────────────
const FS = {
  name: '~', children: [
    { name: 'proj', children: [
      { name: 'app', children: [
        { name: 'cc-pocket', sessions: 3, children: [ { name: 'src', children: [] }, { name: 'protocol', sessions: 1, children: [] }, { name: 'build', children: [] } ] },
        { name: 'cc-dashboard', sessions: 8, children: [ { name: 'src', children: [] }, { name: 'public', children: [] } ] },
        { name: 'analyse', children: [ { name: 'notebooks', children: [] } ] },
        { name: 'ReleaseAdmin', sessions: 2, children: [ { name: 'app', children: [] } ] },
        { name: 'nanobanana', children: [ { name: 'model', children: [] } ] },
      ]},
      { name: 'infra', children: [ { name: 'terraform', children: [] } ] },
      { name: 'scripts', children: [] },
    ]},
    { name: 'work', children: [
      { name: 'api-server', sessions: 1, children: [ { name: 'cmd', children: [] } ] },
      { name: 'notes', children: [] },
    ]},
    { name: 'Downloads', children: [] },
  ],
};
function childrenAt(path) {
  let node = FS;
  for (const name of path.slice(1)) {
    node = (node.children || []).find(c => c.name === name) || { children: [] };
  }
  return node.children || [];
}

const RECENTS = [
  { path: '~/proj/app/cc-pocket', sessions: 3 },
  { path: '~/proj/app/cc-dashboard', sessions: 8 },
  { path: '~/work/api-server', sessions: 1 },
];

// ── header ────────────────────────────────────────────────────
function Header() {
  return (
    <div style={{ flexShrink: 0, background: T.base, paddingTop: 52, borderBottom: `1px solid ${T.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px 0 4px', height: 44 }}>
        <button style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{ flex: 1, fontFamily: T.ui, fontSize: 17, fontWeight: 600, color: T.text }}>Working directory</span>
      </div>
      <div
        onClick={() => { location.href = 'Computers.html'; }}
        style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 16px 10px', cursor: 'pointer' }}
      >
        <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 7px ${T.success}99`, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec, flex: 1 }}>Lidapeng-MacBook</span>
        <span style={{ fontFamily: T.ui, fontSize: 11.5, color: T.muted }}>Switch</span>
        <Chevron d="right" c={T.muted} s={12} w={1.8}/>
      </div>
    </div>
  );
}
const iconBtn = { all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 };

// ── breadcrumb ────────────────────────────────────────────────
function Breadcrumb({ path, onJump }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 2, padding: '0 4px 10px' }}>
      {path.map((seg, i) => {
        const last = i === path.length - 1;
        return (
          <React.Fragment key={i}>
            {i > 0 && <span style={{ fontFamily: T.mono, fontSize: 13, color: T.muted, padding: '0 5px' }}>/</span>}
            <span
              onClick={() => !last && onJump(i)}
              style={{
                fontFamily: T.mono, fontSize: 13, fontWeight: last ? 600 : 400,
                color: last ? T.text : T.sec, cursor: last ? 'default' : 'pointer',
                padding: '4px 2px',
              }}
            >{seg}</span>
          </React.Fragment>
        );
      })}
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function DirectoryScreen() {
  const [path, setPath] = React.useState(['~', 'proj', 'app']);
  const kids = childrenAt(path);
  const currentPath = path.join('/').replace('~/', '~/');

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <Header/>
      <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '16px 16px 16px' }}>
        {/* RECENTS */}
        <SectionLabel>Recents</SectionLabel>
        <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden', marginBottom: 24 }}>
          {RECENTS.map((r, i) => (
            <Row key={i} last={i === RECENTS.length - 1} onClick={() => { location.href = 'Sessions.html'; }}>
              <Folder c={T.sec} s={17}/>
              <span style={{ flex: 1, fontFamily: T.mono, fontSize: 12.5, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{r.path}</span>
              <SessionsPill n={r.sessions}/>
              <Chevron d="right" c={T.muted} s={14}/>
            </Row>
          ))}
        </div>

        {/* BROWSE */}
        <SectionLabel>Browse</SectionLabel>
        <Breadcrumb path={path} onJump={(i) => setPath(path.slice(0, i + 1))}/>
        <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' }}>
          {kids.length === 0 && (
            <div style={{ padding: '18px 14px', fontFamily: T.mono, fontSize: 12, color: T.muted }}>empty directory</div>
          )}
          {kids.map((c, i) => (
            <Row key={c.name} last={i === kids.length - 1} onClick={() => setPath([...path, c.name])}>
              <Folder c={T.sec} s={17}/>
              <span style={{ flex: 1, fontFamily: T.mono, fontSize: 13, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>{c.name}</span>
              {c.sessions ? <SessionsPill n={c.sessions}/> : null}
              <Chevron d="right" c={T.muted} s={14}/>
            </Row>
          ))}
        </div>
      </div>

      {/* footer */}
      <div style={{ flexShrink: 0, background: T.base, borderTop: `1px solid ${T.border}`, padding: '12px 16px', paddingBottom: 34 }}>
        <button
          onClick={() => { location.href = 'Sessions.html'; }}
          style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: '100%', height: 52,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            background: T.accent, color: '#0E0F11', borderRadius: 12,
            fontFamily: T.ui, fontSize: 16, fontWeight: 700,
          }}
        >
          Use this directory
        </button>
        <div style={{ textAlign: 'center', marginTop: 8, fontFamily: T.mono, fontSize: 11, color: T.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{currentPath}</div>
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
        <IOSDevice dark><DirectoryScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
