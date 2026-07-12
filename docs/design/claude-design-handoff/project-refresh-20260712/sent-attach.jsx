// cc-pocket — chat · sent attachments in the message stream
// Theme-aware (pass `t`). Renders the delivered loop: file chip, video card,
// image thumb, assistant reply quoting the workspace path, failed variant,
// and the full-screen video player overlay.

// ── themes ────────────────────────────────────────────────────
const DARK = { ...T };
const LIGHT = {
  base:'#FAF9F7', surface:'#FFFFFF', raised:'#FFFFFF', border:'#E4E1DB',
  text:'#211E1B', sec:'#6E665F', muted:'#9C938B',
  accent:'#C15F3C', success:'#3E9B63', warning:'#B8832B', danger:'#C0402E',
  mono:T.mono, ui:T.ui,
};

// ── helpers ───────────────────────────────────────────────────
const mmss = (s) => `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;

// ── small glyphs ──────────────────────────────────────────────
function PlayTri({ c = '#fff', s = 20 }) {
  return <svg width={s} height={s} viewBox="0 0 20 20" fill={c}><path d="M6 3.5l11 6.5-11 6.5z"/></svg>;
}
function PauseGlyph({ c = '#fff', s = 20 }) {
  return <svg width={s} height={s} viewBox="0 0 20 20" fill={c}><rect x="4.5" y="3.5" width="4.2" height="13" rx="1.2"/><rect x="11.3" y="3.5" width="4.2" height="13" rx="1.2"/></svg>;
}
function CheckMini({ c, s = 11 }) {
  return <svg width={s} height={s} viewBox="0 0 12 12" fill="none" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.3l2.4 2.4L9.6 3.5"/></svg>;
}
function FilmGlyph({ c, s = 22 }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3.5" y="5" width="17" height="14" rx="2.5"/>
      <path d="M8 5v14M16 5v14M3.5 9.5h4.5M16 9.5h4.5M3.5 14.5h4.5M16 14.5h4.5"/>
    </svg>
  );
}

// ── workspace-path reference line (the "how the agent sees it") ─
function PathRef({ t, path }) {
  const slash = path.indexOf('/');
  const head = slash > -1 ? path.slice(0, slash + 1) : path;
  const rest = slash > -1 ? path.slice(slash + 1) : '';
  return (
    <span style={{ fontFamily:t.mono, fontSize:11, letterSpacing:0.1 }}>
      <span style={{ color:t.accent }}>{head}</span><span style={{ color:t.sec }}>{rest}</span>
    </span>
  );
}

// ── delivered caption: ✓ · size · in workspace ────────────────
function WorkspaceCap({ t, size, failed }) {
  if (failed) {
    return <span style={{ fontFamily:t.mono, fontSize:11, color:t.danger }}>delivery failed · tap to retry</span>;
  }
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:5, fontFamily:t.mono, fontSize:11, color:t.muted }}>
      <CheckMini c={t.success} s={11}/>{size} · in workspace
    </span>
  );
}

// ── generic file chip (sent) ──────────────────────────────────
function FileChipSent({ t, name, size, kind, path, failed, onRetry }) {
  const Glyph = (GLYPH && GLYPH[kind]) || DocGlyph;
  const border = failed ? `${t.danger}` : t.border;
  return (
    <div onClick={failed ? onRetry : undefined} className={failed ? 'sa-press' : ''}
      style={{ maxWidth:280, background:t.surface, border:`1px solid ${border}`, borderRadius:12, overflow:'hidden', cursor: failed ? 'pointer':'default' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12, padding:'12px 13px 11px' }}>
        <div style={{ width:38, height:38, borderRadius:9, flexShrink:0, background: failed ? 'rgba(197,64,44,0.10)' : t.base, border:`1px solid ${t.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}>
          {failed ? <Retry c={t.danger} s={18}/> : <Glyph c={t.sec} s={20}/>}
        </div>
        <div style={{ minWidth:0, flex:1 }}>
          <div style={{ fontFamily:t.mono, fontSize:13, fontWeight:500, color: failed ? t.danger : t.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</div>
          <div style={{ marginTop:3 }}><WorkspaceCap t={t} size={size} failed={failed}/></div>
        </div>
      </div>
      {!failed && (
        <div style={{ borderTop:`1px solid ${t.border}`, padding:'7px 13px', display:'flex', alignItems:'center', gap:6, background: t===LIGHT ? '#FBFAF8' : 'rgba(255,255,255,0.02)' }}>
          <span style={{ fontFamily:t.mono, fontSize:10.5, color:t.muted }}>agent reads it as</span>
          <PathRef t={t} path={path}/>
        </div>
      )}
    </div>
  );
}

