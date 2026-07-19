// cc-pocket — Context-window occupancy placement · composer thread #3
// Retire the floating "Context 42%" debug pill; give occupancy a home INLINE on
// the accessory row. Reuses the two-layer composer + model/stack pill grammar.

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const WARN = '#E0A93B', CRIT = '#E5604D';
const HIT = 44;

// ── icons ─────────────────────────────────────────────────────
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

// ── the occupancy ring (arc glyph) ────────────────────────────
function Ring({ pct=0, color, track=T.border, size=15, stroke=2.2, unknown=false }){
  const r=(size-stroke)/2, c=size/2, circ=2*Math.PI*r, off=circ*(1-pct/100);
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ display:'block', flexShrink:0 }}>
      <circle cx={c} cy={c} r={r} fill="none" stroke={track} strokeWidth={stroke}/>
      {!unknown && <circle cx={c} cy={c} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={circ} strokeDashoffset={off} transform={`rotate(-90 ${c} ${c})`}/>}
    </svg>
  );
}

// ── OPTION C · the recommended gauge ──────────────────────────
// Chrome-less readout. Calm = bare ring, no number. ≥80% the number appears (amber),
// ≥95% red. Unknown window = hollow ring + raw "~84k", never a %. `degraded` drops
// the number under width pressure, leaving the color-carrying ring.
function Gauge({ pct, raw, known=true, degraded=false }){
  const level = !known ? 'unk' : pct>=95 ? 'crit' : pct>=80 ? 'warn' : 'calm';
  const color = level==='crit' ? CRIT : level==='warn' ? WARN : T.sec;
  const showNum = known && level!=='calm' && !degraded;
  return (
    <button aria-label="Context usage — open session info" style={{ all:'unset', cursor:'pointer', flexShrink:0,
      display:'flex', alignItems:'center', gap:5, height:30, padding:'0 3px', minWidth:HIT-14, justifyContent:'center' }}>
      <Ring pct={known?pct:0} color={color} unknown={!known}/>
      {showNum && <span style={{ fontFamily:T.mono, fontSize:11, color, fontWeight:500, letterSpacing:0.2 }}>{pct}%</span>}
      {!known && <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, fontWeight:500 }}>~{raw}</span>}
    </button>
  );
}

// ── OPTION A · a third quiet pill, model/stack grammar ────────
function OccPill({ pct }){
  const level = pct>=95?'crit':pct>=80?'warn':'calm';
  const color = level==='crit'?CRIT:level==='warn'?WARN:T.sec;
  return (
    <button style={{ all:'unset', cursor:'pointer', flexShrink:0, display:'flex', alignItems:'center', gap:6,
      height:30, padding:'0 10px 0 8px', borderRadius:999, background:T.raised, border:`1px solid ${T.border}` }}>
      <Ring pct={pct} color={color} size={14}/>
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, fontWeight:500 }}>{pct}%</span>
    </button>
  );
}

// ── shared row parts ──────────────────────────────────────────
function ModelChip({ label, dimmed, cap=120 }){
  return (
    <button disabled={dimmed} style={{ all:'unset', boxSizing:'border-box', cursor:dimmed?'default':'pointer', flexShrink:0,
      display:'flex', alignItems:'center', gap:5, height:30, padding:'0 8px 0 10px', borderRadius:999,
      background:T.raised, border:`1px solid ${T.border}`, opacity:dimmed?0.42:1 }}>
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

// ── the two-layer composer ────────────────────────────────────
// leftExtra: node(s) appended to the left cluster after the model chip.
// caption: full-width line above the field (folded critical strip).
// fieldRule: {pct,color} → Option B seam meter under the field.
function Composer({ field, chipLabel, chipCap, chipDimmed, action, leftExtra, caption, fieldRule }){
  const lines = field==='empty' ? null : (Array.isArray(field)?field:[field]);
  return (
    <div style={{ background:T.base, borderTop:`1px solid ${T.border}`, padding:'10px 16px 8px' }}>
      {caption && (
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 2px 8px' }}>
          <AlertTri c={CRIT} s={13}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, color:CRIT, fontWeight:500 }}>Context full — oldest turns will start dropping</span>
        </div>
      )}
      <div style={{ position:'relative', width:'100%', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14,
        padding:'11px 14px', minHeight:44, display:'flex', alignItems:'flex-start' }}>
        {lines
          ? <div style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.text }}>{lines.map((l,i)=><div key={i}>{l}</div>)}</div>
          : <span style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.muted }}>Message Claude…</span>}
      </div>
      {fieldRule && (
        <div style={{ height:3, borderRadius:999, background:T.border, margin:'5px 3px 0', overflow:'hidden' }}>
          <div style={{ width:`${fieldRule.pct}%`, height:'100%', background:fieldRule.color, borderRadius:999 }}/>
        </div>
      )}
      <div style={{ display:'flex', alignItems:'center', marginTop:6, minHeight:HIT }}>
        <div style={{ display:'flex', alignItems:'center', gap:6, minWidth:0 }}>
          <button style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, marginLeft:-8, borderRadius:999, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}><Plus c={T.sec}/></button>
          <ModelChip label={chipLabel} cap={chipCap} dimmed={chipDimmed}/>
          {leftExtra}
        </div>
        <span style={{ flex:1 }}/>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginRight:-6 }}>
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

