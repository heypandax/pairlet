// cc-pocket — FLEET · mobile (triage device): Fleet home · Attention inbox · switcher · chat banner

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', teal:'#3FB5AC', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── OS glyphs (monochrome — machines get NO accent color) ─────
const OS = {
  mac:(c=T.sec,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M12.3 9.5c0-1.8 1.5-2.7 1.6-2.7-.9-1.3-2.2-1.4-2.7-1.5-1.1-.1-2.2.7-2.8.7-.6 0-1.5-.7-2.4-.6-1.2 0-2.4.7-3 1.8-1.3 2.2-.3 5.5.9 7.3.6.9 1.3 1.9 2.2 1.8.9 0 1.2-.6 2.3-.6 1.1 0 1.3.6 2.3.6 1 0 1.6-.9 2.2-1.8.7-1 .9-2 .9-2.1 0 0-1.7-.7-1.7-2.6zM10.8 4.3c.5-.6.8-1.4.7-2.3-.7 0-1.5.5-2 1.1-.4.5-.8 1.3-.7 2.1.8.1 1.5-.4 2-.9z"/></svg>,
  linux:(c=T.sec,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M9 2.5c-1.1 0-1.6 1.1-1.6 2.6 0 1.1-.6 2-1.4 3.2-.7 1.1-1.4 2.2-1.4 3.3 0 .9.7 1.4 1.8 1.7.9.2 1.3.9 2.6.9s1.7-.7 2.6-.9c1.1-.3 1.8-.8 1.8-1.7 0-1.1-.7-2.2-1.4-3.3-.8-1.2-1.4-2.1-1.4-3.2 0-1.5-.5-2.6-1.6-2.6z"/><path d="M7.6 6.4v.01M10.4 6.4v.01"/></svg>,
  win:(c=T.sec,s=13)=> <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M2.5 4.2l5.4-.75v5.05H2.5zM8.6 3.35L15.5 2.4v6.15H8.6zM2.5 9.2h5.4v5.05L2.5 13.5zM8.6 9.2h6.9v6.4l-6.9-.95z"/></svg>,
};
const Chevron = ({ d='left', c=T.sec, s=17, w=2 }) => {
  const p={left:'M11 3L5 9l6 6',right:'M6 3l6 6-6 6',down:'M3 6l6 6 6-6'};
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Shield = ({ c=T.accent, s=16 }) => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2.5l8 3v6c0 5.2-3.8 8.6-8 10-4.2-1.4-8-4.8-8-10v-6l8-3z"/></svg>;
const CheckM = ({ c=T.accent, s=16 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const XG = ({ c=T.danger, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke={c} strokeWidth="1.8" strokeLinecap="round"/></svg>;
const CheckCircle = ({ c=T.success, s=44 }) => <svg width={s} height={s} viewBox="0 0 44 44" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="22" cy="22" r="17" opacity="0.5"/><path d="M14.5 22.5l5 5 10-11"/></svg>;
const Plus = ({ c=T.sec, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>;
const SendArrow = ({ c=T.muted, s=17 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;

// ── fleet language ────────────────────────────────────────────
const STATUS = { online:T.success, reconnecting:T.warning, offline:T.muted };

function MachineChip({ os, name, status='online', mono=12, glyph=14 }) {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:6, minWidth:0 }}>
      <span style={{ display:'flex', flexShrink:0 }}>{OS[os](T.sec, glyph)}</span>
      <span style={{ fontFamily:T.mono, fontSize:mono, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
      <span className={status==='online'?'fl-pulse':''} style={{ width:6, height:6, borderRadius:999, background:STATUS[status], flexShrink:0,
        boxShadow: status==='online'?`0 0 6px ${T.success}88`:'none' }}/>
    </span>
  );
}
function Badge({ n }) {
  return <span style={{ minWidth:18, height:18, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:11, fontWeight:700, display:'inline-flex', alignItems:'center', justifyContent:'center', padding:'0 5px', flexShrink:0 }}>{n}</span>;
}
function FleetStrip({ text='4 machines · 3 online · 2 waiting approval' }) {
  return <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>{text}</span>;
}
function Ring({ t='0:23', amber, s=34 }) {
  const col = amber ? T.warning : T.accent;
  const r=(s-6)/2, C=2*Math.PI*r;
  return (
    <span style={{ position:'relative', width:s, height:s, flexShrink:0, display:'inline-flex', alignItems:'center', justifyContent:'center' }}>
      <svg width={s} height={s} viewBox={`0 0 ${s} ${s}`} style={{ position:'absolute', transform:'rotate(-90deg)' }}>
        <circle cx={s/2} cy={s/2} r={r} stroke={T.border} strokeWidth="2.4" fill="none"/>
        <circle cx={s/2} cy={s/2} r={r} stroke={col} strokeWidth="2.4" fill="none" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C*0.3}/>
      </svg>
      <span style={{ fontFamily:T.mono, fontSize:8.5, color:col }}>{t}</span>
    </span>
  );
}

// ════════════════ ② FLEET HOME ════════════════
const MACHINES = [
  { os:'mac', name:'Lidapeng-MacBook', status:'online', act:'▶ 2 running · ~/proj/app/cc-pocket', last:'active now' },
  { os:'mac', name:'mac-studio', status:'online', act:'⏸ 1 waiting approval · Bash: ./gradlew clean', badge:1, last:'2m ago' },
  { os:'linux', name:'devbox-linux', status:'online', act:'▶ pytest -x · running 12m', badge:1, last:'just now' },
  { os:'win', name:'win-desktop', status:'offline', act:'offline · 2d ago', dim:true },
];

function MachineCard({ m, reconnecting }) {
  const status = reconnecting ? 'reconnecting' : m.status;
  return (
    <div className="fl-press" style={{ position:'relative', background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'13px 14px', cursor:'pointer', opacity:m.dim?0.5:1, overflow:'hidden' }}>
      {reconnecting && <span className="fl-glow" style={{ position:'absolute', left:0, top:0, right:0, height:2, background:T.warning }}/>}
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <MachineChip os={m.os} name={m.name} status={status} mono={13} glyph={15}/>
        <span style={{ flex:1 }}/>
        {m.badge && !m.dim && <Badge n={m.badge}/>}
        <Chevron d="right" c={T.muted} s={14} w={1.8}/>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11, color: reconnecting?T.warning:T.sec, marginTop:9, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>
        {reconnecting ? 'reconnecting…' : m.act}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, marginTop:5 }}>{m.last}</div>
    </div>
  );
}

function FleetHome({ reconnectIdx=-1 }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:56, padding:'56px 16px 12px', borderBottom:`1px solid ${T.border}` }}>
        <div style={{ fontFamily:T.ui, fontSize:21, fontWeight:700, color:T.text, letterSpacing:-0.3 }}>Your computers</div>
        <div style={{ marginTop:5 }}><FleetStrip/></div>
      </div>
      <div className="fl-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'14px 16px 24px' }}>
        {/* attention banner — the one eye magnet */}
        <div className="fl-press" style={{ display:'flex', alignItems:'center', gap:10, background:'rgba(217,119,87,0.10)', border:`1px solid rgba(217,119,87,0.45)`, borderRadius:12, padding:'12px 14px', cursor:'pointer', marginBottom:14 }}>
          <Shield c={T.accent} s={17}/>
          <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.accent, flex:1 }}>2 approvals waiting</span>
          <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:700, color:'#0E0F11', background:T.accent, borderRadius:999, padding:'6px 14px' }}>Review</span>
        </div>
        <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
          {MACHINES.map((m,i)=><MachineCard key={i} m={m} reconnecting={i===reconnectIdx}/>)}
        </div>
        <div className="fl-press" style={{ display:'flex', alignItems:'center', gap:8, padding:'14px 4px', marginTop:6, cursor:'pointer', borderRadius:10 }}>
          <Plus c={T.sec} s={14}/>
          <span style={{ fontFamily:T.ui, fontSize:13, color:T.sec }}>Pair a new computer</span>
        </div>
      </div>
    </div>
  );
}

