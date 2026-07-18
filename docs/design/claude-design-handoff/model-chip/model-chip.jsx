// cc-pocket — Composer model chip + shallow model picker entrance
// Reuses the existing model-picker / gateway-preset grammar. Imports ios-frame.jsx for IOSDevice.

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Check = ({ c=T.accent, s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Tick = ({ c=T.accent, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.5 7.5l2.6 2.6L11.5 3.5" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const ChevUp = ({ c=T.muted, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3 9l4-4 4 4" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Plus = ({ c=T.sec, s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>;
const SendArrow = ({ c='#0E0F11', s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Dots = ({ c=T.sec, s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><circle cx="4" cy="9" r="1.6"/><circle cx="9" cy="9" r="1.6"/><circle cx="14" cy="9" r="1.6"/></svg>;
const AGlyph = ({ s=16, c=T.accent }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M5 5l4 8 4-8" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>;

// ── data ──────────────────────────────────────────────────────
const ALIASES = [
  { alias:'fable',  name:'Fable',  desc:'Fastest — quick edits & drafts',  selected:true },
  { alias:'opus',   name:'Opus',   desc:'Deepest reasoning for hard problems' },
  { alias:'sonnet', name:'Sonnet', desc:'Balanced default for everyday work' },
  { alias:'haiku',  name:'Haiku',  desc:'Lightweight, lowest latency' },
];
const VENDORS = [
  { key:'deepseek', mono:'DS', name:'DeepSeek',        id:'deepseek-chat', tint:'#5B9BD5', suggested:true },
  { key:'glm',      mono:'GL', name:'GLM · Zhipu',     id:'glm-4.6',       tint:'#9B8CD9' },
  { key:'kimi',     mono:'KM', name:'Kimi · Moonshot', id:'kimi-k2',       tint:'#E0A93B' },
];
const HOST = '127.0.0.1:3456';

// middle-truncate long ids: keep head + … + tail
function midTrunc(s, head=8, tail=4) {
  if (s.length <= head+tail+1) return s;
  return s.slice(0, head) + '…' + s.slice(-tail);
}

// ── the composer model chip ───────────────────────────────────
function ModelChip({ label, open, dimmed }) {
  return (
    <button disabled={dimmed} style={{
      all:'unset', boxSizing:'border-box', cursor: dimmed?'default':'pointer', flexShrink:0,
      display:'flex', alignItems:'center', gap:5, height:30, padding:'0 8px 0 10px', borderRadius:999,
      background:T.raised, border:`1px solid ${open?T.accent:T.border}`, opacity: dimmed?0.42:1,
    }}>
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, maxWidth:82, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{label}</span>
      <span style={{ display:'flex', transform: open?'rotate(180deg)':'none', transition:'transform .18s ease' }}><ChevUp c={T.muted} s={12}/></span>
    </button>
  );
}

// ── shared list pieces (mirror gateway-presets grammar) ───────
function Monogram({ v, s=30 }) {
  const bg = v.tint+'22', bd = v.tint+'55';
  return <span style={{ width:s, height:s, flexShrink:0, borderRadius:s*0.28, background:bg, border:`1px solid ${bd}`,
    display:'flex', alignItems:'center', justifyContent:'center', fontFamily:T.mono, fontSize:s*0.4, fontWeight:700, color:v.tint }}>{v.mono}</span>;
}
function AliasRow({ m, size=30 }) {
  return (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:12, padding: size>26?'11px 12px':'9px 10px', borderRadius:12, cursor:'pointer', marginBottom:3,
      background: m.selected?T.raised:'transparent', border:`1px solid ${m.selected?T.border:'transparent'}` }}>
      <span style={{ width:size, height:size, flexShrink:0, borderRadius:size*0.28, background:T.surface, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><AGlyph s={size*0.5}/></span>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'baseline', gap:8 }}>
          <span style={{ fontFamily:T.ui, fontSize: size>26?15:14, fontWeight:600, color:T.text }}>{m.name}</span>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>{m.alias}</span>
        </div>
        <div style={{ fontFamily:T.ui, fontSize: size>26?12.5:12, color:T.sec, marginTop:2 }}>{m.desc}</div>
      </div>
      <span style={{ width:20, display:'flex', justifyContent:'center', flexShrink:0 }}>{m.selected ? <Check/> : null}</span>
    </div>
  );
}
function PresetRow({ v, size=30 }) {
  return (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:12, padding: size>26?'10px 12px':'8px 10px', borderRadius:12, cursor:'pointer', marginBottom:3 }}>
      <Monogram v={v} s={size}/>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8 }}>
          <span style={{ fontFamily:T.ui, fontSize: size>26?14.5:13.5, fontWeight:600, color:T.text }}>{v.name}</span>
          {v.suggested && <span style={{ display:'inline-flex', alignItems:'center', gap:3 }}><Tick c={T.accent} s={11}/><span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, color:T.accent }}>suggested</span></span>}
        </div>
        <div style={{ fontFamily:T.mono, fontSize: size>26?11.5:11, color:T.sec, marginTop:3, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{v.id}</div>
      </div>
      <span style={{ width:18, flexShrink:0 }}/>
    </div>
  );
}
function SectionLabel({ children, right }) {
  return <div style={{ display:'flex', alignItems:'center', gap:8, padding:'2px 6px 8px' }}>
    <span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
    <span style={{ flex:1 }}/>{right}</div>;
}
function HostPill() {
  return <span style={{ display:'inline-flex', alignItems:'center', gap:5, fontFamily:T.mono, fontSize:10.5, color:T.sec, background:T.surface, border:`1px solid ${T.border}`, borderRadius:999, padding:'2px 9px' }}>
    <span style={{ width:5, height:5, borderRadius:999, background:T.success, boxShadow:`0 0 6px ${T.success}88` }}/>via {HOST}</span>;
}
function CustomRow() {
  return (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:10, height:44, padding:'0 12px', borderRadius:12, cursor:'pointer' }}>
      <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, flex:1 }}>Custom model id…</span>
      <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted, border:`1px solid ${T.border}`, borderRadius:6, padding:'2px 7px' }}>set</span>
    </div>
  );
}

