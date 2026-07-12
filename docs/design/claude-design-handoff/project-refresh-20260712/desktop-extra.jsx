// cc-pocket — desktop · append screens (command palette · settings · connect)
// reuses desktop-core.jsx: T, AGENTS, AgentGlyph, AgentTag, I, Key, Dot, Window

// ════════════════ shared: dimmed shell backdrop ════════════════
function ShellBackdrop() {
  return (
    <div style={{ position:'absolute', inset:0, display:'flex', background:T.base }}>
      {/* sidebar ghost */}
      <div style={{ width:300, background:T.surface, borderRight:`1px solid ${T.border}`, display:'flex', flexDirection:'column' }}>
        <div style={{ height:48, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:8, padding:'0 14px' }}>
          {I.apple(T.muted,14)}<span style={{ fontFamily:T.ui, fontSize:13, color:T.sec, fontWeight:600 }}>Lidapeng-MBP</span><Dot c={T.success} s={6}/>
        </div>
        <div style={{ padding:'14px 14px 6px', fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.8, color:T.muted, textTransform:'uppercase' }}>Sessions</div>
        {['Fix relay reconnect','Port parser to Rust','Add WS reconnect test','Refactor auth module'].map((t,i)=>(
          <div key={i} style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 14px' }}>
            <Dot c={i<3?T.success:T.muted} s={6}/><span style={{ fontFamily:T.ui, fontSize:13, color:i===0?T.text:T.sec }}>{t}</span>
          </div>
        ))}
      </div>
      <div style={{ flex:1, display:'flex', flexDirection:'column' }}>
        <div style={{ height:60, borderBottom:`1px solid ${T.border}`, padding:'12px 18px' }}>
          <div style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Fix relay reconnect</div>
          <div style={{ fontFamily:T.mono, fontSize:11, color:T.sec, marginTop:5 }}>~/code/cc-pocket · ⑂ main · sonnet</div>
        </div>
        <div style={{ flex:1, padding:'20px 18px' }}>
          <div style={{ maxWidth:680, margin:'0 auto', display:'flex', flexDirection:'column', gap:14 }}>
            <div style={{ fontFamily:T.ui, fontSize:14, color:T.sec, opacity:0.6 }}>The reconnect loop is scheduled on the socket's own scope…</div>
            <div style={{ height:90, background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:10, opacity:0.6 }}/>
          </div>
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// ① COMMAND PALETTE (⌘K)
// ════════════════════════════════════════════════════════════
function PaletteRow({ kind, name, detail, agent, selected, emphasis }) {
  const glyph = kind==='computer' ? I.apple(selected?T.text:T.sec,15)
    : kind==='project' ? I.folder(selected?T.text:T.sec,15)
    : I.bubble(selected?T.text:T.sec,14);
  // emphasis: wrap matched substring
  const renderName = () => {
    if (!emphasis) return name;
    const idx = name.toLowerCase().indexOf(emphasis.toLowerCase());
    if (idx<0) return name;
    return <>{name.slice(0,idx)}<span style={{ color:T.accent }}>{name.slice(idx,idx+emphasis.length)}</span>{name.slice(idx+emphasis.length)}</>;
  };
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:11, height:42, padding:'0 14px', cursor:'pointer', position:'relative',
      background: selected ? T.raised : 'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:6, bottom:6, width:2, borderRadius:2, background:T.accent }}/>}
      <span style={{ display:'flex', flexShrink:0, width:16, justifyContent:'center' }}>{glyph}</span>
      <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight: selected?600:500, color: selected?T.text:T.text, flexShrink:0 }}>{renderName()}</span>
      {agent==='codex' && <AgentTag agent="codex" s={10}/>}
      <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{detail}</span>
      <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted, flexShrink:0 }}>{kind}</span>
    </div>
  );
}

