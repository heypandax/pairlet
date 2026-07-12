// cc-pocket — Onboarding · install the daemon on your computer (before Pairing)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ c=T.sec, s=17 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M11 3L5 9l6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Copy = ({ c=T.sec, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5"><rect x="6" y="6" width="9" height="9" rx="1.6"/><path d="M12 6V4.5A1.5 1.5 0 0010.5 3h-6A1.5 1.5 0 003 4.5v6A1.5 1.5 0 004.5 12H6" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Check = ({ c=T.success, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Lock = ({ c=T.sec, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="3.5" y="8" width="11" height="7.5" rx="1.6"/><path d="M5.8 8V5.8a3.2 3.2 0 016.4 0V8"/></svg>;
const Play = ({ c=T.accent, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M4 3l7 4-7 4V3z" fill={c}/></svg>;
const Apple = ({ c, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M12.3 9.5c0-1.8 1.5-2.7 1.6-2.7-.9-1.3-2.2-1.4-2.7-1.5-1.1-.1-2.2.7-2.8.7-.6 0-1.5-.7-2.4-.6-1.2 0-2.4.7-3 1.8-1.3 2.2-.3 5.5.9 7.3.6.9 1.3 1.9 2.2 1.8.9 0 1.2-.6 2.3-.6 1.1 0 1.3.6 2.3.6 1 0 1.6-.9 2.2-1.8.7-1 .9-2 .9-2.1 0 0-1.7-.7-1.7-2.6zM10.8 4.3c.5-.6.8-1.4.7-2.3-.7 0-1.5.5-2 1.1-.4.5-.8 1.3-.7 2.1.8.1 1.5-.4 2-.9z"/></svg>;
const Win = ({ c, s=14 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M2.5 4.2l5.4-.75v5.05H2.5zM8.6 3.35L15.5 2.4v6.15H8.6zM2.5 9.2h5.4v5.05L2.5 13.5zM8.6 9.2h6.9v6.4l-6.9-.95z"/></svg>;
const Linux = ({ c, s=15 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3.5c-1 0-1.4 1-1.4 2.4 0 1-.6 1.8-1.3 2.9-.6 1-1.3 2-1.3 3 0 .8.6 1.2 1.6 1.5.8.2 1.2.8 2 1 .8.3 2 .3 2.8 0 .8-.2 1.2-.8 2-1 1-.3 1.6-.7 1.6-1.5 0-1-.7-2-1.3-3-.7-1.1-1.3-1.9-1.3-2.9 0-1.4-.4-2.4-1.4-2.4z"/><path d="M7.3 6.4v.01M9.7 6.4v.01"/></svg>;

// ── command card with working copy ────────────────────────────
function CmdCard({ lines }) {
  const [copied, setCopied] = React.useState(false);
  const text = lines.join('\n');
  const copy = () => {
    try { navigator.clipboard.writeText(text); } catch(e){}
    setCopied(true); setTimeout(()=>setCopied(false), 1500);
  };
  return (
    <div style={{ background:'#0B0C0D', border:`1px solid ${T.border}`, borderRadius:10, display:'flex', alignItems:'flex-start', gap:8, padding:'10px 8px 10px 12px', marginTop:9 }}>
      <div style={{ flex:1, minWidth:0, overflowX:'auto' }} className="ob-scroll">
        {lines.map((l,i)=>(
          <div key={i} style={{ display:'flex', gap:9, whiteSpace:'nowrap', lineHeight:'20px' }}>
            <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent, userSelect:'none' }}>$</span>
            <span style={{ fontFamily:T.mono, fontSize:12, color:T.text }}>{l}</span>
          </div>
        ))}
      </div>
      <button onClick={copy} className="ob-press" aria-label="Copy" style={{ all:'unset', cursor:'pointer', width:30, height:30, borderRadius:7, border:`1px solid ${copied?T.success:T.border}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
        {copied ? <Check/> : <Copy/>}
      </button>
    </div>
  );
}

// ── numbered step ─────────────────────────────────────────────
function Step({ n, title, children, last }) {
  return (
    <div style={{ display:'flex', gap:13, position:'relative' }}>
      {/* connector */}
      {!last && <div style={{ position:'absolute', left:13, top:28, bottom:-6, width:1, background:T.border }}/>}
      <div style={{ width:27, height:27, borderRadius:999, border:`1px solid ${T.accent}`, color:T.accent, display:'flex', alignItems:'center', justifyContent:'center', fontFamily:T.mono, fontSize:13, flexShrink:0, background:T.base, zIndex:1 }}>{n}</div>
      <div style={{ flex:1, minWidth:0, paddingBottom:22 }}>
        <div style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text, marginTop:3 }}>{title}</div>
        {children}
      </div>
    </div>
  );
}

// ── OS data ───────────────────────────────────────────────────
const OS = {
  macOS:   { key:'macOS', icon:Apple, install:['brew install heypandax/tap/cc-pocket'] },
  Windows: { key:'Windows', icon:Win, install:['scoop bucket add heypandax https://github.com/heypandax/scoop-bucket','scoop install cc-pocket'] },
  Linux:   { key:'Linux', icon:Linux, install:['curl -fsSL https://get.ccpocket.dev | sh'] },
};
const ORDER = ['macOS','Windows','Linux'];

// ── screen ────────────────────────────────────────────────────
function InstallScreen({ os='macOS', live }) {
  const [sel, setSel] = React.useState(os);
  const active = live ? sel : os;
  const data = OS[active];

  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      {/* header */}
      <div style={{ flexShrink:0, paddingTop:52, background:T.base }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 8px' }}>
          <button style={{ all:'unset', cursor:'pointer', width:40, height:40, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron/></button>
        </div>
      </div>

      <div className="ob-scroll" style={{ flex:1, minHeight:0, overflowY:'auto', padding:'6px 20px 20px' }}>
        <h1 style={{ fontFamily:T.ui, fontSize:23, fontWeight:700, color:T.text, letterSpacing:-0.4, margin:'0 0 8px' }}>Connect your computer</h1>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'21px', color:T.sec, margin:'0 0 20px' }}>Install the cc-pocket daemon on the computer you want to drive.</p>

        {/* OS segmented switch */}
        <div style={{ display:'flex', gap:3, padding:3, background:T.surface, border:`1px solid ${T.border}`, borderRadius:11, marginBottom:24 }}>
          {ORDER.map(k=>{
            const on = k===active;
            const Ico = OS[k].icon;
            return (
              <button key={k} onClick={()=>live&&setSel(k)} className="ob-press" style={{ all:'unset', cursor: live?'pointer':'default', flex:1, display:'flex', alignItems:'center', justifyContent:'center', gap:6, padding:'8px 0', borderRadius:8, background:on?T.raised:'transparent', border:`1px solid ${on?T.border:'transparent'}` }}>
                <Ico c={on?T.text:T.muted}/>
                <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:on?T.text:T.muted }}>{k}</span>
              </button>
            );
          })}
        </div>

        {/* steps */}
        <Step n="1" title="Install">
          <CmdCard lines={data.install}/>
        </Step>
        <Step n="2" title="Run">
          <CmdCard lines={['cc-pocket run']}/>
          <div style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, marginTop:9 }}>Keep it running — it also installs as a login service, so it survives reboots.</div>
        </Step>
        <Step n="3" title="Pair" last>
          <div style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.sec, marginTop:4 }}>Then tap <span style={{ color:T.text, fontWeight:600 }}>Pair now</span> below and scan the code shown on your computer.</div>
        </Step>

        {/* reassuring callout */}
        <div style={{ display:'flex', gap:10, alignItems:'flex-start', background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'12px 13px', marginTop:6 }}>
          <span style={{ flexShrink:0, marginTop:1 }}><Lock c={T.accent}/></span>
          <span style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec }}>Your pairing code never leaves your devices — the relay only ever sees <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}>ciphertext</span>.</span>
        </div>
      </div>

      {/* footer */}
      <div style={{ flexShrink:0, padding:'12px 20px 34px', borderTop:`1px solid ${T.border}`, background:T.base }}>
        <button className="ob-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', width:'100%', height:50, display:'flex', alignItems:'center', justifyContent:'center', background:T.accent, color:'#0E0F11', borderRadius:13, fontFamily:T.ui, fontSize:16, fontWeight:700 }}>Pair now</button>
        <button className="ob-press" style={{ all:'unset', cursor:'pointer', width:'100%', height:40, marginTop:4, display:'flex', alignItems:'center', justifyContent:'center', gap:7, color:T.sec, fontFamily:T.ui, fontSize:13.5, fontWeight:500 }}>
          <Play/><span>Watch a 30-sec setup video</span>
        </button>
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

function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 44px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · onboarding</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Install the daemon — before pairing</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:0 }}>
        The phone drives Claude Code running on a computer, so step one is installing + running the daemon there. An OS switch swaps the install command; the copy buttons work. The first phone is live — tap the OS switch.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:36, marginTop:40 }}>
        <Cell label="Live · tap the OS switch" note="Interactive: switching OS swaps the install command and its glyph. Copy buttons flip to a green check.">
          <InstallScreen os="macOS" live/>
        </Cell>
        <Cell label="macOS" note="Homebrew tap — a single install line.">
          <InstallScreen os="macOS"/>
        </Cell>
        <Cell label="Windows" note="Scoop — add the bucket, then install (two commands in the card).">
          <InstallScreen os="Windows"/>
        </Cell>
        <Cell label="Linux" note="A download-and-extract one-liner piped to sh.">
          <InstallScreen os="Linux"/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
