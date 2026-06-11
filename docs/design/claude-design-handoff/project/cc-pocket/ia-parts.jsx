// cc-pocket — image attachment · parts (tray, composer, sent turn, viewer)

// ── attachment thumbnail in the composer tray ─────────────────
function TrayThumb({ a, onRemove, size = 60 }) {
  const rejected = a.state === 'rejected';
  const compressing = a.state === 'compressing';
  return (
    <div style={{ position:'relative', width:size, flexShrink:0 }}>
      <div style={{ position:'relative', width:size, height:size, borderRadius:10, overflow:'hidden', border:`1px solid ${T.border}` }}>
        <Photo p={a.p} radius={10} style={{ width:'100%', height:'100%', opacity:rejected?0.4:1 }}/>
        {compressing && (
          <>
            <div className="ia-shimmer" style={{ position:'absolute', inset:0 }}/>
            <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center', background:'rgba(8,9,10,0.32)' }}>
              <Ring s={26}/>
            </div>
          </>
        )}
        {rejected && <WarnBadge/>}
      </div>
      {rejected && (
        <div style={{ fontFamily:T.mono, fontSize:9.5, color:T.danger, textAlign:'center', marginTop:3, letterSpacing:0.1 }}>too large</div>
      )}
      {!compressing && <RemoveBadge onClick={onRemove}/>}
    </div>
  );
}

// ── the attached-images tray (above the input row) ────────────
function AttachTray({ items, onRemove, animate }) {
  if (!items.length) return null;
  const rejected = items.filter(i => i.state === 'rejected').length;
  return (
    <div className={animate ? 'ia-tray-anim' : ''} style={{ borderBottom:`1px solid ${T.border}`, padding:'10px 12px 11px' }}>
      <div className="ia-scroll" style={{ display:'flex', gap:9, overflowX:'auto', paddingRight:4 }}>
        {items.map((a, i) => <TrayThumb key={a.id} a={a} onRemove={() => onRemove(a.id)} />)}
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:9 }}>
        <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>
          {items.filter(i=>i.state!=='rejected').length}/4 · compressed on device
        </span>
        {rejected > 0 && <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.danger }}>· {rejected} excluded</span>}
      </div>
    </div>
  );
}

// ── full composer bar (tray + input row) ──────────────────────
function ComposerBar({ items = [], draft = '', onChangeDraft, onAttach, onRemove, onSend, streaming = false, animateTray = false, atMax = false }) {
  const valid = items.filter(i => i.state === 'ready').length;
  const enabled = !streaming && (valid > 0 || draft.trim().length > 0);
  return (
    <div style={{ background:T.surface, borderTop:`1px solid ${T.border}` }}>
      <AttachTray items={items} onRemove={onRemove} animate={animateTray}/>
      <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px' }}>
        <button onClick={onAttach} disabled={atMax} className="ia-press" aria-label="Attach image"
          style={{ all:'unset', boxSizing:'border-box', cursor:atMax?'default':'pointer', width:44, height:44, borderRadius:10, flexShrink:0,
            display:'flex', alignItems:'center', justifyContent:'center', opacity:atMax?0.4:1 }}>
          <AttachIcon c={items.length ? T.accent : T.sec} s={24}/>
        </button>
        <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 14px', minHeight:44 }}>
          <input value={draft} onChange={e=>onChangeDraft && onChangeDraft(e.target.value)}
            onKeyDown={e=>{ if(e.key==='Enter') onSend && onSend(); }}
            placeholder={items.length ? 'Add a message…' : 'Message Claude…'}
            style={{ all:'unset', flex:1, fontFamily:T.ui, fontSize:14.5, color:T.text, padding:'11px 0', minWidth:0 }}/>
        </div>
        <button onClick={onSend} disabled={!enabled && !streaming} className="ia-press" aria-label={streaming?'Stop':'Send'}
          style={{ all:'unset', boxSizing:'border-box', cursor:(enabled||streaming)?'pointer':'default', width:44, height:44, borderRadius:999, flexShrink:0,
            display:'flex', alignItems:'center', justifyContent:'center',
            background:(enabled||streaming)?T.accent:T.base, border:(enabled||streaming)?'none':`1px solid ${T.border}` }}>
          {streaming ? <StopGlyph c="#0E0F11" s={15}/> : <SendArrow c={(enabled)?'#0E0F11':T.muted} s={18}/>}
        </button>
      </div>
    </div>
  );
}

