// cc-pocket — in-chat components · Document card states + Load-earlier-history row
// Standalone frame. Matches the DocumentCard grammar (site/chat-cards.css) in inline styles.

const D = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', accentPressed:'#C4633F', success:'#4FB477', warning:'#E0A93B',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
// file-family tints (badge fill / border / ext colour)
const FAM = {
  xlsx: { c:'#4FB477', bg:'rgba(79,180,119,0.13)',  bd:'rgba(79,180,119,0.34)' },
  docx: { c:'#5B9BD5', bg:'rgba(91,155,213,0.13)',  bd:'rgba(91,155,213,0.34)' },
  pptx: { c:'#E0A93B', bg:'rgba(224,169,59,0.13)',  bd:'rgba(224,169,59,0.34)' },
  none: { c:'#8B9096', bg:D.raised,                 bd:D.border },
};

// ── icons ─────────────────────────────────────────────────────
const Eye = ({ c=D.sec, s=15 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M1.5 9S4.5 3.5 9 3.5 16.5 9 16.5 9 13.5 14.5 9 14.5 1.5 9 1.5 9z" stroke={c} strokeWidth="1.4"/><circle cx="9" cy="9" r="2.2" stroke={c} strokeWidth="1.4"/></svg>
);
const ShareGlyph = ({ c=D.sec, s=15 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M9 11.5V3M9 3L6 6M9 3l3 3" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/><path d="M4 9v4.5A1.5 1.5 0 005.5 15h7a1.5 1.5 0 001.5-1.5V9" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>
);
const Monitor = ({ c=D.muted, s=13 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke={c} strokeWidth="1.35" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2.5" width="12" height="8" rx="1.4"/><path d="M6 13h4M8 10.5V13"/></svg>
);
function Spinner({ s=14 }) {
  return <span className="cx-spin" style={{ display:'inline-block', width:s, height:s, border:`2px solid ${D.border}`, borderTopColor:D.accent, borderRadius:999, flexShrink:0 }}/>;
}

// ── type badge tile (folded corner + mono ext) ────────────────
function DocBadge({ ext, fam, muted }) {
  const f = muted ? FAM.none : (FAM[fam] || FAM.none);
  return (
    <div style={{ position:'relative', width:52, height:52, borderRadius:11, flexShrink:0, overflow:'hidden',
      display:'flex', alignItems:'center', justifyContent:'center', background:f.bg, border:`1px solid ${f.bd}` }}>
      <span style={{ fontFamily:D.mono, fontSize:12.5, fontWeight:700, letterSpacing:0.2, color:f.c }}>{ext}</span>
      {/* folded top-right corner */}
      <span style={{ position:'absolute', top:0, right:0, borderStyle:'solid', borderWidth:'0 11px 11px 0', borderColor:`transparent ${D.base} transparent transparent`, opacity:0.55 }}/>
      <span style={{ position:'absolute', top:0, right:0, width:11, height:11, background:f.c, opacity:0.14 }}/>
    </div>
  );
}

function Chip({ children, primary, disabled }) {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:6, height:34, padding:'0 14px', borderRadius:9,
      fontFamily:D.ui, fontSize:12.5, fontWeight:600, whiteSpace:'nowrap',
      opacity: disabled ? 0.4 : 1, cursor: disabled ? 'default' : 'pointer',
      background: primary ? D.accent : 'transparent', color: primary ? '#0E0F11' : D.sec,
      border: primary ? 'none' : `1px solid ${D.border}` }}>{children}</span>
  );
}

// ── document card ─────────────────────────────────────────────
function DocCard({ ext, fam, name, children, muted }) {
  return (
    <div style={{ display:'flex', alignItems:'flex-start', gap:13, background:D.surface, border:`1px solid ${D.border}`, borderRadius:13, padding:13 }}>
      <DocBadge ext={ext} fam={fam} muted={muted}/>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontFamily:D.ui, fontSize:14.5, fontWeight:600, color:D.text, lineHeight:1.3, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{name}</div>
        {children}
      </div>
    </div>
  );
}

// state 1 — chunk loading (determinate)
function CardLoading() {
  const loaded = 4.2, total = 12.8;
  const pct = Math.round((loaded/total)*100);
  return (
    <DocCard ext="XLSX" fam="xlsx" name="quarterly-financials.xlsx">
      <div style={{ marginTop:12 }}>
        <div style={{ height:5, borderRadius:999, background:D.raised, overflow:'hidden' }}>
          <div style={{ width:`${pct}%`, height:'100%', borderRadius:999, background:D.accent }}/>
        </div>
        <div style={{ marginTop:7, fontFamily:D.mono, fontSize:10.5, color:D.muted }}>{loaded.toFixed(1)} MB of {total.toFixed(1)} MB</div>
      </div>
      <div style={{ display:'flex', gap:8, marginTop:11 }}>
        <Chip primary disabled><Eye c="#0E0F11" s={14}/>Preview</Chip>
        <Chip disabled><ShareGlyph s={14}/>Share</Chip>
      </div>
    </DocCard>
  );
}

// state 2 — too large (calm, factual, muted)
function CardTooLarge() {
  return (
    <DocCard ext="PPTX" fam="pptx" name="all-hands-deck.pptx" muted>
      <div style={{ marginTop:5, fontFamily:D.mono, fontSize:11, color:D.muted }}>48 MB — too large to fetch remotely</div>
      <div style={{ display:'flex', alignItems:'center', gap:6, marginTop:9 }}>
        <Monitor c={D.muted} s={13}/>
        <span style={{ fontFamily:D.ui, fontSize:12, color:D.sec }}>open it on your computer</span>
      </div>
    </DocCard>
  );
}

