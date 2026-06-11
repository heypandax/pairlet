// cc-pocket — execution permissions · mode-switch sheet

// ── one mode row in the ladder ────────────────────────────────
function ModeRow({ m, selected, onSelect, last }) {
  const [p, setP] = React.useState(false);
  return (
    <div onClick={onSelect}
      onPointerDown={()=>setP(true)} onPointerUp={()=>setP(false)} onPointerLeave={()=>setP(false)}
      style={{ display:'flex', alignItems:'flex-start', gap:12, padding:'13px 14px', cursor:'pointer',
        borderRadius:12, border:`1px solid ${selected?rgba(m.color,0.5):'transparent'}`,
        background: selected?rgba(m.color,0.10):(p?T.surface:'transparent') }}>
      <span style={{ width:11, height:11, borderRadius:999, background:m.color, marginTop:3, flexShrink:0,
        boxShadow: selected?`0 0 0 3px ${rgba(m.color,0.2)}`:'none' }}/>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:7 }}>
          <span style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text }}>{m.label}</span>
          {m.warn && <Warn c={m.color} s={13}/>}
        </div>
        <div style={{ marginTop:3, fontFamily:T.ui, fontSize:12, lineHeight:'17px', color:T.sec }}>
          <span style={{ fontFamily:T.mono, fontSize:11.5, color:m.color }}>{m.tech}</span>
          <span style={{ color:T.muted }}>&nbsp;·&nbsp;</span>
          auto-allows {m.allows} · still asks {m.asks}
        </div>
      </div>
      {selected && <span style={{ marginTop:2, flexShrink:0 }}><Check c={m.color} s={16}/></span>}
    </div>
  );
}

