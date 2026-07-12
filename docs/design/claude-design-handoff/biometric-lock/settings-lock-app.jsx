// cc-pocket — Settings · App Lock control + app-switcher privacy cover

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono', ui-monospace, monospace",
  ui:"'Inter', -apple-system, system-ui, sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ c = T.muted, s = 15, w = 2 }) =>
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M6 3l6 6-6 6" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Check = ({ c = T.accent, s = 18 }) =>
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Warn = ({ c = T.warning, s = 13 }) =>
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 2.4l6.7 12.2H2.3L9 2.4z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/><path d="M9 7v3.3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/><circle cx="9" cy="12.5" r="0.95" fill={c}/></svg>;

// Face ID line icon (1.5pt) — leading row icon + gate/cover signifier
function FaceID({ c, w = 2.4, s = 22, className }) {
  return (
    <svg width={s} height={s} viewBox="0 0 64 64" fill="none" className={className}
         stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 20 V11 A5 5 0 0 1 11 6 H20"/>
      <path d="M44 6 H53 A5 5 0 0 1 58 11 V20"/>
      <path d="M6 44 V53 A5 5 0 0 1 11 58 H20"/>
      <path d="M44 58 H53 A5 5 0 0 1 58 53 V44"/>
      <path d="M24 25 V30"/><path d="M40 25 V30"/>
      <path d="M32 26 V36 H28.5"/>
      <path d="M25 41 Q32 46.5 39 41"/>
    </svg>
  );
}

// cc-pocket app mark — chevron + bar, monochrome
function AppMark({ c, s = 30 }) {
  return (
    <svg width={s} height={s * (37/56)} viewBox="0 0 56 37" fill="none">
      <path d="M7 4 L23 18.5 L7 33" stroke={c} strokeWidth="7.5" strokeLinecap="round" strokeLinejoin="round"/>
      <rect x="33" y="3" width="15" height="31" rx="4" fill={c}/>
    </svg>
  );
}

// ── iOS switch ────────────────────────────────────────────────
function Switch({ on, verifying }) {
  const W = 51, H = 31, K = 27, pad = 2;
  // verifying: knob parked mid-travel, dimmed accent track, pending pulse
  const x = verifying ? (W - K) / 2 : on ? W - K - pad : pad;
  const track = verifying ? T.accent : on ? T.accent : T.border;
  return (
    <span className={verifying ? 'cc-verify' : undefined} style={{
      width: W, height: H, borderRadius: 999, position: 'relative', flexShrink: 0,
      background: track, opacity: verifying ? 0.6 : 1,
      transition: 'background .2s',
    }}>
      <span style={{
        position: 'absolute', top: pad, left: x, width: K, height: K, borderRadius: 999,
        background: '#fff', boxShadow: '0 1px 3px rgba(0,0,0,0.3)', transition: 'left .2s',
      }}/>
    </span>
  );
}

// ── grouped-list primitives (match settings-app.jsx) ──────────
const SectionLabel = ({ children }) =>
  <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.6, color: T.muted, textTransform: 'uppercase', padding: '0 6px 8px' }}>{children}</div>;
