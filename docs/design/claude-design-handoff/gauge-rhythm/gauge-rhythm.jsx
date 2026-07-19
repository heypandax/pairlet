// cc-pocket — Occupancy gauge vertical rhythm · composer thread #5
// Additive fix to the shipped Option-C gauge: the bare 15pt ring reads as debris
// in a band of 30pt pills + 44pt buttons. Reuse ALL other row specs verbatim.

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', raisedHi:'#24282D', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const WARN = '#E0A93B', CRIT = '#E5604D';
const HIT = 44;

// ── icons (verbatim from thread #3) ───────────────────────────
const ChevUp = ({ c=T.muted, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3 9l4-4 4 4" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Plus = ({ c=T.sec, s=20 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M10 4v12M4 10h12" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>;
const SendArrow = ({ c='#0E0F11', s=19 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M10 16V5M10 5l-4.6 4.6M10 5l4.6 4.6" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Mic = ({ c=T.sec, s=20 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><rect x="7.4" y="2.6" width="5.2" height="9.6" rx="2.6" stroke={c} strokeWidth="1.7"/><path d="M4.6 9.4a5.4 5.4 0 0010.8 0M10 14.8v2.6" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>;
const Stop = ({ c=T.accent, s=16 }) => <svg width={s} height={s} viewBox="0 0 16 16"><rect x="3" y="3" width="10" height="10" rx="2.4" fill={c}/></svg>;
const Stack = ({ c=T.sec, s=14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <rect x="2.4" y="4.6" width="11.2" height="8" rx="2" stroke={c} strokeWidth="1.5"/>
    <path d="M4.6 4.6V3.4a1.4 1.4 0 011.4-1.4h4a1.4 1.4 0 011.4 1.4v1.2" stroke={c} strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);
const AlertTri = ({ c=CRIT, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M7 1.6l5.6 9.8H1.4L7 1.6z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/><path d="M7 5.6v2.6" stroke={c} strokeWidth="1.4" strokeLinecap="round"/><circle cx="7" cy="10" r="0.8" fill={c}/></svg>;

function midTrunc(s, head=8, tail=4){ return s.length<=head+tail+1 ? s : s.slice(0,head)+'…'+s.slice(-tail); }

// ── ring glyph ────────────────────────────────────────────────
function Ring({ pct=0, color, track=T.border, size=13, stroke=2.2, unknown=false }){
  const r=(size-stroke)/2, c=size/2, circ=2*Math.PI*r, off=circ*(1-pct/100);
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ display:'block', flexShrink:0 }}>
      <circle cx={c} cy={c} r={r} fill="none" stroke={track} strokeWidth={stroke}/>
      {!unknown && <circle cx={c} cy={c} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={circ} strokeDashoffset={off} transform={`rotate(-90 ${c} ${c})`}/>}
    </svg>
  );
}
function levelOf(pct, known){ return !known?'unk':pct>=95?'crit':pct>=80?'warn':'calm'; }
function colorOf(level){ return level==='crit'?CRIT:level==='warn'?WARN:T.sec; }

// ═══════════════════════════════════════════════════════════════
// GAUGE VARIANTS (all reuse Ring; differ only in the bounding form)
// ═══════════════════════════════════════════════════════════════

// D1 · fill without border — RECOMMENDED
// 30pt capsule, background `raised`, NO hairline. Locks to the control band
// height; the absent border keeps it a tier below the hairline-bordered chips.
function GaugeFill({ pct, raw, known=true, degraded=false, guides=false }){
  const level = levelOf(pct, known), color = colorOf(level);
  const showNum = known && level!=='calm' && !degraded;
  const bare = level==='calm' || degraded; // ring-only footprint
  return (
    <button aria-label="Context usage — open session info" style={{ all:'unset', cursor:'pointer', flexShrink:0,
      display:'flex', alignItems:'center', justifyContent:'center', gap:5,
      height:30, borderRadius:999, background:T.raised,
      padding: bare && known ? 0 : (!known?'0 10px 0 9px':'0 10px 0 9px'),
      width: bare && known ? 30 : 'auto',
      outline: guides?`1px dashed ${T.accent}`:'none' }}>
      <Ring pct={known?pct:0} color={color} unknown={!known} size={13}/>
      {showNum && <span style={{ fontFamily:T.mono, fontSize:11, color, fontWeight:500, letterSpacing:0.2 }}>{pct}%</span>}
      {!known && <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, fontWeight:500 }}>~{raw}</span>}
    </button>
  );
}

// D2 · optical mass, no chrome — bigger ring + permanent quiet number
function GaugeMass({ pct, raw, known=true, degraded=false }){
  const level = levelOf(pct, known), color = colorOf(level);
  return (
    <button style={{ all:'unset', cursor:'pointer', flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', gap:6, height:30, width:HIT-6 }}>
      <Ring pct={known?pct:0} color={color} unknown={!known} size={20} stroke={2.6}/>
      {!degraded && (known
        ? <span style={{ fontFamily:T.mono, fontSize:11, color: level==='calm'?T.muted:color, fontWeight:500 }}>{pct}</span>
        : <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>~{raw}</span>)}
    </button>
  );
}

// D3 · same bare ring, but relocated right of the elastic gap (rendered inline in row)
function GaugeBare({ pct, raw, known=true, degraded=false }){
  const level = levelOf(pct, known), color = colorOf(level);
  const showNum = known && level!=='calm' && !degraded;
  return (
    <button style={{ all:'unset', cursor:'pointer', flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', gap:5, height:HIT, width: showNum||!known?'auto':HIT-8, padding: showNum||!known?'0 4px':0 }}>
      <Ring pct={known?pct:0} color={color} unknown={!known} size={15}/>
      {showNum && <span style={{ fontFamily:T.mono, fontSize:11, color, fontWeight:500 }}>{pct}%</span>}
      {!known && <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>~{raw}</span>}
    </button>
  );
}

// ── shared row parts (verbatim specs from threads #2/#3) ──────
function ModelChip({ label, dimmed, cap=120, leadRing }){
  const lvl = leadRing?levelOf(leadRing.pct, leadRing.known):null;
  return (
    <button disabled={dimmed} style={{ all:'unset', boxSizing:'border-box', cursor:dimmed?'default':'pointer', flexShrink:0,
      display:'flex', alignItems:'center', gap:6, height:30, padding:'0 8px 0 9px', borderRadius:999,
      background:T.raised, border:`1px solid ${T.border}`, opacity:dimmed?0.42:1 }}>
      {leadRing && <Ring pct={leadRing.known?leadRing.pct:0} color={colorOf(lvl)} unknown={!leadRing.known} size={13}/>}
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, maxWidth:cap, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{label}</span>
      <ChevUp c={T.muted} s={12}/>
    </button>
  );
}
function StackChip({ count }){
  return (
    <button style={{ all:'unset', cursor:'pointer', flexShrink:0, display:'flex', alignItems:'center', gap:6,
      height:30, padding:'0 11px', borderRadius:999, background:T.raised, border:`1px solid ${T.border}` }}>
      <Stack c={T.sec} s={14}/>
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, fontWeight:500 }}>{count}</span>
    </button>
  );
}
function ActionBtn({ kind }){
  const base = { all:'unset', cursor:'pointer', width:HIT, height:HIT, borderRadius:999, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center' };
  if (kind==='send') return <button style={{ ...base, background:T.accent }}><SendArrow/></button>;
  if (kind==='stop') return <button style={{ ...base, background:T.surface, border:`1px solid ${T.border}` }}><Stop/></button>;
  return <button style={{ ...base, background:'transparent', border:`1px solid ${T.border}` }}><Mic c={T.sec}/></button>;
}

// ── composer (two-layer; specs verbatim) ─────────────────────
function Composer({ field, chipLabel, chipCap, chipDimmed, chipLeadRing, action, leftGauge, rightGauge, caption }){
  const lines = field==='empty' ? null : (Array.isArray(field)?field:[field]);
  return (
    <div style={{ background:T.base, borderTop:`1px solid ${T.border}`, padding:'10px 16px 8px' }}>
      {caption && (
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 2px 8px' }}>
          <AlertTri c={CRIT} s={13}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, color:CRIT, fontWeight:500 }}>Context full — oldest turns will start dropping</span>
        </div>
      )}
      <div style={{ width:'100%', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:'11px 14px', minHeight:44, display:'flex', alignItems:'flex-start' }}>
        {lines
          ? <div style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.text }}>{lines.map((l,i)=><div key={i}>{l}</div>)}</div>
          : <span style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.muted }}>Message Claude…</span>}
      </div>
      <div style={{ display:'flex', alignItems:'center', marginTop:6, minHeight:HIT }}>
        <div style={{ display:'flex', alignItems:'center', gap:6, minWidth:0 }}>
          <button style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, marginLeft:-8, borderRadius:999, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}><Plus c={T.sec}/></button>
          <ModelChip label={chipLabel} cap={chipCap} dimmed={chipDimmed} leadRing={chipLeadRing}/>
          {leftGauge}
        </div>
        <span style={{ flex:1 }}/>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginRight:-6 }}>
          {rightGauge}
          {action==='mic' && <ActionBtn kind="mic"/>}
          {action==='send' && <ActionBtn kind="send"/>}
          {action==='stop+send' && <><ActionBtn kind="stop"/><ActionBtn kind="send"/></>}
        </div>
      </div>
    </div>
  );
}