// list body shared by sheet + popover
function ModelList({ size=30, generating }) {
  return (
    <>
      <div style={{ marginBottom:10 }}>
        <SectionLabel right={<HostPill/>}>Gateway models</SectionLabel>
        {VENDORS.map(v => <PresetRow key={v.key} v={v} size={size}/>)}
      </div>
      <div style={{ borderTop:`1px solid ${T.border}`, paddingTop:10 }}>
        <SectionLabel>Anthropic aliases</SectionLabel>
        {ALIASES.map((m,i)=> <AliasRow key={i} m={m} size={size}/>)}
      </div>
      <div style={{ borderTop:`1px solid ${T.border}`, marginTop:8, paddingTop:8 }}><CustomRow/></div>
    </>
  );
}

// ══════════════ MOBILE ══════════════
function ChatBackdrop() {
  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <div style={{ paddingTop:52, borderBottom:`1px solid ${T.border}`, flexShrink:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 14px', height:44 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1 }}>Fix relay reconnect</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}>fable</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 14px 9px' }}>
          <span style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>Lidapeng-MBP · ~/code/cc-pocket · fable</span>
        </div>
      </div>
      <div style={{ flex:1, minHeight:0, padding:14, display:'flex', flexDirection:'column', gap:14 }}>
        <div><div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, color:T.muted, marginBottom:6 }}>You</div>
          <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'21px', color:T.text }}>the reconnect dies after the 3rd retry — can you find why?</div></div>
        <div><div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, color:T.muted, marginBottom:6 }}>Claude</div>
          <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'21px', color:T.text }}>The backoff timer runs on the socket's own scope, which is cancelled the moment the socket closes.</div></div>
      </div>
    </div>
  );
}

