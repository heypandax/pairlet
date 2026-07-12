// cc-pocket — desktop · chat pane attachments (drag-over + working state)
// Reuses desktop-core.jsx (T, I, Key, Dot, AgentTag, Window). Denser than mobile.

// ── file-type + media glyphs (dense, ~1.5pt) ──────────────────
function DocG({ c = T.sec, s = 17 }) {
  return <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M8.5 13h7M8.5 16.5h5"/></svg>;
}
function CodeG({ c = T.sec, s = 17 }) {
  return <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M9 8l-4 4 4 4M15 8l4 4-4 4"/></svg>;
}
function PlayTri({ c = '#fff', s = 18 }) {
  return <svg width={s} height={s} viewBox="0 0 20 20" fill={c}><path d="M6 3.5l11 6.5-11 6.5z"/></svg>;
}
function UploadG({ c = T.accent, s = 26 }) {
  return <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 15V4M12 4l-4 4M12 4l4 4"/><path d="M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"/></svg>;
}
function RetryG({ c = T.danger, s = 14 }) {
  return <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M13 8a5 5 0 1 1-1.6-3.7" stroke={c} strokeWidth="1.6" strokeLinecap="round"/><path d="M13 2.4V5.2H10.2" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>;
}
const mmss = (s) => `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;

// ── delivered file chip (in-stream, dense, single line) ───────
function SentFileChip({ name, size, path, kind = 'pdf' }) {
  const G = kind === 'code' ? CodeG : DocG;
  return (
    <div className="da-chip" style={{ position:'relative', display:'inline-flex', alignItems:'center', gap:10, maxWidth:420,
      background:T.surface, border:`1px solid ${T.border}`, borderRadius:9, padding:'7px 34px 7px 8px' }}>
      <span style={{ width:28, height:28, borderRadius:7, flexShrink:0, background:T.base, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}><G c={T.sec} s={16}/></span>
      <span style={{ minWidth:0, display:'flex', alignItems:'baseline', gap:8, whiteSpace:'nowrap', overflow:'hidden' }}>
        <span style={{ fontFamily:T.mono, fontSize:13, color:T.text }}>{name}</span>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted, flexShrink:0 }}>{size}</span>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.accent, minWidth:0, overflow:'hidden', textOverflow:'ellipsis' }}>{path}</span>
      </span>
      <button className="da-x" aria-label="Remove" style={{ all:'unset', cursor:'pointer', position:'absolute', right:8, top:'50%', transform:'translateY(-50%)', width:20, height:20, borderRadius:6, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.x(T.sec,13)}</button>
    </div>
  );
}

// ── delivered video thumb (in-stream, dense) ──────────────────
function SentVideoThumb({ name, size, path, dur = 42 }) {
  return (
    <div style={{ width:220 }}>
      <div className="da-chip" style={{ position:'relative', borderRadius:9, overflow:'hidden', border:`1px solid ${T.border}`, cursor:'pointer' }}>
        <div style={{ width:'100%', aspectRatio:'16 / 9', background:'linear-gradient(150deg,#1b1410 0%,#3a2a20 46%,#7a5238 100%)' }}>
          <div style={{ position:'absolute', left:'30%', top:'28%', width:'46%', height:'46%', transform:'translate(-50%,-50%)', background:'radial-gradient(circle, rgba(255,200,150,0.38) 0%, transparent 68%)' }}/>
        </div>
        <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <div style={{ width:40, height:40, borderRadius:999, background:'rgba(12,10,9,0.5)', backdropFilter:'blur(6px)', WebkitBackdropFilter:'blur(6px)', border:'1px solid rgba(255,255,255,0.28)', display:'flex', alignItems:'center', justifyContent:'center' }}><PlayTri c="#fff" s={17}/></div>
        </div>
        <div style={{ position:'absolute', right:6, bottom:6, background:'rgba(12,10,9,0.66)', borderRadius:5, padding:'2px 6px', fontFamily:T.mono, fontSize:10.5, fontWeight:500, color:'#fff' }}>{mmss(dur)}</div>
        <button className="da-x" aria-label="Remove" style={{ all:'unset', cursor:'pointer', position:'absolute', right:6, top:6, width:22, height:22, borderRadius:6, background:'rgba(8,9,10,0.6)', display:'flex', alignItems:'center', justifyContent:'center' }}>{I.x('#fff',13)}</button>
      </div>
      <div style={{ display:'flex', alignItems:'baseline', gap:8, marginTop:6, whiteSpace:'nowrap', overflow:'hidden' }}>
        <span style={{ fontFamily:T.mono, fontSize:13, color:T.text }}>{name}</span>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted }}>{size}</span>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.accent, minWidth:0, overflow:'hidden', textOverflow:'ellipsis' }}>{path}</span>
      </div>
    </div>
  );
}

// ── pending chip (composer): uploading w/ progress · failed w/ retry ──
function PendingChip({ name, size, kind = 'pdf', state, pct = 0 }) {
  const failed = state === 'failed';
  const G = kind === 'code' ? CodeG : DocG;
  const border = failed ? T.danger : T.border;
  return (
    <div className="da-chip" style={{ position:'relative', display:'inline-flex', alignItems:'center', gap:9, minWidth:190, maxWidth:230,
      background: failed ? 'rgba(229,96,77,0.07)' : T.surface, border:`1px solid ${border}`, borderRadius:9, padding:'7px 30px 8px 8px', overflow:'hidden' }}>
      <span style={{ width:26, height:26, borderRadius:6, flexShrink:0, background: failed ? 'rgba(229,96,77,0.12)' : T.base, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}>
        {failed ? <RetryG c={T.danger} s={15}/> : <G c={T.sec} s={15}/>}
      </span>
      <span style={{ minWidth:0, flex:1 }}>
        <span style={{ display:'block', fontFamily:T.mono, fontSize:13, color: failed ? T.danger : T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</span>
        <span style={{ display:'block', fontFamily:T.mono, fontSize:10.5, color: failed ? T.danger : T.muted, marginTop:1 }}>
          {failed ? 'upload failed · retry' : `${Math.round(pct*100)}%  ·  ${size}`}
        </span>
      </span>
      {/* hover-revealed action button */}
      <button className="da-x" aria-label={failed ? 'Retry' : 'Cancel'} style={{ all:'unset', cursor:'pointer', position:'absolute', right:6, top:6, width:20, height:20, borderRadius:6, background:T.raised, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}>
        {failed ? <RetryG c={T.danger} s={12}/> : I.x(T.sec,12)}
      </button>
      {/* thin linear progress under the chip */}
      {!failed && (
        <span style={{ position:'absolute', left:0, right:0, bottom:0, height:2.5, background:T.border }}>
          <span style={{ position:'absolute', left:0, top:0, bottom:0, width:`${pct*100}%`, background:T.accent, transition:'width .4s ease' }}/>
        </span>
      )}
    </div>
  );
}

// ── composer with pending chips row ───────────────────────────
function ComposerAttach({ chips }) {
  return (
    <div style={{ flexShrink:0, borderTop:`1px solid ${T.border}`, padding:'12px 18px 14px' }}>
      <div style={{ maxWidth:760, margin:'0 auto' }}>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, overflow:'hidden' }}>
          {chips && chips.length > 0 && (
            <div style={{ display:'flex', gap:9, flexWrap:'wrap', padding:'10px 10px 4px' }}>
              {chips.map((c,i)=><PendingChip key={i} {...c}/>)}
            </div>
          )}
          <div style={{ display:'flex', alignItems:'flex-end', gap:9, padding:'8px 10px 8px 12px' }}>
            <span style={{ display:'flex', paddingBottom:5 }}>{I.paperclip(T.sec,17)}</span>
            <div style={{ flex:1, fontFamily:T.ui, fontSize:14, color:T.muted, padding:'6px 0' }}>Add a message…</div>
            <button style={{ all:'unset', cursor:'default', width:34, height:34, borderRadius:999, background:T.base, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', position:'relative' }}>
              <span className="da-spin" style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}><span style={{ width:24, height:24, border:`2px solid ${T.border}`, borderTopColor:T.accent, borderRadius:999 }}/></span>
              {I.send(T.muted,15)}
            </button>
          </div>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:7, paddingLeft:2 }}>
          <span style={{ display:'flex', alignItems:'center', gap:6, fontFamily:T.mono, fontSize:10.5, color:T.muted }}>
            <span style={{ width:5, height:5, borderRadius:999, background:T.accent }}/>uploading 1 of 2 — send waits
          </span>
          <span style={{ flex:1 }}/>
          <Key>⏎</Key><span style={{ fontFamily:T.ui, fontSize:11, color:T.muted }}>send</span>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  DocG, CodeG, PlayTri, UploadG, RetryG, mmss,
  SentFileChip, SentVideoThumb, PendingChip, ComposerAttach,
});
