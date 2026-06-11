// cc-pocket — execution permissions · core

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

function rgba(hex, a) {
  const h = hex.replace('#','');
  const n = parseInt(h.length===3 ? h.split('').map(c=>c+c).join('') : h, 16);
  return `rgba(${(n>>16)&255},${(n>>8)&255},${n&255},${a})`;
}

// ── the autonomy ladder (top = most cautious) ─────────────────
const MODES = [
  { key:'default',     label:"I’m watching · ask each step",     short:'Ask each step', tech:'default',
    color:'#9BA1A6', allows:'nothing automatically', asks:'every sensitive tool' },
  { key:'acceptEdits', label:'Auto-edit files, ask before commands', short:'Auto-edit', tech:'acceptEdits',
    color:'#4FB477', allows:'file edits', asks:'commands' },
  { key:'plan',        label:"Plan first, I’ll approve",          short:'Plan', tech:'plan',
    color:'#5B9BD5', allows:'read-only inspection', asks:'everything until you approve' },
  { key:'bypass',      label:'Full auto · trust it',              short:'Full auto', tech:'bypassPermissions',
    color:'#E0A93B', warn:true, allows:'everything', asks:'nothing' },
];
const MODE = Object.fromEntries(MODES.map(m => [m.key, m]));

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ d='left', c=T.sec, s=18, w=2 }) => {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6', down:'M3 6l6 6 6-6', up:'M3 12l6-6 6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Shield = ({ c=T.sec, s=16 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M9 1.6l5.6 2.1v4.1c0 3.7-2.7 6.1-5.6 7.1-2.9-1-5.6-3.4-5.6-7.1V3.7L9 1.6z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/>
    <path d="M6.4 9l1.8 1.8L11.8 7" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const Branch = ({ c=T.muted, s=12 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
    <circle cx="3.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/><circle cx="3.5" cy="11" r="1.9" stroke={c} strokeWidth="1.4"/>
    <circle cx="10.5" cy="3" r="1.9" stroke={c} strokeWidth="1.4"/>
    <path d="M3.5 4.9v4.2M10.5 4.9c0 2.5-2 3.1-4 3.1" stroke={c} strokeWidth="1.4" strokeLinecap="round"/>
  </svg>
);
const Check = ({ c=T.text, s=14, w=1.9 }) => (
  <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M2.4 7.3l3 3 6.2-6.6" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const Warn = ({ c=T.warning, s=15 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M9 2.4l6.7 12.2H2.3L9 2.4z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/>
    <path d="M9 7v3.3" stroke={c} strokeWidth="1.4" strokeLinecap="round"/><circle cx="9" cy="12.5" r="0.95" fill={c}/>
  </svg>
);
const Ex = ({ c=T.sec, s=12, w=1.5 }) => (
  <svg width={s} height={s} viewBox="0 0 12 12" fill="none"><path d="M3 3l6 6M9 3l-6 6" stroke={c} strokeWidth={w} strokeLinecap="round"/></svg>
);
const Dots = ({ c=T.sec, s=18 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18"><g fill={c}><circle cx="3.5" cy="9" r="1.6"/><circle cx="9" cy="9" r="1.6"/><circle cx="14.5" cy="9" r="1.6"/></g></svg>
);
const Spinner = ({ c=T.sec, s=16 }) => {
  const r=(s-3)/2, C=2*Math.PI*r;
  return <svg className="ep-spin" width={s} height={s} viewBox={`0 0 ${s} ${s}`}>
    <circle cx={s/2} cy={s/2} r={r} stroke={rgba(c,0.25)} strokeWidth="2" fill="none"/>
    <circle cx={s/2} cy={s/2} r={r} stroke={c} strokeWidth="2" fill="none" strokeLinecap="round" strokeDasharray={`${C*0.3} ${C}`}/>
  </svg>;
};

// ── persistent mode badge (chat header) ───────────────────────
function ModeBadge({ modeKey='default', rules=0, onClick, big=false }) {
  const m = MODE[modeKey];
  return (
    <button onClick={onClick} className="ep-press" aria-label="Execution mode"
      style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', display:'inline-flex', alignItems:'center', gap:6,
        height:28, padding:'0 10px', borderRadius:999, flexShrink:0,
        background:rgba(m.color,0.12), border:`1px solid ${rgba(m.color,0.32)}` }}>
      {m.warn
        ? <span style={{ display:'flex', marginLeft:-1 }}><Warn c={m.color} s={12}/></span>
        : <span className={modeKey==='default'?'':'ep-pulse'} style={{ width:6, height:6, borderRadius:999, background:m.color }}/>}
      <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:m.color, letterSpacing:0.1, whiteSpace:'nowrap' }}>{m.short}</span>
      {rules>0 && <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted, whiteSpace:'nowrap' }}>· {rules} rule{rules>1?'s':''}</span>}
      <span style={{ display:'flex', opacity:0.8 }}><Chevron d="down" c={m.color} s={12} w={1.8}/></span>
    </button>
  );
}

// ── chat top bar with the badge ───────────────────────────────
function ChatTopBar({ title='Add relay websocket client', modeKey='default', rules=0, onBadge, host=true }) {
  return (
    <div style={{ background:T.base, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 8px 0 4px', height:46 }}>
        <button style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:30, flexShrink:1 }}>{title}</span>
        <ModeBadge modeKey={modeKey} rules={rules} onClick={onBadge}/>
        <span style={{ flex:1 }}/>
        <button style={iconBtn} aria-label="More"><Dots c={T.sec} s={18}/></button>
      </div>
      {host && (
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
          <span className="ep-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
        </div>
      )}
    </div>
  );
}
const iconBtn = { all:'unset', boxSizing:'border-box', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 };

// ── bottom-sheet shell (scrim + raised card, radius-20 top) ───
function Scrim({ onClick }) {
  return <div onClick={onClick} style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.58)', zIndex:20 }}/>;
}
function Sheet({ children, z=30 }) {
  return (
    <div style={{ position:'absolute', left:0, right:0, bottom:0, zIndex:z,
      background:T.raised, borderTopLeftRadius:20, borderTopRightRadius:20,
      borderTop:`1px solid ${T.border}`, borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`,
      paddingBottom:34 }}>
      <div style={{ display:'flex', justifyContent:'center', padding:'8px 0 4px' }}>
        <div style={{ width:38, height:5, borderRadius:999, background:T.border }}/>
      </div>
      {children}
    </div>
  );
}

Object.assign(window, { T, rgba, MODES, MODE, Chevron, Shield, Branch, Check, Warn, Ex, Dots, Spinner, ModeBadge, ChatTopBar, iconBtn, Scrim, Sheet });
