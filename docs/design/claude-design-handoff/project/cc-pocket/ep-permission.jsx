// cc-pocket — execution permissions · upgraded permission sheet (3-way + remember)

// ── countdown ring ────────────────────────────────────────────
function CountdownRing({ seconds, total }) {
  const sz=50, r=21, C=2*Math.PI*r;
  const frac=Math.max(0, seconds/total);
  const low=seconds<=5;
  const col=low?T.danger:T.accent;
  const mm=Math.floor(seconds/60), ss=String(seconds%60).padStart(2,'0');
  return (
    <div style={{ position:'relative', width:sz, height:sz, flexShrink:0 }}>
      <svg width={sz} height={sz} viewBox={`0 0 ${sz} ${sz}`} style={{ transform:'rotate(-90deg)' }}>
        <circle cx={sz/2} cy={sz/2} r={r} stroke={T.border} strokeWidth="3" fill="none"/>
        <circle cx={sz/2} cy={sz/2} r={r} stroke={col} strokeWidth="3" fill="none" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C*(1-frac)}/>
      </svg>
      <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center', fontFamily:T.mono, fontSize:12, fontWeight:500, color:col }}>{mm}:{ss}</div>
    </div>
  );
}

// ── the shared anatomy (shield, tool name, command, target) ───
function PermBody({ req, dim=false }) {
  return (
    <div style={{ opacity: dim?0.5:1 }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:10 }}>
        <Shield c={req.danger?T.danger:T.warning} s={16}/>
        <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:500, color:T.sec }}>Claude needs permission</span>
      </div>
      <div style={{ fontFamily:T.ui, fontSize:21, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>
        {req.title}&nbsp;<span style={{ color:T.muted, fontWeight:600 }}>·</span>&nbsp;<span style={{ fontFamily:T.mono, fontSize:17, fontWeight:600 }}>{req.tool}</span>
      </div>
      <div style={{ marginTop:13, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, padding:'13px 14px' }}>
        <pre style={{ margin:0, fontFamily:T.mono, fontSize:13, lineHeight:'20px', color:T.text, whiteSpace:'pre-wrap', wordBreak:'break-word' }}>{req.cmd}</pre>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:12 }}>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, whiteSpace:'nowrap' }}>{req.dir}</span>
        <span style={{ color:T.border }}>·</span>
        <span style={{ display:'flex', alignItems:'center', gap:4, flexShrink:0 }}><Branch c={T.muted} s={11}/><span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec }}>{req.branch}</span></span>
      </div>
    </div>
  );
}