const PALETTE_ALL = [
  { kind:'session', name:'Fix relay reconnect', detail:'~/code/cc-pocket', selected:true },
  { kind:'session', name:'Port parser to Rust', detail:'~/code/parser', agent:'codex' },
  { kind:'project', name:'cc-pocket', detail:'~/code/cc-pocket' },
  { kind:'project', name:'relay', detail:'~/code/relay' },
  { kind:'project', name:'dotfiles', detail:'~/dotfiles' },
  { kind:'project', name:'pandax-site', detail:'~/work/pandax-site' },
  { kind:'computer', name:'Lidapeng-MBP', detail:'liam@anthropic' },
  { kind:'computer', name:'devbox-linux', detail:'liam@devbox' },
];
const PALETTE_RELAY = [
  { kind:'project', name:'relay', detail:'~/code/relay', selected:true, emphasis:'relay' },
  { kind:'session', name:'Fix relay reconnect', detail:'~/code/cc-pocket', emphasis:'relay' },
  { kind:'project', name:'relay-server', detail:'~/work/relay-server', emphasis:'relay' },
  { kind:'session', name:'relay WS client', detail:'~/code/relay', agent:'codex', emphasis:'relay' },
];

function CommandPalette({ query='', rows=PALETTE_ALL, count='12 results', empty }) {
  return (
    <div style={{ width:560, background:T.raised, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden', boxShadow:'0 30px 80px -20px rgba(0,0,0,0.8)' }}>
      <div style={{ display:'flex', alignItems:'center', gap:11, padding:'14px 16px', borderBottom:`1px solid ${T.border}` }}>
        {I.search(T.sec,17)}
        <span style={{ fontFamily:T.ui, fontSize:15, color: query?T.text:T.muted, flex:1 }}>{query || 'Jump to a project, session, or computer…'}</span>
        {query && <span className="dk-caret-static" style={{ width:1.5, height:17, background:T.accent }}/>}
      </div>
      {empty ? (
        <div style={{ padding:'40px 0', textAlign:'center', fontFamily:T.ui, fontSize:14, color:T.muted }}>No matches</div>
      ) : (
        <div className="dk-scroll" style={{ maxHeight:336, overflowY:'auto', padding:'6px 0' }}>
          {rows.map((r,i)=><PaletteRow key={i} {...r}/>)}
        </div>
      )}
      <div style={{ display:'flex', alignItems:'center', gap:9, padding:'9px 16px', borderTop:`1px solid ${T.border}` }}>
        <Key>↑</Key><Key>↓</Key><span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>navigate</span>
        <Key>⏎</Key><span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>open</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>{empty?'0 results':count}</span>
      </div>
    </div>
  );
}

// overlay wrapper (palette centered over dimmed shell)
function PaletteOverlay(props) {
  return (
    <div style={{ width:1180, height:680, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0, opacity:0.55 }}><ShellBackdrop/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.62)' }}/>
      <div style={{ position:'absolute', top:90, left:'50%', transform:'translateX(-50%)' }}><CommandPalette {...props}/></div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// ② SETTINGS MODAL
// ════════════════════════════════════════════════════════════
function Rail({ active }) {
  const items = [['General','gear'],['Computers','apple'],['Shortcuts','key'],['About','info']];
  const ico = (k,c)=> k==='gear'?I.gear(c,16): k==='apple'?I.apple(c,15): k==='key'? <svg width="16" height="16" viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5"><rect x="2.5" y="4.5" width="13" height="9" rx="2"/><path d="M5.5 8v2M8 8v2M10.5 8v2" strokeLinecap="round"/></svg>
    : <svg width="16" height="16" viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5"><circle cx="9" cy="9" r="6.5"/><path d="M9 8.2v4M9 5.8v.01" strokeLinecap="round"/></svg>;
  return (
    <div style={{ width:176, flexShrink:0, borderRight:`1px solid ${T.border}`, padding:'12px 10px', display:'flex', flexDirection:'column', gap:2 }}>
      {items.map(([label,k])=>{
        const on = active===label;
        return (
          <div key={label} className="dk-row" style={{ display:'flex', alignItems:'center', gap:10, padding:'8px 10px', borderRadius:8, cursor:'pointer', background:on?T.raised:'transparent' }}>
            {ico(k, on?T.accent:T.sec)}
            <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:on?600:400, color:on?T.text:T.sec }}>{label}</span>
          </div>
        );
      })}
    </div>
  );
}

function Group({ title, sub, children }) {
  return (
    <div style={{ marginBottom:24 }}>
      <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>{title}</div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:12, color:T.muted, margin:'3px 0 12px' }}>{sub}</div>}
      {!sub && <div style={{ height:12 }}/>}
      {children}
    </div>
  );
}

