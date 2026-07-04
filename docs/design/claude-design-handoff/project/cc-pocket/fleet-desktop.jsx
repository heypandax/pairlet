// cc-pocket — FLEET · desktop (command center)
// reuses desktop-core.jsx: T, AGENTS, AgentGlyph, AgentTag, I, Key, Dot, Window

// ── fleet language ────────────────────────────────────────────
const FSTATUS = { online:T.success, reconnecting:T.warning, offline:T.muted };
const OSG = { mac:'apple', linux:'linux', win:'win' };

function MachineChip({ os, name, status='online', mono=12, glyph=14, bright }) {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:6, minWidth:0 }}>
      <span style={{ display:'flex', flexShrink:0 }}>{I[OSG[os]](T.sec, glyph)}</span>
      <span style={{ fontFamily:T.mono, fontSize:mono, color: bright?T.text:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
      <span className={status==='online'?'dk-pulse':''} style={{ width:6, height:6, borderRadius:999, background:FSTATUS[status], flexShrink:0 }}/>
    </span>
  );
}
function FBadge({ n }) {
  return <span style={{ minWidth:17, height:17, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:10.5, fontWeight:700, display:'inline-flex', alignItems:'center', justifyContent:'center', padding:'0 5px', flexShrink:0 }}>{n}</span>;
}
const Bell = (c=T.sec,s=16)=> <svg width={s} height={s} viewBox="0 0 20 20" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M10 2.8a4.6 4.6 0 00-4.6 4.6c0 4-1.9 5.2-1.9 5.2h13s-1.9-1.2-1.9-5.2A4.6 4.6 0 0010 2.8z"/><path d="M8.4 15.6a1.7 1.7 0 003.2 0"/></svg>;

// ── sidebar: sessions grouped by machine ──────────────────────
function GroupHeader({ os, name, status, badge, thisMac, collapsed, keyHint }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:7, height:32, padding:'0 12px', cursor:'pointer', opacity: status==='offline'?0.5:1 }}>
      <span style={{ display:'flex', transform:collapsed?'rotate(-90deg)':'none', transition:'transform .15s' }}>{I.chevD(T.muted,12)}</span>
      <MachineChip os={os} name={name} status={status} mono={12} glyph={13}/>
      {thisMac && <span style={{ fontFamily:T.ui, fontSize:9.5, color:T.muted, border:`1px solid ${T.border}`, borderRadius:999, padding:'1px 6px', flexShrink:0 }}>this Mac</span>}
      <span style={{ flex:1 }}/>
      {badge && <FBadge n={badge}/>}
      <Key>{keyHint}</Key>
    </div>
  );
}
function SessRow({ title, agent, running, selected, meta }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:30, padding:'0 12px 0 30px', cursor:'pointer', position:'relative', background:selected?T.raised:'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:4, bottom:4, width:2, borderRadius:2, background:T.accent }}/>}
      {running ? <Dot c={T.success} pulse s={5}/> : <span style={{ width:5, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:12.5, color:selected?T.text:T.sec, fontWeight:selected?600:400, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={9.5}/>}
      {meta && <span style={{ fontFamily:T.mono, fontSize:9.5, color:T.muted, flexShrink:0 }}>{meta}</span>}
    </div>
  );
}

function FleetSidebar() {
  return (
    <div style={{ width:280, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column' }}>
      {/* fleet strip + bell */}
      <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:8, padding:'11px 12px', borderBottom:`1px solid ${T.border}` }}>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, flex:1 }}>4 machines · 3 online</span>
        <span style={{ position:'relative', display:'flex', cursor:'pointer', padding:4 }}>
          {Bell(T.sec,16)}
          <span style={{ position:'absolute', top:-1, right:-2, minWidth:15, height:15, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:9.5, fontWeight:700, display:'flex', alignItems:'center', justifyContent:'center', padding:'0 4px' }}>2</span>
        </span>
      </div>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'6px 0' }}>
        <GroupHeader os="mac" name="Lidapeng-MacBook" status="online" thisMac keyHint="⌘1"/>
        <SessRow title="Refactor auth module" running selected/>
        <SessRow title="Fix stream parser test" running/>
        <SessRow title="Tidy CI workflow" agent="codex"/>
        <div style={{ height:8 }}/>
        <GroupHeader os="mac" name="mac-studio" status="online" badge={1} keyHint="⌘2"/>
        <SessRow title="Bump API deps" running/>
        <SessRow title="Port parser to Rust" agent="codex" running/>
        <div style={{ height:8 }}/>
        <GroupHeader os="linux" name="devbox-linux" status="online" badge={1} keyHint="⌘3"/>
        <SessRow title="Run integration tests" running meta="pytest -x · 12m"/>
        <div style={{ height:8 }}/>
        <GroupHeader os="win" name="win-desktop" status="offline" collapsed keyHint="⌘4"/>
        <div style={{ fontFamily:T.mono, fontSize:9.5, color:T.muted, padding:'2px 12px 0 30px', opacity:0.5 }}>offline · 2d</div>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'9px 12px' }}>
        {I.gear(T.sec,15)}<span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>Settings</span>
        <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>v0.5.0</span>
      </div>
    </div>
  );
}

