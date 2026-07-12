// cc-pocket — multi-agent · core (tokens, agent identity, icons, shells)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', accentPressed:'#C4633F',
  success:'#4FB477', warning:'#E0A93B', danger:'#E5604D', info:'#5B9BD5',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── agent identity ────────────────────────────────────────────
// Claude = terracotta (the app accent). Codex = calm teal that sits on dark.
const AGENTS = {
  claude: { id:'claude', name:'Claude', tagline:'Claude Code · Anthropic', color:'#D97757', tint:'rgba(217,119,87,0.12)', tintBorder:'rgba(217,119,87,0.40)' },
  codex:  { id:'codex',  name:'Codex',  tagline:'Codex · OpenAI',         color:'#3FB5AC', tint:'rgba(63,181,172,0.12)',  tintBorder:'rgba(63,181,172,0.42)' },
};

// agent glyphs (simple line marks, 1.5pt)
function AgentGlyph({ agent, c, s=18 }) {
  const col = c || AGENTS[agent].color;
  if (agent === 'claude') {
    // shell-prompt chevron — echoes the app mark
    return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M5 5l4.2 4.2L5 13.4" stroke={col} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/><path d="M11 14h4" stroke={col} strokeWidth="1.8" strokeLinecap="round"/></svg>;
  }
  // codex — concentric/orbit mark
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.3" stroke={col} strokeWidth="1.6"/><path d="M10 3.2c3.8 0 6.8 3 6.8 6.8M10 16.8c-3.8 0-6.8-3-6.8-6.8" stroke={col} strokeWidth="1.6" strokeLinecap="round"/></svg>;
}

// agent tag/chip — the consistent identity surface used app-wide
function AgentTag({ agent, size='sm', showName=true }) {
  const a = AGENTS[agent];
  const pad = size==='sm' ? '2px 7px' : '3px 9px';
  const fs = size==='sm' ? 10.5 : 12;
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:5, background:a.tint, border:`1px solid ${a.tintBorder}`, borderRadius:999, padding:pad, flexShrink:0 }}>
      <AgentGlyph agent={agent} s={size==='sm'?12:14}/>
      {showName && <span style={{ fontFamily:T.ui, fontSize:fs, fontWeight:600, color:a.color, letterSpacing:0.1 }}>{a.name}</span>}
    </span>
  );
}

// ── icons (1.5pt line) ────────────────────────────────────────
const I = {
  check:(c,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevD:(c=T.muted,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 6.5L9 12l5.5-5.5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevR:(c=T.muted,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M6.5 3.5L12 9l-5.5 5.5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  shield:(c=T.sec,s=18)=> <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2.5l8 3v6c0 5.2-3.8 8.6-8 10-4.2-1.4-8-4.8-8-10v-6l8-3z"/></svg>,
  warn:(c=T.warning,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 2.6l6.6 12H2.4L9 2.6z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/><path d="M9 7.2v3.1" stroke={c} strokeWidth="1.5" strokeLinecap="round"/><circle cx="9" cy="12.5" r=".9" fill={c}/></svg>,
  branch:(c=T.muted,s=12)=> <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/><circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/><circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/><path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/></svg>,
  bubble:(c=T.muted,s=12)=> <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M2.2 4.4A2 2 0 014.2 2.4h7.6a2 2 0 012 2v4a2 2 0 01-2 2H6.4l-3 2.4a.4.4 0 01-.65-.32V10.4H4.2A2 2 0 012.2 8.4z" stroke={c} strokeWidth="1.3" strokeLinejoin="round"/></svg>,
  eye:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M1.6 9S4.3 4.2 9 4.2 16.4 9 16.4 9 13.7 13.8 9 13.8 1.6 9 1.6 9z"/><circle cx="9" cy="9" r="2.1"/></svg>,
  pencil:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M11.5 3.5l3 3M3 13.2l8.5-8.5 3 3L6 16.2 2.5 16.7z"/></svg>,
  write:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M9 2.5H4A1.5 1.5 0 002.5 4v10A1.5 1.5 0 004 15.5h10A1.5 1.5 0 0015.5 14V9"/><path d="M12 2.5l3.5 3.5M7.5 9.5l5-5"/></svg>,
  globe:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4"><circle cx="9" cy="9" r="6.5"/><path d="M2.5 9h13M9 2.5c2 2 2 11 0 13M9 2.5c-2 2-2 11 0 13" strokeLinecap="round"/></svg>,
  lock:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="3.5" y="8" width="11" height="7.5" rx="1.6"/><path d="M5.8 8V5.8a3.2 3.2 0 016.4 0V8"/></svg>,
};

// ── status dot ────────────────────────────────────────────────
function Dot({ c, pulse, s=7 }) {
  return <span className={pulse?'ma-pulse':''} style={{ width:s, height:s, borderRadius:999, background:c, flexShrink:0, boxShadow: pulse?`0 0 7px ${c}99`:'none' }}/>;
}

// ── bottom-sheet shell (over a dim scrim) ─────────────────────
function Sheet({ children, title, subtitle, scrimTap }) {
  return (
    <div style={{ position:'absolute', inset:0, display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
      <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.55)' }}/>
      <div style={{ position:'relative', background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20,
        borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`,
        maxHeight:'92%', display:'flex', flexDirection:'column' }}>
        <div style={{ display:'flex', justifyContent:'center', padding:'9px 0 3px', flexShrink:0 }}>
          <div style={{ width:38, height:5, borderRadius:999, background:T.border }}/>
        </div>
        {title && (
          <div style={{ padding:'8px 18px 0', flexShrink:0 }}>
            <div style={{ fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>{title}</div>
            {subtitle && <div style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, marginTop:4, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', direction:'rtl', textAlign:'left' }}>{subtitle}</div>}
          </div>
        )}
        <div className="ma-scroll" style={{ overflowY:'auto', padding:'14px 18px 30px' }}>{children}</div>
      </div>
    </div>
  );
}

// section label inside a sheet
function SLabel({ children, style }) {
  return <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', margin:'4px 0 10px', ...style }}>{children}</div>;
}

// mono chip
function MChip({ children, c=T.sec }) {
  return <span style={{ fontFamily:T.mono, fontSize:10.5, color:c, background:T.surface, border:`1px solid ${T.border}`, borderRadius:6, padding:'2px 6px' }}>{children}</span>;
}

// ── device + board helpers ────────────────────────────────────
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}
function Cell({ tag, label, children, note, w=402*0.82 }) {
  return (
    <div style={{ width:w }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
        {tag && <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{tag}</span>}
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      {children}
      {note && <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:w }}>{note}</div>}
    </div>
  );
}
function Divider({ children, sub }) {
  return (
    <div style={{ margin:'58px 0 26px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontSize:12, fontWeight:700, letterSpacing:1.2, textTransform:'uppercase', color:T.accent, fontFamily:T.mono }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:10, maxWidth:680 }}>{sub}</div>}
    </div>
  );
}

Object.assign(window, { T, AGENTS, AgentGlyph, AgentTag, I, Dot, Sheet, SLabel, MChip, Phone, Cell, Divider });