// ════════════════ ③ ATTENTION INBOX ════════════════
const PENDING = [
  { os:'mac', name:'mac-studio', tool:'Run command · Bash', preview:'rm -rf ./build && ./gradlew clean', t:'0:23', amber:true },
  { os:'linux', name:'devbox-linux', tool:'Edit file · Write', preview:'~/src/relay/src/main/kotlin/Relay.kt  +42 −7', t:'0:41' },
];
const STORM = [
  ...PENDING,
  { os:'mac', name:'Lidapeng-MacBook', tool:'Run command · Bash', preview:'git push --force-with-lease origin main', t:'1:12' },
];
const FINISHED = [
  { os:'mac', name:'Lidapeng-MacBook', title:'Refactor auth module', ok:true, time:'4m ago' },
  { os:'linux', name:'devbox-linux', title:'Fix stream parser test', ok:false, time:'12m ago' },
];

function ApprovalCard({ p }) {
  return (
    <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'12px 13px', marginBottom:10 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:10 }}>
        <MachineChip os={p.os} name={p.name} mono={11.5} glyph={13}/>
        <span style={{ flex:1 }}/>
        <Ring t={p.t} amber={p.amber}/>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:8 }}>
        <Shield c={T.accent} s={15}/>
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{p.tool}</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11.5, color:T.text, background:T.base, border:`1px solid ${T.border}`, borderRadius:8, padding:'8px 10px', marginBottom:11, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{p.preview}</div>
      <div style={{ display:'flex', gap:9 }}>
        <button className="fl-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', flex:1, height:44, display:'flex', alignItems:'center', justifyContent:'center', borderRadius:10, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:14, fontWeight:600 }}>Deny</button>
        <button className="fl-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', flex:1.25, height:44, display:'flex', alignItems:'center', justifyContent:'center', borderRadius:10, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:14, fontWeight:700 }}>Allow</button>
      </div>
    </div>
  );
}

