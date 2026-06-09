// cc-pocket — Permission request bottom sheet (over a dimmed Chat)

const T = {
  base: '#0E0F11', surface: '#16181B', raised: '#1E2125', border: '#2A2E33',
  text: '#ECEDEE', sec: '#9BA1A6', muted: '#6B7177',
  accent: '#D97757', success: '#4FB477', warning: '#E0A93B', danger: '#E5604D',
  mono: "'JetBrains Mono', ui-monospace, monospace",
  ui: "'Inter', -apple-system, system-ui, sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Shield = ({ c = T.sec, s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M9 1.6l5.6 2.1v4.1c0 3.7-2.7 6.1-5.6 7.1-2.9-1-5.6-3.4-5.6-7.1V3.7L9 1.6z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/>
    <path d="M6.4 9l1.8 1.8L11.8 7" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const Branch = ({ c = T.muted, s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
    <circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
  </svg>
);
const Chevron = ({ d = 'down', c = T.muted, s = 14, w = 1.8 }) => {
  const p = { down: 'M3 6l6 6 6-6', up: 'M3 12l6-6 6 6', left: 'M11 3L5 9l6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Check = ({ c = '#0E0F11', s = 13 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.5 7.3l2.8 2.8L11.5 4" stroke={c} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const Terminal = ({ c = T.sec, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <rect x="1.2" y="2.4" width="13.6" height="11.2" rx="2.6" stroke={c} strokeWidth="1.3"/>
    <path d="M4.3 6.2l2.1 1.9-2.1 1.9" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M8.4 10.2h3.3" stroke={c} strokeWidth="1.3" strokeLinecap="round"/>
  </svg>
);

// ── dimmed chat backdrop (static) ─────────────────────────────
function Backdrop() {
  const Label = ({ who }) => <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.4, color: T.muted, marginBottom: 6 }}>{who}</div>;
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      {/* header */}
      <div style={{ flexShrink: 0, paddingTop: 52, borderBottom: `1px solid ${T.border}` }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px 0 12px', height: 44 }}>
          <Chevron d="left" c={T.sec} s={17} w={2}/>
          <span style={{ flex: 1, fontFamily: T.ui, fontSize: 15, fontWeight: 600, color: T.text, marginLeft: 6 }}>Refactor auth module</span>
          <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 999, padding: '4px 9px' }}>default</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '0 16px 9px' }}>
          <span style={{ width: 6, height: 6, borderRadius: 999, background: T.success }}/>
          <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color: T.muted }}>~/proj/app/cc-pocket</span></span>
        </div>
      </div>
      {/* a little transcript */}
      <div style={{ flex: 1, overflow: 'hidden', padding: '18px 16px' }}>
        <Label who="You"/>
        <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '22px', color: T.text, marginBottom: 20 }}>clean the build and re-run the protocol tests</div>
        <Label who="Claude"/>
        <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
          I’ll wipe the stale build output and run a clean Gradle build before the test suite.
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginTop: 14, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 10, padding: '0 11px', minHeight: 40 }}>
          <Terminal c={T.sec} s={15}/>
          <span style={{ fontFamily: T.ui, fontSize: 12.5, fontWeight: 600, color: T.text }}>Bash</span>
          <span style={{ fontFamily: T.mono, fontSize: 12, color: T.sec, flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>rm -rf ./build && ./gradlew clean</span>
          <span className="cc-pulse" style={{ width: 7, height: 7, borderRadius: 999, background: T.warning }}/>
        </div>
      </div>
    </div>
  );
}

// ── countdown ring ────────────────────────────────────────────
function CountdownRing({ seconds, total }) {
  const sz = 54, r = 23, C = 2 * Math.PI * r;
  const frac = Math.max(0, seconds / total);
  const low = seconds <= 5;
  const col = low ? T.danger : T.accent;
  const mm = Math.floor(seconds / 60), ss = String(seconds % 60).padStart(2, '0');
  return (
    <div style={{ position: 'relative', width: sz, height: sz, flexShrink: 0 }}>
      <svg width={sz} height={sz} viewBox={`0 0 ${sz} ${sz}`} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={sz/2} cy={sz/2} r={r} stroke={T.border} strokeWidth="3" fill="none"/>
        <circle cx={sz/2} cy={sz/2} r={r} stroke={col} strokeWidth="3" fill="none"
          strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - frac)}/>
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: T.mono, fontSize: 13, fontWeight: 500, color: col }}>
        {mm}:{ss}
      </div>
    </div>
  );
}

