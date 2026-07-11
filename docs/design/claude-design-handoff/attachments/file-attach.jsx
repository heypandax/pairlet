// cc-pocket — chat composer · file & video attachment flow
// Extends the image composer: generic files (PDF/CSV/code/Office) + video,
// chunk-uploaded into the paired computer's session workspace inbox.

// ── helpers ───────────────────────────────────────────────────
function middleTrunc(name, head = 3, tail = 5) {
  if (name.length <= head + tail + 1) return name;
  return name.slice(0, head) + '…' + name.slice(name.length - tail);
}
function fmtSize(mb) {
  return mb >= 1 ? `${mb.toFixed(mb < 10 ? 1 : 0)} MB` : `${Math.round(mb * 1024)} KB`;
}

// ── file-type glyphs (1.5pt line) ─────────────────────────────
function DocGlyph({ c = T.sec, s = 20 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
      <path d="M14 3v5h5"/>
      <path d="M8.5 13h7M8.5 16.5h5"/>
    </svg>
  );
}
function TableGlyph({ c = T.sec, s = 20 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="5" width="16" height="14" rx="2"/>
      <path d="M4 9.5h16M4 14h16M9.5 5v14"/>
    </svg>
  );
}
function CodeGlyph({ c = T.sec, s = 20 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 8l-4 4 4 4M15 8l4 4-4 4"/>
    </svg>
  );
}
const GLYPH = { pdf: DocGlyph, doc: DocGlyph, csv: TableGlyph, code: CodeGlyph };

// ── attach-sheet option glyphs (1.5pt line) ───────────────────
function PhotoOptGlyph({ c = T.text, s = 26 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 26 26" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3.5" y="4.5" width="19" height="17" rx="3"/>
      <circle cx="9.2" cy="10" r="1.7"/>
      <path d="M4 18.5l5-5 3.3 3.3 3.5-3.8 5.7 6"/>
    </svg>
  );
}
function FileOptGlyph({ c = T.text, s = 26 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 26 26" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M15 3.5H8A2.5 2.5 0 0 0 5.5 6v14A2.5 2.5 0 0 0 8 22.5h10a2.5 2.5 0 0 0 2.5-2.5V9z"/>
      <path d="M15 3.5V9h5.5"/>
    </svg>
  );
}
function VideoOptGlyph({ c = T.text, s = 26 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 26 26" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3.5" y="6" width="14" height="14" rx="3"/>
      <path d="M17.5 11l5-3v10l-5-3z"/>
    </svg>
  );
}

// small inbox/tray glyph for the sheet caption
function InboxGlyph({ c = T.muted, s = 13 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2.5 9.5L4 3.5h8l1.5 6"/>
      <path d="M2.5 9.5v3h11v-3h-3a2 2 0 0 1-4 0z"/>
    </svg>
  );
}

// ── progress ring wrapping a file-type icon ───────────────────
function ChipRing({ pct = 0, state, kind, size = 30 }) {
  const stroke = 2.4;
  const r = (size - stroke) / 2;
  const C = 2 * Math.PI * r;
  const Glyph = GLYPH[kind] || DocGlyph;
  const track = 'rgba(255,255,255,0.12)';
  const on = state === 'failed' ? T.danger : T.accent;
  const dash = state === 'uploading' ? pct : (state === 'failed' ? 1 : 0);
  const iconC = state === 'failed' ? T.danger : (state === 'queued' ? T.muted : T.text);
  return (
    <div style={{ position:'relative', width:size, height:size, flexShrink:0 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ transform:'rotate(-90deg)' }}>
        <circle cx={size/2} cy={size/2} r={r} stroke={track} strokeWidth={stroke} fill="none"
          strokeDasharray={state==='queued' ? '2 4' : undefined} />
        {dash > 0 && (
          <circle cx={size/2} cy={size/2} r={r} stroke={on} strokeWidth={stroke} fill="none" strokeLinecap="round"
            strokeDasharray={C} strokeDashoffset={C * (1 - dash)}
            style={{ transition:'stroke-dashoffset .4s ease' }} />
        )}
      </svg>
      <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}>
        {state === 'failed'
          ? <Retry c={T.danger} s={14}/>
          : <Glyph c={iconC} s={15}/>}
      </div>
    </div>
  );
}

