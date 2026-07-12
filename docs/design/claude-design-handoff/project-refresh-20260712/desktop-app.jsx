// cc-pocket — desktop · app (two-pane shell + permission + tray + sidebar states)

// ════════════════ SIDEBAR ════════════════
function SectionLabel({ children, right }) {
  return (
    <div style={{ display:'flex', alignItems:'center', padding:'14px 14px 6px' }}>
      <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase' }}>{children}</span>
      <span style={{ flex:1 }}/>{right}
    </div>
  );
}

function ComputerSwitcher({ open }) {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}` }}>
      <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'11px 14px', cursor:'pointer' }}>
        {I.apple(T.sec,15)}
        <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Lidapeng-MBP</span>
        <Dot c={T.success} pulse s={6}/>
        <span style={{ flex:1 }}/>
        <span style={{ transform:open?'rotate(180deg)':'none', transition:'transform .2s', display:'flex' }}>{I.chevD(T.muted,14)}</span>
      </div>
      {open && (
        <div style={{ borderTop:`1px solid ${T.border}`, background:T.base, padding:'4px 0' }}>
          {[['apple','Lidapeng-MBP','online · active now',true,false],['linux','devbox-linux','online · 3m ago',true,false],['win','win-desktop','offline · 2d ago',false,true]].map(([os,name,meta,on,dim],i)=>(
            <div key={i} className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'8px 14px', cursor:'pointer', opacity:dim?0.5:1 }}>
              {I[os](T.sec,14)}
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.text }}>{name}</div>
                <div style={{ fontFamily:T.mono, fontSize:9.5, color:T.muted }}>{meta}</div>
              </div>
              <Dot c={on?T.success:T.muted} s={6}/>
            </div>
          ))}
          <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, padding:'9px 14px', cursor:'pointer' }}>
            {I.plus(T.accent,14)}<span style={{ fontFamily:T.ui, fontSize:12.5, color:T.accent, fontWeight:500 }}>Add computer</span>
          </div>
        </div>
      )}
    </div>
  );
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

function SessionRow({ title, agent, running, pending, selected }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 14px 0 12px', cursor:'pointer', position:'relative',
      background: selected?T.raised:'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:5, bottom:5, width:2, borderRadius:2, background:T.accent }}/>}
      {running ? <Dot c={T.success} pulse s={6}/> : <span style={{ width:6, flexShrink:0 }}/>}
      <span style={{ fontFamily:T.ui, fontSize:13, color: selected?T.text:T.sec, fontWeight: selected?600:400, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      {agent==='codex' && <AgentTag agent="codex" s={10}/>}
      {pending && <span style={{ display:'flex', alignItems:'center', gap:3, background:T.accent, borderRadius:999, padding:'1px 6px', flexShrink:0 }}>{I.warn('#0E0F11',10)}<span style={{ fontFamily:T.mono, fontSize:10, color:'#0E0F11', fontWeight:600 }}>{pending}</span></span>}
      <span className="dk-trail" style={{ display:'flex', flexShrink:0 }}>{I.x(T.muted,13)}</span>
    </div>
  );
}

function Sidebar({ switcherOpen }) {
  return (
    <div style={{ width:300, flexShrink:0, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column' }}>
      <ComputerSwitcher open={switcherOpen}/>
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
        <SessionRow title="Fix relay reconnect" running selected/>
        <SessionRow title="Port parser to Rust" agent="codex" running pending="1"/>
        <SessionRow title="Add WS reconnect test" running/>
        <SessionRow title="Refactor auth module"/>
        <SessionRow title="Tidy CI workflow" agent="codex"/>
      </div>
      <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:9, padding:'9px 14px' }}>
        {I.gear(T.sec,16)}<span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Settings</span>
        <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>v0.4.1</span>
      </div>
    </div>
  );
}

// ════════════════ CHAT MAIN PANE ════════════════
function ChatSubHeader({ codex }) {
  return (
    <div style={{ flexShrink:0, borderBottom:`1px solid ${T.border}`, padding:'10px 18px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>{codex?'Port parser to Rust':'Fix relay reconnect'}</span>
        {codex && <AgentTag agent="codex"/>}
        <span style={{ flex:1 }}/>
        <span style={{ display:'flex', alignItems:'center', gap:5, border:`1px solid ${T.border}`, borderRadius:999, padding:'3px 9px' }}>
          <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>default</span>{I.chevD(T.muted,12)}
        </span>
        <span style={{ display:'flex', cursor:'pointer' }}>{I.dots(T.sec,16)}</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:5 }}>~/code/cc-pocket&nbsp; · &nbsp;⑂ main&nbsp; · &nbsp;{codex?'gpt-5.1-codex':'sonnet'}</div>
    </div>
  );
}

function CodeBlock({ lang, children }) {
  return (
    <div style={{ background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', margin:'4px 0' }}>
      <div style={{ display:'flex', alignItems:'center', padding:'7px 12px', borderBottom:`1px solid ${T.border}` }}>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>{lang}</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, cursor:'pointer' }}>copy</span>
      </div>
      <pre style={{ margin:0, padding:'11px 13px', fontFamily:T.mono, fontSize:12, lineHeight:'19px', color:T.sec, overflowX:'auto' }}>{children}</pre>
    </div>
  );
}

function ToolRow({ name, cmd, status }) {
  const col = status==='ok'?T.success:status==='run'?T.accent:T.danger;
  return (
    <div style={{ display:'flex', alignItems:'center', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'9px 12px' }}>
      {status==='run' ? <span className="dk-spin" style={{ width:12, height:12, border:`1.6px solid ${T.border}`, borderTopColor:T.accent, borderRadius:999, flexShrink:0 }}/> : <Dot c={col} s={7}/>}
      <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>{name}</span>
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{cmd}</span>
      {status==='ok' && I.check(T.success,14)}
      {status!=='run' && status!=='ok' && I.x(T.danger,14)}
    </div>
  );
}

// inline permission card embedded in the stream
function InlinePermCard({ codex }) {
  return (
    <div style={{ background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, padding:'14px 15px', maxWidth:680 }}>
      <div style={{ display:'flex', alignItems:'flex-start', gap:11 }}>
        <span style={{ width:34, height:34, borderRadius:9, background: codex?AGENTS.codex.tint:AGENTS.claude.tint, border:`1px solid ${codex?AGENTS.codex.tintBorder:AGENTS.claude.tintBorder}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>{I.shield(codex?AGENTS.codex.color:AGENTS.claude.color,18)}</span>
        <div style={{ flex:1, minWidth:0 }}>
          <div style={{ display:'flex', alignItems:'center', gap:7 }}>
            <span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>{codex?'Codex':'Claude'} needs permission</span>
            {codex && <AgentTag agent="codex" s={10}/>}
          </div>
          <div style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, margin:'2px 0 10px' }}>Run command · Bash</div>
          <div style={{ fontFamily:T.mono, fontSize:12, color:T.text, background:T.base, border:`1px solid ${T.border}`, borderRadius:8, padding:'9px 11px', marginBottom:10 }}>rm -rf ./build &amp;&amp; ./gradlew clean</div>
          <div style={{ display:'flex', alignItems:'center', gap:7, fontFamily:T.mono, fontSize:10.5, color:T.muted, marginBottom:12 }}>
            <span>~/code/cc-pocket</span><span>·</span>{I.branch(T.muted,11)}<span>main</span>
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:10 }}>
            <span style={{ display:'flex', alignItems:'center', gap:7 }}>
              <span style={{ width:16, height:16, borderRadius:4, border:`1.5px solid ${T.muted}` }}/>
              <span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>Remember for this session</span>
            </span>
            <span style={{ flex:1 }}/>
            {/* countdown ring */}
            <svg width="26" height="26" viewBox="0 0 26 26" style={{ transform:'rotate(-90deg)' }}><circle cx="13" cy="13" r="10" stroke={T.border} strokeWidth="2.2" fill="none"/><circle cx="13" cy="13" r="10" stroke={T.accent} strokeWidth="2.2" fill="none" strokeLinecap="round" strokeDasharray="62.8" strokeDashoffset="18"/></svg>
            <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'8px 16px', borderRadius:9, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:13, fontWeight:600 }}>Deny</button>
            <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'8px 16px', borderRadius:9, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:13, fontWeight:700, display:'flex', alignItems:'center', gap:7 }}>Allow <Key>⌘⏎</Key></button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Composer() {
  return (
    <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'12px 18px 14px' }}>
      <div style={{ maxWidth:760, margin:'0 auto' }}>
        <div style={{ display:'flex', alignItems:'flex-end', gap:9, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'8px 10px 8px 12px' }}>
          <span style={{ display:'flex', paddingBottom:5 }}>{I.paperclip(T.sec,17)}</span>
          <div style={{ flex:1, fontFamily:T.ui, fontSize:14, color:T.muted, padding:'6px 0' }}>Message Claude…</div>
          <button style={{ all:'unset', cursor:'pointer', width:34, height:34, borderRadius:999, background:T.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send('#0E0F11',16)}</button>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:7, paddingLeft:2 }}>
          <Key>⏎</Key><span style={{ fontFamily:T.ui, fontSize:11, color:T.muted }}>send</span>
          <Key>⇧⏎</Key><span style={{ fontFamily:T.ui, fontSize:11, color:T.muted }}>newline</span>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>↑1.2k ↓340</span>
        </div>
      </div>
    </div>
  );
}