function SLabel({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', margin:'16px 2px 10px' }}>{children}</div>;
}

function AttentionInbox({ rows=PENDING, empty, strip }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 8px' }}>
          <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron/></button>
          <span style={{ fontFamily:T.ui, fontSize:17, fontWeight:600, color:T.text }}>Needs you</span>
        </div>
        <div style={{ padding:'0 16px 10px' }}><FleetStrip text={strip || (empty?'4 machines · 3 online · 0 waiting':'4 machines · 3 online · '+rows.length+' waiting approval')}/></div>
      </div>
      {empty ? (
        <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:16, padding:'0 44px 60px' }}>
          <CheckCircle c={T.success} s={46}/>
          <div style={{ fontFamily:T.ui, fontSize:16, fontWeight:600, color:T.sec }}>All clear — nothing needs you</div>
          <div style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.muted, textAlign:'center' }}>Approvals from any machine will queue here the moment they arrive.</div>
        </div>
      ) : (
        <div className="fl-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'2px 16px 24px' }}>
          <SLabel>Needs approval</SLabel>
          {rows.map((p,i)=><ApprovalCard key={i} p={p}/>)}
          <SLabel>Recently finished</SLabel>
          {FINISHED.map((f,i)=>(
            <div key={i} className="fl-press" style={{ display:'flex', alignItems:'center', gap:9, padding:'11px 4px', borderBottom:`1px solid ${T.border}`, cursor:'pointer' }}>
              <MachineChip os={f.os} name={f.name} mono={11} glyph={12}/>
              <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{f.title}</span>
              {f.ok ? <CheckM c={T.success} s={13}/> : <XG c={T.danger} s={12}/>}
              <span style={{ fontFamily:T.mono, fontSize:10, color:T.muted, flexShrink:0 }}>{f.time}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ════════════════ ④ MACHINE SWITCHER SHEET ════════════════
function ChatGhost() {
  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <div style={{ paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 14px', height:44 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1 }}>Refactor auth module</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 14px 9px' }}>
          <MachineChip os="mac" name="Lidapeng-MacBook" mono={10.5} glyph={12}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>· ~/proj/app/cc-pocket</span>
        </div>
      </div>
      <div style={{ flex:1, padding:14, opacity:0.45, display:'flex', flexDirection:'column', gap:12 }}>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.text }}>add a unit test for the stream parser</div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, height:44 }}/>
      </div>
    </div>
  );
}

