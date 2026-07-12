// cc-pocket — Projects screen (hierarchical browse + view-mode toggle)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons (1.5pt line) ────────────────────────────────────────
const Ico = {
  back: (c=T.sec,s=18)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M11 3L5 9l6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevR: (c=T.muted,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M6.5 3.5L12 9l-5.5 5.5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevD: (c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 6.5L9 12l5.5-5.5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  gear: (c=T.sec,s=20)=> <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>,
  folder: (c=T.sec,s=18)=> <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M2.5 5.5A1.5 1.5 0 014 4h3.4l1.6 1.8H16A1.5 1.5 0 0117.5 7.3v7.2A1.5 1.5 0 0116 16H4a1.5 1.5 0 01-1.5-1.5z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/></svg>,
  search: (c=T.muted,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><circle cx="8" cy="8" r="5.2" stroke={c} strokeWidth="1.5"/><path d="M12 12l3.2 3.2" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>,
  branch: (c=T.muted,s=13)=> <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/><circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/><circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/><path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/></svg>,
  bubble: (c=T.muted,s=12)=> <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M2.2 4.4A2 2 0 014.2 2.4h7.6a2 2 0 012 2v4a2 2 0 01-2 2H6.4l-3 2.4a.4.4 0 01-.65-.32V10.4H4.2A2 2 0 012.2 8.4z" stroke={c} strokeWidth="1.3" strokeLinejoin="round"/></svg>,
  // view-mode icons
  flat: (c=T.sec,s=17)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3 4.5h12M3 9h12M3 13.5h12" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>,
  tree: (c=T.sec,s=17)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3 4h9M6 9h9M9 14h6" stroke={c} strokeWidth="1.6" strokeLinecap="round"/><path d="M3 4v8.5M6 9v0" stroke={c} strokeWidth="1.6" strokeLinecap="round"/><path d="M3 12.5h3" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>,
};

// ── view-mode 2-segment toggle ────────────────────────────────
function ViewToggle({ mode='tree', size=30 }) {
  const seg = (m, icon) => {
    const on = mode===m;
    return (
      <span style={{ width:size, height:size-4, borderRadius:7, display:'flex', alignItems:'center', justifyContent:'center',
        background: on ? T.accent : 'transparent' }}>
        {icon(on ? '#0E0F11' : T.sec, 16)}
      </span>
    );
  };
  return (
    <div style={{ display:'flex', gap:2, padding:2, background:T.surface, border:`1px solid ${T.border}`, borderRadius:9 }}>
      {seg('flat', Ico.flat)}
      {seg('tree', Ico.tree)}
    </div>
  );
}

// ── top bar ───────────────────────────────────────────────────
function TopBar({ title='Projects', computer='Lidapeng-MacBook', mode='tree', breadcrumbTitle, online=true }) {
  return (
    <div style={{ flexShrink:0, paddingTop:50, borderBottom:`1px solid ${T.border}`, background:T.base }}>
      <div style={{ display:'flex', alignItems:'flex-start', gap:8, padding:'8px 12px 11px 16px' }}>
        <div style={{ flex:1, minWidth:0 }}>
          {breadcrumbTitle ? (
            <div style={{ fontFamily:T.mono, fontSize:17, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>
              {breadcrumbTitle}
            </div>
          ) : (
            <div style={{ fontFamily:T.ui, fontSize:21, fontWeight:700, color:T.text, letterSpacing:-0.3 }}>{title}</div>
          )}
          <div style={{ display:'flex', alignItems:'center', gap:6, marginTop:3 }}>
            <span className={online?'pj-pulse':''} style={{ width:6, height:6, borderRadius:999, background: online?T.success:T.muted, flexShrink:0, boxShadow: online?`0 0 6px ${T.success}88`:'none' }}/>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{computer}</span>
          </div>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:9, paddingTop:2 }}>
          <ViewToggle mode={mode}/>
          <button style={{ all:'unset', cursor:'pointer', width:32, height:30, display:'flex', alignItems:'center', justifyContent:'center' }} aria-label="Settings">{Ico.gear(T.sec,20)}</button>
        </div>
      </div>
    </div>
  );
}

// ── filter field ──────────────────────────────────────────────
function Filter({ value='' }) {
  return (
    <div style={{ flexShrink:0, padding:'10px 16px 8px', background:T.base }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'0 12px', height:38 }}>
        {Ico.search(T.muted,16)}
        <span style={{ fontFamily:T.ui, fontSize:13.5, color: value?T.text:T.muted, flex:1 }}>{value||'Filter…'}</span>
      </div>
    </div>
  );
}

// ── breadcrumb row ────────────────────────────────────────────
function Breadcrumb({ segs }) {
  return (
    <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:5, padding:'8px 16px', background:T.base, borderBottom:`1px solid ${T.border}`, overflow:'hidden' }}>
      <span style={{ display:'flex', flexShrink:0, marginRight:2 }}>{Ico.back(T.sec,17)}</span>
      {segs.map((s,i)=>(
        <React.Fragment key={i}>
          <span style={{ fontFamily:T.mono, fontSize:12, color: i===segs.length-1 ? T.text : T.sec, whiteSpace:'nowrap', fontWeight: i===segs.length-1?600:400 }}>{s}</span>
          {i<segs.length-1 && <span style={{ color:T.muted, fontFamily:T.mono, fontSize:12, flexShrink:0 }}>›</span>}
        </React.Fragment>
      ))}
    </div>
  );
}