function ChatPane({ withInlinePerm }) {
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:T.base }}>
      <ChatSubHeader/>
      <div className="dk-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'20px 18px' }}>
        <div style={{ maxWidth:760, margin:'0 auto', display:'flex', flexDirection:'column', gap:18 }}>
          <div>
            <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:7 }}>You</div>
            <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>the websocket reconnect dies after the 3rd retry — can you find why and add a regression test?</div>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text }}>
            The reconnect loop is scheduled on the socket's own <span style={{ fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px' }}>CoroutineScope</span>, which is cancelled the moment the socket closes — so the backoff timer never fires. Moving it to an app-level scope fixes it:
          </div>
          <CodeBlock lang="kotlin">{`fun reconnect() {
  appScope.launch {
    delay(backoff)
    open()
  }
}`}</CodeBlock>
          <ToolRow name="Bash" cmd="gradle :relay:test" status="ok"/>
          {withInlinePerm && <InlinePermCard/>}
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text }}>
            Tests pass. The reconnect now survives socket-scope cancellation and backs off correctly.<span className="dk-caret"/>
          </div>
        </div>
      </div>
      <Composer/>
    </div>
  );
}

// ════════════════ MAIN SHELL ════════════════
function MainShell({ platform='mac', switcherOpen, withInlinePerm }) {
  return (
    <Window platform={platform} titleRight={
      <span style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ display:'flex', alignItems:'center', gap:6, border:`1px solid ${T.border}`, borderRadius:7, padding:'3px 9px' }}>{I.search(T.muted,13)}<span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Search</span><Key>⌘K</Key></span>
        <Dot c={T.success} pulse s={7}/>
      </span>
    }>
      <Sidebar switcherOpen={switcherOpen}/>
      <ChatPane withInlinePerm={withInlinePerm}/>
    </Window>
  );
}

