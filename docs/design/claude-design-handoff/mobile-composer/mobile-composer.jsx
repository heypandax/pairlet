// cc-pocket — Mobile composer redesign (issue #157 follow-up)
// Two-layer composer: full-width field on top, accessory row (attach · chip · action) below.
// Reuses the Model Chip pill spec + desktop tokens. Imports ios-frame.jsx for IOSDevice.

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const ChevUp = ({ c=T.muted, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3 9l4-4 4 4" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Plus = ({ c=T.sec, s=20 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M10 4v12M4 10h12" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>;
const SendArrow = ({ c='#0E0F11', s=19 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M10 16V5M10 5l-4.6 4.6M10 5l4.6 4.6" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Mic = ({ c=T.sec, s=20 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><rect x="7.4" y="2.6" width="5.2" height="9.6" rx="2.6" stroke={c} strokeWidth="1.7"/><path d="M4.6 9.4a5.4 5.4 0 0010.8 0M10 14.8v2.6" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>;
const Stop = ({ c=T.accent, s=16 }) => <svg width={s} height={s} viewBox="0 0 16 16"><rect x="3" y="3" width="10" height="10" rx="2.4" fill={c}/></svg>;

// middle-truncate long ids: keep head + … + tail
function midTrunc(s, head=8, tail=4) {
  if (s.length <= head+tail+1) return s;
  return s.slice(0, head) + '…' + s.slice(-tail);
}

// ── the composer model chip (identical pixel spec to Model Chip screen,
//    label cap relaxed to ~120 now that the row is its own) ──────
function ModelChip({ label, open, dimmed, cap=120 }) {
  return (
    <button disabled={dimmed} style={{
      all:'unset', boxSizing:'border-box', cursor: dimmed?'default':'pointer', flexShrink:0,
      display:'flex', alignItems:'center', gap:5, height:30, padding:'0 8px 0 10px', borderRadius:999,
      background:T.raised, border:`1px solid ${open?T.accent:T.border}`, opacity: dimmed?0.42:1,
    }}>
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, maxWidth:cap, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{label}</span>
      <span style={{ display:'flex', transform: open?'rotate(180deg)':'none', transition:'transform .18s ease' }}><ChevUp c={T.muted} s={12}/></span>
    </button>
  );
}

// ── round 44pt action buttons ─────────────────────────────────
const HIT = 44;
function ActionBtn({ kind }) {
  // kind: 'mic' | 'send' | 'stop'
  const base = { all:'unset', cursor:'pointer', width:HIT, height:HIT, borderRadius:999, flexShrink:0,
    display:'flex', alignItems:'center', justifyContent:'center' };
  if (kind==='send') return <button style={{ ...base, background:T.accent }}><SendArrow c="#0E0F11"/></button>;
  if (kind==='stop') return <button style={{ ...base, background:T.surface, border:`1px solid ${T.border}` }}><Stop/></button>;
  return <button style={{ ...base, background:'transparent', border:`1px solid ${T.border}` }}><Mic c={T.sec}/></button>;
}

// ── the two-layer composer ────────────────────────────────────
// field: 'empty' | one string | array of lines (wrapped)
// action: 'mic' | 'send' | 'stop+send'
function Composer({ field, chipLabel, chipOpen, chipDimmed, chipCap, action }) {
  const lines = field==='empty' ? null : (Array.isArray(field) ? field : [field]);
  return (
    <div style={{ background:T.base, borderTop:`1px solid ${T.border}`, padding:'10px 16px 8px' }}>
      {/* Layer 1 — full-width text field */}
      <div style={{ width:'100%', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14,
        padding:'11px 14px', minHeight:44, display:'flex', alignItems:'flex-start' }}>
        {lines
          ? <div style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.text }}>
              {lines.map((l,i)=><div key={i}>{l}</div>)}
            </div>
          : <span style={{ fontFamily:T.ui, fontSize:15, lineHeight:'21px', color:T.muted }}>Message Claude…</span>}
      </div>
      {/* Layer 2 — accessory row */}
      <div style={{ display:'flex', alignItems:'center', marginTop:6, minHeight:HIT }}>
        {/* left cluster */}
        <div style={{ display:'flex', alignItems:'center', gap:6 }}>
          <button style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, marginLeft:-8, borderRadius:999,
            display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}><Plus c={T.sec}/></button>
          <ModelChip label={chipLabel} open={chipOpen} dimmed={chipDimmed} cap={chipCap}/>
        </div>
        <span style={{ flex:1 }}/>
        {/* right cluster — action slot */}
        <div style={{ display:'flex', alignItems:'center', gap:8, marginRight:-6 }}>
          {action==='mic' && <ActionBtn kind="mic"/>}
          {action==='send' && <ActionBtn kind="send"/>}
          {action==='stop+send' && <><ActionBtn kind="stop"/><ActionBtn kind="send"/></>}
        </div>
      </div>
    </div>
  );
}

// ── greeked message stream above each composer slice ──────────
function Greek({ turns }) {
  // turns: array of {who:'you'|'claude', w:[widths...]}
  return (
    <div style={{ flex:1, minHeight:0, padding:'16px 16px 12px', display:'flex', flexDirection:'column', gap:16,
      justifyContent:'flex-end', opacity:0.5 }}>
      {turns.map((t,i)=>(
        <div key={i} style={{ alignSelf: t.who==='you'?'flex-end':'flex-start', maxWidth:'78%',
          display:'flex', flexDirection:'column', gap:6, alignItems: t.who==='you'?'flex-end':'flex-start' }}>
          {t.who==='you'
            ? <div style={{ background:T.raised, border:`1px solid ${T.border}`, borderRadius:16, padding:'9px 13px', display:'flex', flexDirection:'column', gap:6 }}>
                {t.w.map((w,j)=><div key={j} style={{ height:8, width:w, borderRadius:999, background:T.muted }}/>)}
              </div>
            : <>{t.w.map((w,j)=><div key={j} style={{ height:8, width:w, borderRadius:999, background:T.border }}/>)}</>}
        </div>
      ))}
    </div>
  );
}

// ── one labelled state slice ──────────────────────────────────
function Slice({ n, label, note, stream, composer, keyboard }) {
  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      {/* status-bar spacer, then caption below it */}
      <div style={{ height:52, flexShrink:0 }}/>
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 16px 4px' }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:600, color:T.accent }}>{n}</span>
        <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Greek turns={stream}/>
      {composer}
      {note && <div style={{ fontFamily:T.ui, fontSize:11.5, lineHeight:'16px', color:T.muted, padding:'0 16px 10px', background:T.base }}>{note}</div>}
      {keyboard}
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
const FRAME = 390, SCALE = 0.9;
function Phone({ children }) {
  return (
    <div style={{ width:FRAME*SCALE, flexShrink:0 }}>
      <div style={{ width:FRAME, transform:`scale(${SCALE})`, transformOrigin:'top left', height:640/SCALE }}>
        <IOSDevice dark width={FRAME} height={640}><div style={{ position:'relative', height:'100%' }}>{children}</div></IOSDevice>
      </div>
    </div>
  );
}

const STREAM_A = [
  { who:'you', w:['84%','62%'] },
  { who:'claude', w:['96%','88%','54%'] },
];
const STREAM_B = [
  { who:'claude', w:['92%','70%'] },
  { who:'you', w:['78%'] },
];
const STREAM_C = [
  { who:'you', w:['70%','48%'] },
  { who:'claude', w:['94%','82%','90%','40%'] },
];
const STREAM_D = [
  { who:'claude', w:['88%','60%'] },
  { who:'you', w:['66%','52%'] },
];

function Card({ n, label, note, stream, composer }) {
  return (
    <div style={{ display:'flex', flexDirection:'column' }}>
      <Phone>
        <Slice n={n} label={label} note={note} stream={stream} composer={composer}/>
      </Phone>
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1320, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat · issue #157</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Mobile composer — two-layer restack</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:780, margin:'0 0 8px' }}>
        The shipped model chip rode the single composer row and squeezed the text field to ~110–160dp on a 375pt phone. It now moves down a level: the field takes the <strong style={{color:T.text}}>full composer width</strong> on top, and a slim accessory row below carries the <strong style={{color:T.text}}>+ attach</strong> and the <strong style={{color:T.text}}>model chip</strong> on the left with the action button on the right. Tapping the chip still opens the existing Model Picker sheet — same quiet pill, same mid-truncation, chevron flips while its sheet is open. Desktop keeps its one-row layout; it has the width.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:36, marginTop:44 }}>
        <Card n="1" label="Idle · empty" stream={STREAM_A}
          note="Field holds the placeholder full width. Chip “fable”, mic on the right (outline circle)."
          composer={<Composer field="empty" chipLabel="fable" action="mic"/>} />
        <Card n="2" label="Text · wrapped to 2 lines" stream={STREAM_B}
          note="Field grows upward as text wraps; send fills terracotta. Chip unchanged."
          composer={<Composer field={["can you also add a regression test that", "kills the socket mid-backoff?"]} chipLabel="fable" action="send"/>} />
        <Card n="3" label="Generating · queued text" stream={STREAM_C}
          note="Chip dimmed to 42% — switch applies next turn. ■ stop + send sit side by side; queueing mid-turn is allowed."
          composer={<Composer field={["and once that lands, bump the retry", "ceiling to 8"]} chipLabel="fable" chipDimmed action="stop+send"/>} />
        <Card n="4" label="Gateway model · long id" stream={STREAM_D}
          note="Chip label mid-truncates to “deepseek…chat”; full id lives in the picker. Everything else idle."
          composer={<Composer field="empty" chipLabel={midTrunc('deepseek-chat',6,4)} chipCap={140} action="mic"/>} />
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
