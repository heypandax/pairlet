// cc-pocket — Desktop sidebar redesign: 3 stable zones + pinned sessions
// reuses desktop-core.jsx: T, AGENTS, AgentTag, I, Key, Dot, Window

// ── local atoms ───────────────────────────────────────────────
const PI = {
  pinO:(c=T.sec,s=13)=> <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M6.2 2h3.6l-.4 3.5 2.3 2.7H4.3l2.3-2.7z"/><path d="M8 8.2V14"/></svg>,
  pinSlash:(c=T.sec,s=13)=> <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M6.2 2h3.6l-.4 3.5 2.3 2.7H8.6M4.9 5.7l-.6.5h3.1M8 8.2V14"/><path d="M2.5 2.5l11 11" stroke={c}/></svg>,
  grip:(c=T.muted,s=12)=> <svg width={s} height={s} viewBox="0 0 12 16" fill={c}><circle cx="4" cy="3" r="1.2"/><circle cx="8" cy="3" r="1.2"/><circle cx="4" cy="8" r="1.2"/><circle cx="8" cy="8" r="1.2"/><circle cx="4" cy="13" r="1.2"/><circle cx="8" cy="13" r="1.2"/></svg>,
  bell:(c=T.sec,s=16)=> <svg width={s} height={s} viewBox="0 0 20 20" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M10 2.8a4.6 4.6 0 00-4.6 4.6c0 4-1.9 5.2-1.9 5.2h13s-1.9-1.2-1.9-5.2A4.6 4.6 0 0010 2.8z"/><path d="M8.4 15.6a1.7 1.7 0 003.2 0"/></svg>,
};
const OSG = { mac:'apple', linux:'linux', win:'win' };

function Host({ os, name, dim, mono=12, glyph=13, color }) {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:6, minWidth:0, opacity:dim?0.55:1 }}>
      <span style={{ display:'flex', flexShrink:0 }}>{I[OSG[os]](T.sec, glyph)}</span>
      <span style={{ fontFamily:T.mono, fontSize:mono, color:color||T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
    </span>
  );
}
function CountPill({ n }) {
  return <span style={{ minWidth:17, height:17, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:10.5, fontWeight:700, display:'inline-flex', alignItems:'center', justifyContent:'center', padding:'0 5px', flexShrink:0 }}>{n}</span>;
}
function OutlinePill({ children }) {
  return <span style={{ fontFamily:T.ui, fontSize:9.5, color:T.muted, border:`1px solid ${T.border}`, borderRadius:999, padding:'1px 7px', flexShrink:0, whiteSpace:'nowrap' }}>{children}</span>;
}
function SecLabel({ children, right }) {
  return (
    <div style={{ display:'flex', alignItems:'center', padding:'14px 12px 6px' }}>
      <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase' }}>{children}</span>
      <span style={{ flex:1 }}/>
      {right}
    </div>
  );
}

// ── zone 1: machine switcher header ───────────────────────────
function SwitcherHeader({ offline }) {
  return (
    <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:8, height:48, padding:'0 12px', borderBottom:`1px solid ${T.border}` }}>
      <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:7, padding:'5px 8px', margin:'0 -8px', borderRadius:8, cursor:'pointer', minWidth:0, flex:1 }}>
        <Host os="mac" name="Lidapeng-MBP" mono={12.5} glyph={14}/>
        {offline
          ? <span style={{ display:'flex', alignItems:'center', gap:5, flexShrink:0 }}><Dot c={T.muted} s={6}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>reconnecting…</span></span>
          : <Dot c={T.success} pulse s={6}/>}
        <span style={{ display:'flex', flexShrink:0 }}>{I.chevD(T.muted,13)}</span>
      </div>
      <Key>⌘0</Key>
      <span style={{ position:'relative', display:'flex', cursor:'pointer', padding:3, flexShrink:0 }}>
        {PI.bell(T.sec,16)}
        <span style={{ position:'absolute', top:-3, right:-4, minWidth:15, height:15, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:9.5, fontWeight:700, display:'flex', alignItems:'center', justifyContent:'center', padding:'0 4px' }}>2</span>
      </span>
    </div>
  );
}

