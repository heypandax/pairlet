// cc-pocket — desktop · attachments · app (two-pane variants A + B)

// ── compact static sidebar (matches desktop-app.jsx styling) ──
function SectionLabel({ children }) {
  return <div style={{ padding:'14px 14px 6px', fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase' }}>{children}</div>;
}
function ProjectRow({ name, running, history }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, height:34, padding:'0 14px', cursor:'pointer' }}>
      {I.folder(T.sec,15)}
      <span style={{ fontFamily:T.mono, fontSize:13, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
      {running && <span style={{ display:'flex', alignItems:'center', gap:4 }}><Dot c={T.accent} pulse s={6}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.accent }}>running</span></span>}
      {history && !running && <span style={{ fontFamily:T.mono, fontSize:9.5, color:T.accent, border:`1px solid ${T.accent}55`, borderRadius:999, padding:'1px 7px' }}>history</span>}
    </div>
  );
}
function SessionRow({ title, agent, running, selected }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 14px 0 12px', cursor:'pointer', position:'relative', background: selected?T.raised:'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
      {running ? <Dot c={T.success} pulse s={6}/> : <span style={{ width:6, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:13, color: selected?T.text:T.sec, fontWeight: selected?600:400, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={10}/>}
      <span className="dk-trail" style={{ display:'flex', flexShrink:0 }}>{I.x(T.muted,13)}</span>
    </div>
  );
}
function Sidebar() {
  return (
    <div style={{ width:300, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column' }}>
      <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}` }}>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'11px 14px', cursor:'pointer' }}>
          {I.apple(T.sec,15)}<span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Lidapeng-MBP</span><Dot c={T.success} pulse s={6}/>
          <span style={{ flex:1 }}/>{I.chevD(T.muted,14)}
        </div>
      </div>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto' }}>
        <SectionLabel>Projects</SectionLabel>
        <ProjectRow name="cc-pocket" running/>
        <ProjectRow name="relay" history/>
        <ProjectRow name="dotfiles"/>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, height:34, padding:'0 14px', cursor:'pointer' }}>
          {I.plus(T.accent,15)}<span style={{ fontFamily:T.ui, fontSize:13, color:T.accent, fontWeight:500 }}>New session</span>
          <span style={{ flex:1 }}/><Key>⌘N</Key>
        </div>
        <SectionLabel>Sessions</SectionLabel>
        <SessionRow title="Share error logs" running selected/>
        <SessionRow title="Fix relay reconnect" running/>
        <SessionRow title="Port parser to Rust" agent="codex" running/>
        <SessionRow title="Refactor auth module"/>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'9px 14px' }}>
        {I.gear(T.sec,16)}<span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Settings</span>
        <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>v0.4.1</span>
      </div>
    </div>
  );
}

// ── sub-header ────────────────────────────────────────────────
function SubHeader() {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, padding:'10px 18px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Share error logs</span>
        <span style={{ flex:1 }}/>
        <span style={{ display:'flex', alignItems:'center', gap:5, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}><span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>default</span>{I.chevD(T.muted,12)}</span>
        <span style={{ display:'flex', cursor:'pointer' }}>{I.dots(T.sec,16)}</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:5 }}>~/code/cc-pocket&nbsp; · &nbsp;⑂ main&nbsp; · &nbsp;sonnet</div>
    </div>
  );
}

// ── stream (delivered turn) ───────────────────────────────────
function Stream() {
  return (
    <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'20px 18px' }}>
      <div style={{ maxWidth:760, margin:'0 auto', display:'flex', flexDirection:'column', gap:18 }}>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:8 }}>You</div>
          <div style={{ display:'flex', flexDirection:'column', gap:10, alignItems:'flex-start' }}>
            <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>here’s the crash report and a screen recording of the repro — logs first, then the video.</div>
            <SentFileChip name="Q3-metrics-report.pdf" size="2.4 MB" path="@inbox/report.pdf" kind="pdf"/>
            <SentVideoThumb name="repro.mov" size="12.8 MB" path="@inbox/repro.mov" dur={42}/>
          </div>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text }}>
          Both are in the workspace. Reading <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px', color:T.accent }}>@inbox/report.pdf</span> now — the traceback lines up with <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px' }}>WsClient.reconnect()</span>, and the recording shows the socket closing first.<span className="dk-caret"/>
        </div>
      </div>
    </div>
  );
}

// ── drop overlay ──────────────────────────────────────────────
function DropOverlay() {
  return (
    <div style={{ position:'absolute', inset:0, zIndex:20, background:'rgba(8,9,10,0.55)', backdropFilter:'blur(1px)', WebkitBackdropFilter:'blur(1px)', display:'flex', alignItems:'center', justifyContent:'center', padding:16 }}>
      <div style={{ position:'absolute', inset:12, border:`2px dashed ${T.accent}`, borderRadius:14, background:'rgba(217,119,87,0.06)' }}/>
      <div style={{ position:'relative', display:'flex', flexDirection:'column', alignItems:'center', gap:14, textAlign:'center' }}>
        <div style={{ width:58, height:58, borderRadius:16, background:'rgba(217,119,87,0.14)', border:`1px solid ${T.accent}66`, display:'flex', alignItems:'center', justifyContent:'center' }}><UploadG c={T.accent} s={28}/></div>
        <div style={{ fontFamily:T.ui, fontSize:17, fontWeight:600, color:T.text }}>Drop to add to this session’s workspace</div>
        <div style={{ fontFamily:T.mono, fontSize:12, color:T.sec }}>images · files · videos — up to 200 MB</div>
      </div>
    </div>
  );
}

// ── chat pane (variant switch) ────────────────────────────────
function ChatPane({ variant }) {
  const pending = [
    { name:'server.log', size:'6.1 MB', kind:'code', state:'uploading', pct:0.64 },
    { name:'trace.txt', size:'—', kind:'code', state:'failed' },
  ];
  return (
    <div style={{ flex:1, minWidth:0, position:'relative', display:'flex', flexDirection:'column', background:T.base }}>
      <SubHeader/>
      <Stream/>
      {variant === 'working'
        ? <ComposerAttach chips={pending}/>
        : (
          <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'12px 18px 14px' }}>
            <div style={{ maxWidth:760, margin:'0 auto' }}>
              <div style={{ display:'flex', alignItems:'flex-end', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'8px 10px 8px 12px' }}>
                <span style={{ display:'flex', paddingBottom:5 }}>{I.paperclip(T.sec,17)}</span>
                <div style={{ flex:1, fontFamily:T.ui, fontSize:14, color:T.muted, padding:'6px 0' }}>Message Claude…</div>
                <button style={{ all:'unset', cursor:'pointer', width:34, height:34, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send('#0E0F11',16)}</button>
              </div>
            </div>
          </div>
        )}
      {variant === 'drag' && <DropOverlay/>}
    </div>
  );
}

function Shell({ variant, platform='mac' }) {
  return (
    <Window platform={platform} titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      <Sidebar/>
      <ChatPane variant={variant}/>
    </Window>
  );
}

// ── page ──────────────────────────────────────────────────────
function Divider({ children, sub }) {
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

function Page() {
  return (
    <div style={{ maxWidth:1300, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop edition</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Desktop chat pane — attachments</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:760, margin:0 }}>
        The same workspace-inbox attachment system, with two desktop-native affordances: drag a file anywhere onto the chat pane, and hover a chip for its inline actions. Denser than mobile — 13px mono names and paths, hairline borders, no heavy shadows.
      </p>

      <Divider sub="Drag a file over the conversation and the whole pane arms: a dashed terracotta boundary, a dimmed backdrop over the stream, and the workspace-framed prompt. The sidebar stays put — only the active pane is a drop target.">A · drag-over the chat pane</Divider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><Shell variant="drag"/></div>

      <Divider sub="Mid-upload. A delivered turn above (file chip + video thumbnail, single-line captions with the @inbox path in terracotta), and two pending chips in the composer — one uploading with a thin progress bar along its base, one failed on a danger hairline. Hover any chip for its × / retry button; Send stays in its waiting spinner until uploads settle.">B · working state · uploading + failed + delivered</Divider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><Shell variant="working"/></div>

      <div style={{ marginTop:22, display:'flex', gap:26, flexWrap:'wrap', fontFamily:T.ui, fontSize:12.5, color:T.muted }}>
        <span>· hover a chip → × (cancel) / ↻ (retry)</span>
        <span>· names &amp; paths: 13px mono</span>
        <span>· progress: 2.5px linear, terracotta</span>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