// ── section label ─────────────────────────────────────────────
function SectionLabel({ children, right }) {
  return (
    <div style={{ display:'flex', alignItems:'center', padding:'14px 4px 8px' }}>
      <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase' }}>{children}</span>
      <span style={{ flex:1 }}/>
      {right}
    </div>
  );
}

// ── active session card ───────────────────────────────────────
function ActiveCard({ s }) {
  return (
    <div className="pj-press" style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'11px 13px', cursor:'pointer' }}>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:0 }}>{s.title}</span>
        {s.running && <span style={{ display:'flex', alignItems:'center', gap:4, flexShrink:0 }}>
          <span className="pj-pulse" style={{ width:6, height:6, borderRadius:999, background:T.accent }}/>
          <span style={{ fontFamily:T.mono, fontSize:10, color:T.accent }}>running</span>
        </span>}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, margin:'3px 0 7px', whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{s.preview}</div>
      <div style={{ display:'flex', alignItems:'center', gap:7, fontFamily:T.mono, fontSize:10.5, color:T.muted }}>
        <span style={{ display:'flex', alignItems:'center', gap:3 }}>{Ico.bubble(T.muted,11)}{s.msgs}</span>
        <span>·</span><span style={{ display:'flex', alignItems:'center', gap:3 }}>{Ico.branch(T.muted,10)}{s.branch}</span>
        <span>·</span><span>{s.time}</span>
      </div>
    </div>
  );
}