// composer row (attach · input · chip · send)
function MobileComposer({ chipLabel, chipOpen, chipDimmed, generating }) {
  return (
    <div style={{ background:T.base, borderTop:`1px solid ${T.border}`, paddingBottom:34 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'11px 12px' }}>
        <button style={{ all:'unset', cursor:'pointer', width:38, height:38, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}><Plus c={T.sec}/></button>
        <div style={{ flex:1, minWidth:0, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 12px', minHeight:40 }}>
          <span style={{ fontFamily:T.ui, fontSize:14, color:T.muted }}>Message Claude…</span>
        </div>
        <ModelChip label={chipLabel} open={chipOpen} dimmed={chipDimmed}/>
        <button style={{ all:'unset', cursor:'pointer', width:40, height:40, borderRadius:999, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center',
          background: generating?T.surface:T.accent, border: generating?`1px solid ${T.border}`:'none' }}>
          {generating ? <svg width="15" height="15" viewBox="0 0 16 16"><rect x="3.5" y="3.5" width="9" height="9" rx="2.2" fill={T.accent}/></svg> : <SendArrow c="#0E0F11"/>}
        </button>
      </div>
    </div>
  );
}

// mobile screen with the sheet OPEN
function MobileSheetScreen() {
  const COMP_H = 40+22+34; // composer content height incl. safe area
  return (
    <div style={{ position:'relative', height:'100%' }}>
      <ChatBackdrop/>
      {/* dim over chat only, composer stays connected */}
      <div style={{ position:'absolute', left:0, right:0, top:0, bottom:COMP_H, background:'rgba(0,0,0,0.55)' }}/>
      {/* sheet rises above the composer */}
      <div style={{ position:'absolute', left:0, right:0, bottom:COMP_H, background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20,
        borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column', maxHeight:600 }}>
        <div style={{ display:'flex', justifyContent:'center', padding:'9px 0 3px' }}><div style={{ width:38, height:5, borderRadius:999, background:T.border }}/></div>
        <div style={{ padding:'8px 16px 4px' }}><div style={{ fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text }}>Model</div></div>
        <div className="mp-scroll" style={{ overflowY:'auto', padding:'10px 12px 4px' }}><ModelList size={30}/></div>
        <div style={{ padding:'8px 18px 16px' }}>
          <div style={{ borderTop:`1px solid ${T.border}`, paddingTop:11, fontFamily:T.ui, fontSize:12.5, color:T.muted }}>Switching restarts the session — your history is kept.</div>
        </div>
      </div>
      {/* live composer with active chip */}
      <div style={{ position:'absolute', left:0, right:0, bottom:0 }}>
        <MobileComposer chipLabel="fable" chipOpen/>
      </div>
    </div>
  );
}

// ══════════════ DESKTOP ══════════════
function DesktopFrame() {
  return (
    <div style={{ width:1280, height:800, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, overflow:'hidden', display:'flex', flexDirection:'column', boxShadow:'0 40px 100px -30px rgba(0,0,0,0.7)' }}>
      {/* title bar */}
      <div style={{ height:38, flexShrink:0, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:10, padding:'0 12px' }}>
        <div style={{ display:'flex', gap:8 }}>{['#ED6A5E','#F4BE4F','#61C554'].map(c=><span key={c} style={{ width:12, height:12, borderRadius:999, background:c }}/>)}</div>
        <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted }}>cc-pocket</span>
      </div>
      <div style={{ flex:1, minHeight:0, display:'flex' }}>
        {/* sidebar edge for context */}
        <div style={{ width:230, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, padding:'12px 0' }}>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase', padding:'6px 14px 8px' }}>Sessions</div>
          {['Fix relay reconnect','Port parser to Rust','Refactor auth module','Tidy CI workflow'].map((t,i)=>(
            <div key={i} style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 14px', background: i===0?T.raised:'transparent', position:'relative' }}>
              {i===0 && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
              <span style={{ width:6, height:6, borderRadius:999, background: i<3?T.success:T.muted }}/>
              <span style={{ fontFamily:T.ui, fontSize:13, color: i===0?T.text:T.sec, fontWeight:i===0?600:400 }}>{t}</span>
            </div>
          ))}
        </div>
        {/* chat pane */}
        <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base, position:'relative' }}>
          <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, padding:'10px 18px' }}>
            <div style={{ display:'flex', alignItems:'center', gap:10 }}>
              <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Fix relay reconnect</span>
              <span style={{ flex:1 }}/>
            </div>
            <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:5 }}>Lidapeng-MBP · ~/code/cc-pocket · ⑂ main · fable</div>
          </div>
          <div style={{ flex:1, minHeight:0, padding:'20px 18px', display:'flex', flexDirection:'column', gap:16 }}>
            <div style={{ maxWidth:680 }}><div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, color:T.muted, marginBottom:6 }}>You</div>
              <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>the websocket reconnect dies after the 3rd retry — can you find why and add a regression test?</div></div>
            <div style={{ maxWidth:680, fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text }}>The backoff loop runs on the socket's own scope, which is cancelled the moment the socket closes — so the timer never fires. Moving it to an app-level scope fixes it.</div>
          </div>
          {/* composer */}
          <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'12px 18px 16px', position:'relative' }}>
            {/* anchored model popover, above the chip */}
            <div style={{ position:'absolute', right:70, bottom:66, width:340, background:T.raised, border:`1px solid ${T.border}`, borderRadius:13, boxShadow:'0 24px 60px -18px rgba(0,0,0,0.78)', overflow:'hidden', zIndex:20 }}>
              <div style={{ padding:'12px 12px 6px' }}><ModelList size={24}/></div>
              {/* little tail pointing down toward the chip */}
              <div style={{ position:'absolute', right:36, bottom:-7, width:12, height:12, background:T.raised, borderRight:`1px solid ${T.border}`, borderBottom:`1px solid ${T.border}`, transform:'rotate(45deg)' }}/>
            </div>
            <div style={{ maxWidth:900, margin:'0 auto', display:'flex', alignItems:'center', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'7px 8px 7px 12px' }}>
              <Plus c={T.sec}/>
              <span style={{ flex:1, fontFamily:T.ui, fontSize:14, color:T.muted }}>Message Claude…</span>
              <button style={{ all:'unset', cursor:'pointer', width:32, height:32, borderRadius:8, display:'flex', alignItems:'center', justifyContent:'center' }}><Dots c={T.sec}/></button>
              <ModelChip label="fable" open/>
              <button style={{ all:'unset', cursor:'pointer', width:34, height:34, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}><SendArrow c="#0E0F11" s={16}/></button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── ⋯ menu inset (model row as a plain shortcut) ──────────────
function DotsMenuInset() {
  const Row = ({ label, hint, shortcut }) => (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:10, height:38, padding:'0 12px', borderRadius:9, cursor:'pointer' }}>
      <span style={{ fontFamily:T.ui, fontSize:13, color:T.text, flex:1 }}>{label}</span>
      {hint && <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>{hint}</span>}
      {shortcut && <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px' }}>{shortcut}</span>}
    </div>
  );
  return (
    <div style={{ width:250, background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, boxShadow:'0 20px 50px -18px rgba(0,0,0,0.7)', overflow:'hidden', padding:'6px' }}>
      <Row label="Model" hint="fable" shortcut="⌘/"/>
      <Row label="Permission mode" hint="default"/>
      <Row label="Effort / thinking" hint="auto"/>
      <div style={{ height:1, background:T.border, margin:'5px 8px' }}/>
      <Row label="Compact conversation"/>
      <Row label="Session settings…"/>
    </div>
  );
}

