// AUTO-GENERATED — each source wrapped in an IIFE (private scope; globals via window)

/* ===== ia-core.jsx ===== */
;(function(){
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

})();

/* ===== workflow-core.jsx ===== */
;(function(){
// cc-pocket — Workflow orchestration cards (core)
// The orchestration-level sibling of SubagentCard: a CONTAINER of many agents,
// grouped into phases. Same family (status tile · hairline card · pulse-dot
// running · ✓/✗ terminal glyphs) but reads as a fleet, never one more agent.

// ── theme factory ─────────────────────────────────────────────
function wfTokens(dark) {
  const mono = "'JetBrains Mono', ui-monospace, monospace";
  const ui = "'Inter', -apple-system, system-ui, sans-serif";
  if (dark) {
    return {
      dark: true, mono, ui,
      base:'#0E0F11', surface:'#16181B', raised:'#1E2125', hair:'#2A2E33',
      text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
      accent:'#D97757', success:'#4FB477', danger:'#E5604D',
      onAcc:'#0E0F11',
      accTint:'rgba(217,119,87,0.14)', accBd:'rgba(217,119,87,0.40)',
      okTint:'rgba(79,180,119,0.15)',  okBd:'rgba(79,180,119,0.38)',
      danTint:'rgba(229,96,77,0.13)',  danBd:'rgba(229,96,77,0.40)',
    };
  }
  return {
    dark: false, mono, ui,
    base:'#FAF9F7', surface:'#FFFFFF', raised:'#F1EFEB', hair:'#E4E1DB',
    text:'#211E1A', sec:'#6E6860', muted:'#A29A8D',
    accent:'#C15F3C', success:'#3B8F5B', danger:'#C0483A',
    onAcc:'#FFFFFF',
    accTint:'rgba(193,95,60,0.12)', accBd:'rgba(193,95,60,0.34)',
    okTint:'rgba(59,143,91,0.12)',  okBd:'rgba(59,143,91,0.34)',
    danTint:'rgba(192,72,58,0.11)', danBd:'rgba(192,72,58,0.34)',
  };
}

// ── glyphs ────────────────────────────────────────────────────
// stacked lanes — three offset horizontal bars = orchestration (NOT one agent)
const LanesGlyph = ({ c, s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <rect x="1"   y="3.2"  width="11" height="2.6" rx="1.3" fill={c}/>
    <rect x="4.5" y="7.7"  width="12.5" height="2.6" rx="1.3" fill={c}/>
    <rect x="2.5" y="12.2" width="9.5" height="2.6" rx="1.3" fill={c}/>
  </svg>
);
const CheckGlyph = ({ c, s = 16, w = 2.1 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M3.5 9.4l3.6 3.6L14.5 5" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const XGlyph = ({ c, s = 15, w = 2 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M4 4l8 8M12 4l-8 8" stroke={c} strokeWidth={w} strokeLinecap="round"/>
  </svg>
);
const ChevWF = ({ d = 'right', c, s = 15, w = 1.9 }) => {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6', down:'M3 6l6 6 6-6', up:'M3 12l6-6 6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};

// synchronized pulse dot — ALL running rows share this one heartbeat (no spinners)
function PulseDot({ k, s = 7, glow = true }) {
  return (
    <span style={{ position:'relative', width:s, height:s, flexShrink:0, display:'inline-block' }}>
      <span className="wf-pulse" style={{
        position:'absolute', inset:0, borderRadius:999, background:k.accent,
        boxShadow: glow ? `0 0 7px ${k.accent}88` : 'none',
      }}/>
    </span>
  );
}

// hollow muted dot — queued
const HollowDot = ({ k, s = 8 }) => (
  <span style={{ width:s, height:s, borderRadius:999, border:`1.5px solid ${k.muted}`, flexShrink:0, display:'inline-block', opacity:0.7 }}/>
);

// small danger failure chip  ✗ 2
function FailChip({ k, n }) {
  return (
    <span style={{
      display:'inline-flex', alignItems:'center', gap:4, flexShrink:0,
      fontFamily:k.mono, fontSize:11, fontWeight:600, color:k.danger,
      background:k.danTint, border:`1px solid ${k.danBd}`, borderRadius:999, padding:'1px 7px 1px 6px',
    }}>
      <XGlyph c={k.danger} s={9} w={2.2}/>{n}
    </span>
  );
}

// ── segmented phase progress bar ──────────────────────────────
// one segment per phase · 3pt tall · pill ends
function PhaseBar({ k, phases }) {
  return (
    <div style={{ display:'flex', gap:4, marginTop:11 }}>
      {phases.map((p, i) => {
        const base = { flex:1, height:3, borderRadius:999 };
        if (p === 'done')   return <span key={i} style={{ ...base, background:k.accent }}/>;
        if (p === 'active') return <span key={i} className="wf-breathe" style={{ ...base, background:k.accent, opacity:0.5 }}/>;
        // pending
        return <span key={i} style={{ ...base, background:'transparent', border:`1px solid ${k.hair}`, height:1 }}/>;
      })}
    </div>
  );
}

// ── the WorkflowCard ──────────────────────────────────────────
// variants: 'live' | 'live-unknown' | 'ok' | 'ok-fail' | 'aborted'
function WorkflowCard({ k, variant = 'live', name = 'release-pipeline' }) {
  const terminal = variant === 'ok' || variant === 'ok-fail' || variant === 'aborted';
  const aborted = variant === 'aborted';

  // tile appearance
  let tileTint = k.accTint, tileBd = k.accBd, tileInk = k.accent, tileGlyph = <LanesGlyph c={k.accent} s={16}/>;
  if (variant === 'ok' || variant === 'ok-fail') {
    tileTint = k.okTint; tileBd = k.okBd; tileInk = k.success; tileGlyph = <CheckGlyph c={k.success} s={15}/>;
  } else if (aborted) {
    tileTint = k.danTint; tileBd = k.danBd; tileInk = k.danger; tileGlyph = <XGlyph c={k.danger} s={14}/>;
  }

  // phase bar
  let phases;
  if (variant === 'ok' || variant === 'ok-fail') phases = ['done','done','done','done'];
  else if (aborted) phases = ['done','done','pending','pending'];
  else phases = ['done','active','pending','pending'];

  return (
    <div className="wf-press" style={{
      position:'relative', background:k.surface, border:`1px solid ${k.hair}`,
      borderRadius:14, overflow:'hidden', cursor:'pointer',
    }}>
      <div style={{ padding:'12px 13px 13px' }}>
        {/* header row */}
        <div style={{ display:'flex', alignItems:'center', gap:11 }}>
          <div style={{
            width:28, height:28, borderRadius:7, flexShrink:0,
            display:'flex', alignItems:'center', justifyContent:'center',
            background:tileTint, border:`1px solid ${tileBd}`, color:tileInk,
          }}>{tileGlyph}</div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{
              fontFamily:k.ui, fontSize:15, fontWeight:600, color:k.text,
              whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.1,
            }}>{name}</div>
          </div>
          {/* right cluster: live → pulse + elapsed; terminal → final elapsed muted */}
          {!terminal ? (
            <div style={{ display:'flex', alignItems:'center', gap:8, flexShrink:0 }}>
              <PulseDot k={k}/>
              <span style={{ fontFamily:k.mono, fontSize:12, color:k.sec }}>3m 12s</span>
            </div>
          ) : (
            <span style={{ fontFamily:k.mono, fontSize:12, color:k.muted, flexShrink:0 }}>
              {aborted ? '4m 01s' : '6m 32s'}
            </span>
          )}
        </div>

        <PhaseBar k={k} phases={phases}/>

        {/* meta caption row */}
        <div style={{ display:'flex', alignItems:'center', gap:0, marginTop:11 }}>
          <div style={{
            flex:1, minWidth:0, display:'flex', alignItems:'center', gap:7,
            fontFamily:k.mono, fontSize:12, color:k.sec, whiteSpace:'nowrap', overflow:'hidden',
          }}>
            {variant === 'live' && (<>
              <span>phase 2/4 · <span style={{ color:k.text }}>analyze</span></span>
              <span style={{ color:k.muted }}>·</span>
              <span style={{ color:k.muted }}>12/34 agents</span>
              <FailChip k={k} n={2}/>
            </>)}
            {variant === 'live-unknown' && (<>
              <span>phase 2/4 · <span style={{ color:k.text }}>analyze</span></span>
              <span style={{ color:k.muted }}>·</span>
              <span style={{ color:k.muted }}>12 done · 6 running</span>
            </>)}
            {(variant === 'ok' || variant === 'ok-fail') && (<>
              <span style={{ color:k.muted }}>34 agents · 4 phases · 6m 32s</span>
              {variant === 'ok-fail' && <FailChip k={k} n={2}/>}
            </>)}
            {aborted && (
              <span style={{ color:k.muted }}>aborted in phase 3 · 4m 01s</span>
            )}
          </div>
          <span style={{ display:'flex', flexShrink:0, marginLeft:6 }}><ChevWF d="right" c={k.muted} s={15}/></span>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  wfTokens, LanesGlyph, CheckGlyph, XGlyph, ChevWF, PulseDot, HollowDot, FailChip, PhaseBar, WorkflowCard,
});

})();

/* ===== ios-frame.jsx ===== */
;(function(){
// @ds-adherence-ignore -- omelette starter scaffold (raw elements/hex/px by design)

/* BEGIN USAGE */
// iOS.jsx — Simplified iOS 26 (Liquid Glass) device frame
// Based on the iOS 26 UI Kit + Figma status bar spec. No assets, no deps.
// Exports (to window): IOSDevice, IOSStatusBar, IOSNavBar, IOSGlassPill, IOSList, IOSListRow, IOSKeyboard
//
// Usage — wrap your screen content in <IOSDevice> to get the bezel, status bar
// and home indicator (props: title, dark, keyboard):
//
//   <IOSDevice title="Settings">
//     ...your screen content...
//   </IOSDevice>
//   <IOSDevice dark title="Search" keyboard>…</IOSDevice>
/* END USAGE */

// ─────────────────────────────────────────────────────────────
// Status bar
// ─────────────────────────────────────────────────────────────
function IOSStatusBar({ dark = false, time = '9:41' }) {
  const c = dark ? '#fff' : '#000';
  return (
    <div style={{
      display: 'flex', gap: 154, alignItems: 'center', justifyContent: 'center',
      padding: '21px 24px 19px', boxSizing: 'border-box',
      position: 'relative', zIndex: 20, width: '100%',
    }}>
      <div style={{ flex: 1, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', paddingTop: 1.5 }}>
        <span style={{
          fontFamily: '-apple-system, "SF Pro", system-ui', fontWeight: 590,
          fontSize: 17, lineHeight: '22px', color: c,
        }}>{time}</span>
      </div>
      <div style={{ flex: 1, height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 7, paddingTop: 1, paddingRight: 1 }}>
        <svg width="19" height="12" viewBox="0 0 19 12">
          <rect x="0" y="7.5" width="3.2" height="4.5" rx="0.7" fill={c}/>
          <rect x="4.8" y="5" width="3.2" height="7" rx="0.7" fill={c}/>
          <rect x="9.6" y="2.5" width="3.2" height="9.5" rx="0.7" fill={c}/>
          <rect x="14.4" y="0" width="3.2" height="12" rx="0.7" fill={c}/>
        </svg>
        <svg width="17" height="12" viewBox="0 0 17 12">
          <path d="M8.5 3.2C10.8 3.2 12.9 4.1 14.4 5.6L15.5 4.5C13.7 2.7 11.2 1.5 8.5 1.5C5.8 1.5 3.3 2.7 1.5 4.5L2.6 5.6C4.1 4.1 6.2 3.2 8.5 3.2Z" fill={c}/>
          <path d="M8.5 6.8C9.9 6.8 11.1 7.3 12 8.2L13.1 7.1C11.8 5.9 10.2 5.1 8.5 5.1C6.8 5.1 5.2 5.9 3.9 7.1L5 8.2C5.9 7.3 7.1 6.8 8.5 6.8Z" fill={c}/>
          <circle cx="8.5" cy="10.5" r="1.5" fill={c}/>
        </svg>
        <svg width="27" height="13" viewBox="0 0 27 13">
          <rect x="0.5" y="0.5" width="23" height="12" rx="3.5" stroke={c} strokeOpacity="0.35" fill="none"/>
          <rect x="2" y="2" width="20" height="9" rx="2" fill={c}/>
          <path d="M25 4.5V8.5C25.8 8.2 26.5 7.2 26.5 6.5C26.5 5.8 25.8 4.8 25 4.5Z" fill={c} fillOpacity="0.4"/>
        </svg>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Liquid glass pill — blur + tint + shine
// ─────────────────────────────────────────────────────────────
function IOSGlassPill({ children, dark = false, style = {} }) {
  return (
    <div style={{
      height: 44, minWidth: 44, borderRadius: 9999,
      position: 'relative', overflow: 'hidden',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      boxShadow: dark
        ? '0 2px 6px rgba(0,0,0,0.35), 0 6px 16px rgba(0,0,0,0.2)'
        : '0 1px 3px rgba(0,0,0,0.07), 0 3px 10px rgba(0,0,0,0.06)',
      ...style,
    }}>
      {/* blur + tint */}
      <div style={{
        position: 'absolute', inset: 0, borderRadius: 9999,
        backdropFilter: 'blur(12px) saturate(180%)',
        WebkitBackdropFilter: 'blur(12px) saturate(180%)',
        background: dark ? 'rgba(120,120,128,0.28)' : 'rgba(255,255,255,0.5)',
      }} />
      {/* shine */}
      <div style={{
        position: 'absolute', inset: 0, borderRadius: 9999,
        boxShadow: dark
          ? 'inset 1.5px 1.5px 1px rgba(255,255,255,0.15), inset -1px -1px 1px rgba(255,255,255,0.08)'
          : 'inset 1.5px 1.5px 1px rgba(255,255,255,0.7), inset -1px -1px 1px rgba(255,255,255,0.4)',
        border: dark ? '0.5px solid rgba(255,255,255,0.15)' : '0.5px solid rgba(0,0,0,0.06)',
      }} />
      <div style={{ position: 'relative', zIndex: 1, display: 'flex', alignItems: 'center', padding: '0 4px' }}>
        {children}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Navigation bar — glass pills + large title
// ─────────────────────────────────────────────────────────────
function IOSNavBar({ title = 'Title', dark = false, trailingIcon = true }) {
  const muted = dark ? 'rgba(255,255,255,0.6)' : '#404040';
  const text = dark ? '#fff' : '#000';
  const pillIcon = (content) => (
    <IOSGlassPill dark={dark}>
      <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {content}
      </div>
    </IOSGlassPill>
  );
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', gap: 10,
      paddingTop: 62, paddingBottom: 10, position: 'relative', zIndex: 5,
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 16px',
      }}>
        {/* back chevron */}
        {pillIcon(
          <svg width="12" height="20" viewBox="0 0 12 20" fill="none" style={{ marginLeft: -1 }}>
            <path d="M10 2L2 10l8 8" stroke={muted} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        )}
        {/* trailing ellipsis */}
        {trailingIcon && pillIcon(
          <svg width="22" height="6" viewBox="0 0 22 6">
            <circle cx="3" cy="3" r="2.5" fill={muted}/>
            <circle cx="11" cy="3" r="2.5" fill={muted}/>
            <circle cx="19" cy="3" r="2.5" fill={muted}/>
          </svg>
        )}
      </div>
      {/* large title */}
      <div style={{
        padding: '0 16px',
        fontFamily: '-apple-system, system-ui',
        fontSize: 34, fontWeight: 700, lineHeight: '41px',
        color: text, letterSpacing: 0.4,
      }}>{title}</div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Grouped list (inset card, r:26) + row (52px)
// ─────────────────────────────────────────────────────────────
function IOSListRow({ title, detail, icon, chevron = true, isLast = false, dark = false }) {
  const text = dark ? '#fff' : '#000';
  const sec = dark ? 'rgba(235,235,245,0.6)' : 'rgba(60,60,67,0.6)';
  const ter = dark ? 'rgba(235,235,245,0.3)' : 'rgba(60,60,67,0.3)';
  const sep = dark ? 'rgba(84,84,88,0.65)' : 'rgba(60,60,67,0.12)';
  return (
    <div style={{
      display: 'flex', alignItems: 'center', minHeight: 52,
      padding: '0 16px', position: 'relative',
      fontFamily: '-apple-system, system-ui', fontSize: 17,
      letterSpacing: -0.43,
    }}>
      {icon && (
        <div style={{
          width: 30, height: 30, borderRadius: 7, background: icon,
          marginRight: 12, flexShrink: 0,
        }} />
      )}
      <div style={{ flex: 1, color: text }}>{title}</div>
      {detail && <span style={{ color: sec, marginRight: 6 }}>{detail}</span>}
      {chevron && (
        <svg width="8" height="14" viewBox="0 0 8 14" style={{ flexShrink: 0 }}>
          <path d="M1 1l6 6-6 6" stroke={ter} strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      )}
      {!isLast && (
        <div style={{
          position: 'absolute', bottom: 0, right: 0,
          left: icon ? 58 : 16, height: 0.5, background: sep,
        }} />
      )}
    </div>
  );
}

function IOSList({ header, children, dark = false }) {
  const hc = dark ? 'rgba(235,235,245,0.6)' : 'rgba(60,60,67,0.6)';
  const bg = dark ? '#1C1C1E' : '#fff';
  return (
    <div>
      {header && (
        <div style={{
          fontFamily: '-apple-system, system-ui', fontSize: 13,
          color: hc, textTransform: 'uppercase',
          padding: '8px 36px 6px', letterSpacing: -0.08,
        }}>{header}</div>
      )}
      <div style={{
        background: bg, borderRadius: 26,
        margin: '0 16px', overflow: 'hidden',
      }}>{children}</div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Device frame
// ─────────────────────────────────────────────────────────────
function IOSDevice({
  children, width = 402, height = 874, dark = false,
  title, keyboard = false,
}) {
  return (
    <div style={{
      width, height, borderRadius: 48, overflow: 'hidden',
      position: 'relative', background: dark ? '#000' : '#F2F2F7',
      boxShadow: '0 40px 80px rgba(0,0,0,0.18), 0 0 0 1px rgba(0,0,0,0.12)',
      fontFamily: '-apple-system, system-ui, sans-serif',
      WebkitFontSmoothing: 'antialiased',
    }}>
      {/* dynamic island */}
      <div style={{
        position: 'absolute', top: 11, left: '50%', transform: 'translateX(-50%)',
        width: 126, height: 37, borderRadius: 24, background: '#000', zIndex: 50,
      }} />
      {/* status bar (absolute) */}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 10 }}>
        <IOSStatusBar dark={dark} />
      </div>
      {/* nav + content */}
      <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        {title !== undefined && <IOSNavBar title={title} dark={dark} />}
        <div style={{ flex: 1, overflow: 'auto' }}>{children}</div>
        {keyboard && <IOSKeyboard dark={dark} />}
      </div>
      {/* home indicator — always on top */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0, zIndex: 60,
        height: 34, display: 'flex', justifyContent: 'center', alignItems: 'flex-end',
        paddingBottom: 8, pointerEvents: 'none',
      }}>
        <div style={{
          width: 139, height: 5, borderRadius: 100,
          background: dark ? 'rgba(255,255,255,0.7)' : 'rgba(0,0,0,0.25)',
        }} />
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Keyboard — iOS 26 liquid glass
// ─────────────────────────────────────────────────────────────
function IOSKeyboard({ dark = false }) {
  const glyph = dark ? 'rgba(255,255,255,0.7)' : '#595959';
  const sugg = dark ? 'rgba(255,255,255,0.6)' : '#333';
  const keyBg = dark ? 'rgba(255,255,255,0.22)' : 'rgba(255,255,255,0.85)';

  // special-key icons
  const icons = {
    shift: <svg width="19" height="17" viewBox="0 0 19 17"><path d="M9.5 1L1 9.5h4.5V16h8V9.5H18L9.5 1z" fill={glyph}/></svg>,
    del: <svg width="23" height="17" viewBox="0 0 23 17"><path d="M7 1h13a2 2 0 012 2v11a2 2 0 01-2 2H7l-6-7.5L7 1z" fill="none" stroke={glyph} strokeWidth="1.6" strokeLinejoin="round"/><path d="M10 5l7 7M17 5l-7 7" stroke={glyph} strokeWidth="1.6" strokeLinecap="round"/></svg>,
    ret: <svg width="20" height="14" viewBox="0 0 20 14"><path d="M18 1v6H4m0 0l4-4M4 7l4 4" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>,
  };

  const key = (content, { w, flex, ret, fs = 25, k } = {}) => (
    <div key={k} style={{
      height: 42, borderRadius: 8.5,
      flex: flex ? 1 : undefined, width: w, minWidth: 0,
      background: ret ? '#08f' : keyBg,
      boxShadow: '0 1px 0 rgba(0,0,0,0.075)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: '-apple-system, "SF Compact", system-ui',
      fontSize: fs, fontWeight: 458, color: ret ? '#fff' : glyph,
    }}>{content}</div>
  );

  const row = (keys, pad = 0) => (
    <div style={{ display: 'flex', gap: 6.5, justifyContent: 'center', padding: `0 ${pad}px` }}>
      {keys.map(l => key(l, { flex: true, k: l }))}
    </div>
  );

  return (
    <div style={{
      position: 'relative', zIndex: 15, borderRadius: 27, overflow: 'hidden',
      padding: '11px 0 2px',
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      boxShadow: dark
        ? '0 -2px 20px rgba(0,0,0,0.09)'
        : '0 -1px 6px rgba(0,0,0,0.018), 0 -3px 20px rgba(0,0,0,0.012)',
    }}>
      {/* liquid glass bg — same recipe as nav pills */}
      <div style={{
        position: 'absolute', inset: 0, borderRadius: 27,
        backdropFilter: 'blur(12px) saturate(180%)',
        WebkitBackdropFilter: 'blur(12px) saturate(180%)',
        background: dark ? 'rgba(120,120,128,0.14)' : 'rgba(255,255,255,0.25)',
      }} />
      <div style={{
        position: 'absolute', inset: 0, borderRadius: 27,
        boxShadow: dark
          ? 'inset 1.5px 1.5px 1px rgba(255,255,255,0.15)'
          : 'inset 1.5px 1.5px 1px rgba(255,255,255,0.7), inset -1px -1px 1px rgba(255,255,255,0.4)',
        border: dark ? '0.5px solid rgba(255,255,255,0.15)' : '0.5px solid rgba(0,0,0,0.06)',
        pointerEvents: 'none',
      }} />

      {/* autocorrect bar */}
      <div style={{
        display: 'flex', gap: 20, alignItems: 'center',
        padding: '8px 22px 13px', width: '100%', boxSizing: 'border-box',
        position: 'relative',
      }}>
        {['"The"', 'the', 'to'].map((w, i) => (
          <React.Fragment key={i}>
            {i > 0 && <div style={{ width: 1, height: 25, background: '#ccc', opacity: 0.3 }} />}
            <div style={{
              flex: 1, textAlign: 'center',
              fontFamily: '-apple-system, system-ui', fontSize: 17,
              color: sugg, letterSpacing: -0.43, lineHeight: '22px',
            }}>{w}</div>
          </React.Fragment>
        ))}
      </div>

      {/* key layout */}
      <div style={{
        display: 'flex', flexDirection: 'column', gap: 13,
        padding: '0 6.5px', width: '100%', boxSizing: 'border-box',
        position: 'relative',
      }}>
        {row(['q','w','e','r','t','y','u','i','o','p'])}
        {row(['a','s','d','f','g','h','j','k','l'], 20)}
        <div style={{ display: 'flex', gap: 14.25, alignItems: 'center' }}>
          {key(icons.shift, { w: 45, k: 'shift' })}
          <div style={{ display: 'flex', gap: 6.5, flex: 1 }}>
            {['z','x','c','v','b','n','m'].map(l => key(l, { flex: true, k: l }))}
          </div>
          {key(icons.del, { w: 45, k: 'del' })}
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {key('ABC', { w: 92.25, fs: 18, k: 'abc' })}
          {key('', { flex: true, k: 'space' })}
          {key(icons.ret, { w: 92.25, ret: true, k: 'ret' })}
        </div>
      </div>

      {/* bottom spacer (emoji+mic area, icons omitted) */}
      <div style={{ height: 56, width: '100%', position: 'relative' }} />
    </div>
  );
}

Object.assign(window, {
  IOSDevice, IOSStatusBar, IOSNavBar, IOSGlassPill, IOSList, IOSListRow, IOSKeyboard,
});

})();

/* ===== desktop-core.jsx ===== */
;(function(){
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

})();

/* ===== scene-only.jsx ===== */
;(function(){
// cc-pocket — Workflow run screen (scene composition)
// Reuses window globals from workflow-core.jsx (WorkflowCard, glyphs, PulseDot,
// HollowDot, FailChip, PhaseBar, wfTokens) and ios-frame.jsx (IOSDevice).
// Two mobile frames: A = chat stream w/ live card + terminal variant strip;
// B = full-screen "Workflow run" progress tree. Plus a light-theme dup of A.

// ── small building blocks ─────────────────────────────────────

// compact chat thread header (sits under the status bar, custom — not the big iOS nav title)
function ChatHeader({ k }) {
  const { ChevWF } = window;
  return (
    <div style={{ flexShrink:0, paddingTop:54, paddingBottom:10, borderBottom:`1px solid ${k.hair}`, background:k.base }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 8px 0 6px', minHeight:36 }}>
        <div style={{ width:36, height:36, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <ChevWF d="left" c={k.sec} s={17} w={2}/>
        </div>
        <div style={{ flex:1, minWidth:0, textAlign:'center' }}>
          <div style={{ fontFamily:k.ui, fontSize:15, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Ship 2.4</div>
          <div style={{ fontFamily:k.mono, fontSize:10.5, color:k.muted, marginTop:1 }}>agent · main</div>
        </div>
        <div style={{ width:36, height:36, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <svg width="20" height="6" viewBox="0 0 20 6"><circle cx="3" cy="3" r="2.4" fill={k.sec}/><circle cx="10" cy="3" r="2.4" fill={k.sec}/><circle cx="17" cy="3" r="2.4" fill={k.sec}/></svg>
        </div>
      </div>
    </div>
  );
}

function Spk({ k, children }) {
  return <div style={{ fontFamily:k.ui, fontSize:11, fontWeight:600, letterSpacing:'0.04em', color:k.muted, marginBottom:6 }}>{children}</div>;
}
function YouTurn({ k, children }) {
  return (
    <div>
      <Spk k={k}>YOU</Spk>
      <div style={{ fontFamily:k.ui, fontSize:15, lineHeight:1.5, color:k.text }}>{children}</div>
    </div>
  );
}
function AsstTurn({ k, children, caret }) {
  return (
    <div style={{ fontFamily:k.ui, fontSize:14.5, lineHeight:1.52, color:k.text }}>
      {children}
      {caret && <span className="wf-caret" style={{ display:'inline-block', width:6, height:14, background:k.accent, borderRadius:1, verticalAlign:'text-bottom', marginLeft:2 }}/>}
    </div>
  );
}

// slim composer grounding the chat (dark + light via k)
function MiniComposer({ k }) {
  const { AttachIcon, SendArrow } = window;
  return (
    <div style={{ flexShrink:0, background:k.surface, borderTop:`1px solid ${k.hair}`, paddingBottom:26 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 12px' }}>
        <div style={{ width:44, height:44, borderRadius:10, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <AttachIcon c={k.sec} s={23}/>
        </div>
        <div style={{ flex:1, background:k.base, border:`1px solid ${k.hair}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 14px', minHeight:44 }}>
          <span style={{ fontFamily:k.ui, fontSize:14.5, color:k.muted }}>Message Claude…</span>
        </div>
        <div style={{ width:44, height:44, borderRadius:999, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background:k.accent }}>
          <SendArrow c={k.onAcc} s={18}/>
        </div>
      </div>
    </div>
  );
}

// ── FRAME A · chat stream (device content) ────────────────────
function FrameAContent({ k }) {
  const { WorkflowCard } = window;
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:k.base }}>
      <ChatHeader k={k}/>
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'18px 16px 22px' }}>
        <YouTurn k={k}>Ship the 2.4 release — run the full pipeline across every affected module.</YouTurn>

        <div style={{ height:1, background:k.hair, margin:'20px 0' }}/>

        <AsstTurn k={k}>
          Starting the <b style={{ fontWeight:600 }}>release-pipeline</b> workflow — I'll resolve the changeset, analyze the affected modules in parallel, then package and publish. I'll keep this card live.
        </AsstTurn>

        <div style={{ marginTop:16 }}>
          <WorkflowCard k={k} variant="live" name="release-pipeline"/>
        </div>

        <div style={{ marginTop:16 }}>
          <AsstTurn k={k} caret>Analyzing 34 modules — two auth checks came back with issues, tracking them below</AsstTurn>
        </div>
      </div>
      <MiniComposer k={k}/>
    </div>
  );
}

// ── variant strip (beside the phone) ──────────────────────────
function SpecLabel({ k, children }) {
  return <div style={{ fontFamily:k.mono, fontSize:10.5, letterSpacing:'0.06em', color:k.muted, textTransform:'uppercase', marginBottom:9 }}>{children}</div>;
}
function VariantStrip({ k }) {
  const { WorkflowCard } = window;
  const rows = [
    { lab:'live · unknown total', v:'live-unknown' },
    { lab:'done · all green', v:'ok' },
    { lab:'done · agent failures', v:'ok-fail' },
    { lab:'aborted', v:'aborted' },
  ];
  return (
    <div style={{ width:352, display:'flex', flexDirection:'column', gap:22 }}>
      <div>
        <div style={{ fontFamily:k.ui, fontSize:13, fontWeight:600, color:k.text, marginBottom:3 }}>Card states</div>
        <div style={{ fontFamily:k.ui, fontSize:12, lineHeight:1.45, color:k.muted, maxWidth:320 }}>
          One card, every terminal outcome. Danger lives only in glyphs and chips — never a red card border.
        </div>
      </div>
      {rows.map((r) => (
        <div key={r.v}>
          <SpecLabel k={k}>{r.lab}</SpecLabel>
          <WorkflowCard k={k} variant={r.v} name="release-pipeline"/>
        </div>
      ))}
    </div>
  );
}

// ── FRAME B · full-screen progress tree ───────────────────────

function StatusPill({ k, elapsed }) {
  const { PulseDot } = window;
  return (
    <div style={{ display:'flex', alignItems:'center', gap:7, flexShrink:0, background:k.accTint, border:`1px solid ${k.accBd}`, borderRadius:999, padding:'5px 11px 5px 10px' }}>
      <PulseDot k={k} s={6} glow={false}/>
      <span style={{ fontFamily:k.mono, fontSize:12, color:k.accent, fontWeight:500 }}>{elapsed}</span>
    </div>
  );
}

function AgentRow({ k, state, label, dur }) {
  const { CheckGlyph, XGlyph, PulseDot, HollowDot } = window;
  let glyph, ink = k.text;
  if (state === 'run') { glyph = <PulseDot k={k} s={7}/>; }
  else if (state === 'done') { glyph = <CheckGlyph c={k.success} s={14}/>; }
  else if (state === 'fail') { glyph = <XGlyph c={k.danger} s={13} w={2.1}/>; }
  else { glyph = <HollowDot k={k} s={8}/>; ink = k.muted; }
  return (
    <div style={{ display:'flex', alignItems:'center', gap:11, minHeight:40, padding:'0 2px' }}>
      <span style={{ width:16, display:'flex', justifyContent:'center', flexShrink:0 }}>{glyph}</span>
      <span style={{ flex:1, minWidth:0, fontFamily:k.ui, fontSize:14, color:ink, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>
        {label}
      </span>
      <span style={{ fontFamily:k.mono, fontSize:12, color: state==='queued' ? k.muted : k.sec, flexShrink:0 }}>{dur}</span>
    </div>
  );
}

// one queued-overflow summary row that expands on tap
function QueuedRow({ k, n }) {
  const { useState } = React;
  const { HollowDot, ChevWF } = window;
  const [open, setOpen] = useState(false);
  const extra = [
    { label:'scan module-config', dur:'queued' },
    { label:'scan module-search', dur:'queued' },
    { label:'scan module-notify', dur:'queued' },
  ];
  return (
    <div>
      <div onClick={() => setOpen(o=>!o)} className="wf-press" style={{ display:'flex', alignItems:'center', gap:11, minHeight:40, padding:'0 2px', cursor:'pointer' }}>
        <span style={{ width:16, display:'flex', justifyContent:'center', flexShrink:0 }}><HollowDot k={k} s={8}/></span>
        <span style={{ flex:1, minWidth:0, fontFamily:k.ui, fontSize:14, color:k.sec }}>+ {n} queued</span>
        <span style={{ display:'flex', flexShrink:0, transform: open ? 'rotate(180deg)':'none', transition:'transform .2s' }}><ChevWF d="down" c={k.muted} s={15}/></span>
      </div>
      {open && (
        <div style={{ paddingLeft:0 }}>
          {extra.map((e,i) => <AgentRow key={i} k={k} state="queued" label={e.label} dur={e.dur}/>)}
          <div style={{ fontFamily:k.mono, fontSize:11.5, color:k.muted, padding:'4px 2px 0 27px' }}>+ {n-extra.length} more</div>
        </div>
      )}
    </div>
  );
}

// a phase disclosure section
function PhaseSection({ k, phase, defaultOpen }) {
  const { useState } = React;
  const { CheckGlyph, PulseDot, HollowDot, ChevWF, FailChip } = window;
  const [open, setOpen] = useState(defaultOpen);
  const isPending = phase.status === 'pending';
  const isActive = phase.status === 'active';
  const isDone = phase.status === 'done';

  let statusGlyph;
  if (isActive) statusGlyph = <PulseDot k={k} s={7}/>;
  else if (isDone) statusGlyph = <CheckGlyph c={k.success} s={14}/>;
  else statusGlyph = <HollowDot k={k} s={8}/>;

  const nameCol = isPending ? k.muted : k.text;
  const failed = phase.failed || 0;
  // completed-with-failures: header collapsed but the failed rows stay pinned
  const showBody = open && !isPending;
  const pinnedFails = isDone && failed > 0 && !open;

  return (
    <div style={{ borderTop:`1px solid ${k.hair}` }}>
      <div onClick={() => !isPending && setOpen(o=>!o)} className="wf-press" style={{
        display:'flex', alignItems:'center', gap:10, minHeight:46, padding:'0 2px', cursor: isPending ? 'default' : 'pointer', opacity: isPending ? 0.62 : 1,
      }}>
        <span style={{ display:'flex', flexShrink:0, transform: open ? 'rotate(0deg)':'none', opacity: isPending ? 0 : 1 }}>
          <ChevWF d={open ? 'down' : 'right'} c={k.muted} s={14}/>
        </span>
        <span style={{ width:16, display:'flex', justifyContent:'center', flexShrink:0 }}>{statusGlyph}</span>
        <span style={{ fontFamily:k.ui, fontSize:14, fontWeight:500, color:nameCol, flexShrink:0 }}>{phase.name}</span>
        {failed > 0 && <FailChip k={k} n={failed}/>}
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:k.mono, fontSize:12, color: isPending ? k.muted : k.sec, flexShrink:0 }}>{phase.count}</span>
        <span style={{ fontFamily:k.mono, fontSize:12, color:k.muted, flexShrink:0, minWidth:44, textAlign:'right' }}>{phase.timer}</span>
      </div>

      {/* collapsed completed → one summary line (+ pinned failures) */}
      {isDone && !open && (
        <div style={{ paddingBottom:10 }}>
          <div style={{ fontFamily:k.mono, fontSize:12, color:k.muted, padding:'0 2px 2px 42px', display:'flex', alignItems:'center', gap:7 }}>
            <CheckGlyph c={k.success} s={12}/>{phase.summary}
          </div>
          {pinnedFails && (
            <div style={{ paddingLeft:42, paddingTop:4 }}>
              {phase.rows.filter(r => r.state === 'fail').map((r,i) => (
                <AgentRow key={i} k={k} state={r.state} label={r.label} dur={r.dur}/>
              ))}
            </div>
          )}
        </div>
      )}

      {/* pending → dimmed one-liner */}
      {isPending && (
        <div style={{ fontFamily:k.mono, fontSize:12, color:k.muted, padding:'0 2px 12px 42px', opacity:0.62 }}>{phase.summary}</div>
      )}

      {/* expanded body */}
      {showBody && (
        <div style={{ paddingLeft:42, paddingBottom:12 }}>
          {phase.rows.map((r,i) => (
            r.queued
              ? <QueuedRow key={i} k={k} n={r.n}/>
              : <AgentRow key={i} k={k} state={r.state} label={r.label} dur={r.dur}/>
          ))}
        </div>
      )}
    </div>
  );
}

function JumpPill({ k, show }) {
  const { ChevWF } = window;
  return (
    <div style={{
      position:'absolute', left:'50%', bottom:40, transform:`translateX(-50%) translateY(${show?0:14}px)`,
      opacity: show?1:0, transition:'opacity .2s, transform .2s', pointerEvents: show?'auto':'none', zIndex:40,
    }}>
      <div className="wf-press" style={{
        display:'flex', alignItems:'center', gap:7, cursor:'pointer',
        background:k.raised, border:`1px solid ${k.hair}`, borderRadius:999, padding:'8px 15px 8px 13px',
        boxShadow:'0 6px 20px rgba(0,0,0,0.35)',
      }}>
        <ChevWF d="down" c={k.accent} s={14} w={2.1}/>
        <span style={{ fontFamily:k.ui, fontSize:12.5, fontWeight:600, color:k.text }}>Jump to active</span>
      </div>
    </div>
  );
}

function FrameBContent({ k }) {
  const { useState, useRef } = React;
  const { ChevWF, LanesGlyph } = window;
  const [jump, setJump] = useState(false);
  const scRef = useRef(null);

  const phases = [
    {
      name:'resolve', status:'done', count:'6/6', timer:'48s',
      summary:'6 agents · 48s', failed:0, rows:[],
    },
    {
      name:'analyze', status:'active', count:'8/12', timer:'2m 24s', failed:0,
      rows:[
        { state:'run', label:'scan module-auth', dur:'42s' },
        { state:'run', label:'scan module-billing', dur:'38s' },
        { state:'run', label:'scan module-gateway', dur:'31s' },
        { state:'done', label:'scan module-core', dur:'1m 12s' },
        { state:'done', label:'scan module-api', dur:'58s' },
        { state:'done', label:'lint module-ui', dur:'44s' },
        { queued:true, n:18 },
      ],
    },
    {
      name:'checks', status:'done', count:'10/12', timer:'1m 40s', failed:2,
      summary:'10 passed · 2 failed · 1m 40s',
      rows:[
        { state:'fail', label:'test auth.session.expiry', dur:'19s' },
        { state:'fail', label:'test auth.token.refresh', dur:'12s' },
      ],
    },
    {
      name:'package', status:'pending', count:'0/8', timer:'—', failed:0,
      summary:'8 agents queued', rows:[],
    },
    {
      name:'publish', status:'pending', count:'0/3', timer:'—', failed:0,
      summary:'3 agents queued', rows:[],
    },
  ];

  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:k.base, position:'relative' }}>
      {/* header */}
      <div style={{ flexShrink:0, paddingTop:54, paddingBottom:12, borderBottom:`1px solid ${k.hair}`, background:k.base }}>
        <div style={{ display:'flex', alignItems:'center', gap:9, padding:'0 10px 0 6px' }}>
          <div style={{ width:36, height:36, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
            <ChevWF d="left" c={k.sec} s={17} w={2}/>
          </div>
          <div style={{ width:26, height:26, borderRadius:7, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background:k.accTint, border:`1px solid ${k.accBd}` }}>
            <LanesGlyph c={k.accent} s={15}/>
          </div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{ fontFamily:k.ui, fontSize:15, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.1 }}>release-pipeline</div>
            <div style={{ fontFamily:k.mono, fontSize:11.5, color:k.muted, marginTop:1 }}>wf_8f3a21…</div>
          </div>
          <StatusPill k={k} elapsed="3m 12s"/>
        </div>
      </div>

      {/* scrolling phase tree */}
      <div ref={scRef} onScroll={(e)=>setJump(e.target.scrollTop > 150)} style={{ flex:1, minHeight:0, overflowY:'auto', padding:'2px 16px 40px' }}>
        {phases.map((p,i) => (
          <PhaseSection key={p.name} k={k} phase={p} defaultOpen={p.status === 'active'}/>
        ))}
        <div style={{ borderTop:`1px solid ${k.hair}` }}/>
        {/* tail spacer so there is room to scroll the active phase off-screen */}
        <div style={{ height:120 }}/>
      </div>

      <JumpPill k={k} show={jump}/>
    </div>
  );
}

// ── device wrapper + canvas ───────────────────────────────────
function DeviceLabel({ k, tag, title, sub }) {
  return (
    <div style={{ marginBottom:16 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:10 }}>
        <span style={{ fontFamily:k.mono, fontSize:12, color:k.accent, letterSpacing:'0.04em' }}>{tag}</span>
        <span style={{ fontFamily:k.ui, fontSize:16, fontWeight:600, color:'#ECEDEE' }}>{title}</span>
      </div>
      {sub && <div style={{ fontFamily:k.ui, fontSize:13, color:'#6B7177', marginTop:4, maxWidth:520 }}>{sub}</div>}
    </div>
  );
}

function WorkflowScene() {
  const { IOSDevice, wfTokens } = window;
  const kd = wfTokens(true);
  const kl = wfTokens(false);

  return (
    <div style={{ padding:'56px 60px 80px', display:'flex', flexDirection:'column', gap:64, minWidth:'max-content' }}>
      {/* title */}
      <div style={{ maxWidth:760 }}>
        <div style={{ fontFamily:kd.mono, fontSize:12.5, color:kd.accent, letterSpacing:'0.08em', textTransform:'uppercase' }}>cc-pocket · orchestration</div>
        <div style={{ fontFamily:kd.ui, fontSize:32, fontWeight:700, color:'#ECEDEE', marginTop:10, letterSpacing:-0.6 }}>Workflow run — card & progress tree</div>
        <div style={{ fontFamily:kd.ui, fontSize:15, lineHeight:1.55, color:'#9BA1A6', marginTop:12 }}>
          The orchestration-level sibling of SubagentCard: a container of many agents grouped into phases. Stacked-lanes tile, segmented phase bar, one synchronized pulse across all running rows. Tapping the chat card pushes the full progress tree.
        </div>
      </div>

      {/* FRAME A + variant strip */}
      <div>
        <DeviceLabel k={kd} tag="A" title="Chat stream — live run card" sub="One compact Workflow card between assistant turns. Header: stacked-lanes tile, name, pulse + elapsed. Segmented phase bar, then a meta caption with the ✗ chip only when failures exist." />
        <div style={{ display:'flex', gap:56, alignItems:'flex-start' }}>
          <div style={{ flexShrink:0 }}>
            <IOSDevice dark width={402} height={874}>
              <FrameAContent k={kd}/>
            </IOSDevice>
          </div>
          <div style={{ paddingTop:8 }}>
            <VariantStrip k={kd}/>
          </div>
        </div>
      </div>

      {/* FRAME B */}
      <div>
        <DeviceLabel k={kd} tag="B" title="Workflow run — full-screen progress tree" sub="Completed phases collapse to a summary line; the active phase expands with running rows first then a queued-overflow row. Failures never fold — the checks phase stays collapsed but pins its two failed rows. Scroll down to reveal the jump-to-active pill." />
        <div style={{ flexShrink:0, width:402 }}>
          <IOSDevice dark width={402} height={874}>
            <FrameBContent k={kd}/>
          </IOSDevice>
        </div>
      </div>

      {/* FRAME A · light lock */}
      <div>
        <DeviceLabel k={kd} tag="A · light" title="Light theme lock" sub="Same Frame A on the locked light tokens — base #FAF9F7, surface #FFFFFF, accent #C15F3C. Verifies the card, tile tints, phase bar and chips hold up on light." />
        <div style={{ flexShrink:0, width:402 }}>
          <IOSDevice width={402} height={874}>
            <FrameAContent k={kl}/>
          </IOSDevice>
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
//  SCREEN 2 · Agent detail sheet + finished-run journal review
// ════════════════════════════════════════════════════════════

function CopyGlyph({ c, s = 12 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
      <rect x="4.5" y="4.5" width="8" height="8" rx="1.6" stroke={c} strokeWidth="1.3"/>
      <path d="M9.5 4.2V3A1.5 1.5 0 0 0 8 1.5H3A1.5 1.5 0 0 0 1.5 3v5A1.5 1.5 0 0 0 3 9.5h1.2" stroke={c} strokeWidth="1.3" strokeLinecap="round"/>
    </svg>
  );
}
function DocGlyph({ c, s = 13 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 14 14" fill="none">
      <path d="M3.5 1.5h4.2L11 4.8V12A0.9 0.9 0 0 1 10.1 12.9H3.5A0.9 0.9 0 0 1 2.6 12V2.4A0.9 0.9 0 0 1 3.5 1.5Z" stroke={c} strokeWidth="1.2" strokeLinejoin="round"/>
      <path d="M7.5 1.6V4.6h3" stroke={c} strokeWidth="1.2" strokeLinejoin="round"/>
    </svg>
  );
}

// status glyph helper (shared)
function StatusGlyph({ k, status, s = 15 }) {
  const { CheckGlyph, XGlyph, PulseDot } = window;
  if (status === 'fail') return <XGlyph c={k.danger} s={s-1} w={2.1}/>;
  if (status === 'run') return <PulseDot k={k} s={7}/>;
  return <CheckGlyph c={k.success} s={s}/>;
}

// small status tile (sheet header)
function StatusTile({ k, status }) {
  let tint = k.okTint, bd = k.okBd;
  if (status === 'fail') { tint = k.danTint; bd = k.danBd; }
  else if (status === 'run') { tint = k.accTint; bd = k.accBd; }
  return (
    <div style={{ width:30, height:30, borderRadius:8, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background:tint, border:`1px solid ${bd}` }}>
      <StatusGlyph k={k} status={status} s={15}/>
    </div>
  );
}

// ── the report zone (SubagentCard language: eyebrow · scroll · copy foot) ──
function ReportZone({ k, danger, eyebrow, maxH = 260, footLeft, footAction, children }) {
  return (
    <div style={{ background:k.base, border:`1px solid ${danger ? k.danBd : k.hair}`, borderRadius:12, overflow:'hidden' }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, padding:'10px 14px 0' }}>
        <span style={{ fontFamily:k.mono, fontSize:9.5, letterSpacing:'0.13em', textTransform:'uppercase', color: danger ? k.danger : k.muted }}>{eyebrow}</span>
      </div>
      <div style={{ maxHeight:maxH, overflowY:'auto', padding:'8px 14px 6px' }}>
        {children}
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:10, padding:'9px 14px', borderTop:`1px solid ${k.hair}` }}>
        <span style={{ flex:1, minWidth:0, fontFamily:k.mono, fontSize:10.5, color:k.muted, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{footLeft}</span>
        {footAction}
        <span className="wf-press" style={{ display:'inline-flex', alignItems:'center', gap:6, cursor:'pointer', color:k.sec, flexShrink:0 }}>
          <CopyGlyph c={k.sec} s={12}/>
          <span style={{ fontFamily:k.mono, fontSize:10.5, color:k.sec }}>Copy return</span>
        </span>
      </div>
    </div>
  );
}

// report prose primitives (quieter than main text — matches .sac-rep)
function RepP({ k, children }) { return <p style={{ margin:'0 0 8px', fontFamily:k.ui, fontSize:13, lineHeight:1.56, color:k.sec }}>{children}</p>; }
function RepH({ k, children }) { return <div style={{ fontFamily:k.ui, fontSize:12.5, fontWeight:600, color:k.text, margin:'12px 0 5px' }}>{children}</div>; }
function Mono({ k, children }) { return <code style={{ fontFamily:k.mono, fontSize:12, color:k.text, background:k.surface, border:`1px solid ${k.hair}`, borderRadius:4, padding:'0 4px' }}>{children}</code>; }
function RepUL({ k, items }) {
  return (
    <ul style={{ margin:'0 0 8px', padding:0, listStyle:'none', display:'flex', flexDirection:'column', gap:4 }}>
      {items.map((it,i)=>(
        <li key={i} style={{ position:'relative', paddingLeft:15, fontFamily:k.ui, fontSize:13, lineHeight:1.56, color:k.sec }}>
          <span style={{ position:'absolute', left:0, color:k.muted }}>–</span>{it}
        </li>
      ))}
    </ul>
  );
}

// ── the Prompt block (collapsed to 2 lines · more) ────────────
function PromptBlock({ k, text }) {
  const { useState } = React;
  const [open, setOpen] = useState(false);
  return (
    <div>
      <div style={{ fontFamily:k.mono, fontSize:9.5, letterSpacing:'0.13em', textTransform:'uppercase', color:k.muted, marginBottom:8 }}>Prompt</div>
      <div style={{ background:k.raised, border:`1px solid ${k.hair}`, borderRadius:12, padding:'11px 13px', position:'relative' }}>
        <div style={{ position:'absolute', left:0, top:11, bottom:11, width:2.5, borderRadius:2, background:k.hair }}/>
        <div style={{ paddingLeft:11, fontFamily:k.ui, fontSize:14, lineHeight:1.5, color:k.sec,
          display:'-webkit-box', WebkitLineClamp: open ? 'unset' : 2, WebkitBoxOrient:'vertical', overflow:'hidden' }}>
          {text}
        </div>
        <span onClick={()=>setOpen(o=>!o)} className="wf-press" style={{ display:'inline-block', marginTop:6, marginLeft:11, cursor:'pointer', fontFamily:k.ui, fontSize:12.5, fontWeight:600, color:k.accent }}>
          {open ? 'less' : 'more'}
        </span>
      </div>
    </div>
  );
}

// ── report content renderers per agent ────────────────────────
function ReturnSuccess({ k }) {
  return (<>
    <RepP k={k}>Reproduced the failing charge: expired sessions hit the gateway without re-auth, so <Mono k={k}>chargeIntent()</Mono> returned a stale 401. Patched the retry path in <Mono k={k}>src/payments/gateway.ts</Mono> to refresh the session before the second attempt.</RepP>
    <RepH k={k}>Changes</RepH>
    <RepUL k={k} items={[
      <>re-auth on 401 before retry — <Mono k={k}>gateway.ts</Mono></>,
      <>idempotency key carried across retries — <Mono k={k}>charge.ts</Mono></>,
      <>regression covering the expiry path — <Mono k={k}>gateway.test.ts</Mono></>,
    ]}/>
    <RepH k={k}>Result</RepH>
    <RepP k={k}>All 6 payment checks pass. Latency unchanged (<Mono k={k}>p95 142ms</Mono>). No public API change.</RepP>
  </>);
}
function ReturnError({ k }) {
  return (
    <pre style={{ margin:0, fontFamily:k.mono, fontSize:12, lineHeight:1.62, color:k.sec, whiteSpace:'pre-wrap', wordBreak:'break-word' }}>
<span style={{ color:k.danger }}>AssertionError: expected session to refresh before charge</span>{`
  at auth.session.expiry (tests/auth/session.spec.ts:48)
  at Runner.runTest (runner/exec.ts:212)

  expected: `}<span style={{ color:k.text }}>session.status === 'active'</span>{`
  received: `}<span style={{ color:k.text }}>'expired'</span>{`
  cause:    refresh() returned `}<span style={{ color:k.danger }}>401 token_expired</span>{`

  2 assertions failed · 0 passed`}
    </pre>
  );
}

// ── the agent detail sheet (content only; caller frames it) ────
function AgentDetailSheet({ k, status = 'ok', label, caption, prompt, chrome = true, height = '100%', onClose }) {
  const failed = status === 'fail';
  return (
    <div style={{ display:'flex', flexDirection:'column', height, background:k.surface, borderRadius:'20px 20px 0 0', overflow:'hidden', boxShadow:'0 -8px 40px rgba(0,0,0,0.45)' }}>
      {/* grab handle */}
      <div onClick={onClose} style={{ flexShrink:0, display:'flex', justifyContent:'center', padding:'9px 0 4px', cursor: onClose ? 'pointer' : 'default' }}>
        <span style={{ width:38, height:4.5, borderRadius:999, background:k.hair }}/>
      </div>
      {/* header */}
      <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:11, padding:'6px 16px 12px', borderBottom:`1px solid ${k.hair}` }}>
        <StatusTile k={k} status={status}/>
        <div style={{ flex:1, minWidth:0 }}>
          <div style={{ fontFamily:k.ui, fontSize:17, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.2 }}>{label}</div>
          <div style={{ fontFamily:k.mono, fontSize:11.5, color:k.muted, marginTop:2, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{caption}</div>
        </div>
      </div>
      {/* body */}
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 24px', display:'flex', flexDirection:'column', gap:20 }}>
        <PromptBlock k={k} text={prompt}/>
        <div>
          <div style={{ fontFamily:k.mono, fontSize:9.5, letterSpacing:'0.13em', textTransform:'uppercase', color:k.muted, marginBottom:8 }}>Return</div>
          <ReportZone
            k={k}
            danger={failed}
            eyebrow={failed ? 'error' : 'return'}
            maxH={280}
            footLeft={failed ? 'exit 1 · 2 failed' : '3 files changed · 6 checks'}
            footAction={
              <span className="wf-press" style={{ display:'inline-flex', alignItems:'center', gap:6, cursor:'pointer', color:k.sec, flexShrink:0 }}>
                <DocGlyph c={k.sec} s={12}/>
                <span style={{ fontFamily:k.mono, fontSize:10.5, color:k.sec }}>Open full transcript</span>
              </span>
            }
          >
            {failed ? <ReturnError k={k}/> : <ReturnSuccess k={k}/>}
          </ReportZone>
        </div>
      </div>
    </div>
  );
}

// ── FRAME A · detail sheet over the (dimmed) progress tree ─────
function FrameADetail({ k }) {
  return (
    <div style={{ position:'relative', height:'100%', background:k.base }}>
      <div style={{ position:'absolute', inset:0, filter:'saturate(0.8)', pointerEvents:'none' }}>
        <FrameBContent k={k}/>
      </div>
      <div style={{ position:'absolute', inset:0, background:'rgba(6,7,8,0.58)' }}/>
      <div style={{ position:'absolute', left:0, right:0, bottom:0, height:'72%' }}>
        <AgentDetailSheet
          k={k}
          status="ok"
          label="fix module-payments"
          caption="phase · fix — 1m 08s — agent-07"
          prompt="Investigate failing payment charges after session expiry. Reproduce the issue, find the root cause inside the payments module, and patch it with a regression test. Keep the public charge API stable and don't touch unrelated modules."
        />
      </div>
    </div>
  );
}

// ── FRAME B · journal review (terminal) ───────────────────────
function FinalReturnCard({ k }) {
  const { useState } = React;
  const [open, setOpen] = useState(false);
  const lines = [
    ['release ', <span key="a" style={{ color:k.success }}>2.4.0 published</span>],
    ['  registry:  npm · 12 packages'],
    ['  checks:    32 passed · ', <span key="b" style={{ color:k.danger }}>2 failed</span>, ' (non-blocking)'],
    ['  artifacts: dist/ · 4.2 MB'],
    ['  commit:    8f3a21c → tag v2.4.0'],
    ['  duration:  6m 32s'],
    ['  agents:    34  (32 ok · 2 failed)'],
    ['  changelog: CHANGELOG.md updated'],
  ];
  const shown = open ? lines : lines.slice(0, 6);
  return (
    <div style={{ background:k.base, border:`1px solid ${k.hair}`, borderRadius:12, overflow:'hidden' }}>
      <div style={{ padding:'10px 14px 0', fontFamily:k.mono, fontSize:9.5, letterSpacing:'0.13em', textTransform:'uppercase', color:k.muted }}>Final return</div>
      <div style={{ position:'relative', padding:'8px 14px 6px' }}>
        <pre style={{ margin:0, fontFamily:k.mono, fontSize:12.5, lineHeight:1.7, color:k.sec, whiteSpace:'pre-wrap', wordBreak:'break-word' }}>
          {shown.map((ln,i)=>(<div key={i}>{ln}</div>))}
        </pre>
        {!open && <div style={{ position:'absolute', left:0, right:0, bottom:0, height:26, background:`linear-gradient(180deg, transparent, ${k.base})` }}/>}
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:10, padding:'9px 14px', borderTop:`1px solid ${k.hair}` }}>
        <span onClick={()=>setOpen(o=>!o)} className="wf-press" style={{ flex:1, cursor:'pointer', fontFamily:k.ui, fontSize:12.5, fontWeight:600, color:k.accent }}>{open ? 'Collapse' : 'Expand'}</span>
        <span className="wf-press" style={{ display:'inline-flex', alignItems:'center', gap:6, cursor:'pointer', flexShrink:0 }}>
          <CopyGlyph c={k.sec} s={12}/>
          <span style={{ fontFamily:k.mono, fontSize:10.5, color:k.sec }}>Copy</span>
        </span>
      </div>
    </div>
  );
}

function Segmented({ k, tabs, value, onChange }) {
  return (
    <div style={{ display:'flex', gap:3, background:k.base, border:`1px solid ${k.hair}`, borderRadius:10, padding:3 }}>
      {tabs.map(t=>(
        <div key={t} onClick={()=>onChange(t)} className="wf-press" style={{
          flex:1, textAlign:'center', cursor:'pointer', borderRadius:7, padding:'7px 0',
          fontFamily:k.ui, fontSize:13, fontWeight:600,
          background: value===t ? k.raised : 'transparent',
          color: value===t ? k.text : k.muted,
          border: value===t ? `1px solid ${k.hair}` : '1px solid transparent',
        }}>{t}</div>
      ))}
    </div>
  );
}

function JournalRow({ k, row, onOpen }) {
  return (
    <div onClick={()=>onOpen(row)} className="wf-press" style={{ display:'flex', alignItems:'center', gap:11, minHeight:52, padding:'8px 2px', cursor:'pointer', borderTop:`1px solid ${k.hair}` }}>
      <span style={{ fontFamily:k.mono, fontSize:12, color:k.muted, width:26, flexShrink:0 }}>#{row.idx}</span>
      <span style={{ width:16, display:'flex', justifyContent:'center', flexShrink:0 }}><StatusGlyph k={k} status={row.status} s={14}/></span>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontFamily:k.ui, fontSize:14, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{row.label}</div>
        <div style={{ fontFamily:k.ui, fontSize:12.5, lineHeight:1.35, color: row.status==='fail' ? k.danger : k.muted, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', marginTop:2 }}>{row.preview}</div>
      </div>
      <span style={{ fontFamily:k.mono, fontSize:12, color:k.sec, flexShrink:0 }}>{row.dur}</span>
    </div>
  );
}

function FrameBJournal({ k }) {
  const { useState } = React;
  const { ChevWF, LanesGlyph, CheckGlyph } = window;
  const [tab, setTab] = useState('Journal');
  const [sheet, setSheet] = useState(null); // open agent detail

  const rows = [
    { idx:'01', status:'ok',   label:'resolve changeset',       preview:'resolved 34 files across 8 modules', dur:'12s' },
    { idx:'02', status:'ok',   label:'scan module-core',        preview:'no issues · 214 symbols indexed',    dur:'1m 12s' },
    { idx:'03', status:'ok',   label:'scan module-gateway',     preview:'flagged retry path for review',      dur:'31s' },
    { idx:'04', status:'fail', label:'scan module-auth',        preview:'2 checks failing on session expiry', dur:'42s' },
    { idx:'05', status:'ok',   label:'fix module-payments',     preview:'patched retry path + regression test — re-auth on 401 before charge, idempotency key carried across retries, all 6 payment checks green', dur:'1m 08s' },
    { idx:'06', status:'fail', label:'test auth.session.expiry',preview:'AssertionError: expected session to refresh before charge', dur:'19s' },
    { idx:'07', status:'ok',   label:'package dist',            preview:'bundled 12 packages · 4.2 MB',       dur:'58s' },
    { idx:'08', status:'ok',   label:'publish npm',             preview:'published v2.4.0 · tag pushed',       dur:'24s' },
  ];

  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:k.base }}>
      {/* header — terminal ✓ */}
      <div style={{ flexShrink:0, paddingTop:54, paddingBottom:12, borderBottom:`1px solid ${k.hair}`, background:k.base }}>
        <div style={{ display:'flex', alignItems:'center', gap:9, padding:'0 10px 0 6px' }}>
          <div style={{ width:36, height:36, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
            <ChevWF d="left" c={k.sec} s={17} w={2}/>
          </div>
          <div style={{ width:26, height:26, borderRadius:7, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background:k.okTint, border:`1px solid ${k.okBd}` }}>
            <LanesGlyph c={k.success} s={15}/>
          </div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{ fontFamily:k.ui, fontSize:15, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.1 }}>release-pipeline</div>
            <div style={{ fontFamily:k.mono, fontSize:11.5, color:k.muted, marginTop:1 }}>wf_8f3a21… · 34 agents</div>
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:6, flexShrink:0, background:k.okTint, border:`1px solid ${k.okBd}`, borderRadius:999, padding:'5px 11px 5px 9px' }}>
            <CheckGlyph c={k.success} s={13}/>
            <span style={{ fontFamily:k.mono, fontSize:12, color:k.success, fontWeight:500 }}>6m 32s</span>
          </div>
        </div>
      </div>

      {/* scroll body */}
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'16px 16px 40px' }}>
        <FinalReturnCard k={k}/>
        <div style={{ marginTop:18 }}>
          <Segmented k={k} tabs={['Phases','Journal']} value={tab} onChange={setTab}/>
        </div>
        {tab === 'Journal' ? (
          <div style={{ marginTop:14 }}>
            <div style={{ fontFamily:k.mono, fontSize:10.5, letterSpacing:'0.06em', textTransform:'uppercase', color:k.muted, paddingBottom:4 }}>34 agent() calls · chronological</div>
            {rows.map(r => <JournalRow key={r.idx} k={k} row={r} onOpen={setSheet}/>)}
            <div style={{ borderTop:`1px solid ${k.hair}`, paddingTop:12, fontFamily:k.mono, fontSize:11.5, color:k.muted, textAlign:'center' }}>+ 26 earlier calls</div>
          </div>
        ) : (
          <div style={{ marginTop:14, fontFamily:k.mono, fontSize:12, color:k.muted, textAlign:'center', padding:'30px 0' }}>Phase tree — see Frame B (screen 1)</div>
        )}
      </div>

      {/* tapped-row detail sheet */}
      {sheet && (
        <div style={{ position:'absolute', inset:0, zIndex:30 }}>
          <div onClick={()=>setSheet(null)} style={{ position:'absolute', inset:0, background:'rgba(6,7,8,0.58)' }}/>
          <div style={{ position:'absolute', left:0, right:0, bottom:0, height:'72%' }}>
            <AgentDetailSheet
              k={k}
              status={sheet.status}
              label={sheet.label}
              caption={`journal · #${sheet.idx} — ${sheet.dur} — agent-${sheet.idx}`}
              prompt={sheet.status==='fail'
                ? 'Run the auth session-expiry assertion suite against the patched gateway and report pass/fail with the failing assertion detail.'
                : 'Investigate failing payment charges after session expiry. Reproduce the issue, find the root cause inside the payments module, and patch it with a regression test. Keep the public charge API stable.'}
              onClose={()=>setSheet(null)}
            />
          </div>
        </div>
      )}
    </div>
  );
}

// ── screen-2 canvas ───────────────────────────────────────────
function WorkflowJournalScene() {
  const { IOSDevice, wfTokens } = window;
  const kd = wfTokens(true);
  const kl = wfTokens(false);

  return (
    <div style={{ padding:'56px 60px 80px', display:'flex', flexDirection:'column', gap:64, minWidth:'max-content' }}>
      <div style={{ maxWidth:760 }}>
        <div style={{ fontFamily:kd.mono, fontSize:12.5, color:kd.accent, letterSpacing:'0.08em', textTransform:'uppercase' }}>cc-pocket · orchestration</div>
        <div style={{ fontFamily:kd.ui, fontSize:32, fontWeight:700, color:'#ECEDEE', marginTop:10, letterSpacing:-0.6 }}>Agent detail & finished-run journal</div>
        <div style={{ fontFamily:kd.ui, fontSize:15, lineHeight:1.55, color:'#9BA1A6', marginTop:12 }}>
          A tapped agent opens a detail sheet reusing SubagentCard's report-zone language — scrollable mono-friendly return with a copy footer. When the run finishes, the same view flips to a terminal journal: a pinned Final return plus a chronological list of every agent() call.
        </div>
      </div>

      {/* FRAME A — detail sheet + failed variant */}
      <div>
        <DeviceLabel k={kd} tag="A" title="Agent detail sheet" sub="Bottom sheet (~72%) over the dimmed progress tree. Prompt collapses to 2 lines; Return is a scrollable report card with Copy return + Open full transcript. The failed variant (right) carries the error behind a thin danger hairline — the rest of the sheet stays calm." />
        <div style={{ display:'flex', gap:56, alignItems:'flex-start' }}>
          <div style={{ flexShrink:0 }}>
            <IOSDevice dark width={402} height={874}>
              <FrameADetail k={kd}/>
            </IOSDevice>
          </div>
          {/* failed variant — detached smaller sheet */}
          <div style={{ paddingTop:8 }}>
            <SpecLabel k={kd}>failed detail · variant</SpecLabel>
            <div style={{ width:352, height:620, borderRadius:'20px 20px 16px 16px', overflow:'hidden', border:`1px solid ${kd.hair}` }}>
              <AgentDetailSheet
                k={kd}
                status="fail"
                label="test auth.session.expiry"
                caption="phase · checks — 19s — agent-06"
                prompt="Run the auth session-expiry assertion suite against the patched gateway and report pass/fail with the failing assertion detail and the token state at failure."
              />
            </div>
          </div>
        </div>
      </div>

      {/* FRAME B — journal (terminal) */}
      <div>
        <DeviceLabel k={kd} tag="B" title="Finished run — journal review" sub="Terminal header shows ✓ · 34 agents · 6m 32s. Pinned Final return (mono, collapsed to 6 lines). [Phases | Journal] toggle → Journal: every agent() call with index, return preview, glyph and duration. Failed rows show the error's first line in danger. Tap any row to reopen the detail sheet." />
        <div style={{ flexShrink:0, width:402 }}>
          <IOSDevice dark width={402} height={874}>
            <FrameBJournal k={kd}/>
          </IOSDevice>
        </div>
      </div>

      {/* FRAME B — light lock */}
      <div>
        <DeviceLabel k={kd} tag="B · light" title="Journal — light theme lock" sub="The journal is a key review surface — locked on the light tokens (base #FAF9F7, surface #FFFFFF, accent #C15F3C)." />
        <div style={{ flexShrink:0, width:402 }}>
          <IOSDevice width={402} height={874}>
            <FrameBJournal k={kl}/>
          </IOSDevice>
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
//  SCREEN 3 · Desktop — workflow monitoring as a docked panel
//  Reuses desktop-core.jsx (Window chrome via k, icon set I, Dot).
// ════════════════════════════════════════════════════════════

// window chrome parameterised by k (so the light lock recolours too)
function DeskWindow({ k, w = 1300, h = 820, children }) {
  return (
    <div style={{ width:w, height:h, background:k.base, border:`1px solid ${k.hair}`, borderRadius:12, overflow:'hidden', display:'flex', flexDirection:'column', boxShadow: k.dark ? '0 40px 100px -30px rgba(0,0,0,0.7)' : '0 40px 100px -34px rgba(120,110,98,0.35)' }}>
      <div style={{ height:38, flexShrink:0, background:k.base, borderBottom:`1px solid ${k.hair}`, display:'flex', alignItems:'center', gap:10, padding:'0 12px' }}>
        <div style={{ display:'flex', gap:8, width:54 }}>
          <span style={{ width:12, height:12, borderRadius:999, background:'#ED6A5E' }}/>
          <span style={{ width:12, height:12, borderRadius:999, background:'#F4BE4F' }}/>
          <span style={{ width:12, height:12, borderRadius:999, background:'#61C554' }}/>
        </div>
        <span style={{ fontFamily:k.ui, fontSize:12.5, color:k.muted, fontWeight:500 }}>cc-pocket — acme-web</span>
        <span style={{ flex:1 }}/>
      </div>
      <div style={{ flex:1, minHeight:0, display:'flex' }}>{children}</div>
    </div>
  );
}

// ── sidebar (~240) ────────────────────────────────────────────
function DeskSidebar({ k }) {
  const { I } = window;
  const threads = [
    { t:'Ship 2.4', active:true, sub:'workflow running' },
    { t:'Flaky billing tests', sub:'2h ago' },
    { t:'Migrate auth to v3', sub:'yesterday' },
    { t:'Perf budget audit', sub:'2d ago' },
    { t:'Docs: gateway retries', sub:'3d ago' },
  ];
  return (
    <div style={{ width:240, flexShrink:0, background:k.base, borderRight:`1px solid ${k.hair}`, display:'flex', flexDirection:'column' }}>
      <div style={{ padding:'12px 12px 10px' }}>
        <div className="wf-press" style={{ display:'flex', alignItems:'center', gap:8, height:34, padding:'0 10px', borderRadius:9, background:k.accTint, border:`1px solid ${k.accBd}`, cursor:'pointer' }}>
          {I.plus(k.accent,15)}
          <span style={{ fontFamily:k.ui, fontSize:13, fontWeight:600, color:k.accent }}>New chat</span>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:k.mono, fontSize:10.5, color:k.accent, opacity:0.7 }}>⌘N</span>
        </div>
      </div>
      <div style={{ padding:'0 12px 8px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, height:32, padding:'0 10px', borderRadius:8, border:`1px solid ${k.hair}`, background:k.surface }}>
          {I.search(k.muted,14)}
          <span style={{ fontFamily:k.ui, fontSize:12.5, color:k.muted }}>Search…</span>
        </div>
      </div>
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'6px 8px' }}>
        <div style={{ fontFamily:k.ui, fontSize:10.5, fontWeight:600, letterSpacing:'0.06em', textTransform:'uppercase', color:k.muted, padding:'6px 8px 4px' }}>Recent</div>
        {threads.map((th,i)=>(
          <div key={i} className="wf-press" style={{ display:'flex', alignItems:'center', gap:9, minHeight:40, padding:'0 10px', borderRadius:9, cursor:'pointer', background: th.active ? k.surface : 'transparent', border: th.active ? `1px solid ${k.hair}` : '1px solid transparent' }}>
            <span style={{ display:'flex', flexShrink:0 }}>{I.bubble(th.active ? k.accent : k.muted,14)}</span>
            <div style={{ flex:1, minWidth:0 }}>
              <div style={{ fontFamily:k.ui, fontSize:13, fontWeight: th.active ? 600 : 500, color: th.active ? k.text : k.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{th.t}</div>
              <div style={{ fontFamily: th.active ? k.mono : k.ui, fontSize:10.5, color: th.active ? k.accent : k.muted, marginTop:1, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{th.sub}</div>
            </div>
            {th.active && <span style={{ width:6, height:6, borderRadius:999, background:k.accent, flexShrink:0, boxShadow:`0 0 6px ${k.accent}99` }}/>}
          </div>
        ))}
        <div style={{ fontFamily:k.ui, fontSize:10.5, fontWeight:600, letterSpacing:'0.06em', textTransform:'uppercase', color:k.muted, padding:'14px 8px 4px' }}>Projects</div>
        {['acme-web','payments-svc'].map((p,i)=>(
          <div key={i} className="wf-press" style={{ display:'flex', alignItems:'center', gap:9, minHeight:36, padding:'0 10px', borderRadius:9, cursor:'pointer' }}>
            {I.folder(k.muted,15)}
            <span style={{ fontFamily:k.ui, fontSize:13, color:k.sec }}>{p}</span>
          </div>
        ))}
      </div>
      <div style={{ borderTop:`1px solid ${k.hair}`, padding:'10px 12px', display:'flex', alignItems:'center', gap:9 }}>
        <span style={{ width:26, height:26, borderRadius:999, background:k.raised, border:`1px solid ${k.hair}`, display:'flex', alignItems:'center', justifyContent:'center', fontFamily:k.ui, fontSize:11, fontWeight:600, color:k.sec, flexShrink:0 }}>DL</span>
        <span style={{ flex:1, fontFamily:k.ui, fontSize:12.5, color:k.sec }}>Dana Lee</span>
        {I.gear(k.muted,16)}
      </div>
    </div>
  );
}

// ── dense workflow card (chat stream) ─────────────────────────
function DenseWorkflowCard({ k, terminal, failures, active }) {
  const { LanesGlyph, CheckGlyph, PulseDot, PhaseBar, FailChip, ChevWF } = window;
  const phases = terminal ? ['done','done','done','done'] : ['done','active','pending','pending'];
  return (
    <div className="wf-press wf-hovcard" style={{
      position:'relative', background: active ? k.accTint : k.surface,
      border:`1px solid ${active ? k.accBd : k.hair}`, borderRadius:11, cursor:'pointer',
      padding:'10px 11px', maxWidth:440,
    }}>
      <div style={{ display:'flex', alignItems:'center', gap:10 }}>
        <div style={{ width:24, height:24, borderRadius:6, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background: terminal ? k.okTint : k.accTint, border:`1px solid ${terminal ? k.okBd : k.accBd}` }}>
          {terminal ? <CheckGlyph c={k.success} s={13}/> : <LanesGlyph c={k.accent} s={14}/>}
        </div>
        <span style={{ flex:1, minWidth:0, fontFamily:k.ui, fontSize:14, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.1 }}>release-pipeline</span>
        {!terminal
          ? <><PulseDot k={k} s={6}/><span style={{ fontFamily:k.mono, fontSize:12, color:k.sec, flexShrink:0 }}>3m 12s</span></>
          : <span style={{ fontFamily:k.mono, fontSize:12, color:k.muted, flexShrink:0 }}>6m 32s</span>}
        <span className={active ? '' : 'wf-hovchev'} style={{ display:'flex', flexShrink:0, marginLeft:2 }}><ChevWF d="right" c={active ? k.accent : k.muted} s={15}/></span>
      </div>
      <PhaseBar k={k} phases={phases}/>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginTop:9, fontFamily:k.mono, fontSize:11.5, color:k.sec, whiteSpace:'nowrap', overflow:'hidden' }}>
        {terminal ? (
          <><span style={{ color:k.muted }}>34 agents · 4 phases · 6m 32s</span>{failures && <FailChip k={k} n={2}/>}</>
        ) : (
          <><span>phase 2/4 · <span style={{ color:k.text }}>analyze</span></span><span style={{ color:k.muted }}>·</span><span style={{ color:k.muted }}>12/34 agents</span><FailChip k={k} n={2}/></>
        )}
      </div>
    </div>
  );
}

// ── chat pane ─────────────────────────────────────────────────
function DeskChatPane({ k, terminal }) {
  const { I } = window;
  return (
    <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', background:k.base }}>
      {/* header */}
      <div style={{ flexShrink:0, borderBottom:`1px solid ${k.hair}`, padding:'11px 18px', display:'flex', alignItems:'center', gap:12 }}>
        <div style={{ minWidth:0 }}>
          <div style={{ fontFamily:k.ui, fontSize:14, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>Ship 2.4</div>
          <div style={{ fontFamily:k.mono, fontSize:11, color:k.muted, marginTop:2, display:'flex', alignItems:'center', gap:6 }}>{I.branch(k.muted,12)} acme-web · main</div>
        </div>
        <span style={{ flex:1 }}/>
        {I.dots(k.muted,16)}
      </div>
      {/* body */}
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'20px 18px' }}>
        <div style={{ maxWidth:560 }}>
          <div style={{ fontFamily:k.ui, fontSize:10.5, fontWeight:600, letterSpacing:'0.05em', color:k.muted, marginBottom:6 }}>YOU</div>
          <div style={{ fontFamily:k.ui, fontSize:14, lineHeight:1.55, color:k.text }}>Ship the 2.4 release — run the full pipeline across every affected module.</div>

          <div style={{ height:1, background:k.hair, margin:'18px 0' }}/>

          <div style={{ fontFamily:k.ui, fontSize:14, lineHeight:1.55, color:k.text }}>
            Starting the <b style={{ fontWeight:600 }}>release-pipeline</b> workflow — I docked it to the right so you can watch every agent while we keep talking.
          </div>
          <div style={{ marginTop:14 }}>
            <DenseWorkflowCard k={k} terminal={terminal} failures active/>
          </div>
          {!terminal ? (
            <div style={{ marginTop:14, fontFamily:k.ui, fontSize:14, lineHeight:1.55, color:k.text }}>
              Analyzing 34 modules — two auth checks came back red, I'm tracking them in the panel
              <span className="wf-caret" style={{ display:'inline-block', width:6, height:14, background:k.accent, borderRadius:1, verticalAlign:'text-bottom', marginLeft:2 }}/>
            </div>
          ) : (
            <div style={{ marginTop:14, fontFamily:k.ui, fontSize:14, lineHeight:1.55, color:k.text }}>
              Done — <b style={{ fontWeight:600 }}>v2.4.0</b> is published. 32 of 34 agents passed; the two auth failures were non-blocking and are captured in the run journal.
            </div>
          )}
        </div>
      </div>
      {/* composer */}
      <div style={{ flexShrink:0, borderTop:`1px solid ${k.hair}`, padding:'12px 18px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:10, background:k.surface, border:`1px solid ${k.hair}`, borderRadius:12, padding:'0 12px', minHeight:44, maxWidth:560 }}>
          {I.paperclip(k.muted,17)}
          <span style={{ flex:1, fontFamily:k.ui, fontSize:13.5, color:k.muted }}>Reply to Claude…</span>
          <span style={{ width:30, height:30, borderRadius:8, background:k.accent, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.send(k.onAcc,16)}</span>
        </div>
      </div>
    </div>
  );
}

// ── docked panel: dense agent row w/ hover + in-place accordion ─
function DeskAgentRow({ k, row, forceHover, defaultOpen }) {
  const { useState } = React;
  const { CheckGlyph, XGlyph, PulseDot, HollowDot, ChevWF } = window;
  const [hov, setHov] = useState(false);
  const [open, setOpen] = useState(!!defaultOpen);
  const showHov = hov || forceHover;
  let glyph;
  if (row.status === 'run') glyph = <PulseDot k={k} s={6}/>;
  else if (row.status === 'done') glyph = <CheckGlyph c={k.success} s={12}/>;
  else if (row.status === 'fail') glyph = <XGlyph c={k.danger} s={12} w={2}/>;
  else glyph = <HollowDot k={k} s={7}/>;
  const canOpen = row.status === 'done' || row.status === 'fail';

  return (
    <div>
      <div
        onMouseEnter={()=>setHov(true)} onMouseLeave={()=>setHov(false)}
        onClick={()=>canOpen && setOpen(o=>!o)}
        style={{ display:'flex', alignItems:'center', gap:9, minHeight:28, padding:'0 8px', borderRadius:7, cursor: canOpen ? 'pointer' : 'default',
          background: showHov ? k.raised : 'transparent', transform: showHov ? 'translateX(1px)' : 'none', transition:'background .12s, transform .12s' }}>
        <span style={{ width:14, display:'flex', justifyContent:'center', flexShrink:0 }}>{glyph}</span>
        <span style={{ flex:1, minWidth:0, fontFamily:k.ui, fontSize:13, color: row.status==='queued' ? k.muted : k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{row.label}</span>
        {canOpen && (showHov || open) && <span style={{ display:'flex', flexShrink:0, transform: open ? 'rotate(90deg)':'none', transition:'transform .15s' }}><ChevWF d="right" c={k.muted} s={12}/></span>}
        <span style={{ fontFamily:k.mono, fontSize:11, color: row.status==='queued' ? k.muted : k.sec, flexShrink:0 }}>{row.dur}</span>
      </div>
      {open && canOpen && (
        <div style={{ margin:'5px 4px 8px 22px', background:k.base, border:`1px solid ${row.status==='fail' ? k.danBd : k.hair}`, borderRadius:9, overflow:'hidden' }}>
          <div className="wf-hovcard" style={{ display:'flex', alignItems:'center', gap:6, padding:'7px 11px 0' }}>
            <span style={{ fontFamily:k.mono, fontSize:9, letterSpacing:'0.13em', textTransform:'uppercase', color: row.status==='fail' ? k.danger : k.muted }}>{row.status==='fail' ? 'error' : 'return'}</span>
            <span style={{ flex:1 }}/>
            <span className="wf-hovchev wf-press" style={{ display:'inline-flex', cursor:'pointer' }}><CopyGlyph c={k.sec} s={11}/></span>
          </div>
          <div style={{ maxHeight:150, overflowY:'auto', padding:'6px 11px 9px' }}>
            {row.status==='fail' ? <ReturnError k={k}/> : <ReturnSuccess k={k}/>}
          </div>
        </div>
      )}
    </div>
  );
}

function DeskPhaseSection({ k, phase, defaultOpen, forceHoverIdx, expandIdx }) {
  const { useState } = React;
  const { CheckGlyph, PulseDot, HollowDot, ChevWF, FailChip } = window;
  const [open, setOpen] = useState(defaultOpen);
  const isPending = phase.status==='pending', isActive = phase.status==='active', isDone = phase.status==='done';
  let sg;
  if (isActive) sg = <PulseDot k={k} s={6}/>; else if (isDone) sg = <CheckGlyph c={k.success} s={12}/>; else sg = <HollowDot k={k} s={7}/>;
  const failed = phase.failed || 0;
  const pinnedFails = isDone && failed>0 && !open;
  return (
    <div style={{ borderTop:`1px solid ${k.hair}` }}>
      <div onClick={()=>!isPending && setOpen(o=>!o)} className="wf-press" style={{ display:'flex', alignItems:'center', gap:8, minHeight:38, padding:'0 8px', cursor:isPending?'default':'pointer', opacity:isPending?0.6:1 }}>
        <span style={{ display:'flex', flexShrink:0, opacity:isPending?0:1 }}><ChevWF d={open?'down':'right'} c={k.muted} s={12}/></span>
        <span style={{ width:14, display:'flex', justifyContent:'center', flexShrink:0 }}>{sg}</span>
        <span style={{ fontFamily:k.ui, fontSize:13, fontWeight:500, color:isPending?k.muted:k.text, flexShrink:0 }}>{phase.name}</span>
        {failed>0 && <FailChip k={k} n={failed}/>}
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:k.mono, fontSize:11, color:isPending?k.muted:k.sec, flexShrink:0 }}>{phase.count}</span>
        <span style={{ fontFamily:k.mono, fontSize:11, color:k.muted, flexShrink:0, minWidth:38, textAlign:'right' }}>{phase.timer}</span>
      </div>
      {isDone && !open && (
        <div style={{ paddingBottom:8 }}>
          <div style={{ fontFamily:k.mono, fontSize:11, color:k.muted, padding:'0 8px 2px 42px', display:'flex', alignItems:'center', gap:6 }}><CheckGlyph c={k.success} s={11}/>{phase.summary}</div>
          {pinnedFails && (
            <div style={{ padding:'2px 8px 0 30px' }}>
              {phase.rows.filter(r=>r.status==='fail').map((r,i)=>(
                <DeskAgentRow key={i} k={k} row={r} defaultOpen={expandIdx==='pinned' && i===0}/>
              ))}
            </div>
          )}
        </div>
      )}
      {isPending && <div style={{ fontFamily:k.mono, fontSize:11, color:k.muted, padding:'0 8px 9px 42px', opacity:0.6 }}>{phase.summary}</div>}
      {open && !isPending && (
        <div style={{ padding:'2px 8px 9px 30px' }}>
          {phase.rows.map((r,i)=>(
            r.queued
              ? <DeskQueuedRow key={i} k={k} n={r.n}/>
              : <DeskAgentRow key={i} k={k} row={r} forceHover={i===forceHoverIdx} defaultOpen={i===expandIdx}/>
          ))}
        </div>
      )}
    </div>
  );
}

function DeskQueuedRow({ k, n }) {
  const { useState } = React;
  const { HollowDot, ChevWF } = window;
  const [open, setOpen] = useState(false);
  return (
    <div>
      <div onClick={()=>setOpen(o=>!o)} className="wf-press" style={{ display:'flex', alignItems:'center', gap:9, minHeight:28, padding:'0 8px', borderRadius:7, cursor:'pointer' }}>
        <span style={{ width:14, display:'flex', justifyContent:'center', flexShrink:0 }}><HollowDot k={k} s={7}/></span>
        <span style={{ flex:1, fontFamily:k.ui, fontSize:13, color:k.sec }}>+ {n} queued</span>
        <span style={{ display:'flex', flexShrink:0, transform:open?'rotate(180deg)':'none', transition:'transform .2s' }}><ChevWF d="down" c={k.muted} s={12}/></span>
      </div>
      {open && ['scan module-config','scan module-search','scan module-notify'].map((l,i)=>(
        <div key={i} style={{ display:'flex', alignItems:'center', gap:9, minHeight:26, padding:'0 8px' }}>
          <span style={{ width:14, display:'flex', justifyContent:'center', flexShrink:0 }}><HollowDot k={k} s={7}/></span>
          <span style={{ flex:1, fontFamily:k.ui, fontSize:13, color:k.muted }}>{l}</span>
          <span style={{ fontFamily:k.mono, fontSize:11, color:k.muted }}>queued</span>
        </div>
      ))}
    </div>
  );
}

// ── docked workflow panel (~360) ──────────────────────────────
function DeskWorkflowPanel({ k, terminal }) {
  const { LanesGlyph, CheckGlyph, PulseDot, PhaseBar, FailChip, ChevWF, XGlyph } = window;
  const { useState } = React;
  const [frOpen, setFrOpen] = useState(false);
  const phases = terminal
    ? [
        { name:'resolve', status:'done', count:'6/6', timer:'48s', summary:'6 agents · 48s', failed:0, rows:[] },
        { name:'analyze', status:'done', count:'12/12', timer:'2m 40s', summary:'12 agents · 2m 40s', failed:0, rows:[] },
        { name:'checks',  status:'done', count:'10/12', timer:'1m 40s', failed:2, summary:'10 passed · 2 failed · 1m 40s',
          rows:[ { status:'fail', label:'test auth.session.expiry', dur:'19s' }, { status:'fail', label:'test auth.token.refresh', dur:'12s' } ] },
        { name:'package', status:'done', count:'8/8', timer:'58s', summary:'8 agents · 58s', failed:0, rows:[] },
      ]
    : [
        { name:'resolve', status:'done', count:'6/6', timer:'48s', summary:'6 agents · 48s', failed:0, rows:[] },
        { name:'analyze', status:'active', count:'8/12', timer:'2m 24s', failed:0, rows:[
            { status:'run', label:'scan module-auth', dur:'42s' },
            { status:'run', label:'scan module-billing', dur:'38s' },
            { status:'run', label:'scan module-gateway', dur:'31s' },
            { status:'done', label:'fix module-payments', dur:'1m 08s' },
            { status:'done', label:'scan module-core', dur:'1m 12s' },
            { status:'done', label:'lint module-ui', dur:'44s' },
            { queued:true, n:18 },
        ] },
        { name:'checks',  status:'done', count:'10/12', timer:'1m 40s', failed:2, summary:'10 passed · 2 failed · 1m 40s',
          rows:[ { status:'fail', label:'test auth.session.expiry', dur:'19s' }, { status:'fail', label:'test auth.token.refresh', dur:'12s' } ] },
        { name:'package', status:'pending', count:'0/8', timer:'—', summary:'8 agents queued', failed:0, rows:[] },
      ];
  const barPhases = terminal ? ['done','done','done','done'] : ['done','active','done','pending'];

  return (
    <div style={{ width:360, flexShrink:0, borderLeft:`1px solid ${k.hair}`, background:k.base, display:'flex', flexDirection:'column' }}>
      {/* header */}
      <div style={{ flexShrink:0, borderBottom:`1px solid ${k.hair}`, padding:'12px 14px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:9 }}>
          <div style={{ width:24, height:24, borderRadius:6, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center', background: terminal ? k.okTint : k.accTint, border:`1px solid ${terminal ? k.okBd : k.accBd}` }}>
            {terminal ? <CheckGlyph c={k.success} s={13}/> : <LanesGlyph c={k.accent} s={14}/>}
          </div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{ fontFamily:k.ui, fontSize:14, fontWeight:600, color:k.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', letterSpacing:-0.1 }}>release-pipeline</div>
            <div style={{ fontFamily:k.mono, fontSize:11, color:k.muted, marginTop:1 }}>wf_8f3a21…{terminal && ' · 34 agents'}</div>
          </div>
          {!terminal ? (
            <div style={{ display:'flex', alignItems:'center', gap:6, flexShrink:0, background:k.accTint, border:`1px solid ${k.accBd}`, borderRadius:999, padding:'4px 10px 4px 8px' }}>
              <PulseDot k={k} s={6} glow={false}/><span style={{ fontFamily:k.mono, fontSize:11.5, color:k.accent, fontWeight:500 }}>3m 12s</span>
            </div>
          ) : (
            <div style={{ display:'flex', alignItems:'center', gap:6, flexShrink:0, background:k.okTint, border:`1px solid ${k.okBd}`, borderRadius:999, padding:'4px 10px 4px 8px' }}>
              <CheckGlyph c={k.success} s={12}/><span style={{ fontFamily:k.mono, fontSize:11.5, color:k.success, fontWeight:500 }}>6m 32s</span>
            </div>
          )}
          <span className="wf-press" style={{ display:'flex', flexShrink:0, cursor:'pointer', color:k.muted, marginLeft:2 }}>{window.I.x(k.muted,16)}</span>
        </div>
        <PhaseBar k={k} phases={barPhases}/>
        <div style={{ display:'flex', alignItems:'center', gap:7, marginTop:10, fontFamily:k.mono, fontSize:11.5, color:k.sec }}>
          {terminal ? (
            <><span style={{ color:k.muted }}>4 phases · 34 agents</span><FailChip k={k} n={2}/></>
          ) : (
            <><span>phase 2/4 · <span style={{ color:k.text }}>analyze</span></span><span style={{ color:k.muted }}>·</span><span style={{ color:k.muted }}>12/34</span><FailChip k={k} n={2}/></>
          )}
        </div>
      </div>

      {/* tree */}
      <div style={{ flex:1, minHeight:0, overflowY:'auto', padding:'0 8px 12px' }}>
        {phases.map((p,i)=>(
          <DeskPhaseSection key={p.name} k={k} phase={p}
            defaultOpen={p.status==='active' || (terminal && p.name==='checks')}
            forceHoverIdx={!terminal && p.status==='active' ? 1 : -1}
            expandIdx={!terminal && p.status==='active' ? 3 : (terminal && p.name==='checks' ? 0 : -1)}
          />
        ))}
        <div style={{ borderTop:`1px solid ${k.hair}` }}/>
      </div>

      {/* terminal: pinned Final return */}
      {terminal && (
        <div style={{ flexShrink:0, borderTop:`1px solid ${k.hair}`, background:k.base, padding:'10px 12px 12px' }}>
          <div className="wf-hovcard" style={{ background:k.base, border:`1px solid ${k.hair}`, borderRadius:10, overflow:'hidden' }}>
            <div style={{ display:'flex', alignItems:'center', gap:6, padding:'8px 11px 0' }}>
              <span style={{ fontFamily:k.mono, fontSize:9, letterSpacing:'0.13em', textTransform:'uppercase', color:k.muted }}>Final return</span>
              <span style={{ flex:1 }}/>
              <span className="wf-hovchev wf-press" style={{ display:'inline-flex', alignItems:'center', gap:5, cursor:'pointer' }}><CopyGlyph c={k.sec} s={11}/><span style={{ fontFamily:k.mono, fontSize:10, color:k.sec }}>Copy</span></span>
            </div>
            <div style={{ position:'relative', padding:'6px 11px 8px' }}>
              <pre style={{ margin:0, fontFamily:k.mono, fontSize:11.5, lineHeight:1.65, color:k.sec, whiteSpace:'pre-wrap' }}>
{`release `}<span style={{ color:k.success }}>2.4.0 published</span>{`
  npm · 12 packages · 4.2 MB
  checks: 32 passed · `}<span style={{ color:k.danger }}>2 failed</span>{frOpen ? `\n  commit: 8f3a21c → v2.4.0\n  duration: 6m 32s\n  agents: 34 (32 ok · 2 failed)` : ''}</pre>
              {!frOpen && <div style={{ position:'absolute', left:0, right:0, bottom:0, height:20, background:`linear-gradient(180deg, transparent, ${k.base})` }}/>}
            </div>
            <div style={{ borderTop:`1px solid ${k.hair}`, padding:'7px 11px' }}>
              <span onClick={()=>setFrOpen(o=>!o)} className="wf-press" style={{ cursor:'pointer', fontFamily:k.ui, fontSize:12, fontWeight:600, color:k.accent }}>{frOpen ? 'Collapse' : 'Expand'}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function DeskFrame({ k, terminal }) {
  return (
    <DeskWindow k={k} w={1300} h={824}>
      <DeskSidebar k={k}/>
      <DeskChatPane k={k} terminal={terminal}/>
      <DeskWorkflowPanel k={k} terminal={terminal}/>
    </DeskWindow>
  );
}

function WorkflowDesktopScene() {
  const { wfTokens } = window;
  const kd = wfTokens(true);
  const kl = wfTokens(false);
  return (
    <div style={{ padding:'56px 60px 80px', display:'flex', flexDirection:'column', gap:64, minWidth:'max-content' }}>
      <div style={{ maxWidth:820 }}>
        <div style={{ fontFamily:kd.mono, fontSize:12.5, color:kd.accent, letterSpacing:'0.08em', textTransform:'uppercase' }}>cc-pocket · desktop</div>
        <div style={{ fontFamily:kd.ui, fontSize:32, fontWeight:700, color:'#ECEDEE', marginTop:10, letterSpacing:-0.6 }}>Workflow monitoring — docked right panel</div>
        <div style={{ fontFamily:kd.ui, fontSize:15, lineHeight:1.55, color:'#9BA1A6', marginTop:12 }}>
          The chat stream keeps only the dense Workflow card; clicking it docks a persistent ~360px panel so dozens of agents stay in view while the conversation continues. Agent rows expand in place into an accordion report — no third navigation level inside the panel.
        </div>
      </div>

      <div>
        <DeviceLabel k={kd} tag="A" title="Live — docked panel with hover + accordion" sub="Three columns: sidebar · chat pane (card active: accent hairline, chevron toward the panel) · workflow panel. In the panel: one agent row hovered (raise + chevron), one expanded in place into its report block (scroll ≤150px, copy on hover), completed phases collapsed, checks' two failures pinned, a + 18 queued row." />
        <div style={{ flexShrink:0, width:1300 }}>
          <DeskFrame k={kd} terminal={false}/>
        </div>
      </div>

      <div>
        <DeviceLabel k={kd} tag="B" title="Terminal — finished run" sub="Same window after the run: panel header shows ✓ · 34 agents · 6m 32s, the tree is fully in terminal glyphs with the two ✗ rows visible, and a Final return block is pinned to the panel bottom (mono, collapsed, copy on hover). The chat card flips to its terminal state." />
        <div style={{ flexShrink:0, width:1300 }}>
          <DeskFrame k={kd} terminal={true}/>
        </div>
      </div>

      <div>
        <DeviceLabel k={kd} tag="A · light" title="Light theme lock" sub="The live docked panel on the locked light tokens — base #FAF9F7, surface #FFFFFF, raised #F1EFEB, accent #C15F3C." />
        <div style={{ flexShrink:0, width:1300 }}>
          <DeskFrame k={kl} terminal={false}/>
        </div>
      </div>
    </div>
  );
}

if (typeof module !== 'undefined') module.exports = { WorkflowScene, WorkflowJournalScene, WorkflowDesktopScene };
window.WorkflowScene = WorkflowScene;
window.WorkflowJournalScene = WorkflowJournalScene;
window.WorkflowDesktopScene = WorkflowDesktopScene;

})();