// ── folder / project row ──────────────────────────────────────
function Row({ name, badge, branch, indent=0, expandable, expanded, onToggle, leaf }) {
  return (
    <div className="pj-press" style={{ display:'flex', alignItems:'center', gap:10, minHeight:48, padding:'0 4px 0 '+(4+indent*20)+'px', cursor:'pointer', borderBottom:`1px solid ${T.border}` }}>
      {expandable ? (
        <span onClick={e=>{e.stopPropagation(); onToggle&&onToggle();}} style={{ display:'flex', width:18, justifyContent:'center', flexShrink:0 }}>
          {expanded ? Ico.chevD(T.sec,14) : Ico.chevR(T.sec,14)}
        </span>
      ) : indent>0 ? <span style={{ width:18, flexShrink:0 }}/> : null}
      <span style={{ flexShrink:0, display:'flex' }}>{leaf ? Ico.branch(T.accent,15) : Ico.folder(T.sec,18)}</span>
      <span style={{ fontFamily:T.mono, fontSize:13.5, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
      {leaf && <span style={{ display:'flex', alignItems:'center', gap:4, flexShrink:0, fontFamily:T.mono, fontSize:11.5, color:T.sec }}>{Ico.branch(T.muted,11)}{branch}</span>}
      {badge && <span style={{ flexShrink:0, fontFamily:T.mono, fontSize:10.5, color:T.accent, border:`1px solid ${T.accent}55`, borderRadius:999, padding:'2px 8px' }}>{badge}</span>}
      {!leaf && <span style={{ flexShrink:0, display:'flex' }}>{Ico.chevR(T.muted,16)}</span>}
    </div>
  );
}

// ── flat project row (name + full path) ───────────────────────
function FlatRow({ name, path, badge }) {
  return (
    <div className="pj-press" style={{ display:'flex', alignItems:'center', gap:11, minHeight:52, padding:'0 4px', cursor:'pointer', borderBottom:`1px solid ${T.border}` }}>
      <span style={{ flexShrink:0, display:'flex' }}>{Ico.folder(T.sec,18)}</span>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontFamily:T.mono, fontSize:13.5, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</div>
        <div style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', marginTop:2 }}>{path}</div>
      </div>
      {badge && <span style={{ flexShrink:0, fontFamily:T.mono, fontSize:10.5, color:T.accent, border:`1px solid ${T.accent}55`, borderRadius:999, padding:'2px 8px' }}>{badge}</span>}
      <span style={{ flexShrink:0, display:'flex' }}>{Ico.chevR(T.muted,16)}</span>
    </div>
  );
}

// ── data ──────────────────────────────────────────────────────
const ACTIVE = { title:'Refactor auth module', preview:'add a unit test for the stream parser', msgs:12, branch:'main', time:'2h', running:true };
const ROOT_FOLDERS = [
  { name:'proj', badge:null },
  { name:'work', badge:null },
  { name:'experiments', badge:null },
  { name:'dotfiles', leaf:true, branch:'main' },
];
const APP_FOLDERS = [
  { name:'cc-pocket', badge:'3 sessions', leaf:true, branch:'main' },
  { name:'cc-dashboard', badge:'8 sessions', leaf:true, branch:'main' },
  { name:'analyse', badge:null },
  { name:'relay-server', badge:'2 sessions', leaf:true, branch:'feat/ws' },
  { name:'nanobanana', badge:null },
];
const FLAT_PROJECTS = [
  { name:'cc-pocket', path:'~/proj/app/cc-pocket', badge:'3 sessions' },
  { name:'cc-dashboard', path:'~/proj/app/cc-dashboard', badge:'8 sessions' },
  { name:'relay-server', path:'~/proj/app/relay-server', badge:'2 sessions' },
  { name:'api-server', path:'~/work/api-server', badge:'1 session' },
  { name:'pandax-site', path:'~/work/pandax-site', badge:'4 sessions' },
];

// ── screen shell ──────────────────────────────────────────────
function Screen({ children }) {
  return <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>{children}</div>;
}
function List({ children }) {
  return <div className="pj-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'0 16px 20px' }}>{children}</div>;
}

// ════════════════ SCREEN 1 — tree root ════════════════
function TreeRoot({ titleMode='A' }) {
  return (
    <Screen>
      <TopBar mode="tree" breadcrumbTitle={titleMode==='B'?'~ ›':undefined}/>
      <Filter/>
      <List>
        <SectionLabel>Active</SectionLabel>
        <ActiveCard s={ACTIVE}/>
        <SectionLabel>Projects</SectionLabel>
        <div>{ROOT_FOLDERS.map((f,i)=><Row key={i} {...f}/>)}</div>
      </List>
    </Screen>
  );
}

// ════════════════ SCREEN 2 — drilled w/ breadcrumb ════════════════
function TreeDrilled({ titleMode='A' }) {
  return (
    <Screen>
      <TopBar mode="tree" breadcrumbTitle={titleMode==='B'?'~ › proj › app':undefined}/>
      <Filter/>
      {titleMode==='A' && <Breadcrumb segs={['~','proj','app']}/>}
      <List>
        <SectionLabel>Projects</SectionLabel>
        <div>{APP_FOLDERS.map((f,i)=><Row key={i} {...f}/>)}</div>
      </List>
    </Screen>
  );
}

// ════════════════ SCREEN 3 — flat view ════════════════
function FlatView() {
  return (
    <Screen>
      <TopBar mode="flat"/>
      <Filter/>
      <List>
        <SectionLabel>Active</SectionLabel>
        <ActiveCard s={ACTIVE}/>
        <SectionLabel>Projects</SectionLabel>
        <div>{FLAT_PROJECTS.map((p,i)=><FlatRow key={i} {...p}/>)}</div>
      </List>
    </Screen>
  );
}

// ════════════════ SCREEN 4 — empty ════════════════
function EmptyState() {
  return (
    <Screen>
      <TopBar mode="tree"/>
      <Filter/>
      <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:14, padding:'0 40px 60px' }}>
        <span style={{ opacity:0.5 }}>{Ico.folder(T.muted,44)}</span>
        <div style={{ fontFamily:T.ui, fontSize:16, fontWeight:600, color:T.sec }}>No projects yet</div>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.muted, textAlign:'center' }}>Pull to refresh</div>
      </div>
    </Screen>
  );
}