function SettingsGeneral() {
  return (
    <>
      <Group title="Default agent" sub="Which backend new sessions start with.">
        <div style={{ display:'flex', gap:10 }}>
          <div style={{ flex:1, border:`1.5px solid ${AGENTS.claude.color}`, background:AGENTS.claude.tint, borderRadius:11, padding:'13px', display:'flex', alignItems:'center', gap:9 }}>
            <AgentGlyph agent="claude" s={18}/><span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>Claude</span>
          </div>
          <div style={{ flex:1, border:`1.5px solid ${T.border}`, background:T.surface, borderRadius:11, padding:'13px', display:'flex', alignItems:'center', gap:9 }}>
            <AgentGlyph agent="codex" s={18}/><span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.sec }}>Codex</span>
          </div>
        </div>
      </Group>
      <Group title="Default permission mode" sub="How much a new session may do before it asks.">
        {[['Ask each step','default',T.sec,true],['Accept edits','acceptEdits',T.success],['Plan','plan',T.info],['Full auto','bypass',T.warning]].map(([l,t,c,on],i)=>(
          <div key={i} style={{ display:'flex', alignItems:'center', gap:10, padding:'10px 12px', borderRadius:9, marginBottom:7, border:`1.5px solid ${on?T.accent:T.border}`, background:on?T.surface:'transparent' }}>
            <Dot c={c} s={8}/><span style={{ fontFamily:T.ui, fontSize:13, color:T.text }}>{l}</span>
            {l==='Full auto' && I.warn(T.warning,13)}
            <span style={{ flex:1 }}/><span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>{t}</span>
          </div>
        ))}
      </Group>
    </>
  );
}

function SettingsComputers() {
  return (
    <Group title="Paired computers">
      {[['apple','Lidapeng-MBP','liam@anthropic',true,false],['linux','devbox-linux','liam@devbox',false,true]].map(([os,name,acct,on,rename],i)=>(
        <div key={i} style={{ display:'flex', alignItems:'center', gap:12, padding:'12px 14px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:11, marginBottom:9 }}>
          {I[os](T.sec,17)}
          {rename ? (
            <div style={{ flex:1, display:'flex', alignItems:'center', gap:8 }}>
              <span style={{ flex:1, fontFamily:T.ui, fontSize:13.5, color:T.text, background:T.base, border:`1.5px solid ${T.accent}`, borderRadius:7, padding:'5px 9px' }}>devbox-linux</span>
              <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'5px 12px', borderRadius:7, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:12, fontWeight:700 }}>Save</button>
              <button className="dk-btn" style={{ all:'unset', cursor:'pointer', padding:'5px 10px', borderRadius:7, color:T.sec, fontFamily:T.ui, fontSize:12 }}>Cancel</button>
            </div>
          ) : (
            <>
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>{name}</div>
                <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginTop:2 }}>{acct}</div>
              </div>
              <span style={{ display:'flex', alignItems:'center', gap:6 }}>
                <Dot c={on?T.success:T.muted} pulse={on} s={6}/>
                <span style={{ fontFamily:T.ui, fontSize:12, color: on?T.success:T.muted }}>{on?'online':'offline'}</span>
              </span>
              <button className="dk-btn" style={{ all:'unset', cursor:'pointer', fontFamily:T.ui, fontSize:12.5, color:T.sec, padding:'4px 8px' }}>Rename</button>
              <button className="dk-btn" style={{ all:'unset', cursor:'pointer', fontFamily:T.ui, fontSize:12.5, color:T.danger, fontWeight:500, padding:'4px 8px' }}>Revoke</button>
            </>
          )}
        </div>
      ))}
      <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'11px 14px', borderRadius:11, border:`1px dashed ${T.border}`, cursor:'pointer' }}>
        {I.plus(T.accent,15)}<span style={{ fontFamily:T.ui, fontSize:13, color:T.accent, fontWeight:500 }}>Add computer</span>
      </div>
    </Group>
  );
}