// ── one pending-attachment chip ───────────────────────────────
function FileChip({ f, onCancel, onRetry }) {
  const failed = f.state === 'failed';
  const border = failed ? `${T.danger}66` : T.border;
  const bg = failed ? 'rgba(229,96,77,0.08)' : T.raised;
  const nameC = failed ? T.danger : T.text;
  const cap =
    f.state === 'uploading' ? `${Math.round(f.pct * 100)}% · ${fmtSize(f.size)}` :
    f.state === 'queued'    ? `queued · ${fmtSize(f.size)}` :
                              `upload failed`;
  const capC = failed ? T.danger : T.muted;
  return (
    <div style={{ position:'relative', flexShrink:0, width:117 }}>
      <div
        onClick={failed ? onRetry : undefined}
        className={failed ? 'fa-press' : ''}
        style={{
          display:'flex', alignItems:'center', gap:8, padding:'8px 9px',
          background:bg, border:`1px solid ${border}`, borderRadius:12,
          cursor: failed ? 'pointer' : 'default',
        }}>
        <ChipRing pct={f.pct} state={f.state} kind={f.kind}/>
        <div style={{ minWidth:0, flex:1 }}>
          <div style={{ fontFamily:T.mono, fontSize:11.5, fontWeight:500, color:nameC, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'clip' }}>
            {middleTrunc(f.name)}
          </div>
          <div style={{ fontFamily:T.mono, fontSize:10, color:capC, marginTop:2, whiteSpace:'nowrap', display:'flex', alignItems:'center', gap:3 }}>
            {failed && <Retry c={T.danger} s={10}/>}
            {failed ? 'retry' : cap}
          </div>
        </div>
      </div>
      {/* cancel / dismiss */}
      <button onClick={(e)=>{ e.stopPropagation(); onCancel && onCancel(); }} aria-label={failed ? 'Dismiss' : 'Cancel upload'}
        style={{ all:'unset', cursor:'pointer', position:'absolute', top:-7, right:-7, width:22, height:22, borderRadius:999,
          background:T.raised, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', zIndex:3 }}>
        <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2.4 2.4l5.2 5.2M7.6 2.4L2.4 7.6" stroke={T.sec} strokeWidth="1.6" strokeLinecap="round"/></svg>
      </button>
    </div>
  );
}

// ── pending-attachment strip (above the input row) ────────────
function ChipStrip({ files, onCancel, onRetry }) {
  if (!files.length) return null;
  const uploading = files.filter(f => f.state === 'uploading').length;
  const queued = files.filter(f => f.state === 'queued').length;
  const active = uploading + queued;
  const total = files.length;
  const done = files.filter(f => f.state === 'done').length;
  return (
    <div style={{ padding:'11px 12px 12px', borderBottom:`1px solid ${T.border}` }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:9, padding:'0 2px' }}>
        <span className="fa-spin" style={{ display:'flex' }}><Ring s={14}/></span>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec }}>
          uploading {active} of {total}…
        </span>
      </div>
      <div className="fa-scroll" style={{ display:'flex', gap:8, overflowX:'auto', paddingTop:5, paddingRight:4 }}>
        {files.map(f => <FileChip key={f.id} f={f} onCancel={()=>onCancel(f.id)} onRetry={()=>onRetry(f.id)}/>)}
      </div>
    </div>
  );
}

