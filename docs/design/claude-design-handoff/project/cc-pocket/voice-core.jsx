// cc-pocket — voice input · core (tokens, icons, waveform, shared stream + composer atoms)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons (1.5pt line) ────────────────────────────────────────
function Chevron({ d='left', c=T.sec, s=17, w=2 }) {
  const p={left:'M11 3L5 9l6 6',right:'M6 3l6 6-6 6',down:'M3 6l6 6 6-6'};
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
}
function Plus({ c=T.sec, s=22 }) {
  return <svg width={s} height={s} viewBox="0 0 22 22" fill="none"><path d="M11 4.5v13M4.5 11h13" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>;
}
function Mic({ c=T.sec, s=22 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="3" width="6" height="11" rx="3"/>
      <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0"/>
      <path d="M12 18v3M9 21h6"/>
    </svg>
  );
}
function SendArrow({ c='#0E0F11', s=18 }) {
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
}
function CheckMark({ c='#0E0F11', s=20 }) {
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M4 10.5l4 4 8-9" stroke={c} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
}
function XSmall({ c=T.muted, s=18, w=1.8 }) {
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M4.5 4.5l9 9M13.5 4.5l-9 9" stroke={c} strokeWidth={w} strokeLinecap="round"/></svg>;
}
function ShieldMic({ c=T.accent, s=26 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 26 26" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M13 2.5l8 3v6c0 5.2-3.8 8.6-8 10-4.2-1.4-8-4.8-8-10v-6l8-3z"/>
      <rect x="11" y="8.5" width="4" height="6.4" rx="2"/>
      <path d="M8.7 13.2a4.3 4.3 0 0 0 8.6 0"/>
    </svg>
  );
}
function Spinner({ s=18, c=T.accent }) {
  const r=(s-3)/2, C=2*Math.PI*r;
  return (
    <svg className="v-spin" width={s} height={s} viewBox={`0 0 ${s} ${s}`}>
      <circle cx={s/2} cy={s/2} r={r} stroke="rgba(255,255,255,0.16)" strokeWidth="2" fill="none"/>
      <circle cx={s/2} cy={s/2} r={r} stroke={c} strokeWidth="2" fill="none" strokeLinecap="round" strokeDasharray={`${C*0.28} ${C}`}/>
    </svg>
  );
}
function WarnTri({ c=T.danger, s=15 }) {
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 2.6l6.6 12H2.4L9 2.6z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/><path d="M9 7.2v3.2" stroke={c} strokeWidth="1.4" strokeLinecap="round"/><circle cx="9" cy="12.6" r=".9" fill={c}/></svg>;
}

// ── waveform: thin vertical bars, terracotta ──────────────────
// `live` animates with a pseudo voice level; `frozen` holds the last frame.
function Waveform({ live=true, frozen=false, bars=34, h=30 }) {
  const [tick, setTick] = React.useState(0);
  React.useEffect(() => {
    if (!live || frozen) return;
    let raf, t0=performance.now();
    const loop = (t) => { setTick((t-t0)/1000); raf=requestAnimationFrame(loop); };
    raf=requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [live, frozen]);

  const seed = React.useRef([...Array(bars)].map(() => Math.random()*Math.PI*2));
  return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center', gap:2.5, height:h, flex:1, overflow:'hidden' }}>
      {seed.current.map((ph, i) => {
        const env = 0.55 + 0.45*Math.sin(i/bars*Math.PI); // taper ends
        let level;
        if (frozen) level = (0.25 + 0.6*Math.abs(Math.sin(ph*1.7)))*env;
        else if (!live) level = 0.12;
        else {
          const a = Math.sin(tick*6 + ph) * 0.5 + 0.5;
          const b = Math.sin(tick*11 + ph*1.7) * 0.5 + 0.5;
          level = (0.12 + 0.88*Math.max(a,b)*b) * env;
        }
        const bh = Math.max(3, level*h);
        return <span key={i} style={{ width:2.5, height:bh, borderRadius:2, background:T.accent, opacity: frozen?0.55:(0.5+level*0.5), transition: frozen?'height .2s':'none' }}/>;
      })}
    </div>
  );
}

const fmt = (s) => `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;

// ── shared chat stream (identical across all variants) ────────
function Mono({ children }) {
  return <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px', color:T.text }}>{children}</span>;
}
function ChatStream({ extra = [] }) {
  return (
    <div className="v-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 14px' }}>
      <div style={{ display:'flex', flexDirection:'column', gap:18 }}>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:8 }}>YOU</div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>where does the websocket client retry after a drop?</div>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
          The reconnect path lives in <Mono>WsClient.reconnect()</Mono>. It backs off exponentially, but the socket is closed before the backoff timer is scheduled — so the first retry never fires.
        </div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'11px 12px', display:'flex', alignItems:'center', gap:9 }}>
          <span style={{ width:7, height:7, borderRadius:999, background:T.success, flexShrink:0 }}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>Read</span>
          <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', direction:'rtl', textAlign:'left' }}>src/relay/WsClient.kt</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>88 lines</span>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
          Want me to move the timer scheduling above the close call, or add a guard so reconnect can’t run on an already-closed socket?
        </div>
        {extra.map((t, i) => (
          <div key={'x'+i}>
            <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:8 }}>YOU</div>
            <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>{t}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── chat header (unchanged across variants) ───────────────────
function ChatHeader() {
  return (
    <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:44 }}>
        <button style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={T.sec} s={17}/></button>
        <div style={{ flex:1, display:'flex', flexDirection:'column', minWidth:0 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Fix relay reconnect</span>
        </div>
        <span style={{ display:'flex', alignItems:'center', gap:5, marginRight:8, background:'rgba(155,161,166,0.10)', border:`1px solid ${T.border}`, borderRadius:999, padding:'4px 9px' }}>
          <span style={{ width:6, height:6, borderRadius:999, background:T.sec }}/>
          <span style={{ fontFamily:T.ui, fontSize:11.5, color:T.sec, fontWeight:500 }}>Ask each step</span>
        </span>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
        <span className="v-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
      </div>
    </div>
  );
}

Object.assign(window, { T, Chevron, Plus, Mic, SendArrow, CheckMark, XSmall, ShieldMic, Spinner, WarnTri, Waveform, fmt, Mono, ChatStream, ChatHeader });