// ── video attachment card (sent) ──────────────────────────────
function VideoCardSent({ t, dur = 42, name, size, path, onOpen }) {
  return (
    <div style={{ maxWidth:280 }}>
      <div onClick={onOpen} className="sa-press" style={{ position:'relative', borderRadius:12, overflow:'hidden', border:`1px solid ${t.border}`, cursor:'pointer' }}>
        <div style={{ width:'100%', aspectRatio:'16 / 9', background:'linear-gradient(150deg,#1b1410 0%,#3a2a20 46%,#7a5238 100%)', position:'relative' }}>
          <div style={{ position:'absolute', left:'30%', top:'26%', width:'50%', height:'50%', transform:'translate(-50%,-50%)', background:'radial-gradient(circle, rgba(255,200,150,0.4) 0%, transparent 68%)' }}/>
          <div style={{ position:'absolute', inset:0, backgroundImage:'radial-gradient(120% 120% at 50% 40%, transparent 55%, rgba(0,0,0,0.4) 100%)' }}/>
        </div>
        {/* center play */}
        <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <div style={{ width:52, height:52, borderRadius:999, background:'rgba(12,10,9,0.5)', backdropFilter:'blur(6px)', WebkitBackdropFilter:'blur(6px)', border:'1px solid rgba(255,255,255,0.28)', display:'flex', alignItems:'center', justifyContent:'center' }}>
            <PlayTri c="#fff" s={22}/>
          </div>
        </div>
        {/* duration pill */}
        <div style={{ position:'absolute', right:8, bottom:8, background:'rgba(12,10,9,0.66)', borderRadius:6, padding:'3px 7px', fontFamily:t.mono, fontSize:11, fontWeight:500, color:'#fff', letterSpacing:0.3 }}>{mmss(dur)}</div>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:8, flexWrap:'wrap' }}>
        <span style={{ fontFamily:t.mono, fontSize:12.5, color:t.text }}>{name}</span>
        <WorkspaceCap t={t} size={size}/>
      </div>
      <div style={{ marginTop:4 }}><PathRef t={t} path={path}/></div>
    </div>
  );
}

// ── image thumbnail (existing style, for continuity) ──────────
function ImageThumbSent({ t, p = 0, path, onOpen }) {
  return (
    <div style={{ maxWidth:280 }}>
      <div onClick={onOpen} className="sa-press" style={{ width:'64%', maxWidth:180, cursor:'pointer' }}>
        <Photo p={p} radius={12} style={{ width:'100%', aspectRatio:'4 / 3', border:`1px solid ${t.border}` }}/>
      </div>
      <div style={{ marginTop:7 }}><PathRef t={t} path={path}/></div>
    </div>
  );
}

// ── turns ─────────────────────────────────────────────────────
function UserTurn({ t, children, gap = 12 }) {
  return (
    <div>
      <div style={{ fontFamily:t.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:t.muted, marginBottom:9 }}>YOU</div>
      <div style={{ display:'flex', flexDirection:'column', gap, alignItems:'flex-start' }}>{children}</div>
    </div>
  );
}
function Assistant({ t, children }) {
  return <div style={{ fontFamily:t.ui, fontSize:14.5, lineHeight:'22px', color:t.text }}>{children}</div>;
}
// inline mono path chip inside assistant prose
function PathChip({ t, children }) {
  return <code style={{ fontFamily:t.mono, fontSize:12.5, background:t.surface, border:`1px solid ${t.border}`, borderRadius:5, padding:'1px 6px', color:t.accent, whiteSpace:'nowrap' }}>{children}</code>;
}

Object.assign(window, {
  DARK, LIGHT, mmss, PlayTri, PauseGlyph, CheckMini, FilmGlyph,
  PathRef, WorkspaceCap, FileChipSent, VideoCardSent, ImageThumbSent,
  UserTurn, Assistant, PathChip,
});