// ════════════════ ③ FOCUSED PERMISSION MODAL ════════════════
function FocusedModal({ codex }) {
  return (
    <div style={{ width:1180, height:760, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0, filter:'blur(1.5px)', opacity:0.5 }}><MainShell/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.66)' }}/>
      <div style={{ position:'absolute', top:'50%', left:'50%', transform:'translate(-50%,-50%)', width:460, background:T.raised, border:`1px solid ${T.border}`, borderRadius:16, boxShadow:'0 30px 80px -20px rgba(0,0,0,0.8)', overflow:'hidden' }}>
        <div style={{ padding:'16px 20px 0', display:'flex', alignItems:'center', gap:8 }}>
          {I.apple(T.muted,14)}<span style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted }}>on Lidapeng-MBP</span>
        </div>
        <div style={{ padding:'14px 20px 20px' }}>
          <div style={{ display:'flex', alignItems:'center', gap:11, marginBottom:14 }}>
            <span style={{ width:40, height:40, borderRadius:11, background:AGENTS.claude.tint, border:`1px solid ${AGENTS.claude.tintBorder}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>{I.shield(T.accent,21)}</span>
            <div>
              <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Claude needs permission</div>
              <div style={{ fontFamily:T.ui, fontSize:17, fontWeight:700, color:T.text }}>Run command · Bash</div>
            </div>
            <span style={{ flex:1 }}/>
            <svg width="34" height="34" viewBox="0 0 34 34" style={{ transform:'rotate(-90deg)' }}><circle cx="17" cy="17" r="13" stroke={T.border} strokeWidth="2.6" fill="none"/><circle cx="17" cy="17" r="13" stroke={T.accent} strokeWidth="2.6" fill="none" strokeLinecap="round" strokeDasharray="81.7" strokeDashoffset="26"/></svg>
          </div>
          <div style={{ fontFamily:T.mono, fontSize:12.5, color:T.text, background:T.base, border:`1px solid ${T.border}`, borderRadius:9, padding:'11px 13px', marginBottom:11 }}>rm -rf ./build &amp;&amp; ./gradlew clean</div>
          <div style={{ display:'flex', alignItems:'center', gap:7, fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:16 }}>
            <span>~/code/cc-pocket</span><span>·</span>{I.branch(T.muted,11)}<span>main</span>
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:10 }}>
            <span style={{ display:'flex', alignItems:'center', gap:7, flex:1 }}>
              <span style={{ width:16, height:16, borderRadius:4, border:`1.5px solid ${T.muted}` }}/>
              <span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>Remember this session</span>
            </span>
            <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'10px 18px', borderRadius:10, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:13.5, fontWeight:600 }}>Deny</button>
            <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'10px 18px', borderRadius:10, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:13.5, fontWeight:700, display:'flex', alignItems:'center', gap:7 }}>Allow <Key>⌘⏎</Key></button>
          </div>
        </div>
      </div>
    </div>
  );
}

// Codex diff inline card
function DiffCard() {
  const lines = [' fun reconnect() {','-  scope.launch { open() }','+  appScope.launch {','+    delay(backoff)','+    open()','+  }',' }'];
  return (
    <div style={{ background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, padding:'14px 15px', maxWidth:680 }}>
      <div style={{ display:'flex', alignItems:'center', gap:9, marginBottom:11 }}>
        <span style={{ width:34, height:34, borderRadius:9, background:AGENTS.codex.tint, border:`1px solid ${AGENTS.codex.tintBorder}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>{I.shield(AGENTS.codex.color,18)}</span>
        <div style={{ flex:1 }}>
          <div style={{ display:'flex', alignItems:'center', gap:7 }}><span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>Codex wants to edit files</span><AgentTag agent="codex" s={10}/></div>
          <div style={{ fontFamily:T.mono, fontSize:12.5, color:T.text, marginTop:3 }}>~/code/cc-pocket/src/relay/WsClient.kt <span style={{ color:T.success }}>+5</span> <span style={{ color:T.danger }}>−1</span></div>
        </div>
        <svg width="26" height="26" viewBox="0 0 26 26" style={{ transform:'rotate(-90deg)' }}><circle cx="13" cy="13" r="10" stroke={T.border} strokeWidth="2.2" fill="none"/><circle cx="13" cy="13" r="10" stroke={AGENTS.codex.color} strokeWidth="2.2" fill="none" strokeLinecap="round" strokeDasharray="62.8" strokeDashoffset="18"/></svg>
      </div>
      <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:9, overflow:'hidden', marginBottom:12 }}>
        <pre style={{ margin:0, padding:'10px 0', fontFamily:T.mono, fontSize:12, lineHeight:'19px', overflowX:'auto' }}>
          {lines.map((l,i)=>{ const s=l[0]; const bg=s==='+'?'rgba(79,180,119,0.12)':s==='-'?'rgba(229,96,77,0.12)':'transparent'; const c=s==='+'?T.success:s==='-'?T.danger:T.muted;
            return <div key={i} style={{ background:bg, padding:'0 13px', display:'flex', gap:10 }}><span style={{ color:c, width:8 }}>{s===' '?'':s}</span><span style={{ color:s===' '?T.sec:c, whiteSpace:'pre' }}>{l.slice(1)}</span></div>; })}
        </pre>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:10 }}>
        <span style={{ flex:1 }}/>
        <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'8px 16px', borderRadius:9, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:13, fontWeight:600 }}>Deny</button>
        <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'8px 16px', borderRadius:9, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:13, fontWeight:700 }}>Allow</button>
      </div>
    </div>
  );
}

