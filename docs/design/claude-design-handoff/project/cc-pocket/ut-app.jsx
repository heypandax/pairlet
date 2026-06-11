// cc-pocket — user-turn container exploration: A quiet card · B accent rail · C compact bubble

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

const Chevron = ({ d='left', c=T.sec, s=17, w=2 }) => {
  const p={left:'M11 3L5 9l6 6',right:'M6 3l6 6-6 6',down:'M3 6l6 6 6-6'};
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Plus = ({ c=T.sec, s=20 }) => (
  <svg width={s} height={s} viewBox="0 0 22 22" fill="none"><path d="M11 4.5v13M4.5 11h13" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>
);
const SendArrow = ({ c=T.muted, s=18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const code = { fontFamily:T.mono, fontSize:12, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'0px 4px', color:T.text };

// ── shared chrome ─────────────────────────────────────────────
function Header() {
  return (
    <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:42 }}>
        <button style={{ all:'unset', cursor:'pointer', width:42, height:42, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{ flex:1, fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Fix relay reconnect</span>
        <span style={{ display:'flex', alignItems:'center', gap:5, marginRight:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:999, padding:'4px 9px' }}>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>default</span>
          <Chevron d="down" c={T.muted} s={10} w={1.7}/>
        </span>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 8px' }}>
        <span className="ut-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
      </div>
    </div>
  );
}
function Composer() {
  return (
    <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, background:T.base, paddingBottom:24 }}>
      <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px 0' }}>
        <span style={{ width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Plus c={T.sec} s={20}/></span>
        <div style={{ flex:1, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'10px 14px', minHeight:40 }}>
          <span style={{ fontFamily:T.ui, fontSize:14, color:T.muted }}>Message Claude…</span>
        </div>
        <span style={{ width:40, height:40, borderRadius:999, background:T.surface, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><SendArrow/></span>
      </div>
    </div>
  );
}
function Claude({ children, streaming }) {
  return (
    <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'20px', color:T.text }}>
      {children}
      {streaming && <span className="ut-blink" style={{ display:'inline-block', width:8, height:'1em', background:T.accent, borderRadius:1.5, marginLeft:2, verticalAlign:'text-bottom', transform:'translateY(2px)' }}/>}
    </div>
  );
}
function ToolRow() {
  return (
    <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'9px 11px', display:'flex', alignItems:'center', gap:8 }}>
      <span style={{ width:6, height:6, borderRadius:999, background:T.success, flexShrink:0 }}/>
      <span style={{ fontFamily:T.ui, fontSize:12, fontWeight:600, color:T.text }}>Bash</span>
      <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>gradle :relay:test --info</span>
      <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>9s</span>
    </div>
  );
}
// 2-up image grid (existing treatment) — goes INSIDE the user container
function ImgGrid({ radius=8 }) {
  const ph = (a,b) => ({ flex:1, height:70, borderRadius:radius, background:`radial-gradient(120% 140% at ${a}, #3a2d24 0%, #241d18 55%, #16181B 100%), ${T.surface}`, border:`1px solid ${T.border}`, backgroundBlendMode:'normal' });
  return (
    <div style={{ display:'flex', gap:6 }}>
      <div style={ph('70% 20%','')}/>
      <div style={ph('25% 75%','')}/>
    </div>
  );
}

// ── the conversation, parameterized by user-turn container ────
const LONG = (
  <>the reconnect loop still dies after the 3rd retry — backoff doubles fine but once it passes 8s the timer never fires. I think the scope owning the timer is cancelled in <span style={code}>WsClient.kt</span> when the socket closes. can you add a guard and a regression test for it?</>
);

function Stream({ User }) {
  return (
    <div className="ut-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'12px 16px' }}>
      <div style={{ display:'flex', flexDirection:'column', gap:11 }}>
        <Claude>Relay tests are green — 14 passed.</Claude>
        <User short>run them again with trace logging</User>
        <ToolRow/>
        <Claude>Trace shows the socket closing 200&nbsp;ms before the backoff timer fires.</Claude>
        <User>{LONG}</User>
        <Claude>Guard added — reconnect now survives scope cancellation.</Claude>
        <User images>here’s the crash from the iOS build, same spot both runs</User>
        <User short>also check the retry cap while you’re in there</User>
        <Claude streaming>Looking at the cap now — it’s hard-coded to 30&nbsp;s in</Claude>
      </div>
    </div>
  );
}

// ── direction A: quiet card ───────────────────────────────────
function UserA({ children, images }) {
  return (
    <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'10px 12px' }}>
      <div style={{ fontFamily:T.ui, fontSize:10, fontWeight:600, letterSpacing:0.8, color:T.muted, marginBottom:5 }}>YOU</div>
      {images && <div style={{ marginBottom:8 }}><ImgGrid/></div>}
      <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'20px', color:T.text }}>{children}</div>
    </div>
  );
}

// ── direction B: accent rail ──────────────────────────────────
function UserB({ children, images }) {
  return (
    <div style={{ position:'relative', background:'rgba(217,119,87,0.05)', borderRadius:'4px 10px 10px 4px', padding:'9px 12px 9px 14px' }}>
      <span style={{ position:'absolute', left:0, top:0, bottom:0, width:2, borderRadius:2, background:T.accent, opacity:0.6 }}/>
      {images && <div style={{ marginBottom:8 }}><ImgGrid/></div>}
      <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'20px', color:T.text }}>{children}</div>
    </div>
  );
}