// ════════════════ expandable-tree variant ════════════════
function ExpandableTree() {
  const [open, setOpen] = React.useState({ proj:true, app:true });
  return (
    <Screen>
      <TopBar mode="tree"/>
      <Filter/>
      <List>
        <SectionLabel>Projects · expandable</SectionLabel>
        <div>
          <Row name="proj" expandable expanded={open.proj} onToggle={()=>setOpen(o=>({...o,proj:!o.proj}))}/>
          {open.proj && <>
            <Row name="app" indent={1} expandable expanded={open.app} onToggle={()=>setOpen(o=>({...o,app:!o.app}))}/>
            {open.app && <>
              <Row name="cc-pocket" indent={2} leaf branch="main" badge="3 sessions"/>
              <Row name="cc-dashboard" indent={2} leaf branch="main" badge="8 sessions"/>
              <Row name="analyse" indent={2}/>
            </>}
            <Row name="experiments" indent={1}/>
          </>}
          <Row name="work" expandable expanded={false} onToggle={()=>{}}/>
          <Row name="dotfiles" leaf branch="main"/>
        </div>
      </List>
    </Screen>
  );
}

// ════════════════ loading + no-match (compact) ════════════════
function LoadingState() {
  return (
    <Screen>
      <TopBar mode="tree"/>
      <Filter/>
      <List>
        <SectionLabel>Projects</SectionLabel>
        <div>
          {[0,1,2,3,4].map(i=>(
            <div key={i} style={{ display:'flex', alignItems:'center', gap:11, height:48, borderBottom:`1px solid ${T.border}` }}>
              <span className="pj-shim" style={{ width:18, height:18, borderRadius:5 }}/>
              <span className="pj-shim" style={{ height:11, borderRadius:4, width: 120-i*12 }}/>
              <span style={{ flex:1 }}/>
              {i%2===0 && <span className="pj-shim" style={{ width:54, height:14, borderRadius:999 }}/>}
            </div>
          ))}
        </div>
      </List>
    </Screen>
  );
}
function NoMatch() {
  return (
    <Screen>
      <TopBar mode="tree"/>
      <Filter value="zzqq"/>
      <div style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center' }}>
        <span style={{ fontFamily:T.ui, fontSize:14, color:T.muted }}>No matches</span>
      </div>
    </Screen>
  );
}

// ── status strip variants (thin top) ──────────────────────────
function StatusStrip({ kind }) {
  const map = {
    offline: { bg:'rgba(224,169,59,0.14)', c:T.warning, label:'Computer offline' },
    reconnecting: { bg:'rgba(229,96,77,0.14)', c:T.danger, label:'Reconnecting…' },
  };
  const m = map[kind];
  return (
    <Screen>
      <div style={{ flexShrink:0, height:50, background:T.base }}/>
      <div style={{ flexShrink:0, background:m.bg, padding:'6px 16px', textAlign:'center' }}>
        <span style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:500, color:m.c }}>{m.label}</span>
      </div>
      <TopBar mode="tree" online={false}/>
      <Filter/>
      <List>
        <SectionLabel>Projects</SectionLabel>
        <div style={{ opacity:0.55 }}>{ROOT_FOLDERS.map((f,i)=><Row key={i} {...f}/>)}</div>
      </List>
    </Screen>
  );
}

// ── device wrapper ────────────────────────────────────────────
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

function Cell({ tag, label, children, note }) {
  return (
    <div style={{ width:402*0.82 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
        {tag && <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{tag}</span>}
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone>{children}</Phone>
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:402*0.82 }}>{note}</div>}
    </div>
  );
}