// ── split chat panes ──────────────────────────────────────────
function PaneHeader({ os, name, title, mode, focused, agent }) {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, borderTop: focused?`2px solid ${T.accent}`:'2px solid transparent', padding:'9px 14px', display:'flex', alignItems:'center', gap:9 }}>
      <MachineChip os={os} name={name} mono={11} glyph={12}/>
      <span style={{ color:T.border }}>·</span>
      <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={10}/>}
      <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'2px 8px', flexShrink:0 }}>{mode}</span>
    </div>
  );
}

function LeftPane() {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base }}>
      <PaneHeader os="mac" name="Lidapeng-MacBook" title="Refactor auth module" mode="default" focused/>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
          <div>
            <div style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:6 }}>You</div>
            <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>add a unit test for the stream parser</div>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>
            Feeding a chunked SSE response and asserting the reassembled events. The split-token case straddles a chunk boundary — that's the regression.
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:9, padding:'8px 11px' }}>
            <Dot c={T.success} s={6}/>
            <span style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:600, color:T.text }}>Bash</span>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>gradle :protocol:test</span>
            {I.check(T.success,13)}
          </div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.text }}>
            Both cases pass. Wiring the EOF guard next<span className="dk-caret"/>
          </div>
        </div>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'10px 14px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'8px 10px' }}>
          <span style={{ display:'flex' }}>{I.paperclip(T.sec,15)}</span>
          <span style={{ flex:1, fontFamily:T.ui, fontSize:12.5, color:T.muted }}>Message Claude…</span>
          <span style={{ width:28, height:28, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send('#0E0F11',13)}</span>
        </div>
      </div>
    </div>
  );
}

function RightPane() {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base, opacity:0.82 }}>
      <PaneHeader os="linux" name="devbox-linux" title="Run integration tests" mode="acceptEdits"/>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'12px 14px' }}>
        <pre style={{ margin:0, fontFamily:T.mono, fontSize:10.5, lineHeight:'17px', color:T.sec, whiteSpace:'pre-wrap' }}>{`$ pytest -x tests/integration
============ test session starts ============
platform linux · python 3.12.1
collected 48 items

tests/integration/test_relay.py ......   [ 12%]
tests/integration/test_ws.py ........    [ 29%]
tests/integration/test_pairing.py ....   [ 37%]
tests/integration/test_e2e.py ....F

FAILED test_e2e.py::test_reconnect_backoff
  socket closed before backoff timer fired
  retrying with --lf`}</pre>
      </div>
      {/* waiting-approval strip */}
      <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:9, background:'rgba(217,119,87,0.10)', borderTop:`1px solid rgba(217,119,87,0.45)`, padding:'9px 14px' }}>
        {I.shield(T.accent,14)}
        <span style={{ fontFamily:T.ui, fontSize:12, color:T.text, flex:1 }}>⏸ waiting approval — <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>Write: Relay.kt +42 −7</span></span>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.warning }}>0:41</span>
        <span className="dk-btn" style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:700, color:'#0E0F11', background:T.accent, borderRadius:999, padding:'4px 12px', cursor:'pointer' }}>Review</span>
      </div>
    </div>
  );
}

