// cc-pocket — Connect & system states (3 frames)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ d='left', c=T.sec, s=17, w=2 }) => {
  const p={left:'M11 3L5 9l6 6',right:'M6 3l6 6-6 6',down:'M3 6l6 6 6-6'};
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Eye = ({ c=T.sec, s=15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M1.6 8s2.3-4.2 6.4-4.2S14.4 8 14.4 8s-2.3 4.2-6.4 4.2S1.6 8 1.6 8z"/>
    <circle cx="8" cy="8" r="1.9"/>
  </svg>
);
const Plus = ({ c=T.sec, s=20 }) => (
  <svg width={s} height={s} viewBox="0 0 22 22" fill="none"><path d="M11 4.5v13M4.5 11h13" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>
);
const SendArrow = ({ c='#0E0F11', s=18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const mono = { fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px', color:T.text };

// ════════════════════════════════════════════════════════════
// FRAME 1 — Connect screen (paired, not connected)
// ════════════════════════════════════════════════════════════
const STATUSES = ['disconnected', 'connecting…', 'failed: timeout'];

function ConnectScreen() {
  const [si, setSi] = React.useState(0);
  const [adv, setAdv] = React.useState(false);
  const [url, setUrl] = React.useState('');
  const status = STATUSES[si];
  const connecting = si === 1;

  const connect = () => {
    setSi(1);
    setTimeout(() => setSi(2), 1600);
    setTimeout(() => setSi(0), 3400);
  };

  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, height:60 }}/>
      <div className="cs-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', display:'flex', flexDirection:'column', justifyContent:'center', padding:'0 28px 40px' }}>
        {/* identity */}
        <div style={{ textAlign:'center', marginBottom:36 }}>
          <div style={{ fontFamily:T.ui, fontSize:28, fontWeight:700, color:T.text, letterSpacing:-0.4 }}>CC Pocket</div>
          <div style={{ fontFamily:T.ui, fontSize:14, color:T.sec, marginTop:8 }}>
            Paired&nbsp;·&nbsp;<span style={{ fontFamily:T.mono, fontSize:12.5 }}>a1b2c3d4e5f6…</span>
          </div>
        </div>

        {/* connect */}
        <button onClick={connect} disabled={connecting} className="cs-press" style={{
          all:'unset', boxSizing:'border-box', cursor:connecting?'default':'pointer', width:'100%', height:52,
          display:'flex', alignItems:'center', justifyContent:'center', gap:9,
          background:T.accent, color:'#0E0F11', borderRadius:12,
          fontFamily:T.ui, fontSize:16, fontWeight:700, opacity:connecting?0.75:1,
        }}>
          {connecting && (
            <svg className="cs-spin" width="16" height="16" viewBox="0 0 16 16">
              <circle cx="8" cy="8" r="6" stroke="rgba(14,15,17,0.25)" strokeWidth="2.2" fill="none"/>
              <circle cx="8" cy="8" r="6" stroke="#0E0F11" strokeWidth="2.2" fill="none" strokeLinecap="round" strokeDasharray="11 38"/>
            </svg>
          )}
          Connect
        </button>
        <div style={{ textAlign:'center', marginTop:10, fontFamily:T.mono, fontSize:11.5, color: si===2 ? T.danger : T.muted }}>{status}</div>

        <button className="cs-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', width:'100%', height:44, marginTop:14, display:'flex', alignItems:'center', justifyContent:'center', color:T.muted, fontFamily:T.ui, fontSize:14, fontWeight:500 }}>
          Unpair
        </button>

        {/* advanced */}
        <div style={{ marginTop:34 }}>
          <button onClick={()=>setAdv(a=>!a)} className="cs-press" style={{ all:'unset', cursor:'pointer', display:'flex', alignItems:'center', gap:6, padding:'6px 2px', color:T.muted, fontFamily:T.ui, fontSize:12.5 }}>
            <span style={{ display:'flex', transform:adv?'rotate(180deg)':'none', transition:'transform .2s' }}><Chevron d="down" c={T.muted} s={12} w={1.7}/></span>
            Advanced · direct LAN
          </button>
          {adv && (
            <div style={{ marginTop:10, display:'flex', flexDirection:'column', gap:10 }}>
              <div style={{ display:'flex', alignItems:'center', background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'0 13px', height:44 }}>
                <input value={url} onChange={e=>setUrl(e.target.value)} placeholder="daemon ws url"
                  style={{ all:'unset', flex:1, fontFamily:T.mono, fontSize:12.5, color:T.text, minWidth:0 }}/>
              </div>
              <button className="cs-press" style={{
                all:'unset', boxSizing:'border-box', cursor:'pointer', width:'100%', height:46,
                display:'flex', alignItems:'center', justifyContent:'center',
                border:`1.5px solid ${T.accent}`, color:T.accent, borderRadius:11,
                fontFamily:T.ui, fontSize:14.5, fontWeight:600,
              }}>Connect direct</button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// shared compact Chat frame (for frames 2 & 3)
// ════════════════════════════════════════════════════════════
function ChatHeaderBar() {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:44 }}>
        <button style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{ flex:1, fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Fix relay reconnect</span>
        <span style={{ display:'flex', alignItems:'center', gap:5, marginRight:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:999, padding:'4px 9px' }}>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>default</span>
          <Chevron d="down" c={T.muted} s={10} w={1.7}/>
        </span>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
        <span className="cs-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, whiteSpace:'nowrap' }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
      </div>
    </div>
  );
}

function ChatBody() {
  return (
    <div className="cs-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 14px' }}>
      <div style={{ display:'flex', flexDirection:'column', gap:18 }}>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:8 }}>YOU</div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>run the protocol tests again</div>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
          Running <span style={mono}>gradle :protocol:test</span> — two suites, usually ~40s.
        </div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'11px 12px', display:'flex', alignItems:'center', gap:9 }}>
          <span className="cs-pulse" style={{ width:7, height:7, borderRadius:999, background:T.accent, flexShrink:0 }}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>Bash</span>
          <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>gradle :protocol:test</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>12s</span>
        </div>
      </div>
    </div>
  );
}

