// cc-pocket — macOS menu-bar presence
// Deliverable A: status-glyph state language · Deliverable B: anchored menu-bar-extra popover.
// Reuses desktop-core.jsx tokens/icons and the tray-popover section grammar.

// ── the cc-pocket menu-bar glyph (monochrome, template-style) ──
function CCGlyph({ c='#fff', s=17, op=1 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 18 18" fill="none" style={{ opacity:op }}>
      <path d="M4 4.5l4.3 4.3L4 13.1" stroke={c} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M9.6 13.2h4.4" stroke={c} strokeWidth="1.9" strokeLinecap="round"/>
    </svg>
  );
}
// hollow variant for offline
function CCGlyphHollow({ c='#fff', s=17 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 18 18" fill="none" style={{ opacity:0.5 }}>
      <path d="M4 4.5l4.3 4.3L4 13.1" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M9.6 13.2h4.4" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    </svg>
  );
}

// ── neighbouring macOS system icons (monochrome, for context) ─
const SysWifi = ({ c='#fff' }) => <svg width="17" height="13" viewBox="0 0 17 13" fill={c} style={{ opacity:0.9 }}><path d="M8.5 2.2c2.7 0 5.1 1 6.9 2.8l-1.3 1.3A8.3 8.3 0 008.5 4 8.3 8.3 0 002.4 6.3L1.1 5A9.7 9.7 0 018.5 2.2z"/><path d="M8.5 6.1c1.6 0 3.1.6 4.2 1.7l-1.3 1.3A4.4 4.4 0 008.5 7.9c-1.1 0-2.1.4-2.9 1.2L4.3 7.8A6 6 0 018.5 6.1z"/><circle cx="8.5" cy="11" r="1.5"/></svg>;
const SysBattery = ({ c='#fff' }) => <svg width="26" height="13" viewBox="0 0 26 13" fill="none" style={{ opacity:0.9 }}><rect x="0.6" y="0.6" width="22" height="11.8" rx="3" stroke={c} strokeOpacity="0.4"/><rect x="2.2" y="2.2" width="15" height="8.6" rx="1.6" fill={c}/><path d="M24.5 4.3v4.4c.8-.3 1.5-1.2 1.5-2.2s-.7-1.9-1.5-2.2z" fill={c} fillOpacity="0.5"/></svg>;
const SysControl = ({ c='#fff' }) => <svg width="16" height="13" viewBox="0 0 16 13" fill="none" style={{ opacity:0.9 }}><rect x="1" y="1.5" width="14" height="4" rx="2" stroke={c} strokeWidth="1.3"/><rect x="1" y="7.5" width="14" height="4" rx="2" stroke={c} strokeWidth="1.3"/><circle cx="5" cy="3.5" r="1.3" fill={c}/><circle cx="11" cy="9.5" r="1.3" fill={c}/></svg>;
const SysSpotlight = ({ c='#fff' }) => <svg width="15" height="15" viewBox="0 0 16 16" fill="none" style={{ opacity:0.9 }}><circle cx="7" cy="7" r="4.6" stroke={c} strokeWidth="1.4"/><path d="M10.5 10.5l3 3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/></svg>;

// a menu-bar segment close-up
function BarSlot({ children, glow }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:5, padding:'0 6px', height:22, borderRadius:6, background: glow?'rgba(217,119,87,0.14)':'transparent' }}>{children}</div>
  );
}
function MenuBar({ slot }) {
  return (
    <div style={{ height:28, borderRadius:8, background:'linear-gradient(#26282C, #202225)', border:'1px solid rgba(255,255,255,0.08)',
      boxShadow:'inset 0 1px 0 rgba(255,255,255,0.06)', display:'flex', alignItems:'center', padding:'0 9px', gap:14, minWidth:280 }}>
      {slot}
      <span style={{ flex:1 }}/>
      <SysControl/><SysWifi/><SysBattery/>
      <span style={{ fontFamily:'-apple-system, system-ui', fontSize:12.5, color:'#fff', opacity:0.92, letterSpacing:0.2 }}>Wed 9:41</span>
      <SysSpotlight/>
    </div>
  );
}

// the five states
function StateIdle()   { return <MenuBar slot={<BarSlot><CCGlyph op={0.85}/></BarSlot>}/>; }
function StateRunning(){ return <MenuBar slot={<BarSlot><CCGlyph op={0.9}/><span style={{ fontFamily:T.mono, fontSize:11.5, color:'#fff', opacity:0.75 }}>2</span></BarSlot>}/>; }
function StateNeeds()  { return <MenuBar slot={<BarSlot glow><CCGlyph op={1}/><span style={{ width:6, height:6, borderRadius:999, background:T.accent, boxShadow:`0 0 6px ${T.accent}` }}/><span style={{ fontFamily:T.mono, fontSize:11.5, fontWeight:600, color:T.accent }}>1</span></BarSlot>}/>; }
function StateDone()   { return <MenuBar slot={<BarSlot><CCGlyph op={0.9}/><svg width="14" height="14" viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={T.success} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/></svg></BarSlot>}/>; }
function StateOffline(){ return <MenuBar slot={<BarSlot><CCGlyphHollow/></BarSlot>}/>; }