// ── load-earlier-history row ──────────────────────────────────
function LoadEarlierLoading({ fading }) {
  return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center', gap:9, height:46, opacity: fading ? 0.22 : 1, transition:'opacity .5s ease' }}>
      <Spinner s={14}/>
      <span style={{ fontFamily:D.ui, fontSize:12.5, color:D.muted }}>Loading earlier messages…</span>
    </div>
  );
}
function EarlierSeam() {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:12, height:46, padding:'0 4px' }}>
      <span style={{ flex:1, height:1, background:D.border }}/>
      <span style={{ fontFamily:D.mono, fontSize:10.5, letterSpacing:0.6, color:D.muted, whiteSpace:'nowrap' }}>· earlier messages ·</span>
      <span style={{ flex:1, height:1, background:D.border }}/>
    </div>
  );
}

// ── board scaffolding ─────────────────────────────────────────
function Spec({ n, title, note, children }) {
  return (
    <div style={{ marginBottom:26 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:5 }}>
        <span style={{ fontFamily:D.mono, fontSize:10.5, fontWeight:600, color:D.accent, border:`1px solid ${D.border}`, borderRadius:6, padding:'2px 7px' }}>{n}</span>
        <span style={{ fontFamily:D.ui, fontSize:13.5, fontWeight:600, color:D.text }}>{title}</span>
      </div>
      <div style={{ fontFamily:D.ui, fontSize:12, lineHeight:'17px', color:D.muted, marginBottom:13, maxWidth:360 }}>{note}</div>
      {children}
    </div>
  );
}
// a mobile-width chat surface the specimen sits on
function ChatColumn({ label, children }) {
  return (
    <div style={{ width:392 }}>
      <div style={{ fontFamily:D.ui, fontSize:15, fontWeight:700, color:D.text, marginBottom:16 }}>{label}</div>
      <div style={{ background:D.base, border:`1px solid ${D.border}`, borderRadius:18, padding:'20px 16px' }}>{children}</div>
    </div>
  );
}

function Board() {
  return (
    <div style={{ maxWidth:960, margin:'0 auto', padding:'56px 44px 96px' }}>
      <p style={{ fontFamily:D.mono, fontSize:12, color:D.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat components</p>
      <h1 style={{ fontFamily:D.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:D.text }}>Document card &amp; load-earlier row</h1>
      <p style={{ fontFamily:D.ui, fontSize:15, lineHeight:'23px', color:D.sec, maxWidth:720, margin:0 }}>
        Two small in-chat pieces. The DocumentCard gains a determinate chunk-loading state and a calm over-limit state;
        the top-of-thread row loads older history ambiently as it scrolls into view, and marks the seam once a page lands.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:40, marginTop:44, alignItems:'flex-start' }}>
        <ChatColumn label="A · Document card">
          <Spec n="A1" title="Chunk-loading" note="Streaming from the computer in 384 KB chunks. A determinate terracotta bar on a hairline track, a mono byte caption, and Preview / Share held visible but disabled.">
            <CardLoading/>
          </Spec>
          <Spec n="A2" title="Too large (over 50 MB)" note="Factual, not an error: neutral badge, the size + reason, and a single quiet hint. No red, no warning tint.">
            <CardTooLarge/>
          </Spec>
        </ChatColumn>

        <ChatColumn label="B · Load earlier history">
          <Spec n="B1" title="Fetching (ambient)" note="Auto-triggered on scroll-into-view, so it reads as an ambient status line — centered spinner + caption, never a button.">
            <div style={{ borderBottom:`1px solid ${D.border}`, paddingBottom:2 }}><LoadEarlierLoading/></div>
            <div style={{ paddingTop:12, opacity:0.5 }}>
              <div style={{ fontFamily:D.ui, fontSize:11, fontWeight:600, letterSpacing:0.4, color:D.muted, marginBottom:5 }}>You</div>
              <div style={{ fontFamily:D.ui, fontSize:13.5, lineHeight:'20px', color:D.text }}>where does the reconnect backoff get reset?</div>
            </div>
          </Spec>
          <Spec n="B2" title="Silent-failure collapse" note="If the fetch quietly fails, the row simply fades out — no error, no retry button. The thread stays where it is.">
            <LoadEarlierLoading fading/>
          </Spec>
          <Spec n="B3" title="Seam after a page lands" note="For a beat after older messages arrive, a subtle divider marks where the seam was so the reader keeps their place.">
            <EarlierSeam/>
            <div style={{ paddingTop:6 }}>
              <div style={{ fontFamily:D.ui, fontSize:11, fontWeight:600, letterSpacing:0.4, color:D.muted, marginBottom:5 }}>Claude</div>
              <div style={{ fontFamily:D.ui, fontSize:13.5, lineHeight:'20px', color:D.text }}>It resets in <span style={{ fontFamily:D.mono, fontSize:12, background:D.surface, border:`1px solid ${D.border}`, borderRadius:5, padding:'1px 5px' }}>onOpen()</span> once the socket handshake completes.</div>
            </div>
          </Spec>
        </ChatColumn>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Board/>);