// ── Frame 2: reconnect banner over Chat ───────────────────────
function ReconnectFrame() {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, height:52 }}/>{/* safe area */}
      {/* banner strip — pushes content down, overlays nothing */}
      <div style={{ flexShrink:0, background:'rgba(229,96,77,0.14)', padding:'7px 16px', textAlign:'center' }}>
        <span style={{ fontFamily:T.ui, fontSize:12, fontWeight:500, color:T.danger }}>Connection lost — reconnecting…</span>
      </div>
      <ChatHeaderBar/>
      <ChatBody/>
      {/* normal composer, disabled feel while offline */}
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, background:T.surface, paddingBottom:34 }}>
        <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px', opacity:0.55 }}>
          <span style={{ width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Plus c={T.sec} s={20}/></span>
          <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'11px 14px', minHeight:44 }}>
            <span style={{ fontFamily:T.ui, fontSize:14.5, color:T.muted }}>Message Claude</span>
          </div>
          <span style={{ width:44, height:44, borderRadius:999, background:T.base, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><SendArrow c={T.muted} s={18}/></span>
        </div>
      </div>
    </div>
  );
}

// ── Frame 3: observing bar replaces the composer ──────────────
function ObservingFrame() {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, height:52 }}/>
      <ChatHeaderBar/>
      <ChatBody/>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, background:T.surface, paddingBottom:34 }}>
        <div style={{ display:'flex', alignItems:'center', gap:10, padding:'12px 14px', minHeight:60 }}>
          <span style={{ display:'flex', alignItems:'center', gap:8, flex:1, minWidth:0 }}>
            <Eye c={T.sec} s={15}/>
            <span style={{ fontFamily:T.ui, fontSize:13, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Observing · running in a terminal</span>
          </span>
          <button className="cs-press" style={{
            all:'unset', boxSizing:'border-box', cursor:'pointer', flexShrink:0,
            height:40, padding:'0 16px', display:'flex', alignItems:'center', justifyContent:'center',
            background:T.accent, color:'#0E0F11', borderRadius:10, whiteSpace:'nowrap',
            fontFamily:T.ui, fontSize:13.5, fontWeight:700,
          }}>Continue here</button>
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

const FRAMES = [
  { title:'Connect · paired home', node:<ConnectScreen/>, note:(<>Shown when paired but not connected. Tap <span style={{color:'#ECEDEE',fontWeight:600}}>Connect</span> to cycle the status line (disconnected → connecting… → failed: timeout). “Advanced · direct LAN” expands a mono ws-url input + outlined Connect direct.</>) },
  { title:'Reconnect banner · over Chat', node:<ReconnectFrame/>, note:(<>Slim strip pinned under the safe area, above the header — content pushes down, nothing is overlaid. Auto-retry with backoff; the banner disappears on success. Composer dims while offline.</>) },
  { title:'Observing bar · replaces composer', node:<ObservingFrame/>, note:(<>Session is running in a terminal on the computer; the phone tails it read-only. <span style={{color:'#ECEDEE',fontWeight:600}}>Continue here</span> takes the session over and restores the composer.</>) },
];

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · system</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Connect & system states</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:640, margin:'0 0 36px' }}>
        The three connection-lifecycle surfaces: the paired-but-disconnected home, the in-chat reconnect strip, and the read-only observing bar when the session lives in a terminal.
      </p>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34, alignItems:'flex-start' }}>
        {FRAMES.map((f,i)=>(
          <div key={i} style={{ width:402*0.82 }}>
            <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
              <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{i+1}</span>
              <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{f.title}</span>
            </div>
            <Phone>{f.node}</Phone>
            <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec, marginTop:11 }}>{f.note}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
