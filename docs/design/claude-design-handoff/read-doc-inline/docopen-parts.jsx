// cc-pocket — "Read the doc, right here" · shared parts
// Tokens, icons, PathLink (inline openable path), ToolCard (Write/Edit open affordance),
// Phone frame + FileViewer (read / error-export / loading). Exported to window.

const DARK = {
  page:'#08090A', base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', accentPress:'#C4633F', success:'#4FB477', danger:'#E5604D',
  // openable-path affordance tints (terracotta, low-commitment)
  ulTint:'rgba(217,119,87,0.55)', pillBg:'rgba(217,119,87,0.10)',
  pillBd:'rgba(217,119,87,0.30)', pressBg:'rgba(217,119,87,0.18)',
  synStr:'#7FB59A',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const LIGHT = {
  page:'#EFEDE8', base:'#FAF9F7', surface:'#FFFFFF', raised:'#F1EFEB', border:'#E4E1DB',
  text:'#26292E', sec:'#5C6169', muted:'#8A8F97',
  accent:'#C4633F', accentPress:'#A94E2E', success:'#2E9E5B', danger:'#C0483A',
  ulTint:'rgba(196,99,63,0.5)', pillBg:'rgba(196,99,63,0.07)',
  pillBd:'rgba(196,99,63,0.28)', pressBg:'rgba(196,99,63,0.15)',
  synStr:'#3F8F63',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
// tiny doc glyph — leads an openable path (12–14px, 1.5pt stroke)
const DocGlyph = ({ c, s = 13 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
    <path d="M4 1.75h5l3 3v8.5a1 1 0 01-1 1H4a1 1 0 01-1-1v-10.5a1 1 0 011-1z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M9 1.75V4.75h3" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M5.4 8.2h5.2M5.4 10.6h3.4" stroke={c} strokeWidth="1.3" strokeLinecap="round"/>
  </svg>
);
// open indicator — arrow leaving corner
const OpenArrow = ({ c, s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none" style={{ flexShrink: 0 }}>
    <path d="M5 3H3.5A1.5 1.5 0 002 4.5v6A1.5 1.5 0 003.5 12h6A1.5 1.5 0 0011 10.5V9" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    <path d="M8 2.5h3.5V6M11 3l-5 5" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const WriteGlyph = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
    <path d="M9.5 2.6l3.9 3.9M10.8 1.3a1.4 1.4 0 012 2l-8 8L2 14.5l1.2-2.8 8-8z" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const EditGlyph = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
    <path d="M8 13.5H14" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
    <path d="M9.5 2.6l3.9 3.9M10.8 1.3a1.4 1.4 0 012 2l-7 7L2.6 11l.8-3.2 7.4-6.5z" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const Chevron = ({ d = 'down', c, s = 14, w = 1.8 }) => {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6', down:'M3 6l6 6 6-6', up:'M3 12l6-6 6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Overflow = ({ c, s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18"><circle cx="9" cy="3.5" r="1.4" fill={c}/><circle cx="9" cy="9" r="1.4" fill={c}/><circle cx="9" cy="14.5" r="1.4" fill={c}/></svg>
);
const ClaudeGlyph = ({ c, s = 13 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none" style={{ flexShrink: 0 }}>
    <path d="M5 5l4.2 4.2L5 13.4" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M11 14h4" stroke={c} strokeWidth="1.8" strokeLinecap="round"/>
  </svg>
);
const LockGlyph = ({ c, s = 22 }) => (
  <svg width={s} height={s} viewBox="0 0 24 24" fill="none">
    <rect x="4.5" y="10.5" width="15" height="10" rx="2.4" stroke={c} strokeWidth="1.6"/>
    <path d="M7.5 10.5V7.5a4.5 4.5 0 019 0v3" stroke={c} strokeWidth="1.6" strokeLinecap="round"/>
    <circle cx="12" cy="15.4" r="1.5" fill={c}/>
  </svg>
);
function Spinner({ c, track, s = 16 }) {
  return <span className="do-spin" style={{ display:'inline-block', width:s, height:s, border:`2px solid ${track}`, borderTopColor:c, borderRadius:999, flexShrink:0 }}/>;
}

// ── COMPONENT 1 — inline openable path ────────────────────────
// variant: 'pill'   → standalone openable path on its own line (chip)
//          'inline' → mid-sentence path (tinted underline + glyph)
//          'code'   → path inside a fenced code block (underline only)
function PathLink({ tokens, variant = 'inline', children, glyph = true, pressed = false }) {
  const T = tokens;
  if (variant === 'pill') {
    return (
      <span style={{
        display:'inline-flex', alignItems:'center', gap:6, verticalAlign:'baseline',
        height:26, padding:'0 9px', borderRadius:7,
        background: pressed ? T.pressBg : T.pillBg,
        border:`1px solid ${pressed ? T.accent : T.pillBd}`,
        cursor:'pointer', transition:'background .12s ease, border-color .12s ease',
      }}>
        {glyph && <DocGlyph c={T.accent} s={13}/>}
        <span style={{ fontFamily:T.mono, fontSize:13, lineHeight:'19px', color:T.text }}>{children}</span>
      </span>
    );
  }
  if (variant === 'code') {
    return (
      <span style={{
        fontFamily:T.mono, fontSize:12, color: pressed ? T.accent : T.text, cursor:'pointer',
        background: pressed ? T.pressBg : 'transparent', borderRadius:3, padding:'0 1px', margin:'0 -1px',
        textDecorationLine:'underline', textDecorationColor:T.ulTint,
        textDecorationStyle:'solid', textDecorationThickness:'1px', textUnderlineOffset:'3px',
      }}>{children}</span>
    );
  }
  // inline
  return (
    <span style={{
      display:'inline', whiteSpace:'nowrap', cursor:'pointer',
      background: pressed ? T.pressBg : 'transparent', borderRadius:4, padding:'1px 3px', margin:'0 -2px',
      transition:'background .12s ease',
    }}>
      {glyph && <span style={{ marginRight:4, position:'relative', top:1 }}><DocGlyph c={T.accent} s={12}/></span>}
      <span style={{
        fontFamily:T.mono, fontSize:13, color: pressed ? T.accent : T.text,
        textDecorationLine:'underline', textDecorationColor: pressed ? T.accent : T.ulTint,
        textDecorationStyle:'solid', textDecorationThickness:'1px', textUnderlineOffset:'3px',
      }}>{children}</span>
    </span>
  );
}

// ── COMPONENT 2 — Write / Edit tool card with open affordance ─
function ToolCard({ tokens, tool = 'Write', path, expanded = false, pathPressed = false }) {
  const T = tokens;
  const Icon = tool === 'Edit' ? EditGlyph : WriteGlyph;
  return (
    <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, overflow:'hidden' }}>
      {/* header row — tool name + chevron area EXPAND · path chip OPENS */}
      <div style={{ display:'flex', alignItems:'center', gap:9, padding:'0 6px 0 12px', minHeight:46, cursor:'pointer' }}>
        <Icon c={T.sec} s={15}/>
        <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text, flexShrink:0 }}>{tool}</span>
        {/* OPEN target — bordered tinted path chip */}
        <span style={{
          flex:1, minWidth:0, display:'inline-flex', alignItems:'center', gap:7,
          height:30, padding:'0 9px', borderRadius:8,
          background: pathPressed ? T.pressBg : T.pillBg,
          border:`1px solid ${pathPressed ? T.accent : T.pillBd}`,
          cursor:'pointer', transition:'background .12s ease, border-color .12s ease',
        }}>
          <DocGlyph c={T.accent} s={12}/>
          <span style={{ flex:1, minWidth:0, fontFamily:T.mono, fontSize:12, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{path}</span>
          <OpenArrow c={T.accent} s={12}/>
        </span>
        {/* spatial split → EXPAND affordance */}
        <span style={{ width:1, height:22, background:T.border, flexShrink:0, margin:'0 2px' }}/>
        <span style={{ width:34, height:34, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <span style={{ transform: expanded ? 'rotate(180deg)' : 'none', display:'flex', transition:'transform .18s ease' }}>
            <Chevron d="down" c={T.muted} s={14} w={1.9}/>
          </span>
        </span>
      </div>
      {/* expanded preview */}
      {expanded && (
        <div style={{ borderTop:`1px solid ${T.border}`, background:T.base, padding:'11px 13px' }}>
          {tool === 'Edit' ? (
            <pre style={{ margin:0, fontFamily:T.mono, fontSize:11.5, lineHeight:'18px', whiteSpace:'pre', overflowX:'auto' }}>
              <div style={{ color:T.muted }}>@@ TokenStore.kt · L42</div>
              <div style={{ color:T.danger }}>- private var token: String? = null</div>
              <div style={{ color:T.success }}>+ private val store = TokenStore(clock)</div>
              <div style={{ color:T.success }}>+ private val token get() = store.current()</div>
            </pre>
          ) : (
            <pre style={{ margin:0, fontFamily:T.mono, fontSize:11.5, lineHeight:'18px', whiteSpace:'pre', overflowX:'auto', color:T.sec }}>
              <div><span style={{ color:T.muted }}>1</span>{'  # Auth Module Refactor'}</div>
              <div><span style={{ color:T.muted }}>2</span>{'  '}</div>
              <div><span style={{ color:T.muted }}>3</span>{'  Extracted token refresh into a'}</div>
              <div><span style={{ color:T.muted }}>4</span>{'  dedicated `TokenStore`. 12 tests…'}</div>
            </pre>
          )}
          <div style={{ marginTop:9, fontFamily:T.mono, fontSize:10.5, color:T.muted }}>
            {tool === 'Edit' ? '1 addition · 1 removal' : '38 lines written'}
          </div>
        </div>
      )}
    </div>
  );
}

// ── phone frame ───────────────────────────────────────────────
function Phone({ children, w = 384, h = 812 }) {
  return (
    <div style={{
      width:w, height:h, borderRadius:46, background:'#000', position:'relative', overflow:'hidden',
      boxShadow:'0 40px 90px -30px rgba(0,0,0,0.75), 0 0 0 1px #26292E', fontFamily:DARK.ui,
    }}>
      <div style={{ position:'absolute', top:11, left:'50%', transform:'translateX(-50%)', width:118, height:34, borderRadius:22, background:'#000', zIndex:80 }}/>
      <div style={{ position:'absolute', top:0, left:0, right:0, height:54, display:'flex', alignItems:'center', justifyContent:'space-between', padding:'0 30px', zIndex:70 }}>
        <span style={{ fontFamily:'-apple-system, system-ui', fontSize:15, fontWeight:600, color:'#fff' }}>9:41</span>
        <div style={{ display:'flex', gap:6, alignItems:'center' }}>
          <svg width="18" height="11" viewBox="0 0 18 11"><rect x="0" y="7" width="3" height="4" rx=".6" fill="#fff"/><rect x="4.5" y="4.6" width="3" height="6.4" rx=".6" fill="#fff"/><rect x="9" y="2.3" width="3" height="8.7" rx=".6" fill="#fff"/><rect x="13.5" y="0" width="3" height="11" rx=".6" fill="#fff"/></svg>
          <svg width="25" height="12" viewBox="0 0 25 12"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke="#fff" strokeOpacity=".4" fill="none"/><rect x="2" y="2" width="16" height="8" rx="1.6" fill="#fff"/><path d="M23 4v4c.7-.3 1.2-1 1.2-2S23.7 4.3 23 4z" fill="#fff" fillOpacity=".5"/></svg>
        </div>
      </div>
      {children}
    </div>
  );
}

// ── COMPONENT 3 — full-screen FileViewer ──────────────────────
// mode: 'read' | 'error' | 'loading'
function FileViewer({ mode = 'read', fileName = 'report.md' }) {
  const T = DARK;
  const Bar = () => (
    <div style={{ flexShrink:0, paddingTop:54, background:T.base, borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:2, padding:'0 6px 0 2px', height:46 }}>
        <span style={{ width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <Chevron d="left" c={T.sec} s={18} w={2}/>
        </span>
        <span style={{ flex:1, minWidth:0, fontFamily:T.mono, fontSize:13, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{fileName}</span>
        <span style={{ width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <Overflow c={T.muted} s={18}/>
        </span>
      </div>
    </div>
  );

  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <Bar/>
      {mode === 'read' && <ReadBody/>}
      {mode === 'loading' && <LoadingBody/>}
      {mode === 'error' && <ErrorBody/>}
    </div>
  );
}

function ReadBody() {
  const T = DARK;
  const P = { margin:'0 0 14px', fontFamily:T.ui, fontSize:15, lineHeight:'24px', color:T.text };
  return (
    <div className="do-scroll" style={{ flex:1, overflowY:'auto', padding:'22px 22px 40px' }}>
      <div style={{ fontFamily:T.mono, fontSize:10.5, letterSpacing:0.4, color:T.muted, marginBottom:12 }}>~/Desktop · markdown</div>
      <h1 style={{ margin:'0 0 6px', fontFamily:T.ui, fontSize:23, fontWeight:700, letterSpacing:-0.3, lineHeight:'30px', color:T.text }}>Auth Module Refactor</h1>
      <div style={{ fontFamily:T.ui, fontSize:13, color:T.sec, marginBottom:22 }}>A short summary of what changed and how it was verified.</div>

      <h2 style={{ margin:'0 0 10px', fontFamily:T.ui, fontSize:16.5, fontWeight:700, color:T.text }}>What changed</h2>
      <p style={P}>The token-refresh logic was scattered across three call sites. I pulled it into a single <span style={{ fontFamily:T.mono, fontSize:13, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px' }}>TokenStore</span> so expiry is handled in one place.</p>
      <ul style={{ margin:'0 0 20px', paddingLeft:20, fontFamily:T.ui, fontSize:15, lineHeight:'25px', color:T.text }}>
        <li>Extracted refresh into <span style={{ fontFamily:T.mono, fontSize:12.5, color:T.sec }}>TokenStore</span></li>
        <li>Removed the ad-hoc retry loop from <span style={{ fontFamily:T.mono, fontSize:12.5, color:T.sec }}>AuthClient</span></li>
        <li>Added 12 unit tests covering the expiry edges</li>
      </ul>

      <h2 style={{ margin:'0 0 10px', fontFamily:T.ui, fontSize:16.5, fontWeight:700, color:T.text }}>Verification</h2>
      <p style={P}>The full suite passes, including the new expiry cases. Output from the last run:</p>
      <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', marginBottom:6 }}>
        <div style={{ display:'flex', alignItems:'center', padding:'6px 10px', borderBottom:`1px solid ${T.border}`, background:T.surface }}>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>bash</span>
        </div>
        <pre style={{ margin:0, padding:'10px 12px', fontFamily:T.mono, fontSize:11.5, lineHeight:'19px', color:T.text, overflowX:'auto' }}>
{`$ gradle :auth:test
> Task :auth:test
`}<span style={{ color:T.synStr }}>BUILD SUCCESSFUL</span>{` in 8s
`}<span style={{ color:DARK.muted }}>62 tests, 0 failed</span>
        </pre>
      </div>
    </div>
  );
}

function LoadingBody() {
  const T = DARK;
  return (
    <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:14, padding:'0 40px 60px' }}>
      <Spinner c={T.accent} track={T.border} s={22}/>
      <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.muted }}>Opening report.md…</span>
    </div>
  );
}

function ErrorBody() {
  const T = DARK;
  return (
    <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:0, padding:'0 34px 70px', textAlign:'center' }}>
      <div style={{ width:52, height:52, borderRadius:14, background:T.surface, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', marginBottom:18 }}>
        <LockGlyph c={T.sec} s={24}/>
      </div>
      <div style={{ fontFamily:T.ui, fontSize:16.5, fontWeight:600, color:T.text, marginBottom:8 }}>Can’t read this file directly</div>
      <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.sec, maxWidth:270, marginBottom:22 }}>
        It’s outside the synced workspace — written by Bash beyond the working directory, so the phone can’t reach it yet.
      </div>
      <button style={{
        all:'unset', boxSizing:'border-box', cursor:'pointer', height:44, padding:'0 20px', borderRadius:10,
        background:T.accent, display:'flex', alignItems:'center', justifyContent:'center', gap:8,
      }}>
        <OpenArrow c="#0E0F11" s={14}/>
        <span style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:'#0E0F11' }}>Export &amp; open</span>
      </button>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginTop:14, maxWidth:250, lineHeight:'16px', wordBreak:'break-all' }}>/tmp/report.md</div>
      <button style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', marginTop:16 }}>
        <span style={{ fontFamily:T.ui, fontSize:13, color:T.muted, textDecoration:'underline', textUnderlineOffset:'3px' }}>Copy path instead</span>
      </button>
    </div>
  );
}

Object.assign(window, {
  DO_DARK: DARK, DO_LIGHT: LIGHT,
  DocGlyph, OpenArrow, WriteGlyph, EditGlyph, Chevron, Overflow, ClaudeGlyph, Spinner,
  PathLink, ToolCard, Phone, FileViewer,
});
