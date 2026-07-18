// cc-pocket — desktop · embedded terminal in the ChatPane
// Reuses desktop-core.jsx (T, I, Key, Dot, Window, AgentGlyph, AgentTag).

// ── extra 1.5pt line icons ────────────────────────────────────
const TI = {
  terminal:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="3" width="14" height="12" rx="2"/><path d="M5.5 7.5L8 9.5l-2.5 2M9.5 11.5h3"/></svg>,
  external:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M10 3.5h4.5V8"/><path d="M14 4l-6 6"/><path d="M12.5 10.5v3A1.5 1.5 0 0111 15H4.5A1.5 1.5 0 013 13.5V7A1.5 1.5 0 014.5 5.5h3"/></svg>,
  chevDown:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M4.5 7L9 11.5 13.5 7" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevUp:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M4.5 11L9 6.5 13.5 11" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  check:(c=T.accent,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>,
};

// ════════════════ SIDEBAR (compact, session list) ════════════════
function SbLabel({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase', padding:'14px 14px 6px' }}>{children}</div>;
}
function SbSession({ title, agent, running, selected }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 14px 0 12px', cursor:'pointer', position:'relative', background: selected?T.raised:'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
      {running ? <Dot c={T.success} pulse s={6}/> : <span style={{ width:6, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:13, color: selected?T.text:T.sec, fontWeight: selected?600:400, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={10}/>}
    </div>
  );
}
function Sidebar() {
  return (
    <div style={{ width:280, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column' }}>
      <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'11px 14px', cursor:'pointer', borderBottom:`1px solid ${T.border}` }}>
        {I.apple(T.sec,15)}
        <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Lidapeng-MBP</span>
        <Dot c={T.success} pulse s={6}/>
        <span style={{ flex:1 }}/>{I.chevD(T.muted,14)}
      </div>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto' }}>
        <SbLabel>Projects</SbLabel>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, height:34, padding:'0 14px', cursor:'pointer' }}>
          {I.folder(T.sec,15)}<span style={{ fontFamily:T.mono, fontSize:13, color:T.text, flex:1 }}>cc-pocket</span>
          <span style={{ display:'flex', alignItems:'center', gap:4 }}><Dot c={T.accent} pulse s={6}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.accent }}>running</span></span>
        </div>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, height:34, padding:'0 14px', cursor:'pointer' }}>{I.folder(T.sec,15)}<span style={{ fontFamily:T.mono, fontSize:13, color:T.sec, flex:1 }}>relay</span></div>
        <SbLabel>Sessions</SbLabel>
        <SbSession title="Fix relay reconnect" running selected/>
        <SbSession title="Port parser to Rust" agent="codex" running/>
        <SbSession title="Add WS reconnect test" running/>
        <SbSession title="Refactor auth module"/>
        <SbSession title="Tidy CI workflow" agent="codex"/>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'9px 14px' }}>
        {I.gear(T.sec,16)}<span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Settings</span>
        <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>v0.4.1</span>
      </div>
    </div>
  );
}

// ════════════════ CHAT STREAM ════════════════
function ChatSubHeader() {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, padding:'10px 18px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Fix relay reconnect</span>
        <span style={{ flex:1 }}/>
        <span style={{ display:'flex', alignItems:'center', gap:5, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>default</span>{I.chevD(T.muted,12)}
        </span>
        <span style={{ display:'flex', cursor:'pointer' }}>{I.dots(T.sec,16)}</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:5 }}>~/code/cc-pocket&nbsp; · &nbsp;⑂ main&nbsp; · &nbsp;sonnet</div>
    </div>
  );
}
function CodeBlock({ lang, children }) {
  return (
    <div style={{ background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', margin:'2px 0' }}>
      <div style={{ display:'flex', alignItems:'center', padding:'7px 12px', borderBottom:`1px solid ${T.border}` }}>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>{lang}</span><span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>copy</span>
      </div>
      <pre style={{ margin:0, padding:'11px 13px', fontFamily:T.mono, fontSize:12, lineHeight:'19px', color:T.sec, overflowX:'auto' }}>{children}</pre>
    </div>
  );
}
function ChatStream() {
  return (
    <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'20px 18px' }}>
      <div style={{ maxWidth:720, margin:'0 auto', display:'flex', flexDirection:'column', gap:16 }}>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:7 }}>You</div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>the websocket reconnect dies after the 3rd retry — can you find why and add a regression test?</div>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text }}>
          The reconnect loop runs on the socket's own <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px' }}>CoroutineScope</span>, which is cancelled the moment the socket closes — so the backoff timer never fires. Moving it to an app-level scope fixes it. Run <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px' }}>git status</span> in the terminal below to see the two touched files plus the new test.
        </div>
        <CodeBlock lang="kotlin">{`fun reconnect() {
  appScope.launch { delay(backoff); open() }
}`}</CodeBlock>
      </div>
    </div>
  );
}
function Composer() {
  return (
    <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'12px 18px 14px' }}>
      <div style={{ maxWidth:720, margin:'0 auto' }}>
        <div style={{ display:'flex', alignItems:'flex-end', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'8px 10px 8px 12px' }}>
          <span style={{ display:'flex', paddingBottom:5 }}>{I.paperclip(T.sec,17)}</span>
          <div style={{ flex:1, fontFamily:T.ui, fontSize:14, color:T.muted, padding:'6px 0' }}>Message Claude…</div>
          <button style={{ all:'unset', cursor:'pointer', width:34, height:34, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send('#0E0F11',16)}</button>
        </div>
      </div>
    </div>
  );
}