// ════════════════ ④ TRAY POPOVER ════════════════
function TrayApprovalRow({ agent, computer, cmd }) {
  return (
    <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'10px 11px', marginBottom:8 }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:8 }}>
        <AgentGlyph agent={agent} s={14}/>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>{computer}</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.warning }}>0:22</span>
      </div>
      <div style={{ fontFamily:T.mono, fontSize:11.5, color:T.text, background:T.base, border:`1px solid ${T.border}`, borderRadius:7, padding:'7px 9px', marginBottom:9, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{cmd}</div>
      <div style={{ display:'flex', gap:8 }}>
        <button className="dk-btn" style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', padding:'7px 0', borderRadius:8, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:12.5, fontWeight:600 }}>Deny</button>
        <button className="dk-btn" style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', padding:'7px 0', borderRadius:8, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:12.5, fontWeight:700 }}>Allow</button>
      </div>
    </div>
  );
}

function TrayPopover({ empty }) {
  return (
    <div style={{ width:360, position:'relative', filter:'drop-shadow(0 24px 50px rgba(0,0,0,0.6))' }}>
      <div style={{ width:14, height:14, background:T.raised, border:`1px solid ${T.border}`, borderBottom:'none', borderRight:'none', transform:'rotate(45deg)', position:'absolute', top:-7, left:'50%', marginLeft:-7, borderRadius:'3px 0 0 0' }}/>
      <div style={{ background:T.raised, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden', position:'relative' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'12px 14px', borderBottom:`1px solid ${T.border}` }}>
          <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:700, color:T.text }}>cc-pocket</span>
          <Dot c={T.success} pulse s={6}/>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>2 computers · 3 sessions</span>
        </div>
        <div style={{ padding:'12px 14px' }}>
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', marginBottom:10 }}>Pending approvals</div>
          {empty ? (
            <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 2px', marginBottom:6 }}>
              {I.check(T.success,15)}<span style={{ fontFamily:T.ui, fontSize:13, color:T.sec }}>No pending approvals</span>
            </div>
          ) : (
            <>
              <TrayApprovalRow agent="claude" computer="Lidapeng-MBP" cmd="rm -rf ./build && ./gradlew clean"/>
              <TrayApprovalRow agent="codex" computer="devbox-linux" cmd="edit src/relay/WsClient.kt  +5 −1"/>
            </>
          )}
          <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', margin:'14px 0 8px' }}>Running sessions</div>
          {[['Fix relay reconnect','Lidapeng-MBP',true],['Port parser to Rust','devbox-linux',true],['Tidy CI workflow','Lidapeng-MBP',false]].map(([t,c,run],i)=>(
            <div key={i} className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, padding:'7px 6px', borderRadius:7, cursor:'pointer' }}>
              <Dot c={run?T.success:T.muted} pulse={run} s={6}/>
              <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{t}</span>
              <span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>{c}</span>
            </div>
          ))}
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 14px', borderTop:`1px solid ${T.border}` }}>
          <button className="dk-btn" style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', padding:'8px 0', borderRadius:9, border:`1px solid ${T.border}`, color:T.text, fontFamily:T.ui, fontSize:12.5, fontWeight:600 }}>Open cc-pocket</button>
          <span style={{ display:'flex', cursor:'pointer', padding:6 }}>{I.gear(T.sec,16)}</span>
        </div>
      </div>
    </div>
  );
}