function FleetWindow() {
  return (
    <Window w={1280} h={800} titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      <FleetSidebar/>
      <div style={{ flex:1, minWidth:0, display:'flex' }}>
        <LeftPane/>
        <div style={{ width:1, background:T.border, cursor:'col-resize', flexShrink:0 }}/>
        <RightPane/>
      </div>
    </Window>
  );
}

// ════════════════ ⑦ ATTENTION POPOVER ════════════════
function PopRow({ os, name, tool, preview, t, amber, hover }) {
  return (
    <div className="dk-row" style={{ padding:'9px 11px', borderRadius:9, cursor:'pointer', background:hover?T.hover:'transparent' }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:6 }}>
        <MachineChip os={os} name={name} mono={10.5} glyph={12}/>
        <span style={{ color:T.border }}>·</span>
        <span style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:600, color:T.text }}>{tool}</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:amber?T.warning:T.sec }}>{t}</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, background:T.base, border:`1px solid ${T.border}`, borderRadius:7, padding:'6px 8px', marginBottom:8, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{preview}</div>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        {hover && <span style={{ fontFamily:T.ui, fontSize:10.5, color:T.sec }}>Open session ↗</span>}
        <span style={{ flex:1 }}/>
        <span className="dk-btn" style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, color:T.danger, border:`1px solid ${T.danger}55`, borderRadius:7, padding:'4px 12px', cursor:'pointer' }}>Deny</span>
        <span className="dk-btn" style={{ fontFamily:T.ui, fontSize:11, fontWeight:700, color:'#0E0F11', background:T.accent, borderRadius:7, padding:'4px 12px', cursor:'pointer' }}>Allow</span>
      </div>
    </div>
  );
}

function AttentionPopover() {
  return (
    <div style={{ width:1280, height:800, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0, opacity:0.5 }}><FleetWindow/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.5)' }}/>
      <div style={{ position:'absolute', top:86, left:64, width:380, background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, boxShadow:'0 28px 70px -18px rgba(0,0,0,0.75)', overflow:'hidden' }}>
        <div style={{ width:12, height:12, background:T.raised, border:`1px solid ${T.border}`, borderBottom:'none', borderRight:'none', transform:'rotate(45deg)', position:'absolute', top:-6, left:190, borderRadius:'2px 0 0 0' }}/>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'12px 14px', borderBottom:`1px solid ${T.border}` }}>
          <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:700, color:T.text }}>Needs you</span>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>4 machines · 3 online · 2 waiting</span>
        </div>
        <div style={{ padding:'8px 6px' }}>
          <PopRow os="mac" name="mac-studio" tool="Bash" preview="rm -rf ./build && ./gradlew clean" t="0:23" amber/>
          <PopRow os="linux" name="devbox-linux" tool="Write" preview="Relay.kt  +42 −7" t="0:41" hover/>
        </div>
        <div style={{ padding:'4px 14px 10px', borderTop:`1px solid ${T.border}` }}>
          <div style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', margin:'8px 0 6px' }}>Recently finished</div>
          {[['mac','Lidapeng-MacBook','Refactor auth module',true],['linux','devbox-linux','Fix stream parser test',false]].map(([os,n,t,ok],i)=>(
            <div key={i} style={{ display:'flex', alignItems:'center', gap:7, padding:'5px 0' }}>
              <MachineChip os={os} name={n} mono={10} glyph={11}/>
              <span style={{ fontFamily:T.ui, fontSize:11, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{t}</span>
              {ok ? I.check(T.success,12) : I.x(T.danger,12)}
            </div>
          ))}
          <div style={{ fontFamily:T.ui, fontSize:10, color:T.muted, marginTop:8, lineHeight:'15px' }}>Approve here only for previews you can fully read — click through for diffs.</div>
        </div>
      </div>
    </div>
  );
}

