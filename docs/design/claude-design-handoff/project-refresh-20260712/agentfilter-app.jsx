// cc-pocket — filter sessions by agent (Settings group + filtered list)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', teal:'#3FB5AC', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const AGENTS = {
  claude:{ name:'Claude', color:T.accent, tint:'rgba(217,119,87,0.12)', tintBorder:'rgba(217,119,87,0.4)' },
  codex: { name:'Codex',  color:T.teal,   tint:'rgba(63,181,172,0.12)', tintBorder:'rgba(63,181,172,0.42)' },
};

function AgentGlyph({ agent, s=14 }) {
  const c = AGENTS[agent].color;
  if (agent==='claude') return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M5 5l4.2 4.2L5 13.4" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/><path d="M11 14h4" stroke={c} strokeWidth="1.8" strokeLinecap="round"/></svg>;
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.3" stroke={c} strokeWidth="1.6"/><path d="M10 3.2c3.8 0 6.8 3 6.8 6.8M10 16.8c-3.8 0-6.8-3-6.8-6.8" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>;
}
const Chevron = ({ c=T.sec, s=17 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M11 3L5 9l6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const XSmall = ({ c, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>;
const Branch = ({ c=T.muted, s=11 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><circle cx="3.5" cy="3" r="1.8" stroke={c} strokeWidth="1.4"/><circle cx="3.5" cy="11" r="1.8" stroke={c} strokeWidth="1.4"/><circle cx="10.5" cy="3" r="1.8" stroke={c} strokeWidth="1.4"/><path d="M3.5 4.8v4.4M10.5 4.8c0 2.5-2 3-4 3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/></svg>;
const Bubble = ({ c=T.muted, s=11 }) => <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M2.2 4.4A2 2 0 014.2 2.4h7.6a2 2 0 012 2v4a2 2 0 01-2 2H6.4l-3 2.4a.4.4 0 01-.65-.32V10.4H4.2A2 2 0 012.2 8.4z" stroke={c} strokeWidth="1.3" strokeLinejoin="round"/></svg>;
const SparkIcon = ({ c, s=38 }) => <svg width={s} height={s} viewBox="0 0 34 34" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M17 6l2.2 6.3L25.5 14.5 19.2 16.7 17 23l-2.2-6.3L8.5 14.5l6.3-2.2z"/></svg>;

// ══════════ 1) SETTINGS GROUP ══════════
function SettingsGroup({ value='both' }) {
  const opts = [
    { key:'both', label:'Both', dot:null },
    { key:'claude', label:'Claude only', dot:T.accent },
    { key:'codex', label:'Codex only', dot:T.teal },
  ];
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 8px' }}>
          <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron/></button>
          <span style={{ fontFamily:T.ui, fontSize:17, fontWeight:600, color:T.text }}>Settings</span>
        </div>
      </div>
      <div style={{ flex:1, padding:'20px 16px' }}>
        <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', margin:'0 2px 10px' }}>Show sessions from</div>
        {/* segmented control */}
        <div style={{ display:'flex', gap:4, padding:4, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12 }}>
          {opts.map(o=>{
            const on = o.key===value;
            const col = o.key==='claude'?T.accent : o.key==='codex'?T.teal : T.text;
            return (
              <div key={o.key} style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center', gap:6, padding:'9px 0', borderRadius:9,
                background:on?T.raised:'transparent', border:`1px solid ${on?T.border:'transparent'}` }}>
                {o.dot && <span style={{ width:7, height:7, borderRadius:999, background:o.dot }}/>}
                <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color: on?T.text:T.muted }}>{o.label}</span>
              </div>
            );
          })}
        </div>
        <div style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, margin:'12px 2px 0' }}>
          Choose which agents’ sessions appear in your lists. Claude and Codex stay tagged in their own colors.
        </div>
      </div>
    </div>
  );
}

// ══════════ 2) FILTERED SESSION LIST ══════════
const SESSIONS = [
  { title:'Refactor auth module', preview:'add a unit test for the stream parser', msgs:12, branch:'main', time:'2h', agent:'claude', running:true },
  { title:'Port parser to Rust', preview:'scaffold the nom-based tokenizer', msgs:5, branch:'feat/rs', time:'1h', agent:'codex', running:true },
  { title:'Fix stream parser test', preview:'the parser drops the last token on EOF', msgs:6, branch:'fix/parser', time:'5h', agent:'claude' },
  { title:'Tidy CI workflow', preview:'cache gradle deps between runs', msgs:3, branch:'main', time:'yesterday', agent:'codex' },
  { title:'Add relay websocket client', preview:'Ktor WS client with reconnect', msgs:23, branch:'feat/relay', time:'yesterday', agent:'claude' },
];

