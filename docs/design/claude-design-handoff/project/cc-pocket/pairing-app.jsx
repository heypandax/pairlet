// cc-pocket — Pairing / connect your computer

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

// ── QR viewfinder ─────────────────────────────────────────────
function Viewfinder() {
  const SIZE = 226;
  const corner = (pos) => {
    const len = 30, th = 3, off = -1;
    const base = { position: 'absolute', width: len, height: len, borderColor: T.accent, borderStyle: 'solid', borderWidth: 0 };
    const map = {
      tl: { top: off, left: off, borderTopWidth: th, borderLeftWidth: th, borderTopLeftRadius: 14 },
      tr: { top: off, right: off, borderTopWidth: th, borderRightWidth: th, borderTopRightRadius: 14 },
      bl: { bottom: off, left: off, borderBottomWidth: th, borderLeftWidth: th, borderBottomLeftRadius: 14 },
      br: { bottom: off, right: off, borderBottomWidth: th, borderRightWidth: th, borderBottomRightRadius: 14 },
    };
    return <div className="cc-bracket" style={{ ...base, ...map[pos] }} />;
  };
  return (
    <div style={{
      width: SIZE, height: SIZE, position: 'relative', borderRadius: 16,
      background: 'radial-gradient(120% 120% at 50% 30%, #15171A 0%, #0B0C0D 100%)',
      overflow: 'hidden', boxShadow: 'inset 0 0 0 1px rgba(42,46,51,0.8)',
    }}>
      {/* faint placeholder texture so it reads as a camera feed */}
      <div style={{
        position: 'absolute', inset: 0, opacity: 0.5,
        backgroundImage: 'repeating-linear-gradient(135deg, rgba(255,255,255,0.018) 0 8px, transparent 8px 16px)',
      }}/>
      {/* scan line */}
      <div className="cc-scan" style={{
        position: 'absolute', left: 14, right: 14, height: 2, borderRadius: 2,
        background: `linear-gradient(90deg, transparent, ${T.accent}, transparent)`,
        boxShadow: `0 0 12px ${T.accent}`,
      }}/>
      {corner('tl')}{corner('tr')}{corner('bl')}{corner('br')}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 18, textAlign: 'center',
        fontFamily: T.mono, fontSize: 10.5, color: T.muted, letterSpacing: 0.3,
      }}>scanning…</div>
    </div>
  );
}

// ── divider ───────────────────────────────────────────────────
const Divider = ({ label }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%' }}>
    <div style={{ flex: 1, height: 1, background: T.border }}/>
    <span style={{ fontFamily: T.ui, fontSize: 12.5, color: T.muted }}>{label}</span>
    <div style={{ flex: 1, height: 1, background: T.border }}/>
  </div>
);

// ── 6-digit segmented code ────────────────────────────────────
function CodeInput({ code, setCode }) {
  const ref = React.useRef(null);
  const active = Math.min(code.length, 5);
  const boxes = [0, 1, 2, 3, 4, 5];
  return (
    <div style={{ position: 'relative', width: '100%' }} onClick={() => ref.current && ref.current.focus()}>
      <input
        ref={ref}
        value={code}
        onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
        inputMode="numeric"
        style={{ position: 'absolute', inset: 0, opacity: 0, border: 'none', background: 'transparent', color: 'transparent', caretColor: 'transparent', width: '100%', height: '100%', cursor: 'pointer' }}
        aria-label="Pairing code"
      />
      <div style={{ display: 'flex', gap: 9, justifyContent: 'center' }}>
        {boxes.map(i => {
          const filled = i < code.length;
          const isActive = i === active;
          return (
            <div key={i} style={{
              flex: 1, maxWidth: 50, height: 58, borderRadius: 12,
              background: T.surface,
              border: `1.5px solid ${isActive ? T.accent : T.border}`,
              boxShadow: isActive ? `0 0 0 3px rgba(217,119,87,0.14)` : 'none',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              {filled
                ? <span style={{ fontFamily: T.mono, fontSize: 24, fontWeight: 500, color: T.text }}>{code[i]}</span>
                : isActive
                  ? <span className="cc-blink" style={{ width: 2, height: 26, background: T.accent, borderRadius: 2 }}/>
                  : <span style={{ width: 8, height: 2, background: T.border, borderRadius: 2 }}/>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function PairingScreen() {
  const [code, setCode] = React.useState('481');
  const complete = code.length === 6;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      {/* nav */}
      <div style={{ flexShrink: 0, paddingTop: 52 }}>
        <div style={{ display: 'flex', alignItems: 'center', height: 44, padding: '0 4px' }}>
          <button onClick={() => { location.href = 'Computers.html'; }} style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        </div>
      </div>

      <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '6px 24px 18px', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        {/* title */}
        <div style={{ textAlign: 'center', marginBottom: 22 }}>
          <div style={{ fontFamily: T.ui, fontSize: 24, fontWeight: 700, color: T.text, letterSpacing: -0.3 }}>Connect your computer</div>
          <div style={{ fontFamily: T.ui, fontSize: 14, lineHeight: '20px', color: T.sec, marginTop: 7, maxWidth: 300 }}>
            Pair this phone with the cc-pocket daemon on your computer.
          </div>
        </div>

        <Viewfinder/>

        <div style={{ width: '100%', margin: '22px 0 18px' }}><Divider label="or enter the pairing code"/></div>

        <CodeInput code={code} setCode={setCode}/>

        <div style={{ marginTop: 18, textAlign: 'center', fontFamily: T.ui, fontSize: 13, lineHeight: '20px', color: T.sec }}>
          Run&nbsp;<span style={{ fontFamily: T.mono, fontSize: 12.5, color: T.text, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 6, padding: '2px 7px' }}>cc-pocket pair</span>&nbsp;on your computer to get a code.
        </div>
      </div>

      {/* footer */}
      <div style={{ flexShrink: 0, background: T.base, borderTop: `1px solid ${T.border}`, padding: '12px 16px', paddingBottom: 34 }}>
        <button
          onClick={() => { location.href = 'Sessions.html'; }}
          style={{
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: '100%', height: 52,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: T.accent, color: '#0E0F11', borderRadius: 12,
            fontFamily: T.ui, fontSize: 16, fontWeight: 700,
            opacity: complete ? 1 : 0.92,
          }}
        >Connect</button>
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
        <IOSDevice dark><PairingScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