// ── greeked stream ────────────────────────────────────────────
function Greek({ turns }){
  return (
    <div style={{ flex:1, minHeight:0, overflow:'hidden', padding:'14px 16px 10px', display:'flex', flexDirection:'column', gap:14, justifyContent:'flex-end', opacity:0.5 }}>
      {turns.map((t,i)=>(
        <div key={i} style={{ alignSelf:t.who==='you'?'flex-end':'flex-start', maxWidth:'78%', display:'flex', flexDirection:'column', gap:6, alignItems:t.who==='you'?'flex-end':'flex-start' }}>
          {t.who==='you'
            ? <div style={{ background:T.raised, border:`1px solid ${T.border}`, borderRadius:16, padding:'9px 13px', display:'flex', flexDirection:'column', gap:6 }}>{t.w.map((w,j)=><div key={j} style={{ height:8, width:w, borderRadius:999, background:T.muted }}/>)}</div>
            : <>{t.w.map((w,j)=><div key={j} style={{ height:8, width:w, borderRadius:999, background:T.border }}/>)}</>}
        </div>
      ))}
    </div>
  );
}
const STREAM = [ { who:'claude', w:['92%','70%'] }, { who:'you', w:['80%'] }, { who:'claude', w:['96%','84%','52%'] } ];

function Phone({ frame=390, scale=0.9, h=340, children }){
  return (
    <div style={{ width:frame*scale, flexShrink:0 }}>
      <div style={{ width:frame, transform:`scale(${scale})`, transformOrigin:'top left', height:h/scale }}>
        <IOSDevice dark width={frame} height={h}><div style={{ position:'relative', height:'100%' }}>{children}</div></IOSDevice>
      </div>
    </div>
  );
}
function Screen({ children }){
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ height:44, flexShrink:0 }}/>
      <Greek turns={STREAM}/>
      {children}
    </div>
  );
}