function FilterChip({ agent, onRemove }) {
  const a = AGENTS[agent];
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:7, background:a.tint, border:`1px solid ${a.tintBorder}`, borderRadius:999, padding:'5px 8px 5px 11px' }}>
      <span style={{ width:7, height:7, borderRadius:999, background:a.color }}/>
      <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:a.color }}>{a.name} only</span>
      <span style={{ display:'flex', cursor:'pointer', marginLeft:1 }}><XSmall c={a.color}/></span>
    </span>
  );
}

function SessionCard({ s }) {
  const a = AGENTS[s.agent];
  return (
    <div className="af-press" style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'12px 13px', cursor:'pointer' }}>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ flexShrink:0, display:'flex' }}><AgentGlyph agent={s.agent} s={15}/></span>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{s.title}</span>
        {s.running && <span style={{ display:'flex', alignItems:'center', gap:4, flexShrink:0 }}>
          <span className="af-pulse" style={{ width:6, height:6, borderRadius:999, background:a.color }}/>
          <span style={{ fontFamily:T.mono, fontSize:10, color:a.color }}>running</span>
        </span>}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, margin:'4px 0 8px', paddingLeft:23, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{s.preview}</div>
      <div style={{ display:'flex', alignItems:'center', gap:7, paddingLeft:23, fontFamily:T.mono, fontSize:10.5, color:T.muted }}>
        <span style={{ display:'flex', alignItems:'center', gap:3 }}><Bubble c={T.muted} s={11}/>{s.msgs}</span>
        <span>·</span><span style={{ display:'flex', alignItems:'center', gap:3 }}><Branch c={T.muted} s={10}/>{s.branch}</span>
        <span>·</span><span>{s.time}</span>
      </div>
    </div>
  );
}

function SessionList({ filter='both', empty }) {
  const rows = filter==='both' ? SESSIONS : SESSIONS.filter(s=>s.agent===filter);
  const shown = empty ? [] : rows;
  const emptyAgent = filter==='claude' ? 'Claude' : filter==='codex' ? 'Codex' : '';
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 8px' }}>
          <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron/></button>
          <span style={{ fontFamily:T.ui, fontSize:17, fontWeight:600, color:T.text }}>Sessions</span>
        </div>
      </div>
      {/* filter chip row */}
      {filter!=='both' && (
        <div style={{ flexShrink:0, display:'flex', alignItems:'center', padding:'11px 16px 3px' }}>
          <FilterChip agent={filter}/>
        </div>
      )}
      <div className="af-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'12px 16px 24px' }}>
        {shown.length ? (
          <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
            {shown.map((s,i)=><SessionCard key={i} s={s}/>)}
          </div>
        ) : (
          <div style={{ height:'100%', minHeight:340, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:14, padding:'0 40px' }}>
            <span style={{ opacity:0.5 }}><SparkIcon c={T.muted}/></span>
            <div style={{ fontFamily:T.ui, fontSize:15.5, fontWeight:600, color:T.sec }}>No {emptyAgent} sessions here</div>
            <div style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.muted, textAlign:'center' }}>Start one, or clear the filter to see every agent.</div>
          </div>
        )}
      </div>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Phone({ children, scale=0.86 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}
function Cell({ label, note, children }) {
  return (
    <div style={{ width:402*0.86 }}>
      <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text, marginBottom:10 }}>{label}</div>
      <Phone>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:402*0.86 }}>{note}</div>}
    </div>
  );
}
function Divider({ children }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:12, margin:'54px 0 28px' }}>
      <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, fontFamily:T.mono }}>{children}</span>
      <span style={{ flex:1, height:1, background:T.border }}/>
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · sessions</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Filter by agent</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:0 }}>
        A Settings toggle picks which agents’ sessions show up; the list echoes the choice with a removable colored chip. Claude stays terracotta, Codex teal — every row keeps its identity glyph.
      </p>

      <Divider>Setting</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:36 }}>
        <Cell label="“Show sessions from” · Both" note="Segmented control: Both / Claude only (terracotta dot) / Codex only (teal dot).">
          <SettingsGroup value="both"/>
        </Cell>
        <Cell label="Claude only selected" note="The chosen segment raises; the colored dot marks each agent option.">
          <SettingsGroup value="claude"/>
        </Cell>
      </div>

      <Divider>List · filter applied</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:36 }}>
        <Cell label="Both · no chip, all rows" note="Every session shows, each with its identity glyph (terracotta Claude / teal Codex).">
          <SessionList filter="both"/>
        </Cell>
        <Cell label="Claude only · terracotta chip" note="A slim removable “Claude only ✕” chip pins to the top; only Claude rows remain.">
          <SessionList filter="claude"/>
        </Cell>
        <Cell label="Codex only · teal chip" note="“Codex only ✕” in teal; the list narrows to Codex sessions.">
          <SessionList filter="codex"/>
        </Cell>
        <Cell label="Empty · filter hides everything" note="When the active filter matches nothing: “No Claude sessions here,” with a calm nudge to clear it.">
          <SessionList filter="claude" empty/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