// ── phone ─────────────────────────────────────────────────────
function Phone({ frame=390, scale=0.9, h=360, children }){
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

// ── column (one representative frame) ─────────────────────────
function Column({ tag, rec, title, blurb, verdict, children }){
  return (
    <section style={{ flex:'1 1 300px', minWidth:280, maxWidth:400 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:7 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:rec?T.accent:T.sec, letterSpacing:0.5 }}>{tag}</span>
        {rec && <span style={{ fontFamily:T.mono, fontSize:9.5, fontWeight:700, color:T.base, background:T.accent, borderRadius:4, padding:'2px 6px', letterSpacing:0.5 }}>RECOMMENDED</span>}
      </div>
      <h3 style={{ fontFamily:T.ui, fontSize:16, fontWeight:700, color:T.text, letterSpacing:-0.2, margin:'0 0 6px' }}>{title}</h3>
      <p style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.sec, margin:'0 0 14px' }}>{blurb}</p>
      <div style={{ border:`1px solid ${rec?T.accent:T.border}`, borderRadius:16, padding:14, background:'#0B0C0D' }}>
        {children}
      </div>
      <p style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:verdict.good?T.success:T.muted, margin:'12px 0 0' }}>
        <strong style={{ color:verdict.good?T.success:T.accent }}>{verdict.good?'Wins · ':'Loses · '}</strong>{verdict.text}
      </p>
    </section>
  );
}

// ── deep-dive state card (recommended, 5 states) ──────────────
function StateCard({ n, label, note, frame=390, children }){
  return (
    <div style={{ display:'flex', flexDirection:'column', width:frame*0.9 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:8, marginBottom:8 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:T.accent }}>{n}</span>
        <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone frame={frame} h={frame===375?372:360}><Screen>{children}</Screen></Phone>
      <p style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, margin:'10px 2px 0' }}>{note}</p>
    </div>
  );
}