// ── comparison-strip cell ─────────────────────────────────────
function CompareCell({ tag, rec, title, sub, verdict, children }){
  return (
    <div style={{ flex:'1 1 300px', minWidth:280, maxWidth:390 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:6 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:rec?T.accent:T.sec }}>{tag}</span>
        {rec && <span style={{ fontFamily:T.mono, fontSize:9.5, fontWeight:700, color:T.base, background:T.accent, borderRadius:4, padding:'2px 6px', letterSpacing:0.5 }}>RECOMMENDED</span>}
      </div>
      <h3 style={{ fontFamily:T.ui, fontSize:15, fontWeight:700, color:T.text, letterSpacing:-0.2, margin:'0 0 3px' }}>{title}</h3>
      <p style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.muted, margin:'0 0 12px' }}>{sub}</p>
      <div style={{ border:`1px solid ${rec?T.accent:T.border}`, borderRadius:16, padding:12, background:'#0B0C0D' }}>
        <Phone frame={390} scale={0.7} h={300}><Screen>{children}</Screen></Phone>
      </div>
      <p style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:verdict.good?T.success:T.muted, margin:'11px 0 0' }}>
        <strong style={{ color:verdict.good?T.success:T.accent }}>{verdict.good?'Below control ✓ · ':'Reads as control ✗ · '}</strong>{verdict.text}
      </p>
    </div>
  );
}
function StateCard({ n, label, note, frame=390, children }){
  return (
    <div style={{ display:'flex', flexDirection:'column', width:frame*0.9 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:8, marginBottom:8 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:T.accent }}>{n}</span>
        <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone frame={frame} h={frame===375?312:300}><Screen>{children}</Screen></Phone>
      <p style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, margin:'10px 2px 0' }}>{note}</p>
    </div>
  );
}