function Divider({ children }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:12, margin:'56px 0 28px' }}>
      <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
      <span style={{ flex:1, height:1, background:T.border }}/>
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1300, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · post-connect</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Projects — pick where to work</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:0 }}>
        Hierarchical browse with a flat ⇄ tree view toggle. Primary navigation is drill-down + breadcrumb (one level per screen, mobile-first); an expandable inline tree is offered as the alternative.
      </p>

      <Divider>Four main screens</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="1" label="Tree · root" note="Default. Title “Projects” + live connection sub-line. ACTIVE session pinned on top, then folder rows: mono name, terracotta session badge, drill chevron. dotfiles is a project-leaf (branch glyph).">
          <TreeRoot titleMode="A"/>
        </Cell>
        <Cell tag="2" label="Tree · drilled" note="Inside ~/proj/app: breadcrumb row (tappable mono segments + back ‹) under the filter. Project-leaf rows show ⑂ branch and open a session list; plain folders drill further.">
          <TreeDrilled titleMode="A"/>
        </Cell>
        <Cell tag="3" label="Flat view" note="Toggle’s other state: ACTIVE then every project flattened — name + full muted path in mono. No drilling.">
          <FlatView/>
        </Cell>
        <Cell tag="4" label="Empty" note="Centered line folder, “No projects yet”, “Pull to refresh”. Calm and muted.">
          <EmptyState/>
        </Cell>
      </div>

      <Divider>Title options · A vs B</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="A" label="Static “Projects” + breadcrumb" note="Recommended. Stable anchor title; the path appears as a dedicated breadcrumb row only when drilled. Clearest mental model, and the title never gets long.">
          <TreeDrilled titleMode="A"/>
        </Cell>
        <Cell tag="B" label="Path-as-title" note="Breadcrumb IS the title (mono). Saves a row, but the title truncates fast on deep paths and the screen loses a stable name.">
          <TreeDrilled titleMode="B"/>
        </Cell>
      </div>

      <Divider>Expandable-tree variant (alternative)</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34, alignItems:'flex-start' }}>
        <Cell label="Inline disclosure tree" note="Each folder has an expand chevron revealing children indented one step — see structure without navigating. Tap the chevrons to expand/collapse. Recommended as secondary: deep paths get cramped on a phone, so drill-down stays primary.">
          <ExpandableTree/>
        </Cell>
        <div style={{ maxWidth:320, paddingTop:6 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>Recommendation</div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.sec }}>
            Ship <b style={{color:T.text}}>drill-down + breadcrumb</b> as the primary tree (screens 1–2): one level per screen respects the phone, keeps tap targets full-width, and is swipe-back friendly. Keep the <b style={{color:T.text}}>expandable inline tree</b> as an opt-in for users who want to eyeball structure. Use <b style={{color:T.text}}>title option A</b> — a stable “Projects” with a breadcrumb row beats a truncating path-as-title.
          </div>
        </div>
      </div>

      <Divider>Toggle placement</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:40, alignItems:'flex-start' }}>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text, marginBottom:14 }}>Placement 1 · top-bar right (primary)</div>
          <div style={{ display:'inline-flex', padding:'18px 22px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, gap:14, alignItems:'center' }}>
            <ViewToggle mode="tree"/>
            <button style={{ all:'unset', width:32, height:30, display:'flex', alignItems:'center', justifyContent:'center' }}>{Ico.gear(T.sec,20)}</button>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, marginTop:11, maxWidth:300 }}>Sits just left of the gear — always reachable, consistent with the app’s top-bar controls.</div>
        </div>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text, marginBottom:14 }}>Placement 2 · section-header right</div>
          <div style={{ width:330, padding:'14px 16px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14 }}>
            <div style={{ display:'flex', alignItems:'center' }}>
              <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase' }}>Projects</span>
              <span style={{ flex:1 }}/>
              <ViewToggle mode="tree"/>
            </div>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, marginTop:11, maxWidth:300 }}>Scoped to the list it controls, but scrolls away with the header and competes with the section label.</div>
        </div>
      </div>

      <Divider>Folder & breadcrumb components</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:18, alignItems:'flex-start' }}>
        <div style={{ width:330, background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:'4px 16px' }}>
          <Row name="experiments"/>
          <Row name="cc-pocket" badge="3 sessions" leaf branch="main"/>
          <Row name="analyse"/>
          <div style={{ borderBottom:'none' }}><Row name="relay-server" badge="2 sessions" leaf branch="feat/ws"/></div>
        </div>
        <div style={{ width:330 }}>
          <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden' }}>
            <Breadcrumb segs={['~','proj','app']}/>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec, marginTop:11 }}>Back ‹ + tappable mono segments; current segment bolded. Appears only when drilled.</div>
        </div>
      </div>

      <Divider>More states</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell label="Loading · shimmer">
          <LoadingState/>
        </Cell>
        <Cell label="No filter match">
          <NoMatch/>
        </Cell>
        <Cell label="Computer offline (amber strip)">
          <StatusStrip kind="offline"/>
        </Cell>
        <Cell label="Reconnecting (red strip)">
          <StatusStrip kind="reconnecting"/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