// ── allow-rules review section ────────────────────────────────
function RulesReview({ rules, onClear, onClearAll }) {
  if (!rules.length) return null;
  return (
    <div style={{ marginTop:18, borderTop:`1px solid ${T.border}`, paddingTop:16 }}>
      <div style={{ display:'flex', alignItems:'center', marginBottom:10 }}>
        <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.6, textTransform:'uppercase', color:T.muted, flex:1 }}>Remembered this session</span>
        <button onClick={onClearAll} className="ep-press" style={{ all:'unset', cursor:'pointer', fontFamily:T.ui, fontSize:12, fontWeight:600, color:T.danger }}>Clear all</button>
      </div>
      <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
        {rules.map((r,i)=>(
          <div key={i} style={{ display:'flex', alignItems:'center', gap:10, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'9px 11px' }}>
            <Check c={T.success} s={13} w={2}/>
            <span style={{ flex:1, fontFamily:T.mono, fontSize:12, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{r}</span>
            <button onClick={()=>onClear(i)} className="ep-press" aria-label="Remove rule"
              style={{ all:'unset', cursor:'pointer', width:24, height:24, borderRadius:999, display:'flex', alignItems:'center', justifyContent:'center', background:T.raised, border:`1px solid ${T.border}`, flexShrink:0 }}>
              <Ex c={T.sec} s={10}/>
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── full mode sheet — step drives the state ───────────────────
function ModeSheet({ current='default', step='list', rules=[], onSelect, onConfirmFull, onCancelFull, onClearRule, onClearAll }) {
  // step: 'list' | 'confirm' | 'switching'
  if (step === 'confirm') {
    const m = MODE.bypass;
    return (
      <Sheet>
        <div style={{ padding:'8px 20px 20px' }}>
          <div style={{ width:46, height:46, borderRadius:13, background:rgba(m.color,0.14), border:`1px solid ${rgba(m.color,0.4)}`, display:'flex', alignItems:'center', justifyContent:'center', marginBottom:16 }}>
            <Warn c={m.color} s={22}/>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:21, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>Enable full auto?</div>
          <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'21px', color:T.sec, marginTop:8 }}>
            Claude will run <span style={{ color:T.text, fontWeight:600 }}>every command without asking</span> — including ones that change or delete files. This session only.
          </div>
          <div style={{ display:'flex', alignItems:'center', gap:7, marginTop:14, fontFamily:T.mono, fontSize:11.5, color:T.muted }}>
            <Branch c={T.muted} s={11}/>~/proj/app/cc-pocket · main
          </div>
          <div style={{ display:'flex', gap:10, marginTop:20 }}>
            <button onClick={onCancelFull} className="ep-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', flex:1, height:52, display:'flex', alignItems:'center', justifyContent:'center', borderRadius:12, border:`1px solid ${T.border}`, color:T.text, fontFamily:T.ui, fontSize:16, fontWeight:600 }}>Cancel</button>
            <button onClick={onConfirmFull} className="ep-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', flex:1.4, height:52, display:'flex', alignItems:'center', justifyContent:'center', gap:7, borderRadius:12, background:m.color, color:'#0E0F11', fontFamily:T.ui, fontSize:15.5, fontWeight:700 }}>
              <Warn c="#0E0F11" s={15}/> Enable full auto
            </button>
          </div>
        </div>
      </Sheet>
    );
  }

  return (
    <Sheet>
      <div style={{ padding:'6px 16px 14px' }}>
        <div style={{ padding:'0 2px 4px' }}>
          <div style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>Execution mode</div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:4 }}>How much should Claude ask before acting?</div>
        </div>

        {step === 'switching' && (
          <div style={{ display:'flex', alignItems:'center', gap:10, margin:'12px 2px 4px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, padding:'10px 12px' }}>
            <Spinner c={T.accent} s={15}/>
            <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Restarting session to apply the new mode…</span>
          </div>
        )}

        <div style={{ display:'flex', flexDirection:'column', gap:4, marginTop:8, opacity: step==='switching'?0.55:1, pointerEvents: step==='switching'?'none':'auto' }}>
          {MODES.map((m,i)=>(
            <ModeRow key={m.key} m={m} selected={current===m.key} onSelect={()=>onSelect&&onSelect(m.key)} last={i===MODES.length-1}/>
          ))}
        </div>

        <RulesReview rules={rules} onClear={onClearRule} onClearAll={onClearAll}/>

        <div style={{ marginTop:16, display:'flex', alignItems:'center', gap:7, fontFamily:T.ui, fontSize:11.5, color:T.muted, padding:'0 2px' }}>
          <Shield c={T.muted} s={13}/> New sessions always start at “Ask each step”.
        </div>
      </div>
    </Sheet>
  );
}

// ── start-session mode picker — opens from “+ New session” ────
// No mode pre-selected; dismissing starts the session at “Ask each step”.
function StartSessionSheet({ step='list', onPick, onConfirmFull, onCancelFull }) {
  // step: 'list' | 'confirm'  — confirm reuses the guarded full-auto step verbatim
  if (step === 'confirm') {
    return <ModeSheet step="confirm" onConfirmFull={onConfirmFull} onCancelFull={onCancelFull}/>;
  }
  return (
    <Sheet>
      <div style={{ padding:'6px 16px 14px' }}>
        <div style={{ padding:'0 2px 4px' }}>
          <div style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>New session</div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, marginTop:4 }}>Start in which mode?</div>
        </div>

        <div style={{ display:'flex', flexDirection:'column', gap:4, marginTop:8 }}>
          {MODES.map((m,i)=>(
            <ModeRow key={m.key} m={m} selected={false} onSelect={()=>onPick&&onPick(m.key)} last={i===MODES.length-1}/>
          ))}
        </div>

        <div style={{ marginTop:16, display:'flex', alignItems:'center', gap:7, fontFamily:T.ui, fontSize:11.5, color:T.muted, padding:'0 2px' }}>
          <Shield c={T.muted} s={13}/> You can change this anytime from the badge.
        </div>
      </div>
    </Sheet>
  );
}

Object.assign(window, { ModeRow, RulesReview, ModeSheet, StartSessionSheet });