// ══════════════ BOARD ══════════════
function Phone({ children, scale=0.86 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark><div style={{ position:'relative', height:'100%' }}>{children}</div></IOSDevice>
      </div>
    </div>
  );
}
function Divi({ children, sub }) {
  return (
    <div style={{ margin:'60px 0 24px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.sec, marginTop:10, maxWidth:820 }}>{sub}</div>}
    </div>
  );
}

// close-up composer strip on a base panel
function ChipCell({ label, note, chipLabel, generating }) {
  return (
    <div style={{ width:388 }}>
      <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text, marginBottom:10 }}>{label}</div>
      <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:16, overflow:'hidden' }}>
        <MobileComposer chipLabel={chipLabel} chipDimmed={generating} generating={generating}/>
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12, lineHeight:'17px', color:T.muted, marginTop:10 }}>{note}</div>
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1320, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>One-tap model switcher on the composer</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:760, margin:0 }}>
        Switching model is a per-task action, so it moves out of the two-deep quick-actions sheet and onto the composer itself. A quiet mono chip sits just left of send, showing the current alias; tapping it opens the <em>existing</em> model picker directly — alias rows, gateway presets, and the custom-id field — one entrance shallower. The chip stays muted so send remains the loudest control; the header meta line is unchanged.
      </p>

      <Divi sub="The chip in place at the composer's bottom-right cluster, and the model sheet opened straight from it — no quick-actions detour. Gateway presets lead (DeepSeek suggested via the detected host), then the Fable / Opus / Sonnet / Haiku aliases with one-line descriptions (Fable selected, terracotta check), then the custom-id field. The chip stays lit and its chevron flips to show it owns the open sheet.">① Mobile · chip → model sheet</Divi>
      <Phone><MobileSheetScreen/></Phone>

      <Divi sub="Chip states. Default shows the short alias. A long gateway id middle-truncates so the cluster never grows. While Claude is generating, the chip dims and disables — and the sheet, if opened, notes the switch applies to the next turn.">② Mobile · chip states</Divi>
      <div style={{ display:'flex', flexWrap:'wrap', gap:28 }}>
        <ChipCell label="1 · Default" chipLabel="fable" note="Quiet mono alias, hairline border on raised surface, chevron-up glyph. No accent fill — send stays loudest."/>
        <ChipCell label="2 · Long gateway id" chipLabel={midTrunc('deepseek-chat')} note="Middle-truncated (deepseek…chat) with the full id in the sheet. The cluster width holds steady."/>
        <ChipCell label="3 · Generating" chipLabel="fable" generating note="Dimmed and disabled while a turn streams; the send button is showing its stop state. Switch applies to the next turn."/>
      </div>

      <Divi sub="Desktop gets the same chip in the composer's bottom-right cluster next to send. Clicking opens an anchored popover (not a sheet) with the identical rows — one click instead of ⋯ → Model. The ⋯ menu keeps its other actions and now lists Model as a plain shortcut that opens this same popover.">③ Desktop · anchored popover</Divi>
      <div style={{ overflowX:'auto' }} className="mp-scroll"><DesktopFrame/></div>
      <div style={{ marginTop:24 }}>
        <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text, marginBottom:12 }}>⋯ quick-actions menu — Model is now a shortcut row</div>
        <DotsMenuInset/>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
