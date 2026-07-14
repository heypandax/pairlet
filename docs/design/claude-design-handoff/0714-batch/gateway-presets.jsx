// cc-pocket — Gateway model presets (inside the existing model picker)
// Mobile bottom-sheet (gateway detected + collapsed/no-gateway) + desktop ⋯ popover (detected).

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// vendor identity — monogram tints drawn from the semantic palette (harmonious, no logos)
const VENDORS = [
  { key:'deepseek', mono:'DS', name:'DeepSeek',         id:'deepseek-chat', tint:'#5B9BD5', suggested:true },
  { key:'glm',      mono:'GL', name:'GLM · Zhipu',      id:'glm-4.6',       tint:'#9B8CD9' },
  { key:'kimi',     mono:'KM', name:'Kimi · Moonshot',  id:'kimi-k2',       tint:'#E0A93B' },
  { key:'qwen',     mono:'QW', name:'Qwen · Alibaba',   id:'qwen3-max',     tint:'#56B6C2' },
  { key:'minimax',  mono:'MM', name:'MiniMax',          id:'minimax-m2',    tint:'#D982A8' },
];
// suggested first, rest keep order
const PRESETS = [...VENDORS].sort((a,b)=> (b.suggested?1:0)-(a.suggested?1:0));
const ALIASES = [
  { name:'Opus 4.8', id:'claude-opus-4-8', ctx:'1M', big:true },
  { name:'Sonnet 4.8', id:'claude-sonnet-4-8', ctx:'1M', big:true },
  { name:'Haiku 4', id:'claude-haiku-4', ctx:'200K' },
];
const HOST = '127.0.0.1:3456';
const SELECTED_ID = 'glm-4.6';

const Check = ({ c=T.accent, s=18 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Chevron = ({ c=T.muted, s=16 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M6.5 4L11.5 9l-5 5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Tick = ({ c=T.accent, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.5 7.5l2.6 2.6L11.5 3.5" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;

function CtxPill({ ctx, big }) {
  return <span style={{ fontFamily:T.mono, fontSize:10.5, fontWeight:600, padding:'2px 8px', borderRadius:999,
    background: big ? T.accent : 'transparent', color: big ? '#0E0F11' : T.muted, border: big ? 'none' : `1px solid ${T.border}` }}>{ctx}</span>;
}

// hex + alpha helper for low-opacity tint fills
const tintBg = (hex, a='22') => hex + a;

function Monogram({ v, s=32 }) {
  return (
    <span style={{ width:s, height:s, flexShrink:0, borderRadius:s*0.28, background:tintBg(v.tint,'22'), border:`1px solid ${tintBg(v.tint,'55')}`,
      display:'flex', alignItems:'center', justifyContent:'center',
      fontFamily:T.mono, fontSize:s*0.4, fontWeight:700, color:v.tint, letterSpacing:0.2 }}>{v.mono}</span>
  );
}

// ── gateway preset row ────────────────────────────────────────
function PresetRow({ v, size=32, selected }) {
  return (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:12, padding: size>28?'11px 12px':'8px 10px', borderRadius:12,
      background: selected ? T.raised : 'transparent', border:`1px solid ${selected ? T.border : 'transparent'}`, cursor:'pointer', marginBottom:4 }}>
      <Monogram v={v} s={size}/>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8 }}>
          <span style={{ fontFamily:T.ui, fontSize: size>28?14.5:13.5, fontWeight:600, color:T.text }}>{v.name}</span>
          {v.suggested && (
            <span style={{ display:'inline-flex', alignItems:'center', gap:3 }}>
              <Tick c={T.accent} s={11}/>
              <span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, color:T.accent }}>suggested</span>
            </span>
          )}
        </div>
        <div style={{ fontFamily:T.mono, fontSize: size>28?11.5:11, color:T.sec, marginTop:3, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{v.id}</div>
      </div>
      <span style={{ width:18, display:'flex', justifyContent:'center', flexShrink:0 }}>{selected ? <Check s={17}/> : null}</span>
    </div>
  );
}

// ── alias row (unchanged existing list) ───────────────────────
function AliasRow({ m, size=32 }) {
  return (
    <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:12, padding: size>28?'12px 12px':'9px 10px', borderRadius:12, cursor:'pointer', marginBottom:4 }}>
      <span style={{ width:size, height:size, flexShrink:0, borderRadius:size*0.28, background:T.surface, border:`1px solid ${T.border}`,
        display:'flex', alignItems:'center', justifyContent:'center' }}>
        <svg width={size*0.5} height={size*0.5} viewBox="0 0 18 18" fill="none"><path d="M5 5l4 8 4-8" stroke={T.accent} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
      </span>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontFamily:T.ui, fontSize: size>28?14.5:13.5, fontWeight:600, color:T.text }}>{m.name}</div>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:3 }}>
          <span style={{ fontFamily:T.mono, fontSize: size>28?11.5:11, color:T.sec }}>{m.id}</span>
          <CtxPill ctx={m.ctx} big={m.big}/>
        </div>
      </div>
      <span style={{ width:18, flexShrink:0 }}/>
    </div>
  );
}