const groupBox = { background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' };
const sep = (last) => ({ borderBottom: last ? 'none' : `1px solid ${T.border}` });

// condensed representation of the Default permission mode group (context above Security)
function PermissionGroup() {
  const rows = [
    { label:"I’m watching · ask each step", tech:'default', color:T.sec, on:true },
    { label:'Auto-edit files, ask before commands', tech:'acceptEdits', color:T.success },
    { label:"Plan first, I’ll approve", tech:'plan', color:'#5B9BD5' },
    { label:'Full auto · trust it', tech:'bypassPermissions', color:T.warning, warn:true },
  ];
  return (
    <div style={{ marginBottom: 26 }}>
      <SectionLabel>Default permission mode</SectionLabel>
      <div style={groupBox}>
        {rows.map((m, i) => (
          <div key={m.tech} style={{ display:'flex', alignItems:'center', gap:12, padding:'11px 14px', background: m.warn ? 'rgba(224,169,59,0.06)' : 'transparent', ...sep(i === rows.length - 1) }}>
            <span style={{ width:22, height:22, borderRadius:999, border:`2px solid ${m.on ? T.accent : T.border}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
              {m.on && <span style={{ width:10, height:10, borderRadius:999, background:T.accent }}/>}
            </span>
            <div style={{ flex:1, minWidth:0 }}>
              <div style={{ display:'flex', alignItems:'center', gap:7 }}>
                <span style={{ width:7, height:7, borderRadius:999, background:m.color, flexShrink:0 }}/>
                <span style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text }}>{m.label}</span>
                {m.warn && <Warn/>}
              </div>
              <div style={{ fontFamily:T.mono, fontSize:11.5, color:m.color, marginTop:3 }}>{m.tech}</div>
            </div>
          </div>
        ))}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted, padding:'8px 6px 0' }}>Applies to new sessions · can be overridden per session</div>
    </div>
  );
}

// ── Security group ────────────────────────────────────────────
function SecurityGroup({ on = true, verifying = false }) {
  const showSub = on && !verifying;
  return (
    <div style={{ marginBottom: 26 }}>
      <SectionLabel>Security</SectionLabel>
      <div style={groupBox}>
        {/* Require Face ID */}
        <div style={{ display:'flex', alignItems:'center', gap:13, padding:'13px 14px', ...sep(!showSub) }}>
          <span style={{ width:26, display:'flex', justifyContent:'center', flexShrink:0 }}>
            <FaceID c={T.accent} s={23}/>
          </span>
          <div style={{ flex:1, minWidth:0, paddingRight:6 }}>
            <div style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Require Face ID</div>
            <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'17px', color:T.sec, marginTop:2 }}>Unlock with Face ID when you open cc-pocket</div>
          </div>
          <Switch on={on} verifying={verifying}/>
        </div>
        {/* Auto-lock sub-row — only when settled ON */}
        {showSub && (
          <div style={{ display:'flex', alignItems:'center', gap:13, padding:'0 14px', minHeight:50 }}>
            <span style={{ width:26, flexShrink:0 }}/>
            <span style={{ fontFamily:T.ui, fontSize:15, color:T.text, flex:1 }}>Auto-lock</span>
            <span style={{ fontFamily:T.ui, fontSize:14.5, color:T.sec }}>Immediately</span>
            <Chevron c={T.muted} s={15}/>
          </div>
        )}
      </div>
      {verifying && (
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'9px 6px 0' }}>
          <FaceID c={T.muted} s={14} className="cc-verify"/>
          <span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Verifying with Face ID to turn on…</span>
        </div>
      )}
    </div>
  );
}

// ── Settings screen slice ─────────────────────────────────────
function SettingsSlice({ verifying = false }) {
  const scrollRef = React.useRef(null);
  const secRef = React.useRef(null);
  React.useLayoutEffect(() => {
    // park the scroll so the tail of permission-mode + full Security group read together
    if (scrollRef.current && secRef.current) {
      scrollRef.current.scrollTop = Math.max(0, secRef.current.offsetTop - 118);
    }
  }, []);
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:52 }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 4px' }}>
          <button style={{ all:'unset', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }} aria-label="Back">
            <svg width="17" height="17" viewBox="0 0 18 18" fill="none"><path d="M11 3L5 9l6 6" stroke={T.sec} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
          </button>
        </div>
      </div>
      <div ref={scrollRef} className="cc-scroll" style={{ flex:1, overflowY:'auto', padding:'6px 16px 28px' }}>
        <div style={{ fontFamily:T.ui, fontSize:28, fontWeight:700, color:T.text, letterSpacing:-0.4, padding:'2px 4px 20px' }}>Settings</div>
        <PermissionGroup/>
        <div ref={secRef}><SecurityGroup on verifying={verifying}/></div>
        {/* next group peeking below, for context */}
        <SectionLabel>Appearance</SectionLabel>
        <div style={{ ...groupBox, height: 56 }}/>
      </div>
    </div>
  );
}

// ── Frame C · Auto-lock options bottom sheet ──────────────────
function OptionsSheet() {
  const opts = [
    { label:'Immediately', on:true },
    { label:'After 1 minute', on:false },
  ];
  return (
    <div style={{ height:'100%', position:'relative', background:T.base, overflow:'hidden' }}>
      {/* dimmed settings backdrop */}
      <div style={{ position:'absolute', inset:0, filter:'saturate(0.9)', pointerEvents:'none', opacity:0.5 }}>
        <div style={{ paddingTop:96, padding:'96px 16px 0' }}>
          <SectionLabel>Security</SectionLabel>
          <div style={groupBox}>
            <div style={{ display:'flex', alignItems:'center', gap:13, padding:'13px 14px', ...sep(false) }}>
              <span style={{ width:26, display:'flex', justifyContent:'center' }}><FaceID c={T.accent} s={23}/></span>
              <div style={{ flex:1 }}><div style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Require Face ID</div></div>
              <Switch on/>
            </div>
            <div style={{ display:'flex', alignItems:'center', gap:13, padding:'0 14px', minHeight:50 }}>
              <span style={{ width:26 }}/>
              <span style={{ fontFamily:T.ui, fontSize:15, color:T.text, flex:1 }}>Auto-lock</span>
              <span style={{ fontFamily:T.ui, fontSize:14.5, color:T.sec }}>Immediately</span>
              <Chevron/>
            </div>
          </div>
        </div>
      </div>
      {/* scrim */}
      <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.5)' }}/>
      {/* sheet */}
      <div style={{
        position:'absolute', left:0, right:0, bottom:0,
        background:T.surface, borderTop:`1px solid ${T.border}`,
        borderTopLeftRadius:22, borderTopRightRadius:22,
        padding:'10px 0 34px',
        boxShadow:'0 -20px 40px rgba(0,0,0,0.45)',
      }}>
        <div style={{ width:38, height:5, borderRadius:999, background:T.border, margin:'0 auto 10px' }}/>
        <div style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, letterSpacing:0.5, textTransform:'uppercase', color:T.muted, padding:'4px 20px 8px' }}>Auto-lock</div>
        <div>
          {opts.map((o, i) => (
            <div key={o.label} style={{ display:'flex', alignItems:'center', minHeight:56, padding:'0 20px', ...sep(i === opts.length - 1) }}>
              <span style={{ flex:1, fontFamily:T.ui, fontSize:16, fontWeight: o.on ? 600 : 500, color: o.on ? T.text : T.sec }}>{o.label}</span>
              {o.on && <Check c={T.accent} s={19}/>}
            </div>
          ))}
        </div>
        <div style={{ fontFamily:T.ui, fontSize:12, lineHeight:'17px', color:T.muted, padding:'12px 20px 0' }}>How soon cc-pocket re-locks after you leave it.</div>
      </div>
    </div>
  );
}

// ── shared lockup (same as the App Lock gate) ─────────────────
function Lockup({ wordmarkColor = T.text }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', alignItems:'center' }}>
      <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:14 }}>
        <AppMark c={T.sec} s={26}/>
        <div style={{ fontFamily:T.mono, fontSize:21, fontWeight:500, letterSpacing:-0.5, color:wordmarkColor }}>cc-pocket</div>
      </div>
      <div style={{ marginTop:40 }}><FaceID c={T.accent} s={74} w={2.4}/></div>
    </div>
  );
}

// ── Frame D · app-switcher opaque cover ───────────────────────
function SwitcherThumb({ appName, mark, dim, children, main }) {
  return (
    <div style={{ flexShrink:0, display:'flex', flexDirection:'column', alignItems:'flex-start', gap:9 }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, paddingLeft:4 }}>
        <span style={{ width:16, height:16, borderRadius:4, background:mark, flexShrink:0 }}/>
        <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:500, color: main ? T.text : T.muted }}>{appName}</span>
      </div>
      <div style={{
        width: main ? 210 : 150, height: main ? 456 : 456, borderRadius:26, overflow:'hidden',
        border:`1px solid ${main ? T.border : '#202226'}`,
        boxShadow: main ? '0 24px 60px rgba(0,0,0,0.5)' : '0 12px 30px rgba(0,0,0,0.4)',
        opacity: dim ? 0.55 : 1,
      }}>{children}</div>
    </div>
  );
}

// a generic "other app" thumbnail that DOES leak content — contrast to cc-pocket's door
function LeakyApp({ tint }) {
  return (
    <div style={{ width:'100%', height:'100%', background:T.surface, padding:'34px 14px 0' }}>
      <div style={{ height:11, width:'55%', borderRadius:3, background:'#2C3036', marginBottom:16 }}/>
      {[...Array(6)].map((_, i) => (
        <div key={i} style={{ display:'flex', gap:9, alignItems:'center', marginBottom:14 }}>
          <span style={{ width:26, height:26, borderRadius:8, background:i === 1 ? tint : '#24272C', flexShrink:0 }}/>
          <span style={{ flex:1 }}>
            <span style={{ display:'block', height:8, width: `${70 - i * 6}%`, borderRadius:3, background:'#2C3036', marginBottom:6 }}/>
            <span style={{ display:'block', height:7, width: `${45 + (i % 3) * 12}%`, borderRadius:3, background:'#202329' }}/>
          </span>
        </div>
      ))}
    </div>
  );
}

function SwitcherScreen() {
  return (
    <div style={{ height:'100%', background:'#050506', display:'flex', flexDirection:'column' }}>
      <div style={{ height:96, flexShrink:0 }}/>
      <div className="cc-scroll" style={{ flex:1, display:'flex', alignItems:'center', gap:20, padding:'0 26px', overflowX:'hidden' }}>
        {/* neighbor peeking left */}
        <div style={{ marginLeft:-60 }}>
          <SwitcherThumb appName="Notes" mark="#D8B24A" dim><LeakyApp tint="#D8B24A"/></SwitcherThumb>
        </div>
        {/* cc-pocket — OPAQUE branded door, no session content */}
        <SwitcherThumb appName="cc-pocket" mark={T.accent} main>
          <div style={{ width:'100%', height:'100%', background:T.base, display:'flex', alignItems:'center', justifyContent:'center' }}>
            <Lockup/>
          </div>
        </SwitcherThumb>
        {/* neighbor peeking right */}
        <div style={{ marginRight:-60 }}>
          <SwitcherThumb appName="Files" mark="#4F9BD5" dim><LeakyApp tint="#4F9BD5"/></SwitcherThumb>
        </div>
      </div>
      {/* home indicator handled by frame */}
      <div style={{ height:60, flexShrink:0 }}/>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Phone({ children, scale = 0.82 }) {
  return (
    <div style={{ width:402 * scale, height:874 * scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}
function Cell({ n, label, note, children }) {
  return (
    <div style={{ width:402 * 0.82 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:11 }}>
        <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{n}</span>
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:12, maxWidth:402 * 0.82 }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1320, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · settings</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, color:T.text, margin:'0 0 10px' }}>App Lock &amp; privacy cover</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:720, margin:0 }}>
        A Settings toggle turns the biometric App Lock on. Because sessions can drive a real computer, the app also swaps its content for an opaque branded door whenever it backgrounds — so the OS app switcher never leaks chat or session content.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:40, marginTop:44 }}>
        <Cell n="A" label="Settings · Security" note="The Security group sits directly under Default permission mode, keeping all access controls contiguous near the top. Require Face ID is ON; a revealed Auto-lock sub-row discloses the timing options.">
          <SettingsSlice/>
        </Cell>
        <Cell n="B" label="Enabling · verifying" note="Flipping the switch on presents the OS Face ID sheet to verify once before it takes effect. The switch parks mid-travel in a dimmed pending state — it only settles ON after a successful check; a cancel snaps it back OFF.">
          <SettingsSlice verifying/>
        </Cell>
        <Cell n="C" label="Auto-lock options" note="A compact bottom sheet: Immediately (the checked default) or After 1 minute — how soon cc-pocket re-locks after you leave it.">
          <OptionsSheet/>
        </Cell>
        <Cell n="D" label="App-switcher cover" note="The privacy mask as it appears in the OS app switcher: a fully opaque door — theme base plus the same cc-pocket lockup as the unlock gate — never a blur. Neighboring apps still show their content; cc-pocket shows only the brand.">
          <SwitcherScreen/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
