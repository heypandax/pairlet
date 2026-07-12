// cc-pocket — desktop · core (tokens, window chrome, agent identity, icons, keycaps)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', hover:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', accentPressed:'#C4633F',
  success:'#4FB477', warning:'#E0A93B', danger:'#E5604D', info:'#5B9BD5',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const AGENTS = {
  claude:{ name:'Claude', color:'#D97757', tint:'rgba(217,119,87,0.12)', tintBorder:'rgba(217,119,87,0.4)' },
  codex: { name:'Codex',  color:'#3FB5AC', tint:'rgba(63,181,172,0.12)', tintBorder:'rgba(63,181,172,0.42)' },
};

function AgentGlyph({ agent, c, s=16 }) {
  const col = c || AGENTS[agent].color;
  if (agent==='claude') return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M5 5l4.2 4.2L5 13.4" stroke={col} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/><path d="M11 14h4" stroke={col} strokeWidth="1.8" strokeLinecap="round"/></svg>;
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.3" stroke={col} strokeWidth="1.6"/><path d="M10 3.2c3.8 0 6.8 3 6.8 6.8M10 16.8c-3.8 0-6.8-3-6.8-6.8" stroke={col} strokeWidth="1.6" strokeLinecap="round"/></svg>;
}
function AgentTag({ agent, s=11 }) {
  const a=AGENTS[agent];
  return <span style={{ display:'inline-flex', alignItems:'center', gap:4, background:a.tint, border:`1px solid ${a.tintBorder}`, borderRadius:999, padding:'1px 7px', flexShrink:0 }}>
    <AgentGlyph agent={agent} s={11}/><span style={{ fontFamily:T.ui, fontSize:s, fontWeight:600, color:a.color }}>{a.name}</span>
  </span>;
}