// tray icon with badge
function TrayIcon() {
  return (
    <div style={{ display:'inline-flex', alignItems:'center', gap:0, position:'relative', background:T.base, border:`1px solid ${T.border}`, borderRadius:8, padding:'6px 9px' }}>
      <AgentGlyph agent="claude" s={17}/>
      <span style={{ position:'absolute', top:-5, right:-5, minWidth:16, height:16, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:10, fontWeight:700, display:'flex', alignItems:'center', justifyContent:'center', padding:'0 4px' }}>1</span>
    </div>
  );
}

// ════════════════ ⑤ NEW-SESSION POPOVER + COLLAPSED SIDEBAR ════════════════
function NewSessionPopover() {
  return (
    <div style={{ width:300, background:T.raised, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden', boxShadow:'0 24px 60px -20px rgba(0,0,0,0.7)' }}>
      <div style={{ padding:'13px 15px 0', fontFamily:T.ui, fontSize:14, fontWeight:700, color:T.text }}>New session</div>
      <div style={{ padding:'12px 15px' }}>
        <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', marginBottom:9 }}>Agent</div>
        <div style={{ display:'flex', gap:9, marginBottom:14 }}>
          <div style={{ flex:1, border:`1.5px solid ${AGENTS.claude.color}`, background:AGENTS.claude.tint, borderRadius:10, padding:'10px', display:'flex', flexDirection:'column', gap:6 }}>
            <AgentGlyph agent="claude" s={17}/><span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>Claude</span>
          </div>
          <div style={{ flex:1, border:`1.5px solid ${T.border}`, background:T.surface, borderRadius:10, padding:'10px', display:'flex', flexDirection:'column', gap:6 }}>
            <AgentGlyph agent="codex" s={17}/><span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.sec }}>Codex</span>
          </div>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', marginBottom:9 }}>Mode</div>
        {[['Ask each step','default',T.sec],['Accept edits','acceptEdits',T.success],['Plan','plan',T.info],['Full auto','bypass',T.warning]].map(([l,t,c],i)=>(
          <div key={i} style={{ display:'flex', alignItems:'center', gap:8, padding:'8px 10px', borderRadius:8, border:`1px solid ${i===0?T.accent:T.border}`, marginBottom:6, background: i===0?T.surface:'transparent' }}>
            <Dot c={c} s={7}/><span style={{ fontFamily:T.ui, fontSize:12.5, color:T.text }}>{l}</span>
            {l==='Full auto' && I.warn(T.warning,13)}
            <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>{t}</span>
          </div>
        ))}
        <button className="dk-btn" style={{ all:'unset', cursor:'pointer', display:'block', textAlign:'center', marginTop:8, padding:'10px 0', borderRadius:10, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:13.5, fontWeight:700 }}>Start session</button>
      </div>
    </div>
  );
}

