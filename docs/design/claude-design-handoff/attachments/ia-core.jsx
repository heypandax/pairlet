// cc-pocket — image attachment · core (tokens, icons, photo placeholders)

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── photographic placeholders (stand-ins for picked photos) ───
// muted, distinct palettes so a grid reads as different real photos
const PALETTES = [
  { bg:'linear-gradient(165deg,#3a2a22 0%,#6e4a38 48%,#cda07a 100%)', sun:{x:'74%',y:'24%',c:'rgba(255,214,170,0.55)'} }, // warm dusk
  { bg:'linear-gradient(180deg,#16222e 0%,#243f57 60%,#3d6488 100%)', lines:true },                                        // blue screenshot
  { bg:'linear-gradient(170deg,#16241c 0%,#274234 58%,#6f9277 100%)', sun:{x:'28%',y:'30%',c:'rgba(210,235,200,0.4)'} },   // forest
  { bg:'linear-gradient(160deg,#241f30 0%,#41354f 52%,#8f76b4 100%)', sun:{x:'70%',y:'70%',c:'rgba(210,180,240,0.35)'} },  // purple
  { bg:'linear-gradient(180deg,#202327 0%,#2f343b 100%)', lines:true },                                                    // slate doc
  { bg:'linear-gradient(155deg,#2e2420 0%,#5a3f33 50%,#b07b54 100%)', sun:{x:'30%',y:'26%',c:'rgba(255,200,150,0.5)'} },   // amber
];

function Photo({ p = 0, radius = 10, style = {} }) {
  const P = PALETTES[((p % PALETTES.length) + PALETTES.length) % PALETTES.length];
  return (
    <div style={{
      position:'relative', background:P.bg, borderRadius:radius, overflow:'hidden',
      boxShadow:'inset 0 0 0 1px rgba(255,255,255,0.05), inset 0 -26px 44px rgba(0,0,0,0.34)',
      ...style,
    }}>
      {P.sun && <div style={{ position:'absolute', left:P.sun.x, top:P.sun.y, width:'46%', height:'46%', transform:'translate(-50%,-50%)', background:`radial-gradient(circle, ${P.sun.c} 0%, transparent 68%)` }}/>}
      {P.lines && (
        <div style={{ position:'absolute', inset:0, opacity:0.5,
          backgroundImage:'repeating-linear-gradient(180deg, rgba(255,255,255,0.05) 0 2px, transparent 2px 11px)' }}/>
      )}
      <div style={{ position:'absolute', inset:0, backgroundImage:'radial-gradient(120% 120% at 50% 0%, transparent 60%, rgba(0,0,0,0.25) 100%)' }}/>
    </div>
  );
}

// ── icons ─────────────────────────────────────────────────────
function AttachIcon({ c = T.sec, s = 24 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M13 4H6.5A2.5 2.5 0 0 0 4 6.5V17.5A2.5 2.5 0 0 0 6.5 20H16.5A2.5 2.5 0 0 0 19 17.5V11"/>
      <path d="M19 4.2V9M16.6 6.6H21.4"/>
      <circle cx="8.7" cy="9.4" r="1.4"/>
      <path d="M4.4 17.6L9 12.6L12.2 15.8L14.2 13.6L18.4 17.8"/>
    </svg>
  );
}
function SendArrow({ c = '#0E0F11', s = 18 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
      <path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}
function StopGlyph({ c = '#0E0F11', s = 15 }) {
  return <span style={{ width:s, height:s, borderRadius:3.5, background:c, display:'block' }}/>;
}
function CloseX({ c = T.text, s = 20, w = 2 }) {
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M5 5l10 10M15 5L5 15" stroke={c} strokeWidth={w} strokeLinecap="round"/></svg>;
}
function RemoveBadge({ onClick }) {
  return (
    <button onClick={(e)=>{e.stopPropagation(); onClick&&onClick();}} aria-label="Remove"
      style={{ all:'unset', cursor:'pointer', position:'absolute', top:-6, right:-6, width:20, height:20, borderRadius:999,
        background:T.raised, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', zIndex:3 }}>
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2.4 2.4l5.2 5.2M7.6 2.4L2.4 7.6" stroke={T.sec} strokeWidth="1.5" strokeLinecap="round"/></svg>
    </button>
  );
}
function WarnBadge({ s = 16 }) {
  return (
    <span style={{ position:'absolute', top:6, left:6, width:s, height:s, borderRadius:999, background:T.danger, display:'flex', alignItems:'center', justifyContent:'center', zIndex:3, boxShadow:'0 1px 4px rgba(0,0,0,0.4)' }}>
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M5 2v3.2" stroke="#0E0F11" strokeWidth="1.5" strokeLinecap="round"/><circle cx="5" cy="7.3" r="0.85" fill="#0E0F11"/></svg>
    </span>
  );
}
function Retry({ c = T.danger, s = 14 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
      <path d="M13 8a5 5 0 1 1-1.6-3.7" stroke={c} strokeWidth="1.5" strokeLinecap="round"/>
      <path d="M13 2.4V5.2H10.2" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}
function Ring({ s = 26 }) {
  const r = (s-4)/2;
  const C = 2*Math.PI*r;
  return (
    <svg className="ia-spin" width={s} height={s} viewBox={`0 0 ${s} ${s}`}>
      <circle cx={s/2} cy={s/2} r={r} stroke="rgba(255,255,255,0.18)" strokeWidth="2.4" fill="none"/>
      <circle cx={s/2} cy={s/2} r={r} stroke={T.accent} strokeWidth="2.4" fill="none" strokeLinecap="round" strokeDasharray={`${C*0.3} ${C}`}/>
    </svg>
  );
}
function Chevron({ d='left', c=T.sec, s=18, w=2 }) {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6', down:'M3 6l6 6 6-6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
}

Object.assign(window, { T, PALETTES, Photo, AttachIcon, SendArrow, StopGlyph, CloseX, RemoveBadge, WarnBadge, Retry, Ring, Chevron });