const SWITCH_ROWS = [
  { os:'mac', name:'Lidapeng-MacBook', where:'Refactor auth module', current:true },
  { os:'mac', name:'mac-studio', where:'~/work/api-server', badge:1 },
  { os:'linux', name:'devbox-linux', where:'Add relay websocket client' },
  { os:'win', name:'win-desktop', where:'offline · 2d ago', dim:true },
];

function SwitcherSheet() {
  return (
    <div style={{ position:'relative', height:'100%' }}>
      <ChatGhost/>
      <div style={{ position:'absolute', inset:0, display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
        <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.55)' }}/>
        <div style={{ position:'relative', background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20, borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`, paddingBottom:34 }}>
          <div style={{ display:'flex', justifyContent:'center', padding:'9px 0 3px' }}><div style={{ width:38, height:5, borderRadius:999, background:T.border }}/></div>
          <div style={{ padding:'8px 18px 4px', fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text }}>Switch computer</div>
          <div style={{ padding:'8px 12px 0' }}>
            {SWITCH_ROWS.map((r,i)=>(
              <div key={i} className={r.dim?'':'fl-press'} style={{ display:'flex', alignItems:'center', gap:10, minHeight:52, padding:'6px 8px', borderRadius:11, cursor:r.dim?'default':'pointer', opacity:r.dim?0.45:1 }}>
                <div style={{ flex:1, minWidth:0 }}>
                  <MachineChip os={r.os} name={r.name} status={r.dim?'offline':'online'} mono={13} glyph={14}/>
                  <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:4, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{r.where}</div>
                </div>
                {r.badge && <Badge n={r.badge}/>}
                {r.current && <CheckM c={T.accent} s={17}/>}
              </div>
            ))}
          </div>
          <div style={{ padding:'10px 18px 0', borderTop:`1px solid ${T.border}`, marginTop:8 }}>
            <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted }}>Manage computers</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// ════════════════ ⑤ CHAT + CROSS-MACHINE BANNER ════════════════
function ChatWithBanner({ two }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base, position:'relative' }}>
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}`, background:T.base, zIndex:2 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 8px', height:44 }}>
          <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron/></button>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Refactor auth module</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px', flexShrink:0 }}>default</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:6, padding:'0 16px 9px' }}>
          <MachineChip os="mac" name="Lidapeng-MacBook" mono={10.5} glyph={12}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>· ~/proj/app/cc-pocket</span>
        </div>
      </div>

      {/* floating cross-machine banner — overlays, does not push the stream */}
      <div style={{ position:'absolute', left:12, right:12, top:118, zIndex:3 }}>
        <div style={{ display:'flex', alignItems:'center', gap:9, background:'rgba(30,33,37,0.97)', border:`1px solid rgba(217,119,87,0.45)`, borderRadius:8, padding:'9px 11px', boxShadow:'0 10px 28px rgba(0,0,0,0.5)' }}>
          <Shield c={T.accent} s={14}/>
          {two ? (
            <span style={{ fontFamily:T.ui, fontSize:12, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>
              <b style={{ fontWeight:600 }}>2 approvals waiting</b> · <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>mac-studio, devbox-linux</span>
            </span>
          ) : (
            <span style={{ fontFamily:T.ui, fontSize:12, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>
              <span style={{ fontFamily:T.mono, fontSize:11 }}>mac-studio</span> · Bash needs approval
            </span>
          )}
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.warning, flexShrink:0 }}>0:23</span>
          <span className="fl-press" style={{ fontFamily:T.ui, fontSize:12, fontWeight:700, color:'#0E0F11', background:T.accent, borderRadius:999, padding:'5px 12px', cursor:'pointer', flexShrink:0 }}>Review</span>
        </div>
      </div>

      <div className="fl-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'44px 16px 14px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:16 }}>
          <div>
            <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:7 }}>YOU</div>
            <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>add a unit test for the stream parser</div>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
            I’ll feed a chunked SSE response and assert the reassembled events — starting with a happy path and a split-token case.
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'10px 12px' }}>
            <span className="fl-pulse" style={{ width:7, height:7, borderRadius:999, background:T.accent }}/>
            <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>Bash</span>
            <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>gradle :protocol:test</span>
            <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>9s</span>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
            Running the suite to confirm the new test fails first<span className="fl-caret"/>
          </div>
        </div>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, background:T.surface, paddingBottom:30 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 12px' }}>
          <span style={{ width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Plus c={T.sec} s={17}/></span>
          <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, padding:'11px 14px', fontFamily:T.ui, fontSize:14, color:T.muted }}>Message Claude</div>
          <span style={{ width:42, height:42, borderRadius:999, background:T.base, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><SendArrow/></span>
        </div>
      </div>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}
function Cell({ tag, label, note, children }) {
  return (
    <div style={{ width:402*0.82 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
        {tag && <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{tag}</span>}
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11 }}>{note}</div>}
    </div>
  );
}
function Divider({ children, sub }) {
  return (
    <div style={{ margin:'58px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:10, maxWidth:700 }}>{sub}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1300, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · fleet · mobile</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Fleet — the phone as a triage device</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:720, margin:0 }}>
        Four computers online at once. On the phone: one decision per screen, approval-first. Machines stay monochrome — OS glyph + mono hostname + status dot — so terracotta keeps meaning “needs you” and teal stays Codex.
      </p>

      <Divider sub="The Computers screen evolved from a picker into a live overview: FleetStrip up top, a terracotta attention banner as the single eye magnet, then four machine cards with ActivityLines. win-desktop is offline and dimmed.">② Fleet home</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="2a" label="Live fleet" note="Banner → Attention inbox. Cards show ▶ running / ⏸ waiting ActivityLines in mono; badges mark machines holding approvals.">
          <FleetHome/>
        </Cell>
        <Cell tag="2b" label="devbox-linux reconnecting" note="Amber status dot, an amber “reconnecting…” ActivityLine, and a thin pulsing amber top edge on the card.">
          <FleetHome reconnectIdx={2}/>
        </Cell>
      </div>

      <Divider sub="A unified queue for ALL machines, sorted by soonest timeout — glance, decide, move on. Tapping a card (not the buttons) opens the full permission screen; “Recently finished” rows jump into their chats.">③ Attention inbox</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="3a" label="Two pending" note="Each card: MachineChip, shield + tool, mono preview, countdown ring (amber near zero), thumb-sized Deny / Allow.">
          <AttentionInbox/>
        </Cell>
        <Cell tag="3b" label="Approval storm · 3 pending" note="The queue simply grows — still one decision at a time, soonest timeout first.">
          <AttentionInbox rows={STORM} strip="4 machines · 3 online · 3 waiting approval"/>
        </Cell>
        <Cell tag="3c" label="All clear" note="Calm check-circle, “All clear — nothing needs you”, FleetStrip still visible.">
          <AttentionInbox empty/>
        </Cell>
      </div>

      <Divider sub="Opened by tapping the machine name in the chat connection bar. One tap jumps straight to that machine's last context — no intermediate screens.">④ Machine switcher</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="4" label="Switch computer sheet" note="Each row: MachineChip + “where you left off” in mono, AttentionBadge when pending, check on the current machine, offline dimmed.">
          <SwitcherSheet/>
        </Cell>
      </div>

      <Divider sub="The existing Chat with one addition: a slim floating banner under the connection bar pulls you to another machine's approval without leaving this conversation. After deciding, you land back here.">⑤ Chat · cross-machine banner</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="5a" label="One request" note="“mac-studio · Bash needs approval” + countdown + a compact terracotta Review. Floats over the stream; nothing reflows.">
          <ChatWithBanner/>
        </Cell>
        <Cell tag="5b" label="Two queued" note="“2 approvals waiting · mac-studio, devbox-linux” with the soonest countdown.">
          <ChatWithBanner two/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
