// cc-pocket — Usage (token usage dashboard). Calm, reassuring — not an alarm.

// theme-aware token sets (dark default + light variant)
const DARK = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', teal:'#3FB5AC', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  chartTrack:'#1E2125',
};
const LIGHT = {
  base:'#FAF9F7', surface:'#FFFFFF', raised:'#F4F2EE', border:'#E7E4DF',
  text:'#1A1A19', sec:'#5C5A55', muted:'#8A8780',
  accent:'#C75A38', teal:'#2E9A91', success:'#3F9A63', warning:'#C28A2A', danger:'#C94A38',
  chartTrack:'#EDEAE4',
};
const MONO = "'JetBrains Mono',ui-monospace,monospace";
const UI = "'Inter',-apple-system,system-ui,sans-serif";

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ c, s=17 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M11 3L5 9l6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const ArrowUp = ({ c, s=11 }) => <svg width={s} height={s} viewBox="0 0 12 12" fill="none"><path d="M6 9.5V2.5M6 2.5L2.8 5.7M6 2.5l3.2 3.2" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const ArrowDn = ({ c, s=11 }) => <svg width={s} height={s} viewBox="0 0 12 12" fill="none"><path d="M6 2.5v7M6 9.5L2.8 6.3M6 9.5l3.2-3.2" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const SparkIcon = ({ c, s=34 }) => <svg width={s} height={s} viewBox="0 0 34 34" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M17 6l2.2 6.3L25.5 14.5 19.2 16.7 17 23l-2.2-6.3L8.5 14.5l6.3-2.2z"/></svg>;
const CloudOff = ({ c, s=34 }) => <svg width={s} height={s} viewBox="0 0 34 34" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M10 25h13a5 5 0 001-9.9A7 7 0 0011.5 12"/><path d="M4 4l26 26"/></svg>;