// ── direction C: compact bubble ───────────────────────────────
function UserC({ children, images }) {
  return (
    <div style={{ display:'flex', justifyContent:'flex-end' }}>
      <div style={{ maxWidth:'85%', background:T.surface, borderRadius:'16px 16px 6px 16px', padding:'10px 13px' }}>
        {images && <div style={{ marginBottom:8 }}><ImgGrid/></div>}
        <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'20px', color:T.text }}>{children}</div>
      </div>
    </div>
  );
}

// ── phone ─────────────────────────────────────────────────────
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>
          <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
            <Header/>
            {children}
            <Composer/>
          </div>
        </IOSDevice>
      </div>
    </div>
  );
}

const DIRS = [
  { id:'A', name:'Quiet card', User:UserA,
    note:(<>Surface card + hairline, 12&nbsp;radius, with a 10sp <b style={{color:T.text}}>YOU</b> overline. Kept the label: without it the card reads as the <i>tool-row</i> vocabulary — that collision is this direction’s structural weakness.</>) },
  { id:'B', name:'Accent rail', User:UserB,
    note:(<>2px terracotta rail @60% + 5% warm tint, content indented. Blockquote semantics — “what I said” — with zero chrome. No label needed: the rail itself is the marker, and it’s scannable in a flick-scroll.</>) },
  { id:'C', name:'Compact bubble', User:UserC,
    note:(<>Right-aligned, max 85%, tighter bottom-right corner, no border. Familiar contrast, but the ragged right edge breaks the terminal reading flow and pushes the app toward consumer chat.</>) },
];

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat iteration</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>User-turn container — 3 directions</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:660, margin:'0 0 36px' }}>
        Same conversation in all three. Claude stays the full-width “document”; only the user’s turns change container. Each screen covers: short prompt between assistant turns, long prompt with inline code, 2-image turn, two consecutive turns, and a streaming reply below.
      </p>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34, alignItems:'flex-start' }}>
        {DIRS.map(d => (
          <div key={d.id} style={{ width:402*0.82 }}>
            <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
              <span style={{ fontFamily:T.mono, fontSize:13, color:T.accent }}>{d.id}</span>
              <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{d.name}</span>
            </div>
            <Phone><Stream User={d.User}/></Phone>
            <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec, marginTop:11 }}>{d.note}</div>
          </div>
        ))}
      </div>

      {/* ── recommendation ── */}
      <div style={{ display:'flex', alignItems:'center', gap:12, margin:'56px 0 18px' }}>
        <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>Ranked recommendation</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
        {[
          ['1','B · Accent rail','Quote semantics fit “user as annotation” exactly; zero collision with tool-card chrome; scannable by color in a fast scroll while preserving the full-width reading flow.'],
          ['2','A · Quiet card','Calm and unambiguous, but it borrows the tool-row container — two different meanings in one visual vocabulary, and the label is doing the disambiguation work.'],
          ['3','C · Compact bubble','Instantly familiar, but right-alignment raggeds the scan line and reads consumer-chat — against the terminal character of the app.'],
        ].map(([n,t,d])=>(
          <div key={n} style={{ display:'flex', gap:14, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'13px 16px' }}>
            <span style={{ fontFamily:T.mono, fontSize:13, color:n==='1'?T.accent:T.muted, flexShrink:0, paddingTop:1 }}>{n}</span>
            <div>
              <div style={{ fontSize:13.5, fontWeight:600, color:T.text }}>{t}</div>
              <div style={{ fontSize:12.5, lineHeight:'19px', color:T.sec, marginTop:3 }}>{d}</div>
            </div>
          </div>
        ))}
      </div>

      {/* ── mini spec (winner: B) ── */}
      <div style={{ display:'flex', alignItems:'center', gap:12, margin:'48px 0 18px' }}>
        <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>Mini spec — B · accent rail</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'4px 18px', maxWidth:680 }}>
        <pre style={{ fontFamily:T.mono, fontSize:12, lineHeight:'20px', color:T.sec, whiteSpace:'pre-wrap' }}>{
`container     Row(IntrinsicSize.Min), full width, no horizontal inset beyond stream's 16dp
rail          width 2dp · fill height · RoundedCornerShape(2dp)
              color = accent #D97757 @ 60% alpha
tint          background #D97757 @ 5% alpha
              shape = RoundedCornerShape(4dp, 10dp, 10dp, 4dp)
padding       start 14dp (12dp content + 2dp rail) · end 12dp · vertical 9dp
label         none — the rail is the marker (drop "YOU")
text          Inter 14.5sp / 21sp line height · #ECEDEE (unchanged from assistant)
inline code   JetBrains Mono 12sp · surface chip (unchanged)
image grid    inside container, ABOVE text · 2-up, gap 6dp, radius 8dp, h 84dp
              marginBottom 8dp when text follows
consecutive   stack with the stream's standard 11–13dp gap — rails read as
              separate quotes; no special merging
streaming     unaffected — assistant treatment untouched`
        }</pre>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