// ── the attach sheet (anchored above the composer) ────────────
function AttachSheet({ open, onPick, pressed, setPressed }) {
  if (!open) return null;
  const OPTS = [
    { k:'photo', label:'Photo', Glyph:PhotoOptGlyph },
    { k:'file',  label:'File',  Glyph:FileOptGlyph },
    { k:'video', label:'Video', Glyph:VideoOptGlyph },
  ];
  return (
    <div className="fa-sheet-anim" style={{ padding:'0 12px 10px' }}>
      <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:18, padding:'14px 12px 13px',
        boxShadow:'0 12px 34px rgba(0,0,0,0.5)' }}>
        <div style={{ display:'flex', gap:9 }}>
          {OPTS.map(({ k, label, Glyph }) => {
            const on = pressed === k;
            return (
              <button key={k}
                onPointerDown={()=>setPressed(k)} onPointerUp={()=>setPressed(null)} onPointerLeave={()=>setPressed(null)}
                onClick={()=>onPick(k)}
                style={{ all:'unset', cursor:'pointer', flex:1, minHeight:80, borderRadius:14,
                  background: on ? 'rgba(217,119,87,0.13)' : T.raised,
                  border:`1px solid ${on ? T.accent+'88' : T.border}`,
                  display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:9,
                  transition:'background .12s ease, border-color .12s ease, transform .08s ease',
                  transform: on ? 'scale(.97)' : 'none' }}>
                <Glyph c={on ? T.accent : T.text} s={26}/>
                <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color: on ? T.accent : T.text }}>{label}</span>
              </button>
            );
          })}
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:6, marginTop:12, padding:'0 2px' }}>
          <InboxGlyph c={T.muted} s={13}/>
          <span style={{ fontFamily:T.ui, fontSize:11.5, lineHeight:'15px', color:T.sec }}>
            Files are copied into this session’s workspace · up to 200&nbsp;MB
          </span>
        </div>
      </div>
    </div>
  );
}

// ── composer (attach sheet + chip strip + input row) ──────────
function FileComposer({ files, sheetOpen, onToggleSheet, onPick, onCancel, onRetry, draft, onDraft }) {
  const [pressed, setPressed] = React.useState(null);
  const busy = files.some(f => f.state === 'uploading' || f.state === 'queued');
  const anyReady = files.length > 0 && !busy;
  const sendActive = anyReady || (!files.length && draft.trim().length > 0);

  return (
    <div>
      <AttachSheet open={sheetOpen} onPick={onPick} pressed={pressed} setPressed={setPressed}/>
      <div style={{ background:T.surface, borderTop:`1px solid ${T.border}` }}>
        <ChipStrip files={files} onCancel={onCancel} onRetry={onRetry}/>
        <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px' }}>
          {/* + attach */}
          <button onClick={onToggleSheet} className="fa-press" aria-label="Attach"
            style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', width:44, height:44, borderRadius:12, flexShrink:0,
              display:'flex', alignItems:'center', justifyContent:'center',
              background: sheetOpen ? 'rgba(217,119,87,0.13)' : 'transparent' }}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke={sheetOpen ? T.accent : T.sec} strokeWidth="1.9" strokeLinecap="round"
              style={{ transition:'transform .2s ease', transform: sheetOpen ? 'rotate(45deg)' : 'none' }}>
              <path d="M12 5v14M5 12h14"/>
            </svg>
          </button>
          {/* input */}
          <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 14px', minHeight:44 }}>
            <input value={draft} onChange={e=>onDraft(e.target.value)}
              placeholder={files.length ? 'Add a message…' : 'Message Claude…'}
              style={{ all:'unset', flex:1, fontFamily:T.ui, fontSize:14.5, color:T.text, padding:'11px 0', minWidth:0 }}/>
          </div>
          {/* send — waiting while uploads run */}
          <button disabled={busy || !sendActive} className={sendActive ? 'fa-press' : ''} aria-label={busy ? 'Waiting for uploads' : 'Send'}
            style={{ all:'unset', boxSizing:'border-box', cursor: sendActive ? 'pointer' : 'default', width:44, height:44, borderRadius:999, flexShrink:0,
              position:'relative', display:'flex', alignItems:'center', justifyContent:'center',
              background: sendActive ? T.accent : T.base, border: sendActive ? 'none' : `1px solid ${T.border}` }}>
            {busy ? (
              <>
                <span className="fa-spin" style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}><Ring s={30}/></span>
                <SendArrow c={T.muted} s={16}/>
              </>
            ) : (
              <SendArrow c={sendActive ? '#0E0F11' : T.muted} s={18}/>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  middleTrunc, fmtSize, ChipRing, FileChip, ChipStrip, AttachSheet, FileComposer,
  DocGlyph, TableGlyph, CodeGlyph, GLYPH,
});