// ── icons ─────────────────────────────────────────────────────
const I = {
  chevD:(c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M4 6.5L9 11.5l5-5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  chevR:(c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M6.5 4L11.5 9l-5 5" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  folder:(c=T.sec,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M2 4.8A1.3 1.3 0 013.3 3.5h2.9l1.4 1.6h7A1.3 1.3 0 0116 6.4v6.3A1.3 1.3 0 0114.7 14H3.3A1.3 1.3 0 012 12.7z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/></svg>,
  search:(c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><circle cx="8" cy="8" r="5.2" stroke={c} strokeWidth="1.5"/><path d="M12 12l3.2 3.2" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>,
  branch:(c=T.muted,s=12)=> <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><circle cx="3.5" cy="3" r="1.8" stroke={c} strokeWidth="1.4"/><circle cx="3.5" cy="11" r="1.8" stroke={c} strokeWidth="1.4"/><circle cx="10.5" cy="3" r="1.8" stroke={c} strokeWidth="1.4"/><path d="M3.5 4.8v4.4M10.5 4.8c0 2.5-2 3-4 3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/></svg>,
  bubble:(c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M2.2 4.4A2 2 0 014.2 2.4h7.6a2 2 0 012 2v4a2 2 0 01-2 2H6.4l-3 2.4a.4.4 0 01-.65-.32V10.4H4.2A2 2 0 012.2 8.4z" stroke={c} strokeWidth="1.3" strokeLinejoin="round"/></svg>,
  gear:(c=T.sec,s=16)=> <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12.2 2h-.4a2 2 0 0 0-2 2v.2a2 2 0 0 1-1 1.7l-.5.3a2 2 0 0 1-2 0l-.1-.1a2 2 0 0 0-2.7.8l-.3.3a2 2 0 0 0 .8 2.8l.1.1a2 2 0 0 1 1 1.7v.5a2 2 0 0 1-1 1.8l-.1.1a2 2 0 0 0-.8 2.7l.3.4a2 2 0 0 0 2.7.7l.1-.1a2 2 0 0 1 2 0l.5.3a2 2 0 0 1 1 1.7v.2a2 2 0 0 0 2 2h.4a2 2 0 0 0 2-2v-.2a2 2 0 0 1 1-1.7l.5-.3a2 2 0 0 1 2 0l.1.1a2 2 0 0 0 2.7-.7l.3-.4a2 2 0 0 0-.8-2.7l-.1-.1a2 2 0 0 1-1-1.8v-.5a2 2 0 0 1 1-1.7l.1-.1a2 2 0 0 0 .8-2.8l-.3-.3a2 2 0 0 0-2.7-.8l-.1.1a2 2 0 0 1-2 0l-.5-.3a2 2 0 0 1-1-1.7V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>,
  shield:(c=T.sec,s=16)=> <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2.5l8 3v6c0 5.2-3.8 8.6-8 10-4.2-1.4-8-4.8-8-10v-6l8-3z"/></svg>,
  warn:(c=T.warning,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 2.6l6.6 12H2.4L9 2.6z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/><path d="M9 7.2v3.1" stroke={c} strokeWidth="1.5" strokeLinecap="round"/><circle cx="9" cy="12.5" r=".9" fill={c}/></svg>,
  plus:(c=T.accent,s=15)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>,
  bolt:(c=T.accent,s=13)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M10 2L4 10h4l-1 6 6-8H9l1-6z" fill={c} stroke={c} strokeWidth="1" strokeLinejoin="round"/></svg>,
  dots:(c=T.sec,s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><circle cx="4" cy="9" r="1.5"/><circle cx="9" cy="9" r="1.5"/><circle cx="14" cy="9" r="1.5"/></svg>,
  check:(c=T.success,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 9.5l3.5 3.5 7.5-8.5" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  x:(c=T.muted,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M5 5l8 8M13 5l-8 8" stroke={c} strokeWidth="1.6" strokeLinecap="round"/></svg>,
  send:(c='#0E0F11',s=16)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  paperclip:(c=T.sec,s=17)=> <svg width={s} height={s} viewBox="0 0 20 20" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M15 8.5l-5.5 5.5a3 3 0 01-4.2-4.2l6-6a2 2 0 012.8 2.8l-5.6 5.6a1 1 0 01-1.4-1.4L12 5.8"/></svg>,
  apple:(c=T.sec,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M12.3 9.5c0-1.8 1.5-2.7 1.6-2.7-.9-1.3-2.2-1.4-2.7-1.5-1.1-.1-2.2.7-2.8.7-.6 0-1.5-.7-2.4-.6-1.2 0-2.4.7-3 1.8-1.3 2.2-.3 5.5.9 7.3.6.9 1.3 1.9 2.2 1.8.9 0 1.2-.6 2.3-.6 1.1 0 1.3.6 2.3.6 1 0 1.6-.9 2.2-1.8.7-1 .9-2 .9-2.1 0 0-1.7-.7-1.7-2.6zM10.8 4.3c.5-.6.8-1.4.7-2.3-.7 0-1.5.5-2 1.1-.4.5-.8 1.3-.7 2.1.8.1 1.5-.4 2-.9z"/></svg>,
  linux:(c=T.sec,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3.5c-1 0-1.4 1-1.4 2.4 0 1-.6 1.8-1.3 2.9-.6 1-1.3 2-1.3 3 0 .8.6 1.2 1.6 1.5.8.2 1.2.8 2 1 .8.3 2 .3 2.8 0 .8-.2 1.2-.8 2-1 1-.3 1.6-.7 1.6-1.5 0-1-.7-2-1.3-3-.7-1.1-1.3-1.9-1.3-2.9 0-1.4-.4-2.4-1.4-2.4z"/><path d="M7.3 6.2v.01M9.7 6.2v.01"/></svg>,
  win:(c=T.sec,s=14)=> <svg width={s} height={s} viewBox="0 0 18 18" fill={c}><path d="M2.5 4.2l5.4-.75v5.05H2.5zM8.6 3.35L15.5 2.4v6.15H8.6zM2.5 9.2h5.4v5.05L2.5 13.5zM8.6 9.2h6.9v6.4l-6.9-.95z"/></svg>,
  pin:(c=T.accent,s=13)=> <svg width={s} height={s} viewBox="0 0 16 16" fill={c}><path d="M6 1.5h4l-.5 4 2 2.5H4.5l2-2.5z"/><path d="M8 8v6" stroke={c} strokeWidth="1.3" strokeLinecap="round"/></svg>,
};

// ── keycap chip ───────────────────────────────────────────────
function Key({ children }) {
  return <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 6px', lineHeight:'16px', background:T.base, whiteSpace:'nowrap' }}>{children}</span>;
}
function Dot({ c, pulse, s=7 }) {
  return <span className={pulse?'dk-pulse':''} style={{ width:s, height:s, borderRadius:999, background:c, flexShrink:0, boxShadow:pulse?`0 0 6px ${c}99`:'none' }}/>;
}

// ── desktop window chrome ─────────────────────────────────────
// platform: 'mac' | 'win'
function Window({ w=1180, h=760, platform='mac', children, titleRight, label='cc-pocket' }) {
  return (
    <div style={{ width:w, height:h, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, overflow:'hidden',
      display:'flex', flexDirection:'column', boxShadow:'0 40px 100px -30px rgba(0,0,0,0.7)' }}>
      {/* title bar */}
      <div style={{ height:38, flexShrink:0, background:T.base, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:10, padding:'0 12px' }}>
        {platform==='mac' && (
          <div style={{ display:'flex', gap:8, width:54 }}>
            <span style={{ width:12, height:12, borderRadius:999, background:'#ED6A5E' }}/>
            <span style={{ width:12, height:12, borderRadius:999, background:'#F4BE4F' }}/>
            <span style={{ width:12, height:12, borderRadius:999, background:'#61C554' }}/>
          </div>
        )}
        <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.muted, fontWeight:500 }}>{label}</span>
        <span style={{ flex:1 }}/>
        {titleRight}
        {platform==='win' && (
          <div style={{ display:'flex', gap:0, marginLeft:8 }}>
            {['M2 6h8','M2.5 2.5h7v7h-7z','M2 2l6 6M8 2l-6 6'].map((d,i)=>(
              <span key={i} style={{ width:30, height:38, marginRight:-12, display:'flex', alignItems:'center', justifyContent:'center' }}>
                <svg width="11" height="11" viewBox="0 0 11 11" fill="none"><path d={d} stroke={T.sec} strokeWidth="1" strokeLinecap="round"/></svg>
              </span>
            ))}
          </div>
        )}
      </div>
      <div style={{ flex:1, minHeight:0, display:'flex' }}>{children}</div>
    </div>
  );
}

Object.assign(window, { T, AGENTS, AgentGlyph, AgentTag, I, Key, Dot, Window });