// ── zone 2: pinned rows ───────────────────────────────────────
function PinRow({ title, os, host, running, pending, agent, kcap, hover, dim, dragging }) {
  return (
    <div className={dragging?'':'dk-row'} style={{
      display:'flex', alignItems:'center', gap:7, height:32, padding:'0 12px', cursor:'pointer', position:'relative',
      background: dragging||hover ? T.raised : 'transparent', opacity:dim?0.55:1,
      ...(dragging ? { boxShadow:'0 10px 26px rgba(0,0,0,0.55), 0 0 0 1px '+T.border, borderRadius:8, transform:'scale(1.02)', zIndex:3 } : {}),
    }}>
      {(hover||dragging) ? <span style={{ display:'flex', flexShrink:0, cursor:'grab' }}>{PI.grip(T.muted,12)}</span>
        : running ? <Dot c={T.success} pulse s={5}/> : <span style={{ width:5, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:13, color:T.text, fontWeight:500, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', flexShrink:1, minWidth:84 }}>{title}</span>
      {os && <span style={{ display:'inline-flex', alignItems:'center', gap:4, flexShrink:1, minWidth:0 }}>
        <span style={{ display:'flex', flexShrink:0 }}>{I[OSG[os]](T.muted,11)}</span>
        <span style={{ fontFamily:T.mono, fontSize:10, color:T.muted, maxWidth:60, minWidth:36, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{host}</span>
      </span>}
      {agent==='codex' && (os && pending
        ? <span style={{ display:'flex', flexShrink:0 }} title="Codex"><AgentGlyph agent="codex" s={11}/></span>
        : <AgentTag agent="codex" s={9.5}/>)}
      {pending && <CountPill n={pending}/>}
      <span style={{ flex:1 }}/>
      {(hover||dragging) && <span style={{ display:'flex', flexShrink:0, cursor:'pointer' }}>{PI.pinSlash(T.sec,13)}</span>}
      <Key>{kcap}</Key>
    </div>
  );
}
function SlotIndicator() {
  return <div style={{ height:2, borderRadius:2, background:T.accent, margin:'1px 12px' }}/>;
}

// ── zone 3: running ───────────────────────────────────────────
function RunRow({ proj, host }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:30, padding:'0 12px', cursor:'pointer' }}>
      <Dot c={T.accent} pulse s={5}/>
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{proj}</span>
      <span style={{ fontFamily:T.mono, fontSize:10, color:T.muted, flexShrink:0 }}>{host}</span>
    </div>
  );
}

// ── zone 4: browse (projects + docked sessions) ───────────────
function ProjRow({ name, running, history }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:30, padding:'0 12px', cursor:'pointer' }}>
      {I.folder(T.sec,14)}
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
      {running && <Dot c={T.accent} pulse s={5}/>}
      {history && <OutlinePill>history</OutlinePill>}
    </div>
  );
}
function SessRow({ title, selected, running, agent, pending, hover, kcapNew }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:7, height:32, padding:'0 12px 0 12px', cursor:'pointer', position:'relative',
      background: selected||hover ? T.raised : 'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:4, bottom:4, width:2, borderRadius:2, background:T.accent }}/>}
      {running ? <Dot c={T.success} pulse s={5}/> : <span style={{ width:5, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:13, color:selected?T.text:T.sec, fontWeight:selected?600:400, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={9.5}/>}
      {pending && <CountPill n={pending}/>}
      {hover && <>
        <span style={{ display:'flex', flexShrink:0, cursor:'pointer' }} title="Pin">{PI.pinO(T.accent,13)}</span>
        <span style={{ display:'flex', flexShrink:0, cursor:'pointer' }}>{I.x(T.muted,13)}</span>
      </>}
    </div>
  );
}
function NewRow({ label, kcap }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:32, padding:'0 12px', cursor:'pointer' }}>
      {I.plus(T.accent,13)}
      <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.accent, fontWeight:500, flex:1, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{label}</span>
      {kcap && <Key>{kcap}</Key>}
    </div>
  );
}