function SectionLabel({ children, right, style }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:8, padding:'2px 6px 8px', ...style }}>
      <span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
      <span style={{ flex:1 }}/>{right}
    </div>
  );
}
function HostPill({ host }) {
  return <span style={{ display:'inline-flex', alignItems:'center', gap:5, fontFamily:T.mono, fontSize:10.5, color:T.sec, background:T.surface, border:`1px solid ${T.border}`, borderRadius:999, padding:'2px 9px' }}>
    <span style={{ width:5, height:5, borderRadius:999, background:T.success, boxShadow:`0 0 6px ${T.success}88` }}/>via {host}</span>;
}
function Footnote({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:11.5, lineHeight:'16px', color:T.muted, padding:'4px 8px 2px' }}>{children}</div>;
}
function CustomField() {
  return (
    <div>
      <SectionLabel>Custom model id</SectionLabel>
      <div style={{ margin:'0 6px', display:'flex', alignItems:'center', gap:10, height:46, padding:'0 13px', background:T.base, border:`1px solid ${T.border}`, borderRadius:12 }}>
        <span style={{ fontFamily:T.mono, fontSize:12.5, color:T.muted }}>vendor-model-id</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted, border:`1px solid ${T.border}`, borderRadius:6, padding:'2px 7px' }}>set</span>
      </div>
    </div>
  );
}