// ════════════════ EMBEDDED TERMINAL PANEL ════════════════
function BranchChip() {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:4, background:T.base, border:`1px solid ${T.border}`, borderRadius:999, padding:'1px 8px 1px 6px', flexShrink:0 }}>
      {I.branch(T.muted,11)}<span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>main</span>
    </span>
  );
}
function HeaderIconBtn({ children, title }) {
  return <span className="dk-btn tm-iconbtn" title={title} style={{ display:'flex', alignItems:'center', justifyContent:'center', width:26, height:26, borderRadius:7, cursor:'pointer' }}>{children}</span>;
}
function TermHeader({ onTermIcon, menuOpen }) {
  return (
    <div style={{ height:32, flexShrink:0, background:T.raised, borderTop:`1px solid ${T.border}`, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'0 8px 0 11px' }}>
      <span className="dk-btn tm-iconbtn" onClick={onTermIcon} style={{ display:'flex', alignItems:'center', justifyContent:'center', width:24, height:24, borderRadius:6, cursor:'pointer', background: menuOpen?T.surface:'transparent' }}>{TI.terminal(menuOpen?T.text:T.sec,15)}</span>
      <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, flexShrink:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>~/code/cc-pocket/…/relay</span>
      <BranchChip/>
      <span style={{ flex:1 }}/>
      <HeaderIconBtn title="Open in external window">{TI.external(T.sec,15)}</HeaderIconBtn>
      <HeaderIconBtn title="Collapse">{TI.chevDown(T.sec,15)}</HeaderIconBtn>
      <HeaderIconBtn title="Close terminal">{I.x(T.muted,15)}</HeaderIconBtn>
    </div>
  );
}

// git status output, colour-coded
function TermBody({ focused }) {
  const L = ({ c=T.sec, indent=0, children }) => <div style={{ color:c, paddingLeft:indent }}>{children}</div>;
  return (
    <div style={{ flex:1, minHeight:0, position:'relative', background:'#0B0C0D', overflow:'hidden' }}>
      {/* scrollback top fade */}
      <div style={{ position:'absolute', top:0, left:0, right:0, height:22, background:'linear-gradient(#0B0C0D, rgba(11,12,13,0))', zIndex:2, pointerEvents:'none' }}/>
      <div className="dk-scroll" style={{ position:'absolute', inset:0, overflowY:'auto', padding:'12px 14px', fontFamily:T.mono, fontSize:13, lineHeight:'20px' }}>
        <L c={T.text}><span style={{ color:T.accent }}>$</span> git status</L>
        <L c={T.sec}>On branch <span style={{ color:T.text }}>main</span></L>
        <L c={T.muted}>Your branch is up to date with 'origin/main'.</L>
        <div style={{ height:14 }}/>
        <L c={T.text}>Changes not staged for commit:</L>
        <L c={T.muted} indent={16}>(use "git add &lt;file&gt;…" to update what will be committed)</L>
        <L c={T.muted} indent={16}>(use "git restore &lt;file&gt;…" to discard changes)</L>
        <L c={T.danger} indent={16}>modified:   src/relay/WsClient.kt</L>
        <L c={T.danger} indent={16}>modified:   src/relay/Backoff.kt</L>
        <div style={{ height:14 }}/>
        <L c={T.text}>Untracked files:</L>
        <L c={T.muted} indent={16}>(use "git add &lt;file&gt;…" to include in what will be committed)</L>
        <L c={T.danger} indent={16}>src/relay/WsClientTest.kt</L>
        <div style={{ height:14 }}/>
        <L c={T.muted}>no changes added to commit (use "git add" and/or "git commit -a")</L>
        <div style={{ height:6 }}/>
        <div style={{ color:T.text }}><span style={{ color:T.accent }}>$</span> <span className="tm-cursor" style={{ display:'inline-block', width:8, height:15, background:T.accent, borderRadius:1, verticalAlign:'text-bottom', transform:'translateY(2px)' }}/></div>
      </div>
    </div>
  );
}

// draggable hairline divider with grab affordance
function Divider() {
  return (
    <div className="tm-divider" style={{ height:9, flexShrink:0, background:T.base, display:'flex', alignItems:'center', justifyContent:'center', cursor:'row-resize', position:'relative' }}>
      <div style={{ position:'absolute', left:0, right:0, top:4, height:1, background:T.border }}/>
      <span className="tm-grab" style={{ position:'relative', width:34, height:4, borderRadius:999, background:T.border, transition:'background .13s ease' }}/>
    </div>
  );
}

