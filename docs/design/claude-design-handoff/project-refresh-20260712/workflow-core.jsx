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
