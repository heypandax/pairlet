// cc-pocket — Settings (grouped list)

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
const Bolt = ({ c = T.accent, s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M10.6 1.5L4 10.2h3.9l-1.4 6.3L14 7.4h-4l.6-5.9z" fill={c}/></svg>
);
const Warn = ({ c = T.warning, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M9 2.4l6.7 12.2H2.3L9 2.4z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/>
    <path d="M9 7v3.3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    <circle cx="9" cy="12.5" r="0.95" fill={c}/>
  </svg>
);
const Radio = ({ on }) => (
  <span style={{ width: 22, height: 22, borderRadius: 999, border: `2px solid ${on ? T.accent : T.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
    {on && <span style={{ width: 10, height: 10, borderRadius: 999, background: T.accent }}/>}
  </span>
);

// ── grouped list primitives ───────────────────────────────────
const SectionLabel = ({ children }) => (
  <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.6, color: T.muted, textTransform: 'uppercase', padding: '0 6px 8px' }}>{children}</div>
);
const Group = ({ label, children }) => (
  <div style={{ marginBottom: 26 }}>
    <SectionLabel>{label}</SectionLabel>
    <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' }}>{children}</div>
  </div>
);
const sep = (last) => ({ borderBottom: last ? 'none' : `1px solid ${T.border}` });

// ── permission mode rows — canonical ladder (ep-core.jsx) ─────
const MODES = [
  { key:'default',     label:"I’m watching · ask each step",        tech:'default',
    color:'#9BA1A6', allows:'nothing automatically', asks:'every sensitive tool' },
  { key:'acceptEdits', label:'Auto-edit files, ask before commands', tech:'acceptEdits',
    color:'#4FB477', allows:'file edits', asks:'commands' },
  { key:'plan',        label:"Plan first, I’ll approve",             tech:'plan',
    color:'#5B9BD5', allows:'read-only inspection', asks:'everything until you approve' },
  { key:'bypass',      label:'Full auto · trust it',                 tech:'bypassPermissions',
    color:'#E0A93B', warn:true, allows:'everything', asks:'nothing' },
];
function ModeRow({ m, selected, onSelect, last }) {
  const [p, setP] = React.useState(false);
  return (
    <div
      onClick={onSelect}
      onPointerDown={() => setP(true)} onPointerUp={() => setP(false)} onPointerLeave={() => setP(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 12, padding: '11px 14px', cursor: 'pointer',
        background: p ? T.raised : (m.warn ? 'rgba(224,169,59,0.06)' : 'transparent'),
        ...sep(last),
      }}
    >
      <Radio on={selected}/>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
          <span style={{ width: 7, height: 7, borderRadius: 999, background: m.color, flexShrink: 0 }}></span>
          <span style={{ fontFamily: T.ui, fontSize: 14.5, fontWeight: 600, color: T.text }}>{m.label}</span>
          {m.warn && <Warn c={T.warning} s={13}/>}
        </div>
        <div style={{ fontFamily: T.ui, fontSize: 12, lineHeight: '17px', color: T.sec, marginTop: 3 }}>
          <span style={{ fontFamily: T.mono, fontSize: 11.5, color: m.color }}>{m.tech}</span>
          <span style={{ color: T.muted }}>&nbsp;·&nbsp;</span>
          auto-allows {m.allows} · still asks {m.asks}
        </div>
      </div>
    </div>
  );
}

// ── segmented control ─────────────────────────────────────────
function Segmented({ options, value, onChange }) {
  return (
    <div style={{ display: 'flex', gap: 3, background: T.base, border: `1px solid ${T.border}`, borderRadius: 10, padding: 3 }}>
      {options.map(o => {
        const on = o === value;
        return (
          <button key={o} onClick={() => onChange(o)} style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', flex: 1, textAlign: 'center',
            padding: '8px 0', borderRadius: 8, fontFamily: T.ui, fontSize: 13.5, fontWeight: 600,
            color: on ? '#0E0F11' : T.sec, background: on ? T.accent : 'transparent',
          }}>{o}</button>
        );
      })}
    </div>
  );
}

// ── about row ─────────────────────────────────────────────────
const AboutRow = ({ label, value, mono, last }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 48, padding: '0 14px', ...sep(last) }}>
    <span style={{ fontFamily: T.ui, fontSize: 15, color: T.text, flexShrink: 0 }}>{label}</span>
    <span style={{ flex: 1 }}/>
    <span style={{ fontFamily: mono ? T.mono : T.ui, fontSize: mono ? 12.5 : 14.5, color: T.sec, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0, textAlign: 'right' }}>{value}</span>
  </div>
);

// ── screen ────────────────────────────────────────────────────
function SettingsScreen() {
  const [mode, setMode] = React.useState('default');
  const [theme, setTheme] = React.useState('Dark');
  const [revoked, setRevoked] = React.useState(false);

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <div style={{ flexShrink: 0, paddingTop: 52 }}>
        <div style={{ display: 'flex', alignItems: 'center', height: 44, padding: '0 4px' }}>
          <button onClick={() => history.length > 1 ? history.back() : (location.href = 'Sessions.html')} style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        </div>
      </div>

      <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '6px 16px 28px' }}>
        <div style={{ fontFamily: T.ui, fontSize: 28, fontWeight: 700, color: T.text, letterSpacing: -0.4, padding: '2px 4px 20px' }}>Settings</div>

        <div style={{ marginBottom: 26 }}>
          <SectionLabel>Default permission mode</SectionLabel>
          <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' }}>
            {MODES.map((m, i) => (
              <ModeRow key={m.key} m={m} selected={mode === m.key} onSelect={() => setMode(m.key)} last={i === MODES.length - 1}/>
            ))}
          </div>
          <div style={{ fontFamily: T.ui, fontSize: 11.5, color: T.muted, padding: '8px 6px 0' }}>Applies to new sessions · can be overridden per session</div>
        </div>

        <Group label={<React.Fragment>Paired devices <span style={{ textTransform: 'none', fontWeight: 500, letterSpacing: 0.2 }}>(coming later)</span></React.Fragment>}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 56, padding: '0 14px', ...sep(false) }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontFamily: T.ui, fontSize: 15, fontWeight: 500, color: T.text }}>iPhone 15 Pro</div>
              <div style={{ fontFamily: T.ui, fontSize: 12.5, color: T.sec, marginTop: 1 }}>This device</div>
            </div>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
              <span style={{ width: 6, height: 6, borderRadius: 999, background: T.success }}/>
              <span style={{ fontFamily: T.mono, fontSize: 11, color: T.muted }}>current</span>
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 56, padding: '0 14px', opacity: revoked ? 0.5 : 1 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontFamily: T.ui, fontSize: 15, fontWeight: 500, color: T.text }}>iPad Air</div>
              <div style={{ fontFamily: T.mono, fontSize: 11.5, color: T.muted, marginTop: 2 }}>{revoked ? 'access revoked' : 'paired 3d ago'}</div>
            </div>
            <button
              onClick={() => setRevoked(true)} disabled={revoked}
              style={{
                all: 'unset', boxSizing: 'border-box', cursor: revoked ? 'default' : 'pointer',
                padding: '7px 14px', borderRadius: 999, flexShrink: 0,
                border: `1.5px solid ${revoked ? T.border : T.danger}`, color: revoked ? T.muted : T.danger,
                fontFamily: T.ui, fontSize: 13, fontWeight: 600,
              }}
            >{revoked ? 'Revoked' : 'Revoke'}</button>
          </div>
        </Group>

        <Group label="Appearance">
          <div style={{ padding: 12 }}>
            <Segmented options={['System', 'Dark', 'Light']} value={theme} onChange={setTheme}/>
          </div>
        </Group>

        <Group label="About">
          <AboutRow label="Version" value="0.1.0" mono/>
          <AboutRow label="License" value="MIT"/>
          <AboutRow label="Daemon" value="ws://192.168.1.20:8765" mono last/>
        </Group>
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
        <IOSDevice dark><SettingsScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