// ── the sidebar ───────────────────────────────────────────────
function Sidebar({ pinnedVariant='default', sessHover=false, offline=false }) {
  return (
    <div style={{ width:300, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column', minHeight:0 }}>
      <SwitcherHeader offline={offline}/>

      {/* PINNED */}
      <div style={{ flexShrink:0 }}>
        <SecLabel right={<Key>⌘1–9</Key>}>Pinned</SecLabel>
        {pinnedVariant==='empty' ? (
          <div style={{ padding:'4px 12px 8px', fontFamily:T.ui, fontSize:11.5, lineHeight:'17px', color:T.muted }}>
            Pin a session to keep it here — hover any session and hit the pin.
          </div>
        ) : pinnedVariant==='dragging' ? (
          <>
            <PinRow title="Refactor auth module" running kcap="⌘1"/>
            <SlotIndicator/>
            <PinRow title="Port parser to Rust" agent="codex" pending={1} os="mac" host="mac-studio" kcap="⌘3" dragging/>
            <PinRow title="Run integration tests" os="linux" host="devbox-linux" running kcap="⌘2"/>
          </>
        ) : (
          <>
            <PinRow title="Refactor auth module" running kcap="⌘1" dim={offline}/>
            <PinRow title="Run integration tests" os="linux" host="devbox-linux" running kcap="⌘2"/>
            <PinRow title="Port parser to Rust" agent="codex" pending={1} os="mac" host="mac-studio" kcap="⌘3"/>
          </>
        )}
      </div>

      {/* RUNNING */}
      <div style={{ flexShrink:0 }}>
        <SecLabel>Running</SecLabel>
        <RunRow proj="cc-pocket" host="Lidapeng-MBP"/>
        <RunRow proj="relay" host="devbox-linux"/>
      </div>

      {/* BROWSE: projects (flex) */}
      <div style={{ flex:1, minHeight:0, display:'flex', flexDirection:'column', borderTop:`1px solid ${T.border}`, marginTop:10 }}>
        <SecLabel>Projects</SecLabel>
        <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto' }}>
          <NewRow label="New session at path…"/>
          <ProjRow name="cc-pocket" running/>
          <ProjRow name="dotfiles" history/>
          <ProjRow name="pandax-site" history/>
          <ProjRow name="ml-notes"/>
          <ProjRow name="scripts"/>
          <ProjRow name="api-sandbox"/>
        </div>
        {/* SESSIONS docked pane */}
        <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', flexDirection:'column', maxHeight:216 }}>
          <SecLabel>Sessions · cc-pocket</SecLabel>
          <div className="dk-scroll" style={{ minHeight:0, overflowY:'auto' }}>
            <NewRow label="New session" kcap="⌘N"/>
            <SessRow title="Refactor auth module" selected running/>
            <SessRow title="Fix stream parser test" running hover={sessHover}/>
            <SessRow title="Tidy CI workflow" agent="codex"/>
            <SessRow title="Wire up pairing flow"/>
          </div>
        </div>
      </div>

      {/* footer */}
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'9px 12px' }}>
        {I.gear(T.sec,15)}<span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>Settings</span>
        <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>v0.6.0</span>
      </div>
    </div>
  );
}