// ── permission sheet ──────────────────────────────────────────
function PermissionSheet({ onResolve }) {
  const TOTAL = 23;
  const [seconds, setSeconds] = React.useState(TOTAL);
  const [remember, setRemember] = React.useState(false);
  const [expanded, setExpanded] = React.useState(false);

  React.useEffect(() => {
    if (seconds <= 0) { onResolve('deny', true); return; }
    const t = setTimeout(() => setSeconds(s => s - 1), 1000);
    return () => clearTimeout(t);
  }, [seconds]);

  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 30,
      background: T.raised, borderTopLeftRadius: 20, borderTopRightRadius: 20,
      borderTop: `1px solid ${T.border}`, borderLeft: `1px solid ${T.border}`, borderRight: `1px solid ${T.border}`,
      paddingBottom: 34,
    }}>
      {/* grab handle */}
      <div style={{ display: 'flex', justifyContent: 'center', padding: '8px 0 6px' }}>
        <div style={{ width: 38, height: 5, borderRadius: 999, background: T.border }}/>
      </div>

      <div style={{ padding: '4px 18px 18px' }}>
        {/* title row + countdown */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 10 }}>
              <Shield c={T.warning} s={16}/>
              <span style={{ fontFamily: T.ui, fontSize: 13, fontWeight: 500, color: T.sec }}>Claude needs permission</span>
            </div>
            <div style={{ fontFamily: T.ui, fontSize: 22, fontWeight: 700, color: T.text, letterSpacing: -0.2 }}>
              Run command&nbsp;<span style={{ color: T.muted, fontWeight: 600 }}>·</span>&nbsp;<span style={{ fontFamily: T.mono, fontSize: 18, fontWeight: 600, color: T.text }}>Bash</span>
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3, paddingTop: 2 }}>
            <CountdownRing seconds={seconds} total={TOTAL}/>
            <span style={{ fontFamily: T.ui, fontSize: 10, color: T.muted, letterSpacing: 0.2 }}>auto-deny</span>
          </div>
        </div>

        {/* command card */}
        <div style={{ marginTop: 14, background: T.base, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' }}>
          <div style={{ padding: '13px 14px' }}>
            <pre style={{ margin: 0, fontFamily: T.mono, fontSize: 13, lineHeight: '20px', color: T.text, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              <span style={{ color: T.danger }}>rm -rf</span> ./build <span style={{ color: T.muted }}>&&</span> ./gradlew clean
            </pre>
          </div>
          {expanded && (
            <div style={{ padding: '11px 14px', borderTop: `1px solid ${T.border}` }}>
              <div style={{ fontFamily: T.ui, fontSize: 12.5, lineHeight: '19px', color: T.sec }}>
                Recursively deletes <span style={{ fontFamily: T.mono, color: T.text }}>./build</span>, then runs the Gradle <span style={{ fontFamily: T.mono, color: T.text }}>clean</span> task. This removes compiled output and caches.
              </div>
            </div>
          )}
          <button onClick={() => setExpanded(e => !e)} style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: '100%',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
            height: 32, borderTop: `1px solid ${T.border}`, background: T.surface,
          }}>
            <span style={{ display: 'flex', transform: expanded ? 'rotate(180deg)' : 'none' }}><Chevron d="down" c={T.muted} s={13} w={1.8}/></span>
            <span style={{ fontFamily: T.mono, fontSize: 11, color: T.muted }}>{expanded ? 'collapse' : 'expand'}</span>
          </button>
        </div>

        {/* path / branch */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, flexWrap: 'nowrap' }}>
          <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.sec, whiteSpace: 'nowrap' }}>~/proj/app/cc-pocket</span>
          <span style={{ color: T.border }}>·</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
            <Branch c={T.muted} s={11}/>
            <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.sec }}>main</span>
          </span>
        </div>

        {/* remember checkbox */}
        <button onClick={() => setRemember(r => !r)} style={{
          all: 'unset', boxSizing: 'border-box', cursor: 'pointer',
          display: 'flex', alignItems: 'center', gap: 10, marginTop: 16, minHeight: 28,
        }}>
          <span style={{
            width: 20, height: 20, borderRadius: 6, flexShrink: 0,
            border: `1.5px solid ${remember ? T.accent : T.border}`,
            background: remember ? T.accent : 'transparent',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>{remember && <Check c="#0E0F11" s={13}/>}</span>
          <span style={{ fontFamily: T.ui, fontSize: 14, color: T.text }}>Remember for this session</span>
        </button>

        {/* actions */}
        <div style={{ display: 'flex', gap: 10, marginTop: 18 }}>
          <button onClick={() => onResolve('deny', false)} style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', flex: 1, height: 52,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            borderRadius: 12, border: `1.5px solid ${T.danger}`, color: T.danger,
            fontFamily: T.ui, fontSize: 16, fontWeight: 600,
          }}>Deny</button>
          <button onClick={() => onResolve('allow', false)} style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', flex: 1, height: 52,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            borderRadius: 12, background: T.accent, color: '#0E0F11',
            fontFamily: T.ui, fontSize: 16, fontWeight: 700,
          }}>Allow</button>
        </div>
      </div>
    </div>
  );
}

// ── resolved toast (after allow/deny) ─────────────────────────
function ResolvedBar({ verdict, onReopen }) {
  const allowed = verdict === 'allow';
  const c = allowed ? T.success : T.danger;
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, bottom: 46, zIndex: 30,
      display: 'flex', alignItems: 'center', gap: 10,
      background: T.raised, border: `1px solid ${T.border}`, borderRadius: 12, padding: '13px 14px',
      boxShadow: '0 10px 30px rgba(0,0,0,0.5)' }}>
      <span style={{ width: 8, height: 8, borderRadius: 999, background: c, flexShrink: 0 }}/>
      <span style={{ fontFamily: T.ui, fontSize: 13.5, color: T.text, flex: 1 }}>
        {allowed ? 'Allowed' : 'Denied'} <span style={{ color: T.muted }}>· Bash command</span>
      </span>
      <button onClick={onReopen} style={{ all: 'unset', cursor: 'pointer', fontFamily: T.ui, fontSize: 13, fontWeight: 600, color: T.accent }}>Show again</button>
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function PermissionScreen() {
  const [verdict, setVerdict] = React.useState(null); // null | 'allow' | 'deny'

  return (
    <div style={{ position: 'relative', height: '100%', overflow: 'hidden' }}>
      <Backdrop/>
      {/* dim */}
      {!verdict && <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.58)', zIndex: 20 }}/>}
      {!verdict
        ? <PermissionSheet key="sheet" onResolve={(v) => setVerdict(v)}/>
        : <ResolvedBar verdict={verdict} onReopen={() => setVerdict(null)}/>}
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
        <IOSDevice dark><PermissionScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