function StateCell({ label, sub, children, flash }) {
  return (
    <div style={{ flex:'1 1 0', minWidth:250 }}>
      <div style={{ position:'relative' }}>
        {children}
        {flash && <span style={{ position:'absolute', top:-6, right:-6, fontFamily:T.mono, fontSize:9, color:T.success, opacity:0.7 }}></span>}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text, marginTop:13 }}>{label}</div>
      <div style={{ fontFamily:T.ui, fontSize:11.5, lineHeight:'16px', color:T.muted, marginTop:3 }}>{sub}</div>
    </div>
  );
}

// ════════════════ B · MENU-BAR-EXTRA POPOVER ════════════════
function MachineChip({ name }) {
  return <span style={{ display:'inline-flex', alignItems:'center', gap:4, fontFamily:T.mono, fontSize:10.5, color:T.sec, background:T.base, border:`1px solid ${T.border}`, borderRadius:999, padding:'1px 8px', flexShrink:0 }}>{I.apple(T.muted,10)}{name}</span>;
}
function MiniBtn({ children, danger }) {
  return <button className="dk-btn" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', textAlign:'center', padding:'6px 12px', borderRadius:8, fontFamily:T.ui, fontSize:12, fontWeight:600,
    background: danger?'transparent':T.accent, color: danger?T.danger:'#0E0F11', border: danger?`1px solid ${T.danger}66`:'none' }}>{children}</button>;
}
function NeedsRow({ title, ask, machine }) {
  return (
    <div className="dk-row" style={{ borderRadius:10, padding:'10px 10px', cursor:'pointer' }}>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
        <MachineChip name={machine}/>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:9, marginTop:8 }}>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{ask}</span>
        <MiniBtn danger>Deny</MiniBtn>
        <MiniBtn>Allow</MiniBtn>
      </div>
    </div>
  );
}
function RunningRow({ title, machine, elapsed }) {
  return (
    <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:9, borderRadius:10, padding:'9px 10px', cursor:'pointer' }}>
      <Dot c={T.success} pulse s={7}/>
      <span style={{ fontFamily:T.ui, fontSize:13, color:T.text, flex:1, minWidth:0, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{title}</span>
      <MachineChip name={machine}/>
      <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted, width:34, textAlign:'right' }}>{elapsed}</span>
    </div>
  );
}
function SecLabel({ children, right }) {
  return <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 6px 8px' }}>
    <span style={{ fontFamily:T.ui, fontSize:10, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
    <span style={{ flex:1 }}/>{right}</div>;
}

function Popover() {
  return (
    <div style={{ width:360, background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, overflow:'hidden', boxShadow:'0 26px 60px -18px rgba(0,0,0,0.72)' }}>
      {/* header */}
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'12px 12px 12px 14px', borderBottom:`1px solid ${T.border}` }}>
        <CCGlyph c={T.text} s={16} op={0.95}/>
        <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:700, color:T.text }}>cc-pocket</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>2 computers · 5 sessions</span>
        <span className="dk-btn tm-iconbtn" style={{ display:'flex', alignItems:'center', justifyContent:'center', width:26, height:26, borderRadius:7, cursor:'pointer' }}>{I.gear(T.sec,16)}</span>
      </div>
      {/* needs you */}
      <div style={{ padding:'12px 8px 4px' }}>
        <div style={{ padding:'0 6px' }}><SecLabel right={<span style={{ fontFamily:T.mono, fontSize:10, fontWeight:600, color:T.accent, background:'rgba(217,119,87,0.14)', borderRadius:999, padding:'1px 7px' }}>2</span>}>Needs you</SecLabel></div>
        <NeedsRow title="Deploy relay build" ask="Bash · git push origin main" machine="MBP-Pandaa"/>
        <NeedsRow title="Port parser to Rust" ask="Edit · src/relay/WsClient.kt  +5 −1" machine="devbox-linux"/>
      </div>
      {/* running */}
      <div style={{ padding:'8px 8px 4px', borderTop:`1px solid ${T.border}`, marginTop:6 }}>
        <div style={{ padding:'8px 6px 0' }}><SecLabel>Running</SecLabel></div>
        <RunningRow title="Fix relay reconnect" machine="MBP-Pandaa" elapsed="12m"/>
        <RunningRow title="Add WS reconnect test" machine="devbox-linux" elapsed="3m"/>
      </div>
      {/* footer */}
      <div style={{ borderTop:`1px solid ${T.border}`, padding:'6px 8px 8px' }}>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, borderRadius:9, padding:'9px 10px', cursor:'pointer' }}>
          <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted }}>+2 more sessions</span>
          <span style={{ flex:1 }}/>{I.chevR(T.muted,14)}
        </div>
        <div className="dk-row" style={{ display:'flex', alignItems:'center', gap:8, borderRadius:9, padding:'10px 10px', cursor:'pointer' }}>
          <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>Open cc-pocket</span>
          <span style={{ flex:1 }}/><Key>⌘⏎</Key>
        </div>
      </div>
    </div>
  );
}