// ── chat pane (context) ───────────────────────────────────────
function ChatPane() {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, padding:'10px 18px', display:'flex', alignItems:'center', gap:10 }}>
        <div style={{ flex:1, minWidth:0 }}>
          <div style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>Refactor auth module</div>
          <div style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, marginTop:3 }}>~/code/cc-pocket · ⑂ main · sonnet</div>
        </div>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'2px 9px' }}>default</span>
        <span style={{ display:'flex' }}>{I.dots(T.sec,15)}</span>
      </div>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'18px 22px' }}>
        <div style={{ maxWidth:700, margin:'0 auto', display:'flex', flexDirection:'column', gap:14 }}>
          <div>
            <div style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:6 }}>You</div>
            <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>add a unit test for the stream parser</div>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>
            Feeding a chunked SSE response and asserting the reassembled events — a happy path plus a split-token case straddling a chunk boundary.
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:9, padding:'8px 11px' }}>
            <Dot c={T.success} s={6}/>
            <span style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:600, color:T.text }}>Bash</span>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>gradle :protocol:test</span>
            {I.check(T.success,13)}
          </div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>
            Both pass. Wiring the EOF guard next<span className="dk-caret"/>
          </div>
        </div>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'10px 18px' }}>
        <div style={{ maxWidth:700, margin:'0 auto' }}>
          <div style={{ display:'flex', alignItems:'center', gap:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'8px 10px' }}>
            <span style={{ display:'flex' }}>{I.paperclip(T.sec,15)}</span>
            <span style={{ flex:1, fontFamily:T.ui, fontSize:12.5, color:T.muted }}>Message Claude…</span>
            <span style={{ width:28, height:28, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send('#0E0F11',13)}</span>
          </div>
          <div style={{ fontFamily:T.mono, fontSize:10, color:T.muted, marginTop:6, textAlign:'right' }}>⏎ send · ⇧⏎ newline</div>
        </div>
      </div>
    </div>
  );
}

function Shell({ sidebar }) {
  return (
    <Window w={1280} h={800} titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      {sidebar}
      <ChatPane/>
    </Window>
  );
}

// ── screen 2: machine switcher dropdown ───────────────────────
function DropRow({ os, name, thisMac, current, offline, badge, kcap }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:36, padding:'0 12px', cursor:offline?'default':'pointer', position:'relative',
      background: current?T.raised:'transparent', opacity:offline?0.55:1 }}>
      {current && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
      <Host os={os} name={name} mono={12} glyph={13} color={current?T.text:T.sec}/>
      <Dot c={offline?T.muted:T.success} pulse={!offline} s={5}/>
      {thisMac && <OutlinePill>this Mac</OutlinePill>}
      <span style={{ flex:1 }}/>
      {badge && <CountPill n={badge}/>}
      <Key>{kcap}</Key>
    </div>
  );
}
function SwitcherDropdown() {
  return (
    <div style={{ width:280, background:T.raised, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', boxShadow:'0 24px 60px -14px rgba(0,0,0,0.75)' }}>
      <div style={{ padding:'4px 0' }}>
        <DropRow os="mac" name="Lidapeng-MBP" thisMac current kcap="1"/>
        <DropRow os="mac" name="mac-studio" badge={1} kcap="2"/>
        <DropRow os="linux" name="devbox-linux" badge={1} kcap="3"/>
        <DropRow os="win" name="win-desktop" offline kcap="4"/>
      </div>
      <div style={{ borderTop:`1px solid ${T.border}`, padding:6 }}>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:32, padding:'0 8px', borderRadius:7, border:`1px dashed ${T.border}`, cursor:'pointer' }}>
          {I.plus(T.accent,13)}
          <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.accent, fontWeight:500 }}>Add computer</span>
        </div>
      </div>
    </div>
  );
}
function DropdownScreen() {
  return (
    <div style={{ width:1280, height:800, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0 }}><Shell sidebar={<Sidebar/>}/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.45)' }}/>
      <div style={{ position:'absolute', top:76, left:10 }}><SwitcherDropdown/></div>
    </div>
  );
}