// ── header ────────────────────────────────────────────────────
function Header({ t, range='7d' }) {
  const ranges = ['Today','7d','30d'];
  return (
    <div style={{ flexShrink:0, paddingTop:52, background:t.base, borderBottom:`1px solid ${t.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:6, padding:'0 10px 10px', height:44 }}>
        <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron c={t.sec}/></button>
        <span style={{ fontFamily:UI, fontSize:18, fontWeight:700, color:t.text, letterSpacing:-0.3 }}>Usage</span>
        <span style={{ flex:1 }}/>
        <div style={{ display:'flex', gap:2, padding:2, background:t.surface, border:`1px solid ${t.border}`, borderRadius:999 }}>
          {ranges.map(r=>{
            const on = r===range;
            return <span key={r} style={{ fontFamily:UI, fontSize:11.5, fontWeight:600, padding:'4px 10px', borderRadius:999,
              background:on?t.accent:'transparent', color:on?(t===LIGHT?'#fff':'#0E0F11'):t.sec }}>{r}</span>;
          })}
        </div>
      </div>
    </div>
  );
}

// ── stat card ─────────────────────────────────────────────────
function StatCard({ t, value, label, delta, arc, children }) {
  return (
    <div style={{ background:t.surface, border:`1px solid ${t.border}`, borderRadius:14, padding:'14px 15px', minHeight:96, display:'flex', flexDirection:'column', justifyContent:'space-between', position:'relative', overflow:'hidden' }}>
      <div style={{ fontFamily:UI, fontSize:12, color:t.sec, fontWeight:500 }}>{label}</div>
      <div style={{ display:'flex', alignItems:'flex-end', gap:8, justifyContent:'space-between' }}>
        <span style={{ fontFamily:MONO, fontSize:26, fontWeight:600, color:t.text, letterSpacing:-0.5, lineHeight:1 }}>{value}</span>
        {children}
      </div>
      {delta && (
        <div style={{ display:'flex', alignItems:'center', gap:4, marginTop:2 }}>
          {delta.dir==='up' ? <ArrowUp c={delta.color}/> : <ArrowDn c={delta.color}/>}
          <span style={{ fontFamily:MONO, fontSize:11, color:delta.color }}>{delta.text}</span>
          <span style={{ fontFamily:UI, fontSize:11, color:t.muted }}>{delta.vs}</span>
        </div>
      )}
    </div>
  );
}

// small terracotta progress arc (cache hit)
function Arc({ t, pct }) {
  const r=15, C=2*Math.PI*r, off=C*(1-pct/100);
  return (
    <svg width="40" height="40" viewBox="0 0 40 40" style={{ transform:'rotate(-90deg)', flexShrink:0 }}>
      <circle cx="20" cy="20" r={r} stroke={t.chartTrack} strokeWidth="3.5" fill="none"/>
      <circle cx="20" cy="20" r={r} stroke={t.accent} strokeWidth="3.5" fill="none" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={off}/>
    </svg>
  );
}

// ── bar chart ─────────────────────────────────────────────────
function BarChart({ t, data, peakIdx }) {
  const max = Math.max(...data.map(d=>d.v));
  return (
    <div>
      <div style={{ display:'flex', alignItems:'flex-end', gap:7, height:120, padding:'0 2px' }}>
        {data.map((d,i)=>{
          const h = Math.max(4, (d.v/max)*112);
          const peak = i===peakIdx;
          return (
            <div key={i} style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', gap:6, height:'100%', justifyContent:'flex-end' }}>
              <div style={{ width:'100%', height:h, background:t.accent, opacity: peak?1:0.42, borderRadius:'4px 4px 0 0' }}/>
              <span style={{ fontFamily:MONO, fontSize:9, color: peak?t.sec:t.muted }}>{d.label}</span>
            </div>
          );
        })}
      </div>
      <div style={{ height:1, background:t.border, margin:'0 2px 10px' }}/>
    </div>
  );
}

// ── by-model row ──────────────────────────────────────────────
function ModelRow({ t, name, tokens, share, color }) {
  return (
    <div style={{ padding:'9px 0' }}>
      <div style={{ display:'flex', alignItems:'center', gap:10, marginBottom:7 }}>
        <span style={{ fontFamily:MONO, fontSize:12, color:t.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
        <span style={{ fontFamily:MONO, fontSize:12, color:t.sec, flexShrink:0 }}>{tokens}</span>
      </div>
      <div style={{ height:4, background:t.chartTrack, borderRadius:999, overflow:'hidden' }}>
        <div style={{ width:`${share}%`, height:'100%', background:color, borderRadius:999 }}/>
      </div>
    </div>
  );
}

// ── section label ─────────────────────────────────────────────
function SLabel({ t, children, right }) {
  return (
    <div style={{ display:'flex', alignItems:'baseline', margin:'22px 0 12px' }}>
      <span style={{ fontFamily:UI, fontSize:12, fontWeight:600, letterSpacing:0.5, color:t.muted, textTransform:'uppercase' }}>{children}</span>
      <span style={{ flex:1 }}/>
      {right && <span style={{ fontFamily:UI, fontSize:11.5, color:t.muted }}>{right}</span>}
    </div>
  );
}

// ── data ──────────────────────────────────────────────────────
const WEEK = [
  { label:'Wed', v:1.2 }, { label:'Thu', v:0.9 }, { label:'Fri', v:1.6 },
  { label:'Sat', v:0.4 }, { label:'Sun', v:0.7 }, { label:'Mon', v:2.3 }, { label:'Tue', v:1.84 },
];
const MODELS = [
  { name:'claude-opus-4-8', tokens:'1.12M', share:100, agent:'claude' },
  { name:'claude-sonnet-4-8', tokens:'560K', share:50, agent:'claude' },
  { name:'gpt-5.1-codex', tokens:'340K', share:30, agent:'codex' },
  { name:'claude-haiku-4', tokens:'82K', share:8, agent:'claude' },
];

// ════════════════ POPULATED ════════════════
function Populated({ t }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:t.base }}>
      <Header t={t} range="7d"/>
      <div className="us-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 28px' }}>
        <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:10 }}>
          <StatCard t={t} value="1.84M" label="Tokens today" delta={{ dir:'up', text:'12%', vs:'vs yesterday', color:t.warning }}/>
          <StatCard t={t} value="312" label="Requests" delta={{ dir:'up', text:'8%', vs:'vs yesterday', color:t.muted }}/>
          <StatCard t={t} value="$4.20" label="Est. cost" delta={{ dir:'down', text:'3%', vs:'vs yesterday', color:t.success }}/>
          <StatCard t={t} value="68%" label="Cache hit"><Arc t={t} pct={68}/></StatCard>
        </div>

        <SLabel t={t} right="tokens / day">Last 7 days</SLabel>
        <BarChart t={t} data={WEEK} peakIdx={5}/>
        <div style={{ fontFamily:UI, fontSize:12, color:t.muted, marginTop:-2 }}>Peak on <span style={{ color:t.sec, fontFamily:MONO, fontSize:11.5 }}>Mon</span> · 2.3M tokens</div>

        <SLabel t={t}>By model</SLabel>
        <div>
          {MODELS.map((m,i)=><ModelRow key={i} t={t} name={m.name} tokens={m.tokens} share={m.share} color={m.agent==='claude'?t.accent:t.teal}/>)}
        </div>
      </div>
    </div>
  );
}

// ════════════════ LOADING ════════════════
function ShimBox({ t, w='100%', h=12, r=6, style }) {
  return <div className="us-shim" style={{ width:w, height:h, borderRadius:r, ...style }}/>;
}
function Loading({ t }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:t.base }}>
      <Header t={t} range="7d"/>
      <div className="us-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 28px' }}>
        <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:10 }}>
          {[0,1,2,3].map(i=>(
            <div key={i} style={{ background:t.surface, border:`1px solid ${t.border}`, borderRadius:14, padding:'14px 15px', minHeight:96, display:'flex', flexDirection:'column', justifyContent:'space-between' }}>
              <ShimBox t={t} w="56%" h={10}/>
              <ShimBox t={t} w="72%" h={22} r={7}/>
              <ShimBox t={t} w="44%" h={9}/>
            </div>
          ))}
        </div>
        <SLabel t={t}>Last 7 days</SLabel>
        <div style={{ display:'flex', alignItems:'flex-end', gap:7, height:120, padding:'0 2px' }}>
          {[0.5,0.4,0.7,0.3,0.45,0.9,0.6].map((h,i)=>(
            <div key={i} style={{ flex:1 }}><ShimBox t={t} w="100%" h={h*112} r={4}/></div>
          ))}
        </div>
        <div style={{ height:1, background:t.border, margin:'10px 2px' }}/>
        <SLabel t={t}>By model</SLabel>
        {[0,1,2].map(i=>(
          <div key={i} style={{ padding:'9px 0' }}>
            <div style={{ display:'flex', justifyContent:'space-between', marginBottom:7 }}><ShimBox t={t} w="45%" h={11}/><ShimBox t={t} w="18%" h={11}/></div>
            <ShimBox t={t} w="100%" h={4} r={999}/>
          </div>
        ))}
      </div>
    </div>
  );
}

// ════════════════ EMPTY ════════════════
function Empty({ t }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:t.base }}>
      <Header t={t} range="Today"/>
      <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:16, padding:'0 44px 60px' }}>
        <span style={{ opacity:0.6 }}><SparkIcon c={t.muted}/></span>
        <div style={{ fontFamily:UI, fontSize:16, fontWeight:600, color:t.sec }}>No usage yet today</div>
        <div style={{ fontFamily:UI, fontSize:13, lineHeight:'20px', color:t.muted, textAlign:'center' }}>Start a session and your token usage will show up here — nothing to worry about.</div>
      </div>
    </div>
  );
}

// ════════════════ OFFLINE ════════════════
function Offline({ t }) {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:t.base }}>
      <Header t={t} range="7d"/>
      <div className="us-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 28px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 12px', background:t.surface, border:`1px solid ${t.border}`, borderRadius:10, marginBottom:14 }}>
          <CloudOff c={t.muted} s={16}/>
          <span style={{ fontFamily:UI, fontSize:12.5, color:t.sec }}>Can’t reach your computer</span>
        </div>
        <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:10 }}>
          {[['Tokens today'],['Requests'],['Est. cost'],['Cache hit']].map(([label],i)=>(
            <div key={i} style={{ background:t.surface, border:`1px solid ${t.border}`, borderRadius:14, padding:'14px 15px', minHeight:96, display:'flex', flexDirection:'column', justifyContent:'space-between' }}>
              <div style={{ fontFamily:UI, fontSize:12, color:t.muted }}>{label}</div>
              <span style={{ fontFamily:MONO, fontSize:26, fontWeight:600, color:t.muted, lineHeight:1 }}>—</span>
              <div style={{ height:13 }}/>
            </div>
          ))}
        </div>
        <SLabel t={t}>Last 7 days</SLabel>
        <div style={{ height:120, display:'flex', alignItems:'center', justifyContent:'center', border:`1px dashed ${t.border}`, borderRadius:12 }}>
          <span style={{ fontFamily:UI, fontSize:12.5, color:t.muted }}>—</span>
        </div>
      </div>
    </div>
  );
}

// ── device board ──────────────────────────────────────────────
function Phone({ children, scale=0.86 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}
function Cell({ label, note, children, scale }) {
  return (
    <div style={{ width:402*(scale||0.86) }}>
      <div style={{ fontFamily:UI, fontSize:14, fontWeight:600, color:'#ECEDEE', marginBottom:10 }}>{label}</div>
      <Phone scale={scale}>{children}</Phone>
      {note && <div style={{ fontFamily:UI, fontSize:12.5, lineHeight:'18px', color:'#9BA1A6', marginTop:11, maxWidth:402*(scale||0.86) }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:MONO, fontSize:12, color:DARK.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · usage</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:DARK.text }}>Usage — calm token accounting</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:DARK.sec, maxWidth:680, margin:0 }}>
        A dashboard that soothes token anxiety instead of triggering it: quiet stat cards, a low-contrast bar trend with no heavy gridlines, and a by-model breakdown (Claude terracotta · Codex teal). Deltas stay muted — amber only nudges, never alarms.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:36, marginTop:40 }}>
        <Cell label="Populated" note="Four stat cards (2×2): tokens, requests, est. cost, cache-hit with a terracotta arc. A soft 7-day bar trend names the peak day. By-model share bars in each agent's identity color.">
          <Populated t={DARK}/>
        </Cell>
        <Cell label="Loading" note="Skeleton stat cards + shimmering bars and share rows — no spinner, no jolt.">
          <Loading t={DARK}/>
        </Cell>
        <Cell label="Empty" note="“No usage yet today” with a soft spark icon and a calm, reassuring one-liner.">
          <Empty t={DARK}/>
        </Cell>
        <Cell label="Offline" note="Cards show “—”; a quiet “Can’t reach your computer” note. No red, no alarm.">
          <Offline t={DARK}/>
        </Cell>
      </div>

      <div style={{ display:'flex', alignItems:'center', gap:12, margin:'64px 0 30px' }}>
        <span style={{ fontFamily:MONO, fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:DARK.accent }}>Light variant</span>
        <span style={{ flex:1, height:1, background:DARK.border }}/>
      </div>
      <div style={{ display:'flex', flexWrap:'wrap', gap:36 }}>
        <div style={{ padding:24, background:'#FAF9F7', borderRadius:20, border:`1px solid ${DARK.border}` }}>
          <Cell label={<span style={{ color:'#1A1A19' }}>Populated · light</span>}>
            <Populated t={LIGHT}/>
          </Cell>
        </div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