// popover anchored under a menu bar, on a desktop-wallpaper backdrop
function PopoverFrame() {
  return (
    <div style={{ width:900, borderRadius:14, overflow:'hidden', border:`1px solid ${T.border}`, position:'relative',
      background:'radial-gradient(120% 100% at 78% -10%, #2a2320 0%, #14161a 45%, #0B0C0D 100%)' }}>
      {/* real macOS menu bar across the top */}
      <div style={{ height:28, background:'rgba(28,30,34,0.72)', backdropFilter:'blur(20px)', WebkitBackdropFilter:'blur(20px)', borderBottom:'1px solid rgba(255,255,255,0.07)',
        display:'flex', alignItems:'center', padding:'0 14px', gap:18 }}>
        <span style={{ fontFamily:'-apple-system, system-ui', fontSize:13, fontWeight:700, color:'#fff' }}></span>
        <span style={{ fontFamily:'-apple-system, system-ui', fontSize:13, fontWeight:600, color:'#fff', opacity:0.95 }}>cc-pocket</span>
        {['File','Edit','View','Window','Help'].map(m=><span key={m} style={{ fontFamily:'-apple-system, system-ui', fontSize:13, color:'#fff', opacity:0.8 }}>{m}</span>)}
        <span style={{ flex:1 }}/>
        {/* our glyph — needs-you state, highlighted as the active extra */}
        <span style={{ display:'flex', alignItems:'center', gap:5, height:22, padding:'0 7px', borderRadius:6, background:'rgba(255,255,255,0.14)' }}>
          <CCGlyph op={1}/><span style={{ width:6, height:6, borderRadius:999, background:T.accent, boxShadow:`0 0 6px ${T.accent}` }}/><span style={{ fontFamily:T.mono, fontSize:11.5, fontWeight:600, color:T.accent }}>2</span>
        </span>
        <SysControl/><SysWifi/><SysBattery/>
        <span style={{ fontFamily:'-apple-system, system-ui', fontSize:12.5, color:'#fff', opacity:0.92 }}>Wed 9:41</span>
      </div>
      {/* popover dropping from the glyph (right-aligned) */}
      <div style={{ padding:'12px 16px 40px', display:'flex', justifyContent:'flex-end' }}>
        <div style={{ position:'relative', marginRight:96 }}>
          <div style={{ position:'absolute', top:-6, right:22, width:12, height:12, background:T.raised, borderLeft:`1px solid ${T.border}`, borderTop:`1px solid ${T.border}`, transform:'rotate(45deg)', zIndex:2 }}/>
          <Popover/>
        </div>
      </div>
    </div>
  );
}

// ════════════════ BOARD ════════════════
function Divi({ children, sub }) {
  return (
    <div style={{ margin:'60px 0 26px' }}>
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
    <div style={{ maxWidth:1200, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · desktop</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Menu-bar presence</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:760, margin:0 }}>
        The cross-machine "needs you" surface moves up to the OS layer: a persistent macOS menu-bar glyph that reads your agents' state at a glance, and a compact anchored popover to approve, deny, and jump back — without raising the main window. The moment an agent needs you, it comes find you.
      </p>

      <Divi sub="A monochrome template glyph that stays quiet until it matters. Colour is spent only on the one state that needs a human — NEEDS YOU — so the terracotta dot is unmistakable among the system icons. DONE is a brief green flash that decays back to idle.">A · Glyph state language</Divi>
      <div style={{ background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:16, padding:'34px 28px' }}>
        <div style={{ display:'flex', gap:22, flexWrap:'wrap' }}>
          <StateCell label="Idle" sub="Monochrome glyph, quiet. Nothing running, nothing waiting."><StateIdle/></StateCell>
          <StateCell label="Running" sub="Live session count in mono. Still monochrome — work in progress isn't an alert."><StateRunning/></StateCell>
          <StateCell label="Needs you" sub="Terracotta dot + count. The only coloured state in the bar."><StateNeeds/></StateCell>
          <StateCell label="Done (flash)" sub="A green tick flashes beside the glyph, then decays back to idle." flash><StateDone/></StateCell>
          <StateCell label="Offline" sub="Hollow, 50% opacity. The daemon or all computers are unreachable."><StateOffline/></StateCell>
        </div>
      </div>

      <Divi sub="Clicking the glyph drops a native menu-bar-extra: header counts + gear, a NEEDS YOU section with inline Deny / Allow per approval, a RUNNING section with elapsed time and a streaming pulse, and a footer to open the app or overflow the rest. Every row is click-to-jump. Same section grammar as the in-window tray popover — promoted to the OS layer.">B · Anchored popover</Divi>
      <div style={{ overflowX:'auto' }} className="dk-scroll"><PopoverFrame/></div>

      <div style={{ marginTop:30, display:'flex', gap:40, alignItems:'flex-start', flexWrap:'wrap' }}>
        <div><div style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text, marginBottom:14 }}>Popover · detail</div><Popover/></div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