// ── insets (sidebar fragments) ────────────────────────────────
function Fragment({ children, h }) {
  return <div style={{ width:300, height:h, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', display:'flex', flexDirection:'column' }}>{children}</div>;
}
function EmptyPinInset() {
  return (
    <Fragment>
      <SwitcherHeader/>
      <SecLabel right={<Key>⌘1–9</Key>}>Pinned</SecLabel>
      <div style={{ padding:'4px 12px 12px', fontFamily:T.ui, fontSize:11.5, lineHeight:'17px', color:T.muted }}>
        Pin a session to keep it here — hover any session and hit the pin.
      </div>
      <SecLabel>Running</SecLabel>
      <RunRow proj="cc-pocket" host="Lidapeng-MBP"/>
      <div style={{ height:10 }}/>
    </Fragment>
  );
}
function OfflineInset() {
  return (
    <Fragment>
      <SwitcherHeader offline/>
      <SecLabel right={<Key>⌘1–9</Key>}>Pinned</SecLabel>
      <PinRow title="Refactor auth module" running kcap="⌘1" dim/>
      <PinRow title="Run integration tests" os="linux" host="devbox-linux" running kcap="⌘2"/>
      <PinRow title="Port parser to Rust" agent="codex" pending={1} os="mac" host="mac-studio" kcap="⌘3"/>
      <div style={{ padding:'6px 12px 12px', fontFamily:T.ui, fontSize:11, lineHeight:'16px', color:T.muted }}>
        This Mac is reconnecting — its pinned rows dim but stay clickable; remote pins are unaffected.
      </div>
    </Fragment>
  );
}
function CapInset() {
  return (
    <Fragment>
      <SecLabel right={<Key>⌘1–9</Key>}>Pinned</SecLabel>
      <PinRow title="Tidy CI workflow" agent="codex" kcap="⌘8"/>
      <PinRow title="Wire up pairing flow" kcap="⌘9"/>
      <div style={{ margin:'6px 12px 12px', padding:'8px 10px', background:T.base, border:`1px solid ${T.border}`, borderRadius:8, fontFamily:T.ui, fontSize:11, lineHeight:'16px', color:T.sec }}>
        Pinned is full (9) — unpin a session to add another.
      </div>
    </Fragment>
  );
}

// ── board ─────────────────────────────────────────────────────
function BDivider({ children, sub }) {
  return (
    <div style={{ margin:'64px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:10, maxWidth:790, lineHeight:'20px' }}>{sub}</div>}
    </div>
  );
}
function InsetLabel({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text, margin:'0 0 10px' }}>{children}</div>;
}

function Page() {
  return (
    <div style={{ maxWidth:1380, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop · sidebar</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Sidebar redesign — three zones + pinned sessions</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:760, margin:0 }}>
        Each vertical region now has one job: the header owns machines (remote machines live in its dropdown, not the body), PINNED + RUNNING own fast switching, and the browse zone below is only ever the current machine — with the open project's sessions docked at the bottom so a 100-item project list can never bury them. ⌘1–9 jump to pins, ⌘0 opens the switcher, ⌘N starts a session here, ⌘K the palette.
      </p>

      <BDivider sub="Three pins: one local + running, one on devbox-linux (mono machine suffix), one Codex session on mac-studio holding a pending approval. RUNNING stays flat across every machine — terracotta pulse + mono project + muted machine. PROJECTS scrolls; SESSIONS · cc-pocket is docked with its own scroll behind a hairline; selected row carries the 2px terracotta rail.">① Default · populated</BDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><Shell sidebar={<Sidebar/>}/></div>

      <BDivider sub="⌘0 or a click on the header opens the switcher: all four machines with online dots, per-row pending badges, a “this Mac” outline pill, digit keycaps while open, and the dashed “Add computer” docked at the bottom. win-desktop is offline and dimmed to 55%. The current machine is highlighted with the raised fill + terracotta rail.">② Machine switcher · dropdown open</BDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><DropdownScreen/></div>

      <BDivider sub="Hovering “Fix stream parser test” raises the row and fades in the terracotta pin (click → springs into PINNED) beside the close ✕. Above, “Port parser to Rust” is mid-drag: lifted with a soft shadow, grip + pin-slash visible, and a 2px terracotta slot indicator marking where it will land — shortcut numbers reassign to follow the new order on drop.">③ Pin interactions · hover + drag</BDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><Shell sidebar={<Sidebar pinnedVariant="dragging" sessHover/>}/></div>

      <div style={{ display:'flex', gap:26, flexWrap:'wrap', marginTop:34, alignItems:'flex-start' }}>
        <div><InsetLabel>Pinned empty · hint row, no empty box</InsetLabel><EmptyPinInset/></div>
        <div><InsetLabel>Current machine offline · reconnecting</InsetLabel><OfflineInset/></div>
        <div><InsetLabel>Cap reached · gentle inline notice</InsetLabel><CapInset/></div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