function Page(){
  return (
    <div style={{ maxWidth:1360, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat · composer thread #3</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Where context-window occupancy lives</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:880, margin:'0 0 6px' }}>
        Today a mono pill <span style={{ fontFamily:T.mono, fontSize:13, color:T.muted }}>Context 42%</span> floats over the bottom-right of the stream at 85% opacity — costs no layout height but hovers over content, isn't tappable, and reads like a debug overlay. A separate full-width amber strip fires above the composer at ≥90%. The accessory row now has a home for this. Occupancy is <strong style={{ color:T.text }}>ambient</strong> information: it must sit inline, escalate through three levels (calm → amber ≥80% → red ≥95%), survive an unknown denominator without ever faking a %, tap through to the existing Session Info sheet — and <strong style={{ color:T.text }}>degrade before send, the model chip, or the stack chip do</strong>. Three homes below; recommendation rendered across five states.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:34, marginTop:44, alignItems:'flex-start' }}>
        <Column tag="OPTION A" title="A third quiet pill in the left cluster"
          blurb="Same grammar as the model/stack chips — hairline on raised, mono 11, a small ring + percent. Legible and obviously a tap target."
          verdict={{ good:false, text:'adds a whole third pill to a row that is already two chips + three 44pt targets on a 375pt phone. A bordered pill reads as a control equal to the model chip, so ambient readout starts shouting — and it has no graceful way to yield width. Percent-only also can’t express the unknown-window case.' }}>
          <Phone frame={390} scale={0.62} h={360}><Screen>
            <Composer field="empty" chipLabel="fable" action="mic" leftExtra={<OccPill pct={42}/>}/>
          </Screen></Phone>
        </Column>

        <Column tag="OPTION B" title="A hairline meter along the field’s seam"
          blurb="No object at all — a 3pt rule in the dead space between the text field and the accessory row fills left-to-right, coloured by level. Zero row width."
          verdict={{ good:false, text:'beautiful and weightless, but a 3pt line is not a tap target and can’t open Session Info, can’t show a number, and can’t render "~84k" when the window is unknown. So it still needs the full-width amber strip to carry escalation — it doesn’t retire anything.' }}>
          <Phone frame={390} scale={0.62} h={360}><Screen>
            <Composer field={["kill the socket mid-backoff and","assert the retry ceiling holds"]} chipLabel="fable" action="send"
              fieldRule={{ pct:84, color:WARN }}
              caption={null}/>
          </Screen></Phone>
        </Column>

        <Column tag="OPTION C" rec title="A chrome-less gauge that grows only under pressure"
          blurb="A bordered-less ring readout, not a chip. Calm: a bare ambient ring, no number. It gains its percent only at ≥80%, reddening at ≥95%, and drops that number first when width runs out — a floor no chip touches."
          verdict={{ good:true, text:'ambient by default (no chrome = doesn’t compete with send), earns a glance by growing not shouting, degrades label-first so chips and send never move, taps through to Session Info, and its hollow-ring + "~84k" fallback handles unknown windows honestly.' }}>
          <Phone frame={390} scale={0.62} h={360}><Screen>
            <Composer field={["kill the socket mid-backoff and","assert the retry ceiling holds"]} chipLabel="fable" action="send" leftExtra={<Gauge pct={84}/>}/>
          </Screen></Phone>
        </Column>
      </div>

      <p style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text, maxWidth:880, margin:'40px 0 0' }}>
        <strong style={{ color:T.accent }}>Recommendation — Option C.</strong> It is the only form that is quiet at rest, escalates by <em>growing</em> instead of by adding chrome, has a defined graceful floor (drop the number → bare coloured ring), and answers the unknown-denominator case without ever printing a fake percent.
      </p>

      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:'0 0 6px' }}>Option C · the gauge across five states</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:880, margin:'0 0 30px' }}>
          The gauge is the last item in the left cluster, so it sits against the elastic gap and is the natural thing to shed. It carries no border or fill — that is what keeps it from reading as a fourth control one thumb from Send.
        </p>
        <div style={{ display:'flex', flexWrap:'wrap', gap:38, alignItems:'flex-start' }}>
          <StateCard n="1" label="Calm · 42% · idle"
            note="Bare ring, no number, no chrome — ambient texture. Empty field, mic. Nothing to read unless you look; tap still opens Session Info.">
            <Composer field="empty" chipLabel="fable" action="mic" leftExtra={<Gauge pct={42}/>}/>
          </StateCard>

          <StateCard n="2" label="Warning · 84% · text staged"
            note="Crosses 80%: the ring goes amber and its number appears. It grew to earn a glance — still quieter and smaller than the terracotta send.">
            <Composer field={["and once that lands, bump the","retry ceiling to 8"]} chipLabel="fable" action="send" leftExtra={<Gauge pct={84}/>}/>
          </StateCard>

          <StateCard n="3" label="Critical · 97% · generating"
            note="Tightest case: ■ stop + send both present. Ring is red with its number; the old full-width strip is folded into one slim red caption above the field — it fires only here, at critical, not at 90%.">
            <Composer field={["squash the last flake then ship"]} chipLabel="fable" chipDimmed action="stop+send" leftExtra={<Gauge pct={97}/>}
              caption/>
          </StateCard>

          <StateCard n="4" label="Unknown window · ~84k"
            note="Non-Claude backend we can’t size. Hollow ring (the stated fallback) + raw ~84k in mono. Never a percentage, never a fake ring fill.">
            <Composer field="empty" chipLabel="glm-4.6" chipCap={130} action="mic" leftExtra={<Gauge known={false} raw="84k"/>}/>
          </StateCard>

          <StateCard n="5" label="375pt worst case · everything on" frame={375}
            note="375pt: long gateway id, stack count 3, generating — all at once, at 88%. Occupancy yields FIRST: it drops its number to a bare amber ring (~15pt). Model chip, stack, stop and send all keep full size.">
            <Composer field={["ship it"]} chipLabel={midTrunc('deepseek-chat',6,4)} chipCap={140} chipDimmed action="stop+send"
              leftExtra={<><StackChip count={3}/><Gauge pct={88} degraded/></>}/>
          </StateCard>
        </div>
      </section>

      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34, maxWidth:880 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:18, fontWeight:700, color:T.text, margin:'0 0 12px' }}>The full-width amber strip — folded to critical only</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, margin:'0 0 12px' }}>
          The strip existed because the floating pill was too quiet to warn with. Now the gauge itself carries the ≥80% warning inline (amber ring + number), so the heavy full-width block at ≥90% is <strong style={{ color:T.text }}>retired</strong>. Its job — “you are about to lose turns” — is real only at the critical step, so it survives as a <strong style={{ color:T.text }}>single slim caption</strong> above the field, firing at ≥95% (state 3). One escalation channel, three volumes: ring → amber number → red number + caption.
        </p>
        <p style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.muted, margin:0 }}>
          <strong style={{ color:T.accent }}>Trading away:</strong> cold-start discoverability. A chrome-less ring is quieter than a bordered pill; a first-time user may not notice occupancy until it ambers. That is the correct trade for ambient info that must never out-shout Send — and the escalation path guarantees it gets loud exactly when, and only when, it needs to.
        </p>
      </section>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