// ── measurement overlay (the proof artifact) ──────────────────
function MeasureCrop(){
  const S = 3; // px scale of the row zoom
  const bandTop = 60, bandH = 30*S; // 90px band
  return (
    <div style={{ position:'relative', background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:16, padding:'54px 40px 46px', overflow:'hidden' }}>
      {/* the 30pt control band guides */}
      <div style={{ position:'absolute', left:0, right:0, top:bandTop, borderTop:`1px dashed ${T.accent}`, opacity:0.6 }}/>
      <div style={{ position:'absolute', left:0, right:0, top:bandTop+bandH, borderTop:`1px dashed ${T.accent}`, opacity:0.6 }}/>
      <div style={{ position:'absolute', left:12, top:bandTop-18, fontFamily:T.mono, fontSize:11, color:T.accent }}>30pt control band — pill top</div>
      <div style={{ position:'absolute', left:12, top:bandTop+bandH+6, fontFamily:T.mono, fontSize:11, color:T.accent }}>30pt control band — pill bottom</div>

      {/* the row, scaled up */}
      <div style={{ display:'flex', alignItems:'center', gap:6*S, transform:'translateY(0)' }}>
        <div style={{ display:'flex', alignItems:'center', height:30*S, padding:`0 ${8*S}px 0 ${9*S}px`, borderRadius:999, background:T.raised, border:`1px solid ${T.border}`, gap:6*S }}>
          <span style={{ fontFamily:T.mono, fontSize:11*S, color:T.sec }}>fable</span>
          <ChevUp c={T.muted} s={12*S}/>
        </div>
        <div style={{ display:'flex', alignItems:'center', height:30*S, padding:`0 ${11*S}px`, borderRadius:999, background:T.raised, border:`1px solid ${T.border}`, gap:6*S }}>
          <Stack c={T.sec} s={14*S}/>
          <span style={{ fontFamily:T.mono, fontSize:11*S, color:T.sec }}>3</span>
        </div>
        {/* the gauge — fill, no border */}
        <div style={{ position:'relative', width:30*S, height:30*S, borderRadius:999, background:T.raised, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <Ring pct={42} color={T.sec} size={13*S} stroke={2.2*S}/>
          <div style={{ position:'absolute', inset:-1, borderRadius:999, outline:`1.5px dashed ${T.success}`, outlineOffset:0 }}/>
        </div>
      </div>

      {/* annotations */}
      <div style={{ position:'absolute', right:34, top:bandTop-2, display:'flex', flexDirection:'column', alignItems:'flex-end', gap:2 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.success }}>gauge capsule 30×30 · fills the band edge-to-edge</span>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>same raised fill as pills · NO hairline border</span>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>ring 13pt / stroke 2.2 · optically centred</span>
      </div>
    </div>
  );
}

function Page(){
  return (
    <div style={{ maxWidth:1360, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat · composer thread #5</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Fixing the occupancy gauge’s vertical rhythm</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:900, margin:'0 0 6px' }}>
        The chrome-less gauge shipped as a bare <span style={{ fontFamily:T.mono, fontSize:13 }}>15pt</span> ring in a 44pt hit box — the only element in the row with no bounding shape. Against two 30pt pills and three 44pt buttons it scans at roughly half their height, and at calm (its near-permanent state) a lone small circle reads as a stray dot. It only looks right at ≥80%, when the amber number finally gives it mass. The fix must lock the mark onto the <strong style={{ color:T.text }}>30pt control-band rhythm</strong> without promoting an ambient readout into a fourth control — so it can’t just become the bordered pill that thread #3 rejected. Ambient stays below control; send stays the single loudest thing.
      </p>

      {/* 1 · comparison strip */}
      <section style={{ marginTop:44 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:'0 0 4px' }}>1 · Four forms, calm 42% (the broken state)</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:900, margin:'0 0 24px' }}>The one test: does it stay <em>below</em> the model chip in hierarchy, or does it climb up to read as a control?</p>
        <div style={{ display:'flex', flexWrap:'wrap', gap:28, alignItems:'flex-start' }}>
          <CompareCell tag="D1" rec title="Fill without border" sub="30pt raised capsule, no hairline"
            verdict={{ good:true, text:'joins the band height exactly, but the missing border keeps it a tier under the hairline-bordered chips — a soft block, not a control. Solves the rhythm at its root.' }}>
            <Composer field="empty" chipLabel="fable" action="mic" leftGauge={<GaugeFill pct={42}/>}/>
          </CompareCell>
          <CompareCell tag="D2" title="Optical mass, no chrome" sub="20pt ring + permanent quiet number"
            verdict={{ good:false, text:'bigger silhouette helps, but a permanent number pre-empts the ≥80% escalation signal, and with no shape it still floats loose between two crisp pills.' }}>
            <Composer field="empty" chipLabel="fable" action="mic" leftGauge={<GaugeMass pct={42}/>}/>
          </CompareCell>
          <CompareCell tag="D3" title="Leave the pill lane" sub="bare 15pt ring, right of the elastic gap"
            verdict={{ good:false, text:'honest among 44pt buttons — but it parks ambient info against the loudest cluster, one thumb from send, exactly where quiet doesn’t belong.' }}>
            <Composer field="empty" chipLabel="fable" action="mic" rightGauge={<GaugeBare pct={42}/>}/>
          </CompareCell>
          <CompareCell tag="D4" title="Fold into the model chip" sub="leading ring inside the existing pill"
            verdict={{ good:false, text:'loses an object cleanly at rest, but couples two tap intents and — see state (e) — a long gateway id has no room to also carry a ring. Fails the 375pt case.' }}>
            <Composer field="empty" chipLabel="fable" action="mic" chipLeadRing={{ pct:42, known:true }}/>
          </CompareCell>
        </div>
      </section>

      <p style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text, maxWidth:900, margin:'36px 0 0' }}>
        <strong style={{ color:T.accent }}>Recommendation — D1, fill without border.</strong> In this design system a chip is <em>raised fill + hairline</em>; dropping the hairline drops it one rung — a fill-only capsule reads as a passive slot, not an actionable control, while still sitting exactly on the 30pt band. It’s the one form that fixes the rhythm without replaying the rejected pill.
      </p>

      {/* 3 · measurement proof (placed high — it is the ticket) */}
      <section style={{ marginTop:52 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:'0 0 4px' }}>2 · The rhythm, proven</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:900, margin:'0 0 22px' }}>Row zoomed 3×, calm. The gauge capsule’s top and bottom land on the same dashed lines as the pills — the mark no longer reads as debris because it occupies the full band, yet carries no border to claim control status.</p>
        <MeasureCrop/>
      </section>

      {/* 2 · five states */}
      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:'0 0 4px' }}>3 · D1 across five states</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:900, margin:'0 0 30px' }}>Escalation ladder, unknown-window fallback, width-shedding order and the 44pt tap target are all unchanged from thread #3 — only the resting silhouette gains the 30pt capsule.</p>
        <div style={{ display:'flex', flexWrap:'wrap', gap:38, alignItems:'flex-start' }}>
          <StateCard n="a" label="Calm · 42% · idle"
            note="Ring inside a 30×30 raised capsule, no number, no border. Full band height → reads as a quiet slot, not a stray dot. Tap still opens Session Info.">
            <Composer field="empty" chipLabel="fable" action="mic" leftGauge={<GaugeFill pct={42}/>}/>
          </StateCard>
          <StateCard n="b" label="Warning · 88% · text staged"
            note="Crosses 80%: capsule grows rightward to admit the amber number (pad 0 10 0 9, gap 5). Amber, but smaller and un-accented — below the terracotta send.">
            <Composer field={["and bump the retry ceiling","to 8 once that lands"]} chipLabel="fable" action="send" leftGauge={<GaugeFill pct={88}/>}/>
          </StateCard>
          <StateCard n="c" label="Critical · 97% · generating"
            note="Tightest case: ■ stop + send both present. Capsule red with number; the slim red caption fires above the field only here, at ≥95%.">
            <Composer field={["squash the last flake then ship"]} chipLabel="fable" chipDimmed action="stop+send" leftGauge={<GaugeFill pct={97}/>} caption/>
          </StateCard>
          <StateCard n="d" label="Unknown window · ~84k"
            note="Non-Claude backend. Capsule holds a hollow ring + raw ~84k in mono — never a fabricated percentage. Same 30pt height.">
            <Composer field="empty" chipLabel="glm-4.6" chipCap={130} action="mic" leftGauge={<GaugeFill known={false} raw="84k"/>}/>
          </StateCard>
          <StateCard n="e" label="375pt worst case" frame={375}
            note="Long gateway id + stack 3 + generating + 88%. Under width pressure the gauge sheds its NUMBER first and collapses back to the 30×30 ring capsule. Model chip, stack, stop, send never move.">
            <Composer field={["ship it"]} chipLabel={midTrunc('deepseek-chat',6,4)} chipCap={132} chipDimmed action="stop+send"
              leftGauge={<><StackChip count={3}/><GaugeFill pct={88} degraded/></>}/>
          </StateCard>
        </div>
      </section>

      {/* spec table */}
      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34, maxWidth:900 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:18, fontWeight:700, color:T.text, margin:'0 0 16px' }}>Implementation spec · D1 (every value 1:1)</h2>
        <div style={{ display:'grid', gridTemplateColumns:'190px 1fr', rowGap:9, columnGap:20 }}>
          {[
            ['Capsule height','30pt (matches model / stack pills)'],
            ['Capsule fill','raised #1E2125 — identical to pills'],
            ['Capsule border','NONE (this is the whole hierarchy move)'],
            ['Capsule radius','999 (full pill)'],
            ['Calm / degraded width','30pt fixed → 30×30 rounded square, ring centred'],
            ['With-number padding','0 · 10 · 0 · 9  (t·r·b·l), gap 5 between ring & number'],
            ['Ring size / stroke','13pt / 2.2 (unchanged from thread #3)'],
            ['Number type','JetBrains Mono 11 / weight 500 / letter-spacing 0.2'],
            ['Row gap to stack chip','6pt (unchanged left-cluster gap)'],
            ['Hit target','≥44pt — capsule sits in a 44pt touch slot; glyph stays 30pt'],
            ['Colours','calm #9BA1A6 · warn #E0A93B · crit #E5604D · track #2A2E33'],
            ['Shed order','drop number → 30×30 ring capsule; chips & buttons fixed'],
          ].map(([k,v])=>(
            <React.Fragment key={k}>
              <div style={{ fontFamily:T.ui, fontSize:13, color:T.sec }}>{k}</div>
              <div style={{ fontFamily:T.mono, fontSize:12.5, color:T.text }}>{v}</div>
            </React.Fragment>
          ))}
        </div>
        <p style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.muted, margin:'20px 0 0' }}>
          <strong style={{ color:T.accent }}>What did NOT change:</strong> thresholds, the escalation ladder, the unknown-window fallback, the number-first shed order, the 44pt tap-through to Session Info, and every pixel of the field, pills and action buttons. The only edit is wrapping the existing ring in a borderless 30pt raised capsule so it holds the band.
        </p>
      </section>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