function TerminalPanel({ height, focused, onTermIcon, menuOpen }) {
  return (
    <div style={{ height, flexShrink:0, display:'flex', flexDirection:'column', position:'relative', boxShadow: focused?`inset 0 0 0 1px ${T.accent}`:'none' }}>
      <TermHeader onTermIcon={onTermIcon} menuOpen={menuOpen}/>
      <TermBody focused={focused}/>
    </div>
  );
}

// collapsed slim status strip
function CollapsedStrip({ onRestore, stripRef }) {
  return (
    <div ref={stripRef} className="dk-btn tm-strip" onClick={onRestore} style={{ height:30, flexShrink:0, background:T.raised, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'0 12px', cursor:'pointer' }}>
      {TI.terminal(T.sec,14)}
      <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, flexShrink:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>~/code/cc-pocket/…/relay</span>
      <BranchChip/>
      <span style={{ flex:1 }}/>
      <span style={{ display:'flex', alignItems:'center', gap:5 }}><Dot c={T.success} pulse s={6}/><span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>1 running</span></span>
      <span style={{ display:'flex', marginLeft:2 }}>{TI.chevUp(T.muted,14)}</span>
    </div>
  );
}

// open-mode menu (anchored)
function OpenModeMenu({ style }) {
  const Row = ({ icon, title, sub, hint, primary }) => (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:10, padding:'9px 11px', borderRadius:8, cursor:'pointer' }}>
      <span style={{ width:26, height:26, borderRadius:7, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background: primary?T.accentTint||'rgba(217,119,87,0.12)':T.surface, border:`1px solid ${primary?'rgba(217,119,87,0.4)':T.border}` }}>{icon}</span>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:6 }}>
          <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>{title}</span>
          {primary && TI.check(T.accent,14)}
        </div>
        {sub && <div style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted, marginTop:1 }}>{sub}</div>}
      </div>
      {hint && <Key>{hint}</Key>}
    </div>
  );
  return (
    <div style={{ position:'absolute', width:300, background:T.raised, border:`1px solid ${T.border}`, borderRadius:13, boxShadow:'0 24px 60px -20px rgba(0,0,0,0.75)', overflow:'hidden', zIndex:20, ...style }}>
      <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', padding:'12px 13px 6px' }}>Open terminal</div>
      <div style={{ padding:'0 6px 4px' }}>
        <Row icon={TI.terminal(T.accent,15)} title="Open embedded" sub="Docked in this session" hint="⌘J" primary/>
        <Row icon={TI.external(T.sec,15)} title="Open in Ghostty" sub="Your system terminal"/>
      </div>
      <div style={{ borderTop:`1px solid ${T.border}`, padding:'9px 13px', fontFamily:T.ui, fontSize:11.5, color:T.muted, lineHeight:'16px' }}>Default can be changed in <span style={{ color:T.sec }}>Settings → Terminal</span>.</div>
    </div>
  );
}

// ════════════════ SCREENS ════════════════
function ChatPaneOpen() {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base }}>
      <ChatSubHeader/>
      <ChatStream/>
      <Divider/>
      <TerminalPanel height={266} focused/>
    </div>
  );
}
function ScreenOpen() {
  return (
    <Window w={1280} h={800} platform="mac" titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      <Sidebar/>
      <ChatPaneOpen/>
    </Window>
  );
}

function ChatPaneCollapsed() {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base, position:'relative' }}>
      <ChatSubHeader/>
      <ChatStream/>
      <div style={{ position:'relative' }}>
        <CollapsedStrip/>
        <OpenModeMenu style={{ left:8, bottom:38 }}/>
      </div>
    </div>
  );
}
function ScreenCollapsed() {
  return (
    <Window w={1280} h={800} platform="mac" titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      <Sidebar/>
      <ChatPaneCollapsed/>
    </Window>
  );
}

// ════════════════ BOARD ════════════════
function Divi({ children, sub }) {
  return (
    <div style={{ margin:'64px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.sec, marginTop:10, maxWidth:820 }}>{sub}</div>}
    </div>
  );
}
function Page() {
  return (
    <div style={{ maxWidth:1360, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Embedded terminal in the ChatPane</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:760, margin:0 }}>
        The terminal now lives inside the session instead of a separate OS window. It docks at the bottom of the ChatPane behind a draggable hairline divider — the conversation stays readable above, the working directory and branch are always in view, and "open in your system terminal" is demoted to a secondary choice. The sidebar is unchanged.
      </p>

      <Divi sub="The default. An embedded panel at ~35% of the ChatPane height, showing a real git status run in the Kotlin repo. Header (raised surface, hairline top border): terminal glyph, middle-truncated cwd, and a git-branch chip on the left; open-external, collapse, and close on the right. The panel carries a 1px terracotta inner border because it owns keyboard focus. The divider shows a grab handle on hover.">① Terminal open · default</Divi>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><ScreenOpen/></div>

      <Divi sub="Collapsed to a slim status strip at the ChatPane bottom — terminal glyph, cwd, branch, and a live '1 running' dot; click anywhere to restore. The open-mode menu (anchored from the terminal glyph) offers Open embedded (⌘J, the default, checked) and Open in Ghostty as the secondary path, with a note that the default is set in Settings.">② Collapsed strip · open-mode menu</Divi>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><ScreenCollapsed/></div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