// ════════════════ ⑧ COMMAND PALETTE · machine verbs ════════════════
function PSec({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:10, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase', padding:'10px 14px 4px' }}>{children}</div>;
}
function PRow({ icon, label, mono, right, selected, accentText }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:10, height:38, padding:'0 14px', cursor:'pointer', position:'relative', background:selected?T.raised:'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
      <span style={{ display:'flex', width:16, justifyContent:'center', flexShrink:0 }}>{icon}</span>
      <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:selected?600:500, color: accentText?T.accent:T.text, flexShrink:0 }}>{label}</span>
      {mono && <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{mono}</span>}
      {!mono && <span style={{ flex:1 }}/>}
      {right}
    </div>
  );
}

function FleetPalette() {
  return (
    <div style={{ width:1280, height:800, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0, opacity:0.5 }}><FleetWindow/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.62)' }}/>
      <div style={{ position:'absolute', top:80, left:'50%', transform:'translateX(-50%)', width:560, background:T.raised, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden', boxShadow:'0 30px 80px -20px rgba(0,0,0,0.8)' }}>
        <div style={{ display:'flex', alignItems:'center', gap:11, padding:'13px 16px', borderBottom:`1px solid ${T.border}` }}>
          {I.search(T.sec,16)}
          <span style={{ fontFamily:T.ui, fontSize:15, color:T.text }}>mac<span className="dk-caret" style={{ height:16, width:1.5, marginLeft:1 }}/></span>
        </div>
        <div style={{ paddingBottom:6 }}>
          <PSec>Machines</PSec>
          <PRow selected icon={I.apple(T.text,14)} label="Switch to mac-studio" right={<span style={{ display:'flex', alignItems:'center', gap:7 }}><FBadge n={1}/><Key>⌘2</Key></span>}/>
          <PRow icon={I.apple(T.sec,14)} label="Switch to Lidapeng-MacBook" mono="this Mac" right={<Key>⌘1</Key>}/>
          <PSec>Actions</PSec>
          <PRow icon={I.folder(T.sec,14)} label="New session on mac-studio…" mono="~/work/api-server"/>
          <PRow icon={I.folder(T.sec,14)} label="New session on devbox-linux…" mono="~/src/relay"/>
          <PRow icon={I.shield(T.accent,14)} label="Approve pending on mac-studio" accentText mono="Bash rm -rf ./build" right={<span style={{ fontFamily:T.mono, fontSize:10.5, color:T.warning }}>0:23</span>}/>
          <PSec>Sessions</PSec>
          <PRow icon={I.bubble(T.sec,13)} label="Refactor auth module" mono="Lidapeng-MacBook · ⑂ main"/>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:9, padding:'9px 16px', borderTop:`1px solid ${T.border}` }}>
          <Key>↑</Key><Key>↓</Key><span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>navigate</span>
          <Key>⏎</Key><span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>run</span>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>7 results</span>
        </div>
      </div>
    </div>
  );
}

// ════════════════ BOARD ════════════════
function FDivider({ children, sub }) {
  return (
    <div style={{ margin:'64px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:10, maxWidth:780 }}>{sub}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1360, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · fleet · desktop</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Fleet — the desktop as a command center</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:740, margin:0 }}>
        Everything visible at once: sessions from all four machines grouped in the sidebar, two chats side by side, approvals handled inline or from the bell — without leaving your current session. ⌘K gains machine verbs; ⌘1–4 jump between machines.
      </p>

      <FDivider sub="Sidebar groups sessions BY MACHINE — each header a MachineChip + AttentionBadge + collapse chevron + its ⌘n jump key; this Mac is tagged. The chat area splits into two panes: chatting with Claude on the MacBook (focused, 2px terracotta top hairline) while watching pytest stream on devbox-linux, whose pane carries a ⏸ waiting-approval strip.">⑥ Main window · grouped sidebar + split panes</FDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><FleetWindow/></div>

      <FDivider sub="The bell opens a popover for cross-machine approvals without leaving the focused session: compact rows with cursor-sized Deny/Allow, hover reveals “Open session ↗”, recently-finished below, and a caution footer about approving only fully-readable previews.">⑦ Attention popover</FDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><AttentionPopover/></div>

      <FDivider sub="⌘K with machine verbs: switch machines (⌘1–4 hints + badges), start a session on a specific machine, approve a pending request right from the palette (shield row, terracotta, live countdown), and jump to sessions. Monochrome + one ember.">⑧ Command palette · machine verbs</FDivider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><FleetPalette/></div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