function CollapsedSidebar() {
  return (
    <div style={{ width:56, height:560, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', flexDirection:'column', alignItems:'center', padding:'12px 0', gap:6 }}>
      <div style={{ width:34, height:34, borderRadius:9, background:T.raised, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', position:'relative' }}>{I.apple(T.sec,16)}<span style={{ position:'absolute', bottom:-1, right:-1, width:8, height:8, borderRadius:999, background:T.success, border:`2px solid ${T.surface}` }}/></div>
      <div style={{ height:1, width:24, background:T.border, margin:'6px 0' }}/>
      <div style={{ width:34, height:34, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.folder(T.sec,17)}</div>
      <div style={{ width:34, height:34, borderRadius:9, background:T.raised, display:'flex', alignItems:'center', justifyContent:'center', position:'relative' }}>
        <AgentGlyph agent="claude" s={17}/>
        <span style={{ position:'absolute', top:-3, right:-3, width:15, height:15, borderRadius:999, background:T.accent, color:'#0E0F11', fontFamily:T.mono, fontSize:9, fontWeight:700, display:'flex', alignItems:'center', justifyContent:'center' }}>1</span>
      </div>
      <span style={{ flex:1 }}/>
      <div style={{ width:34, height:34, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.gear(T.sec,17)}</div>
    </div>
  );
}

// ════════════════ BOARD ════════════════
function Divider({ children, sub }) {
  return (
    <div style={{ margin:'64px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:10, maxWidth:760 }}>{sub}</div>}
    </div>
  );
}
function Label({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text, margin:'0 0 14px' }}>{children}</div>;
}

function Page() {
  return (
    <div style={{ maxWidth:1300, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop edition</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Desktop — two-pane, multi-session</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:720, margin:0 }}>
        The same product, scaled to a mouse-and-keyboard window: a left sidebar folds the computer switcher, projects, and a live multi-session list together; the right pane is the always-on conversation. Away-from-window approvals move to a menu-bar / tray popover.
      </p>

      <Divider sub="The hero. Computer switcher in the sidebar header, PROJECTS, then the SESSIONS list running several at once — a teal Codex tag only on Codex rows, a green live dot when running, and a terracotta ⚠ badge on the session waiting for approval. The open session has a 2px terracotta left edge.">① Main window · two-pane shell</Divider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><MainShell platform="mac"/></div>

      <Divider sub="The core safety interaction, desktop-native (no bottom sheets). Form 1: an inline card right in the stream while you're watching. The composer shows the / slash hint and ⏎/⇧⏎ keycaps.">② Chat · inline permission in the stream</Divider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><MainShell platform="mac" withInlinePerm/></div>

      <Divider sub="Form 2: opened from a tray/notification deep-link when the window was in the background — same content, centered over a dimmed backdrop, naming the computer at the top.">③ Focused permission modal</Divider>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><FocusedModal/></div>

      <Label>Codex file-change variant (inline diff)</Label>
      <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:14, padding:24 }}><DiffCard/></div>

      <Divider sub="The desktop killer feature: when the window is minimized, an approval arrives → tray badge + notification → approve or deny right in a compact popover without raising the whole window.">④ Menu-bar / tray quick-approve</Divider>
      <div style={{ display:'flex', gap:48, alignItems:'flex-start', flexWrap:'wrap' }}>
        <div><Label>Popover · pending approvals</Label><TrayPopover/></div>
        <div><Label>Empty state</Label><TrayPopover empty/></div>
        <div>
          <Label>Tray icon · badge when waiting</Label>
          <div style={{ display:'flex', alignItems:'center', gap:14, padding:'18px 22px', background:'#000', border:`1px solid ${T.border}`, borderRadius:12 }}>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>menu bar</span><TrayIcon/>
          </div>
        </div>
      </div>

      <Divider sub="Desktop uses popovers, not bottom sheets. Computer switcher open, the New-session popover (agent cards + mode presets), and the sidebar collapsed to an icon strip for narrow windows.">⑤ Sidebar states</Divider>
      <div style={{ display:'flex', gap:40, alignItems:'flex-start', flexWrap:'wrap' }}>
        <div><Label>Computer switcher open</Label><div style={{ height:560, overflow:'hidden', borderRadius:12, border:`1px solid ${T.border}` }}><div style={{ transform:'scale(1)', width:300 }}><div style={{ background:T.surface, height:560 }}><Sidebar switcherOpen/></div></div></div></div>
        <div><Label>New-session popover</Label><NewSessionPopover/></div>
        <div><Label>Collapsed icon strip</Label><CollapsedSidebar/></div>
      </div>

      <Divider sub="Platform shell differs only in the window controls — macOS traffic lights on the left, Windows min/max/close on the right. The visual body stays one design.">Platform chrome</Divider>
      <Label>Windows / Linux title bar</Label>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><MainShell platform="win"/></div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