function SettingsShortcuts() {
  const rows = [['Command palette',['⌘','K']],['New session',['⌘','N']],['Send message',['⏎']],['New line',['⇧','⏎']],['Approve permission',['⌘','⏎']],['Open settings',['⌘',',']],['Close / dismiss',['esc']]];
  return (
    <Group title="Keyboard shortcuts">
      {rows.map(([action,keys],i)=>(
        <div key={i} style={{ display:'flex', alignItems:'center', padding:'9px 2px', borderBottom: i<rows.length-1?`1px solid ${T.border}`:'none' }}>
          <span style={{ fontFamily:T.ui, fontSize:13, color:T.text, flex:1 }}>{action}</span>
          <span style={{ display:'flex', gap:4 }}>{keys.map((k,j)=><Key key={j}>{k}</Key>)}</span>
        </div>
      ))}
    </Group>
  );
}

function SettingsAbout() {
  return (
    <div>
      <div style={{ display:'flex', alignItems:'center', gap:10, marginBottom:10 }}>
        <span style={{ width:38, height:38, borderRadius:10, background:T.surface, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><AgentGlyph agent="claude" s={20}/></span>
        <span style={{ fontFamily:T.ui, fontSize:18, fontWeight:700, color:T.text }}>cc-pocket</span>
      </div>
      <div style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.sec, marginBottom:20, maxWidth:380 }}>Desktop edition — one host driving Claude Code / Codex on another, over an end-to-end encrypted link.</div>
      {[['Version','0.4.1'],['Relay','wss://relay.ccpocket.dev'],['License','MIT']].map(([l,v],i)=>(
        <div key={i} style={{ display:'flex', alignItems:'center', padding:'9px 2px', borderBottom: i<2?`1px solid ${T.border}`:'none' }}>
          <span style={{ fontFamily:T.ui, fontSize:13, color:T.sec, flex:1 }}>{l}</span>
          <span style={{ fontFamily:T.mono, fontSize:12, color:T.text }}>{v}</span>
        </div>
      ))}
    </div>
  );
}