// ── 3-way decision row ────────────────────────────────────────
function Decision({ req, onDeny, onOnce, onAlways }) {
  const danger = req.danger;
  // scope explainer line — makes "Always allow" unmistakable
  return (
    <div style={{ marginTop:16 }}>
      <div style={{ display:'flex', alignItems:'flex-start', gap:7, marginBottom:11, minHeight:32 }}>
        {danger ? <Warn c={T.warning} s={14}/> : <span style={{ width:14, height:14, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center' }}><Check c={T.muted} s={12}/></span>}
        <span style={{ fontFamily:T.ui, fontSize:12, lineHeight:'16px', color: danger?T.warning:T.sec }}>
          {danger
            ? <>“Always allow” would let Claude {req.dangerNote} all session — not recommended.</>
            : <>“Always allow” remembers only <span style={{ fontFamily:T.mono, color:T.text }}>{req.rule}</span> for this session.</>}
        </span>
      </div>
      <div style={{ display:'flex', gap:8 }}>
        {/* Deny */}
        <button onClick={onDeny} className="ep-press" style={{ ...btn, flex:1, border:`1.5px solid ${T.danger}`, color:T.danger }}>
          <span style={{ fontSize:15, fontWeight:600 }}>Deny</span>
        </button>
        {/* Allow once — becomes primary (terracotta) when the tool is dangerous */}
        <button onClick={onOnce} className="ep-press" style={{ ...btn, flex:1,
          background: danger?T.accent:T.surface, border: danger?'none':`1px solid ${T.border}`,
          color: danger?'#0E0F11':T.text }}>
          <span style={{ fontSize:15, fontWeight: danger?700:600 }}>Allow once</span>
        </button>
        {/* Always allow — terracotta primary by default; amber-cautioned outline when dangerous */}
        <button onClick={onAlways} className="ep-press" style={{ ...btn, flex:1.25, flexDirection:'column', gap:2,
          background: danger?'transparent':T.accent,
          border: danger?`1.5px solid ${rgba(T.warning,0.6)}`:'none',
          color: danger?T.warning:'#0E0F11' }}>
          <span style={{ fontSize:14, fontWeight:700, display:'flex', alignItems:'center', gap:4 }}>
            {danger && <Warn c={T.warning} s={12}/>}Always allow
          </span>
          <span style={{ fontFamily:T.mono, fontSize:10, fontWeight:500, color: danger?rgba(T.warning,0.85):'rgba(14,15,17,0.72)', maxWidth:'100%', overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>{req.rule}</span>
        </button>
      </div>
    </div>
  );
}
const btn = { all:'unset', boxSizing:'border-box', cursor:'pointer', minHeight:54, borderRadius:12,
  display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', fontFamily:T.ui, textAlign:'center', padding:'6px 4px' };

// ── full permission sheet — variant drives the state ──────────
function PermissionSheet({ req, variant='default', seconds=23, onDeny, onOnce, onAlways, onDismiss }) {
  // variant: 'default' | 'timeout' | 'cancelled'
  const withdrawn = variant==='cancelled';
  const timedout = variant==='timeout';
  return (
    <Sheet>
      <div style={{ padding:'4px 18px 18px' }}>
        <div style={{ display:'flex', alignItems:'flex-start', gap:12 }}>
          <div style={{ flex:1, minWidth:0 }}>
            <PermBody req={req} dim={withdrawn||timedout}/>
          </div>
          {!withdrawn && (
            <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:3, paddingTop:2 }}>
              <CountdownRing seconds={timedout?0:seconds} total={req.total||30}/>
              <span style={{ fontFamily:T.ui, fontSize:10, color:T.muted }}>auto-deny</span>
            </div>
          )}
        </div>

        {variant==='default' && <Decision req={req} onDeny={onDeny} onOnce={onOnce} onAlways={onAlways}/>}

        {timedout && (
          <div style={{ marginTop:16, display:'flex', alignItems:'center', gap:10, background:rgba(T.danger,0.08), border:`1px solid ${rgba(T.danger,0.4)}`, borderRadius:12, padding:'12px 14px' }}>
            <span style={{ width:8, height:8, borderRadius:999, background:T.danger, flexShrink:0 }}/>
            <div style={{ flex:1 }}>
              <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Auto-denied</div>
              <div style={{ fontFamily:T.ui, fontSize:12, color:T.sec, marginTop:1 }}>No response within the time limit.</div>
            </div>
            <button onClick={onDismiss} className="ep-press" style={{ all:'unset', cursor:'pointer', fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.sec, padding:'8px 6px' }}>Dismiss</button>
          </div>
        )}

        {withdrawn && (
          <div style={{ marginTop:16, display:'flex', alignItems:'center', gap:10, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'12px 14px' }}>
            <Spinner c={T.muted} s={15}/>
            <div style={{ flex:1 }}>
              <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Request withdrawn</div>
              <div style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted, marginTop:1 }}>cancelled on Lidapeng-MacBook</div>
            </div>
            <button onClick={onDismiss} className="ep-press" style={{ all:'unset', cursor:'pointer', fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.sec, padding:'8px 6px' }}>Dismiss</button>
          </div>
        )}
      </div>
    </Sheet>
  );
}

// ── the confirmation chip that drops into the stream ──────────
function AllowChip({ rule }) {
  return (
    <div style={{ display:'inline-flex', alignItems:'center', gap:8, alignSelf:'flex-start',
      background:T.surface, border:`1px solid ${T.border}`, borderRadius:999, padding:'7px 13px 7px 11px' }}>
      <span style={{ width:16, height:16, borderRadius:999, background:rgba(T.success,0.16), display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
        <Check c={T.success} s={11} w={2}/>
      </span>
      <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Always allowing&nbsp;
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}>{rule}</span>&nbsp;this session</span>
    </div>
  );
}

Object.assign(window, { CountdownRing, PermBody, Decision, PermissionSheet, AllowChip });