// ── mobile sheet ──────────────────────────────────────────────
function GatewaySheet({ detected }) {
  return (
    <div style={{ position:'absolute', inset:0, display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
      <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.55)' }}/>
      <div style={{ position:'relative', background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20,
        borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`,
        maxHeight:704, display:'flex', flexDirection:'column' }}>
        <div style={{ display:'flex', justifyContent:'center', padding:'9px 0 3px', flexShrink:0 }}>
          <div style={{ width:38, height:5, borderRadius:999, background:T.border }}/>
        </div>
        <div style={{ padding:'8px 16px 6px', flexShrink:0 }}>
          <div style={{ fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>Model</div>
        </div>
        <div className="mp-scroll" style={{ overflowY:'auto', padding:'8px 12px 0' }}>
          {detected && (
            <div style={{ marginBottom:10 }}>
              <SectionLabel right={<HostPill host={HOST}/>}>Gateway models</SectionLabel>
              {PRESETS.map(v => <PresetRow key={v.key} v={v} selected={v.id===SELECTED_ID}/>)}
              <Footnote>Which model an id reaches is decided by your gateway.</Footnote>
            </div>
          )}
          <div style={{ borderTop:`1px solid ${T.border}`, paddingTop:12 }}>
            <SectionLabel>Anthropic API</SectionLabel>
            {ALIASES.map((m,i)=> <AliasRow key={i} m={m}/>)}
          </div>
          <div style={{ paddingTop:8 }}><CustomField/></div>
          {!detected && (
            <div style={{ marginTop:12, borderTop:`1px solid ${T.border}` }}>
              <div className="mp-press" style={{ display:'flex', alignItems:'center', gap:10, height:50, padding:'0 12px', cursor:'pointer', borderRadius:12 }}>
                <span style={{ fontFamily:T.ui, fontSize:14, color:T.sec, flex:1 }}>Gateway model presets</span>
                <Chevron c={T.muted} s={16}/>
              </div>
            </div>
          )}
          <div style={{ height:8 }}/>
        </div>
        <div style={{ flexShrink:0, padding:'10px 18px 34px' }}>
          <div style={{ borderTop:`1px solid ${T.border}`, paddingTop:12, fontFamily:T.ui, fontSize:12.5, color:T.muted }}>
            Switching restarts the session — your history is kept.
          </div>
        </div>
      </div>
    </div>
  );
}

// ── chat backdrop (dim, behind sheet) ─────────────────────────
function ChatBackdrop({ model }) {
  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <div style={{ paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 14px', height:44 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1 }}>Fix relay reconnect</span>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}>{model}</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 14px 9px' }}>
          <span style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>~/code/cc-pocket · ⑂ main</span>
        </div>
      </div>
      <div style={{ flex:1, padding:14, display:'flex', flexDirection:'column', gap:12, opacity:0.4 }}>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.text }}>The reconnect loop dies after the 3rd retry.</div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, height:40 }}/>
      </div>
    </div>
  );
}

// ── desktop popover (⋯ quick actions) ─────────────────────────
function DesktopPopover() {
  return (
    <div style={{ width:340, background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, boxShadow:'0 24px 60px -18px rgba(0,0,0,0.75)', overflow:'hidden' }}>
      <div style={{ padding:'12px 12px 4px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 6px 8px' }}>
          <span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:700, letterSpacing:1, textTransform:'uppercase', color:T.muted }}>Gateway</span>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>· {HOST}</span>
          <span style={{ flex:1 }}/>
          <span style={{ width:5, height:5, borderRadius:999, background:T.success, boxShadow:`0 0 6px ${T.success}88` }}/>
        </div>
        {PRESETS.map(v => <PresetRow key={v.key} v={v} size={24} selected={v.id===SELECTED_ID}/>)}
        <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, padding:'2px 6px 6px' }}>Which model an id reaches is decided by your gateway.</div>
      </div>
      <div style={{ borderTop:`1px solid ${T.border}`, padding:'10px 12px 4px' }}>
        <div style={{ padding:'0 6px 6px', fontFamily:T.ui, fontSize:10.5, fontWeight:700, letterSpacing:1, textTransform:'uppercase', color:T.muted }}>Anthropic API</div>
        {ALIASES.slice(0,2).map((m,i)=> <AliasRow key={i} m={m} size={24}/>)}
      </div>
      <div className="mp-press" style={{ borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:8, height:42, padding:'0 18px', cursor:'pointer' }}>
        <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Custom model id…</span>
      </div>
    </div>
  );
}
function DesktopFrame() {
  return (
    <div style={{ width:720, height:440, borderRadius:12, border:`1px solid ${T.border}`, overflow:'hidden', position:'relative',
      background:'radial-gradient(120% 90% at 70% 0%, #121417 0%, #0B0C0D 65%, #08090A 100%)' }}>
      {/* faux toolbar */}
      <div style={{ height:44, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:10, padding:'0 14px', background:T.base }}>
        <div style={{ display:'flex', gap:7 }}>{['#ED6A5E','#F4BE4F','#61C554'].map(c=><span key={c} style={{ width:11, height:11, borderRadius:999, background:c }}/>)}</div>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 10px' }}>{SELECTED_ID}</span>
        <span style={{ width:30, height:30, borderRadius:8, background:T.raised, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <svg width="16" height="16" viewBox="0 0 18 18" fill={T.sec}><circle cx="4" cy="9" r="1.5"/><circle cx="9" cy="9" r="1.5"/><circle cx="14" cy="9" r="1.5"/></svg>
        </span>
      </div>
      <div style={{ position:'absolute', inset:0, top:44, background:'rgba(8,9,10,0.45)' }}/>
      {/* popover anchored under ⋯ */}
      <div style={{ position:'absolute', top:52, right:14 }}><DesktopPopover/></div>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Phone({ children, model, scale=0.9 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark><div style={{ position:'relative', height:'100%' }}><ChatBackdrop model={model}/>{children}</div></IOSDevice>
      </div>
    </div>
  );
}
function Cell({ label, note, model, children }) {
  return (
    <div style={{ width:402*0.9 }}>
      <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text, marginBottom:10 }}>{label}</div>
      <Phone model={model}>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:402*0.9 }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · model picker</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Gateway model presets</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:720, margin:0 }}>
        When the daemon detects a third-party gateway (<span style={{ fontFamily:T.mono, fontSize:13, color:T.text }}>ANTHROPIC_BASE_URL</span>), the picker leads with one-tap vendor
        presets whose ids no alias list knows. Each vendor gets a tinted two-letter monogram — never a logo — and the row whose vendor
        matches the detected host floats to the top with a “suggested” tick. Official-API users see zero change: the presets collapse behind a disclosure row.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:36, marginTop:40, alignItems:'flex-start' }}>
        <Cell label="Mobile · gateway detected" model="glm-4.6"
          note="“GATEWAY MODELS” leads, with the detected host pill on the right. DeepSeek matches the host so it floats up with a terracotta “suggested” tick; GLM is the current selection (raised row + check). The Anthropic alias list and Custom model id field follow unchanged.">
          <GatewaySheet detected/>
        </Cell>
        <Cell label="Mobile · no gateway (collapsed)" model="sonnet-4.8"
          note="No gateway → the section is gone entirely. A single quiet “Gateway model presets ›” disclosure row sits after the alias list and Custom field; expanding it reveals the same rows without the host pill.">
          <GatewaySheet detected={false}/>
        </Cell>
      </div>

      <h2 style={{ fontFamily:T.ui, fontSize:16, fontWeight:700, color:T.text, margin:'56px 0 6px' }}>Desktop · ⋯ quick-actions popover (detected)</h2>
      <p style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.sec, maxWidth:700, margin:'0 0 22px' }}>
        Same grouping inside the existing popover: a “Gateway · {HOST}” section header above the preset rows with slightly smaller monograms, then the alias list and Custom model id row.
      </p>
      <DesktopFrame/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