// ── image group inside a sent user turn ───────────────────────
function SentImages({ images, onOpen }) {
  const n = images.length;
  const press = { cursor:'pointer' };
  if (n === 1) {
    const a = images[0];
    const ar = a.ar || 4/3;
    return (
      <div className="ia-press" onClick={()=>onOpen(0)} style={{ width:'62%', maxWidth:240, ...press }}>
        <Photo p={a.p} radius={12} style={{ width:'100%', aspectRatio:String(ar), border:`1px solid ${T.border}` }}/>
      </div>
    );
  }
  // 2-up or 2x2 uniform tiles
  const cols = n === 2 ? 2 : 2;
  return (
    <div style={{ display:'grid', gridTemplateColumns:`repeat(${cols},1fr)`, gap:6, width: n===2 ? '78%' : '70%', maxWidth: n===2?300:260 }}>
      {images.map((a,i)=>(
        <div key={a.id} className="ia-press" onClick={()=>onOpen(i)} style={press}>
          <Photo p={a.p} radius={10} style={{ width:'100%', aspectRatio:'1', border:`1px solid ${T.border}` }}/>
        </div>
      ))}
    </div>
  );
}

// ── a full-width user turn (YOU label, images, text) ──────────
function SentTurn({ turn, onOpen, onRetry }) {
  return (
    <div>
      <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:8 }}>YOU</div>
      {turn.images && turn.images.length > 0 && (
        <div style={{ marginBottom: turn.text ? 10 : 0 }}>
          <SentImages images={turn.images} onOpen={(i)=>onOpen(turn.images, i)}/>
        </div>
      )}
      {turn.text && (
        <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>{turn.text}</div>
      )}
      {turn.failed && (
        <button onClick={onRetry} className="ia-press" style={{ all:'unset', cursor:'pointer', display:'inline-flex', alignItems:'center', gap:7, marginTop:10,
          background:'rgba(229,96,77,0.10)', border:`1px solid ${T.danger}55`, borderRadius:999, padding:'6px 12px' }}>
          <Retry c={T.danger} s={13}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.danger }}>Failed — tap to retry</span>
        </button>
      )}
    </div>
  );
}

// ── assistant markdown turn (left-aligned) ────────────────────
function AssistantTurn({ children }) {
  return <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>{children}</div>;
}

// ── full-screen image viewer ──────────────────────────────────
function Viewer({ images, index, onClose, onIndex }) {
  const [drag, setDrag] = React.useState(0);
  const [zoom, setZoom] = React.useState(false);
  const start = React.useRef(null);
  const a = images[index];
  const ar = a.ar || 4/3;
  const n = images.length;

  const onDown = (e) => { start.current = { x:e.clientX, y:e.clientY }; };
  const onMove = (e) => { if (start.current && !zoom) { const dy = e.clientY - start.current.y; if (dy>0) setDrag(dy); } };
  const onUp = (e) => {
    if (!start.current) return;
    const dx = e.clientX - start.current.x, dy = e.clientY - start.current.y;
    if (!zoom && drag > 90) { onClose(); }
    else if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
      if (dx < 0 && index < n-1) onIndex(index+1);
      if (dx > 0 && index > 0) onIndex(index-1);
    }
    setDrag(0); start.current = null;
  };

  return (
    <div style={{ position:'absolute', inset:0, zIndex:60, background:'#08090A',
      opacity: 1 - Math.min(drag/300, 0.55), touchAction:'none' }}
      onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerLeave={onUp}>
      {/* top bar */}
      <div style={{ position:'absolute', top:0, left:0, right:0, zIndex:3, paddingTop:50, display:'flex', alignItems:'center', height:94, padding:'50px 8px 0' }}>
        <button onClick={onClose} className="ia-press" aria-label="Close" style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <CloseX c={T.text} s={20}/>
        </button>
        <div style={{ flex:1, textAlign:'center' }}>
          {n > 1 && <span style={{ fontFamily:T.mono, fontSize:13, color:T.sec }}>{index+1} / {n}</span>}
        </div>
        <div style={{ width:44 }}/>
      </div>
      {/* image */}
      <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center', padding:'0 16px',
        transform:`translateY(${drag}px)` }}>
        <div onClick={()=>setZoom(z=>!z)} style={{ width: zoom?'112%':'92%', maxWidth: zoom?620:520, transition:'width .22s ease', cursor:'zoom-in' }}>
          <Photo p={a.p} radius={14} style={{ width:'100%', aspectRatio:String(ar), border:`1px solid ${T.border}` }}/>
        </div>
      </div>
      {/* dot indicator */}
      {n > 1 && (
        <div style={{ position:'absolute', bottom:44, left:0, right:0, display:'flex', justifyContent:'center', gap:6, zIndex:3 }}>
          {images.map((_,i)=>(
            <span key={i} onClick={()=>onIndex(i)} style={{ width: i===index?18:6, height:6, borderRadius:999, background: i===index?T.accent:T.border, transition:'width .2s', cursor:'pointer' }}/>
          ))}
        </div>
      )}
      {/* swipe hint */}
      <div style={{ position:'absolute', bottom:18, left:0, right:0, textAlign:'center', zIndex:3, fontFamily:T.ui, fontSize:11, color:T.muted }}>Swipe down to close · tap to zoom</div>
    </div>
  );
}

Object.assign(window, { TrayThumb, AttachTray, ComposerBar, SentImages, SentTurn, AssistantTurn, Viewer });