function SettingsModal({ active='General' }) {
  const content = active==='General'?<SettingsGeneral/> : active==='Computers'?<SettingsComputers/> : active==='Shortcuts'?<SettingsShortcuts/> : <SettingsAbout/>;
  return (
    <div style={{ width:700, height:500, background:T.raised, border:`1px solid ${T.border}`, borderRadius:16, overflow:'hidden', display:'flex', flexDirection:'column', boxShadow:'0 30px 80px -20px rgba(0,0,0,0.8)' }}>
      <div style={{ display:'flex', alignItems:'center', padding:'14px 18px', borderBottom:`1px solid ${T.border}`, flexShrink:0 }}>
        <span style={{ fontFamily:T.ui, fontSize:16, fontWeight:700, color:T.text }}>Settings</span>
        <span style={{ flex:1 }}/>
        <span style={{ display:'flex', cursor:'pointer' }}>{I.x(T.sec,16)}</span>
      </div>
      <div style={{ flex:1, minHeight:0, display:'flex' }}>
        <Rail active={active}/>
        <div className="dk-scroll" style={{ flex:1, overflowY:'auto', padding:'20px 22px' }}>{content}</div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// ③ CONNECT & ADD-COMPUTER
// ════════════════════════════════════════════════════════════
function CodeField({ digits='', big }) {
  const cells = [0,1,2,3,4,5];
  return (
    <div style={{ display:'flex', gap:big?10:8, justifyContent:'center' }}>
      {cells.map(i=>{
        const filled = i<digits.length;
        const cur = i===digits.length;
        return (
          <div key={i} style={{ width:big?48:42, height:big?58:52, borderRadius:10, background:T.surface,
            border:`1.5px solid ${cur?T.accent:T.border}`, display:'flex', alignItems:'center', justifyContent:'center',
            fontFamily:T.mono, fontSize:big?24:21, fontWeight:600, color:T.text }}>
            {filled ? digits[i] : (cur ? <span style={{ width:2, height:big?26:22, background:T.accent }}/> : '')}
          </div>
        );
      })}
    </div>
  );
}

function StatusLine({ kind }) {
  const map = {
    none:null,
    connecting:{ c:T.sec, t:'connecting…', spin:true },
    bad:{ c:T.danger, t:'invalid or expired code' },
    offline:{ c:T.warning, t:'computer offline' },
    relay:{ c:T.danger, t:'relay unreachable' },
  };
  const m = map[kind];
  if (!m) return <div style={{ height:18 }}/>;
  return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center', gap:7, height:18 }}>
      {m.spin && <span className="dk-spin" style={{ width:11, height:11, border:`1.5px solid ${T.border}`, borderTopColor:m.c, borderRadius:999 }}/>}
      <span style={{ fontFamily:T.mono, fontSize:11.5, color:m.c }}>{m.t}</span>
    </div>
  );
}

// full-screen connect inside the content area
function ConnectScreen({ mode='picker', digits='', status='none' }) {
  return (
    <div style={{ width:760, height:540, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', justifyContent:'center' }}>
      <div style={{ width:380 }}>
        <div style={{ textAlign:'center', fontFamily:T.ui, fontSize:24, fontWeight:700, color:T.text, marginBottom:28 }}>CC&nbsp;Pocket</div>
        {mode==='picker' ? (
          <>
            <div style={{ fontFamily:T.ui, fontSize:12, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', marginBottom:11 }}>Choose a computer</div>
            {[['apple','Lidapeng-MBP'],['linux','devbox-linux']].map(([os,name],i)=>(
              <div key={i} className="dk-row" style={{ display:'flex', alignItems:'center', gap:11, padding:'13px 14px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:11, marginBottom:9, cursor:'pointer' }}>
                {I[os](T.sec,17)}<span style={{ fontFamily:T.ui, fontSize:14, color:T.text }}>{name}</span><span style={{ flex:1 }}/>{I.chevR(T.muted,15)}
              </div>
            ))}
            <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, padding:'13px 14px', borderRadius:11, border:`1px dashed ${T.border}`, cursor:'pointer' }}>
              {I.plus(T.accent,15)}<span style={{ fontFamily:T.ui, fontSize:13.5, color:T.accent, fontWeight:500 }}>Add computer</span>
            </div>
          </>
        ) : (
          <>
            <div style={{ fontFamily:T.ui, fontSize:12, fontWeight:600, letterSpacing:0.6, color:T.muted, textTransform:'uppercase', marginBottom:16, textAlign:'center' }}>Connect a computer</div>
            <div style={{ marginBottom:18 }}><CodeField digits={digits} big/></div>
            <button className="dk-btn" style={{ all:'unset', cursor:'pointer', display:'block', textAlign:'center', width:'100%', padding:'13px 0', borderRadius:11, marginBottom:14,
              background: digits.length===6?T.accent:T.surface, border: digits.length===6?'none':`1px solid ${T.border}`,
              color: digits.length===6?'#0E0F11':T.muted, fontFamily:T.ui, fontSize:14.5, fontWeight:700 }}>
              {status==='connecting'?'Connecting…':'Connect'}
            </button>
            <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted, textAlign:'center', marginBottom:14, lineHeight:'19px' }}>
              Run <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px' }}>cc-pocket pair</span> on the other computer to get a code.
            </div>
            <StatusLine kind={status}/>
          </>
        )}
      </div>
    </div>
  );
}

// add-computer modal over the live shell
function AddComputerModal({ digits='', busy }) {
  return (
    <div style={{ width:1100, height:600, position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${T.border}` }}>
      <div style={{ position:'absolute', inset:0, opacity:0.5 }}><ShellBackdrop/></div>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.64)' }}/>
      <div style={{ position:'absolute', top:'50%', left:'50%', transform:'translate(-50%,-50%)', width:420, background:T.raised, border:`1px solid ${T.border}`, borderRadius:16, padding:'22px 24px', boxShadow:'0 30px 80px -20px rgba(0,0,0,0.8)' }}>
        <div style={{ fontFamily:T.ui, fontSize:17, fontWeight:700, color:T.text, marginBottom:5 }}>Add a computer</div>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.sec, marginBottom:20 }}>Pair another computer — your current session stays connected.</div>
        <div style={{ marginBottom:18 }}><CodeField digits={digits}/></div>
        <div style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted, textAlign:'center', marginBottom:18, lineHeight:'19px' }}>
          Run <span style={{ fontFamily:T.mono, fontSize:12, color:T.sec, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px' }}>cc-pocket pair</span> on the other computer to get a code.
        </div>
        <div style={{ display:'flex', gap:10 }}>
          <button className="dk-btn" style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', padding:'11px 0', borderRadius:10, border:`1px solid ${T.border}`, color:T.text, fontFamily:T.ui, fontSize:14, fontWeight:600 }}>Cancel</button>
          <button className="dk-btn" style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', padding:'11px 0', borderRadius:10, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:14, fontWeight:700 }}>{busy?'Pairing…':'Connect'}</button>
        </div>
      </div>
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
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 40px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop · append</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Palette · Settings · Connect</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:720, margin:0 }}>
        Three surfaces the main shell didn't cover, matched to the two-pane design: a ⌘K command palette, an in-shell Settings modal, and the connect / add-computer flow. Dark only.
      </p>

      <Divider sub="Centered over a dimmed (not blurred) shell. Flat ranked list — each row carries a type tag instead of group headers, so ↑/↓ map 1:1. Selected row: raised fill + 2px terracotta left edge. Codex sessions get a teal tag.">① Command palette · ⌘K</Divider>
      <div style={{ display:'flex', flexDirection:'column', gap:30 }}>
        <div><Label>Default · mixed results (scrolls)</Label><div style={{ overflowX:'auto' }} className="dk-scroll"><PaletteOverlay/></div></div>
        <div style={{ display:'flex', gap:30, flexWrap:'wrap', alignItems:'flex-start' }}>
          <div><Label>Typed query “relay” · ranked + emphasized</Label><CommandPalette query="relay" rows={PALETTE_RELAY} count="4 results"/></div>
          <div><Label>Empty · no matches</Label><CommandPalette query="xyzzy" empty/></div>
        </div>
      </div>

      <Divider sub="An in-shell modal (not a second OS window) over a dimmed backdrop: a left rail of sections + content pane. No Appearance section — the app is dark-only for now.">② Settings</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:30 }}>
        <div><Label>General</Label><SettingsModal active="General"/></div>
        <div><Label>Computers (incl. inline-rename)</Label><SettingsModal active="Computers"/></div>
        <div><Label>Shortcuts</Label><SettingsModal active="Shortcuts"/></div>
        <div><Label>About</Label><SettingsModal active="About"/></div>
      </div>

      <Divider sub="Desktops have no camera, so a 6-digit code is the primary path (no QR). The not-connected screen fills the content area; Add-computer is a modal inside the live shell so the current session stays connected.">③ Connect & add computer</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:30 }}>
        <div><Label>Device picker (paired computers exist)</Label><ConnectScreen mode="picker"/></div>
        <div><Label>Pairing form · empty</Label><ConnectScreen mode="pair" digits="" status="none"/></div>
        <div><Label>6 digits entered · button active</Label><ConnectScreen mode="pair" digits="481920" status="none"/></div>
        <div><Label>Connecting…</Label><ConnectScreen mode="pair" digits="481920" status="connecting"/></div>
        <div><Label>Error · bad/expired code</Label><ConnectScreen mode="pair" digits="" status="bad"/></div>
      </div>

      <Label>Add-computer modal (inside the live shell)</Label>
      <div style={{ display:'flex', flexWrap:'wrap', gap:30 }}>
        <div><Label>Empty</Label><div style={{ overflowX:'auto' }} className="dk-scroll"><AddComputerModal digits=""/></div></div>
      </div>

      <Label>Bottom status-line variants</Label>
      <div style={{ display:'flex', gap:14, flexWrap:'wrap' }}>
        {[['connecting','connecting'],['offline','computer offline'],['relay','relay unreachable']].map(([k,l])=>(
          <div key={k} style={{ width:240, background:T.base, border:`1px solid ${T.border}`, borderRadius:10, padding:'16px 0' }}><StatusLine kind={k}/></div>
        ))}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
