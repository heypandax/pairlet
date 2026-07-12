// cc-pocket — Model picker bottom sheet (mirrors the permission-mode sheet)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

const Check = ({ c=T.accent, s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;

// context-window pill: filled terracotta for large windows, muted otherwise
function CtxPill({ ctx, big }) {
  return (
    <span style={{
      fontFamily:T.mono, fontSize:10.5, fontWeight:600, padding:'2px 8px', borderRadius:999,
      background: big ? T.accent : 'transparent',
      color: big ? '#0E0F11' : T.muted,
      border: big ? 'none' : `1px solid ${T.border}`,
    }}>{ctx}</span>
  );
}

function Spinner({ s=17, c=T.accent }) {
  return <span className="mp-spin" style={{ display:'inline-block', width:s, height:s, border:`2px solid ${T.border}`, borderTopColor:c, borderRadius:999 }}/>;
}

// ── model row ─────────────────────────────────────────────────
function ModelRow({ name, id, ctx, big, selected, switching, unavailable, dimmed }) {
  return (
    <div className={unavailable||dimmed?'':'mp-press'} style={{
      display:'flex', alignItems:'center', gap:12, padding:'13px 14px', borderRadius:12,
      background: selected ? T.raised : 'transparent',
      border:`1px solid ${selected ? T.border : 'transparent'}`,
      cursor: unavailable ? 'default' : 'pointer',
      opacity: (dimmed||unavailable) ? 0.45 : 1,
      marginBottom:6,
    }}>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>{name}</span>
          {unavailable && <span style={{ fontFamily:T.ui, fontSize:10.5, color:T.muted, border:`1px solid ${T.border}`, borderRadius:999, padding:'1px 8px' }}>not installed</span>}
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:6 }}>
          <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{id}</span>
          <CtxPill ctx={ctx} big={big}/>
        </div>
      </div>
      <span style={{ width:20, display:'flex', justifyContent:'center', flexShrink:0 }}>
        {switching ? <Spinner/> : selected ? <Check/> : null}
      </span>
    </div>
  );
}

// ── the sheet ─────────────────────────────────────────────────
const MODELS = [
  { name:'Opus 4.8', id:'claude-opus-4-8', ctx:'1M', big:true },
  { name:'Sonnet 4.8', id:'claude-sonnet-4-8', ctx:'1M', big:true },
  { name:'Haiku 4', id:'claude-haiku-4', ctx:'200K' },
  { name:'Opus 4.6', id:'claude-opus-4-6', ctx:'200K', unavailable:true },
];

function ModelSheet({ state='default' }) {
  // selected = Sonnet (index 1) by default; in switching, user tapped Opus (index 0)
  const selectedIdx = 1;
  const switchingIdx = 0;
  return (
    <div style={{ position:'absolute', inset:0, display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
      <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.55)' }}/>
      <div style={{ position:'relative', background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20,
        borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`,
        paddingBottom:34 }}>
        <div style={{ display:'flex', justifyContent:'center', padding:'9px 0 3px' }}>
          <div style={{ width:38, height:5, borderRadius:999, background:T.border }}/>
        </div>
        <div style={{ padding:'8px 16px 0' }}>
          <div style={{ fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>Model</div>
        </div>
        <div style={{ padding:'14px 12px 6px' }}>
          {MODELS.map((m,i)=>{
            const isSwitching = state==='switching';
            return (
              <ModelRow key={i} {...m}
                selected={state==='switching' ? i===switchingIdx : i===selectedIdx}
                switching={isSwitching && i===switchingIdx}
                dimmed={isSwitching && i!==switchingIdx}
              />
            );
          })}
        </div>
        <div style={{ padding:'6px 18px 0' }}>
          <div style={{ borderTop:`1px solid ${T.border}`, paddingTop:12, fontFamily:T.ui, fontSize:12.5, color:T.muted }}>
            {state==='switching'
              ? <span style={{ display:'flex', alignItems:'center', gap:8 }}><Spinner s={13} c={T.accent}/><span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec }}>Restarting session… history is kept.</span></span>
              : 'Switching restarts the session — your history is kept.'}
          </div>
        </div>
      </div>
    </div>
  );
}

// dim chat backdrop
function ChatBackdrop() {
  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <div style={{ paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 14px', height:44 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1 }}>Fix relay reconnect</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}>sonnet-4.8</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 14px 9px' }}>
          <span style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>~/code/cc-pocket · ⑂ main</span>
        </div>
      </div>
      <div style={{ flex:1, padding:14, display:'flex', flexDirection:'column', gap:12, opacity:0.5 }}>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.text }}>The reconnect loop dies after the 3rd retry.</div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, height:40 }}/>
      </div>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Phone({ children, scale=0.9 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark><div style={{ position:'relative', height:'100%' }}><ChatBackdrop/>{children}</div></IOSDevice>
      </div>
    </div>
  );
}
function Cell({ label, note, children }) {
  return (
    <div style={{ width:402*0.9 }}>
      <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text, marginBottom:10 }}>{label}</div>
      <Phone>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:402*0.9 }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Model picker</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:0 }}>
        A bottom sheet opened from the Chat top bar, mirroring the permission-mode sheet: a radio list of models, each with a display name, a monospace id, and a context-window pill (filled terracotta for 1M, muted for smaller). Switching restarts the session but keeps history.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:36, marginTop:40 }}>
        <Cell label="Default" note="Sonnet 4.8 selected (terracotta check + faint raised row). Opus & Sonnet carry a filled-terracotta 1M pill; Haiku a muted 200K. Opus 4.6 is dimmed with a “not installed” tag.">
          <ModelSheet state="default"/>
        </Cell>
        <Cell label="Switching" note="Tapping Opus 4.8 swaps its check for a spinner while the daemon relaunches; the other rows dim and disable, and the helper caption becomes “Restarting session… history is kept.”">
          <ModelSheet state="switching"/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
