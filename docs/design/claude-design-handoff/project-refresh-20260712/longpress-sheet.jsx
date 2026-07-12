// cc-pocket — Component sheet · Mobile long-press → action sheet
// Frame A: phone chat with the action sheet open (path + URL variants) + copied pill.
// Frame B: inline entity style reference, dark / light.

const D = {
  page:'#08090A', base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  info:'#5B9BD5', success:'#4FB477', accent:'#D97757', dotU:'#6B7177',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const L = {
  page:'#EFEDE8', base:'#FAF9F7', surface:'#FFFFFF', raised:'#F1EFEB', border:'#E4E1DB',
  text:'#1C1D1F', sec:'#5C6169', muted:'#878C92',
  info:'#3B7DC4', success:'#2E9E5B', accent:'#C4633F', dotU:'#878C92',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const IconFile = ({ c, s = 20 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <path d="M5 2.5h6l4 4v11a1 1 0 01-1 1H5a1 1 0 01-1-1v-14a1 1 0 011-1z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M11 2.5V6.5h4" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
  </svg>
);
const IconGlobe = ({ c, s = 20 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <circle cx="10" cy="10" r="7.5" stroke={c} strokeWidth="1.5"/>
    <path d="M2.5 10h15M10 2.5c2.2 2 3.3 4.7 3.3 7.5S12.2 15.5 10 17.5c-2.2-2-3.3-4.7-3.3-7.5S7.8 4.5 10 2.5z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
  </svg>
);
const IconCopy = ({ c, s = 20 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <rect x="7" y="7" width="10" height="10" rx="2.4" stroke={c} strokeWidth="1.6"/>
    <path d="M13 7V5a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const IconExternal = ({ c, s = 20 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <path d="M8 4H5a2 2 0 00-2 2v9a2 2 0 002 2h9a2 2 0 002-2v-3" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M12 3.5h4.5V8M16 4l-7 7" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const IconCheck = ({ c, s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M3 8.4l3.1 3.1L13 4.6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const ClaudeGlyph = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <path d="M5 5l4.2 4.2L5 13.4" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M11 14h4" stroke={c} strokeWidth="1.8" strokeLinecap="round"/>
  </svg>
);

// ── phone frame ───────────────────────────────────────────────
function Phone({ children, w = 384, h = 812 }) {
  return (
    <div style={{
      width: w, height: h, borderRadius: 46, background: '#000', position: 'relative',
      overflow: 'hidden', boxShadow: '0 40px 90px -30px rgba(0,0,0,0.75), 0 0 0 1px #26292E',
      fontFamily: D.ui,
    }}>
      {/* dynamic island */}
      <div style={{ position: 'absolute', top: 11, left: '50%', transform: 'translateX(-50%)', width: 118, height: 34, borderRadius: 22, background: '#000', zIndex: 80 }}/>
      {/* status bar */}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 54, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 30px', zIndex: 70 }}>
        <span style={{ fontFamily: '-apple-system, system-ui', fontSize: 15, fontWeight: 600, color: '#fff' }}>9:41</span>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <svg width="18" height="11" viewBox="0 0 18 11"><rect x="0" y="7" width="3" height="4" rx=".6" fill="#fff"/><rect x="4.5" y="4.6" width="3" height="6.4" rx=".6" fill="#fff"/><rect x="9" y="2.3" width="3" height="8.7" rx=".6" fill="#fff"/><rect x="13.5" y="0" width="3" height="11" rx=".6" fill="#fff"/></svg>
          <svg width="25" height="12" viewBox="0 0 25 12"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke="#fff" strokeOpacity=".4" fill="none"/><rect x="2" y="2" width="16" height="8" rx="1.6" fill="#fff"/><path d="M23 4v4c.7-.3 1.2-1 1.2-2S23.7 4.3 23 4z" fill="#fff" fillOpacity=".5"/></svg>
        </div>
      </div>
      {children}
    </div>
  );
}

// ── chat excerpt behind the sheet ─────────────────────────────
function ChatExcerpt({ tokens, highlight }) {
  const T = tokens;
  const span = (txt, kind, on) => {
    const isCopy = kind === 'copy';
    return (
      <span style={{
        fontFamily: T.mono, fontSize: 13, color: isCopy ? T.text : T.info,
        padding: '1px 3px', margin: '0 -1px', borderRadius: 4,
        background: on ? T.raised : 'transparent',
        textDecorationLine: 'underline', textDecorationColor: isCopy ? T.dotU : T.info,
        textDecorationStyle: isCopy ? 'dotted' : 'solid',
        textDecorationThickness: isCopy ? '1.5px' : '1px', textUnderlineOffset: '3px',
      }}>{txt}</span>
    );
  };
  return (
    <div style={{ position: 'absolute', inset: 0, background: T.base, paddingTop: 54, display: 'flex', flexDirection: 'column' }}>
      {/* header */}
      <div style={{ flexShrink: 0, padding: '10px 18px 12px', borderBottom: `1px solid ${T.border}`, display: 'flex', alignItems: 'center', gap: 9 }}>
        <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 6px ${T.success}99` }}/>
        <span style={{ fontFamily: T.ui, fontSize: 14, fontWeight: 600, color: T.text }}>Refactor auth module</span>
        <span style={{ flex: 1 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.muted }}>default</span>
      </div>
      {/* body */}
      <div style={{ flex: 1, overflow: 'hidden', padding: '18px 18px' }}>
        <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.4, color: T.muted, marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
          <ClaudeGlyph c={T.accent} s={13}/> CLAUDE
        </div>
        <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '24px', color: T.text }}>
          {highlight === 'url' ? (<>
            The error spike is all in one trace — every failure carries the same request id. Open{' '}
            {span('console.cloud.example.com/logs/query?trace=8f3a', 'open', true)}{' '}
            to see the full window.
          </>) : (<>
            The failing assertion is in{' '}
            {span('src/protocol/StreamParser.kt', 'copy', highlight === 'path')}{' '}
            — the buffer isn&apos;t flushed until the terminating blank line arrives, so a split frame is counted twice.
          </>)}
        </div>
      </div>
    </div>
  );
}

// ── bottom action sheet ───────────────────────────────────────
function ActionSheet({ tokens, variant }) {
  const T = tokens;
  const isUrl = variant === 'url';
  const value = isUrl
    ? 'https://console.cloud.example.com/logs/query?project=cc-pocket-prod&window=24h&severity=error&trace=8f3a9c210b4e7d65'
    : '/Users/panda/dev/cc-pocket/src/protocol/StreamParser.kt';
  const Row = ({ icon, label, danger }) => (
    <button style={{
      all: 'unset', boxSizing: 'border-box', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 14,
      minHeight: 52, padding: '0 20px', width: '100%',
    }}>
      {icon}
      <span style={{ fontFamily: T.ui, fontSize: 16, fontWeight: 500, color: T.text }}>{label}</span>
    </button>
  );
  return (
    <>
      {/* scrim */}
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 40 }}/>
      {/* sheet */}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 50,
        background: T.surface, borderTop: `1px solid ${T.border}`,
        borderTopLeftRadius: 20, borderTopRightRadius: 20,
        boxShadow: '0 -20px 50px -20px rgba(0,0,0,0.6)',
        paddingBottom: 34,
      }}>
        {/* grab handle */}
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <span style={{ width: 40, height: 5, borderRadius: 999, background: T.border }}/>
        </div>
        {/* header zone */}
        <div style={{ display: 'flex', gap: 13, padding: '12px 20px 16px', borderBottom: `1px solid ${T.border}` }}>
          <span style={{ flexShrink: 0, marginTop: 1 }}>{isUrl ? <IconGlobe c={T.sec}/> : <IconFile c={T.sec}/>}</span>
          <div style={{ minWidth: 0 }}>
            <div style={{
              fontFamily: T.mono, fontSize: 13, lineHeight: '19px', color: T.text, wordBreak: 'break-all',
              display: '-webkit-box', WebkitLineClamp: 4, WebkitBoxOrient: 'vertical', overflow: 'hidden',
            }}>{value}</div>
            {!isUrl && (
              <div style={{ fontFamily: T.ui, fontSize: 12, color: T.muted, marginTop: 6 }}>resolved from session cwd</div>
            )}
          </div>
        </div>
        {/* actions */}
        <div style={{ padding: '4px 0' }}>
          <Row icon={<IconCopy c={T.sec}/>} label="Copy"/>
          {isUrl && <Row icon={<IconExternal c={T.sec}/>} label="Open in browser"/>}
        </div>
      </div>
    </>
  );
}

// ── transient copied pill ─────────────────────────────────────
function CopiedPill({ tokens }) {
  const T = tokens;
  return (
    <div style={{
      position: 'absolute', left: '50%', transform: 'translateX(-50%)', bottom: 52, zIndex: 60,
      display: 'flex', alignItems: 'center', gap: 8, height: 40, padding: '0 18px',
      background: T.raised, border: `1px solid ${T.border}`, borderRadius: 999,
      boxShadow: '0 10px 28px -8px rgba(0,0,0,0.6)',
    }}>
      <IconCheck c={T.success} s={16}/>
      <span style={{ fontFamily: T.ui, fontSize: 14, fontWeight: 600, color: T.success }}>Copied</span>
    </div>
  );
}

// ── labelled phone (state caption + device) ───────────────────
function StatePhone({ n, title, note, children }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ maxWidth: 384 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 5 }}>
          <span style={{ fontFamily: D.mono, fontSize: 11, fontWeight: 600, color: D.accent, border: `1px solid ${D.border}`, borderRadius: 6, padding: '2px 7px' }}>{n}</span>
          <span style={{ fontFamily: D.ui, fontSize: 14, fontWeight: 600, color: D.text }}>{title}</span>
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 12.5, lineHeight: '18px', color: D.muted, paddingLeft: 2 }}>{note}</div>
      </div>
      {children}
    </div>
  );
}

// ── Frame B — inline style reference cell ─────────────────────
function StyleCell({ tokens, kind, prose, entity, caption }) {
  const T = tokens;
  const isCopy = kind === 'copy';
  const [a, b] = prose;
  return (
    <div style={{ background: T.base, border: `1px solid ${T.border}`, borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '24px', color: T.text }}>
        {a}
        <span style={{
          fontFamily: T.mono, fontSize: 13, color: isCopy ? T.text : T.info,
          textDecorationLine: 'underline', textDecorationColor: isCopy ? T.dotU : T.info,
          textDecorationStyle: isCopy ? 'dotted' : 'solid',
          textDecorationThickness: isCopy ? '1.5px' : '1px', textUnderlineOffset: '3px',
        }}>{entity}</span>
        {b}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 12 }}>
        <span style={{ width: 5, height: 5, borderRadius: 999, background: isCopy ? T.muted : T.info, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec }}>{caption}</span>
      </div>
    </div>
  );
}

const ROWS = [
  { kind:'open', prose:['Layout math lives in ', '.'], entity:'src/render/measure.ts', caption:'click/tap opens · hover/long-press copies' },
  { kind:'copy', prose:['Golden fixtures sit at ', '.'], entity:'/mnt/ci/artifacts/wrap-2ed.json', caption:'click copies · no open' },
  { kind:'open', prose:['Upstream write-up is at ', '.'], entity:'github.com/anthropics/cc-pocket/issues/482', caption:'click/tap opens browser · hover/long-press copies' },
];

function SectionLabel({ text, tokens = D }) {
  const T = tokens;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
      <span style={{ fontFamily: T.ui, fontSize: 12.5, fontWeight: 600, letterSpacing: 1, textTransform: 'uppercase', color: T.sec }}>{text}</span>
      <span style={{ flex: 1, height: 1, background: T.border }}/>
    </div>
  );
}

// ── sheet ─────────────────────────────────────────────────────
function Sheet() {
  return (
    <div style={{ width: 1360, margin: '0 auto', padding: '56px 48px 96px', fontFamily: D.ui }}>
      {/* header */}
      <div style={{ marginBottom: 48 }}>
        <div style={{ fontFamily: D.mono, fontSize: 11.5, letterSpacing: 1.4, textTransform: 'uppercase', color: D.accent, marginBottom: 14 }}>
          cc-pocket · component sheet
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 30, fontWeight: 700, letterSpacing: -0.4, color: D.text, marginBottom: 12 }}>
          Mobile long-press → action sheet
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 15, lineHeight: '24px', color: D.sec, maxWidth: 760 }}>
          Phones have no hover, so long-pressing a recognised path or URL opens a bottom action sheet — the
          system habit for long-pressing links. Filesystem paths belong to the paired computer, so on the phone
          they are <span style={{ color: D.text }}>copy-only</span>; URLs can also open in the in-app browser.
          The sheet is where a phone user finally sees the full normalized value of a long path.
        </div>
      </div>

      {/* FRAME A */}
      <SectionLabel text="Frame A — long-press action sheet"/>
      <div style={{ display: 'flex', gap: 44, flexWrap: 'wrap', marginBottom: 64 }}>
        <StatePhone n="A1" title="Path — copy only" note="Filesystem path on the paired computer. Header shows the resolved absolute value; the inline text was relative, so “resolved from session cwd” appears. One action: Copy.">
          <Phone>
            <ChatExcerpt tokens={D} highlight="path"/>
            <ActionSheet tokens={D} variant="path"/>
          </Phone>
        </StatePhone>

        <StatePhone n="A2" title="URL — copy + open" note="Globe glyph, full URL wraps freely. Two actions: Copy, and Open in browser (URLs only). Tap the scrim to dismiss.">
          <Phone>
            <ChatExcerpt tokens={D} highlight="url"/>
            <ActionSheet tokens={D} variant="url"/>
          </Phone>
        </StatePhone>

        <StatePhone n="A3" title="Post-copy pill" note="Sheet has dismissed; a transient pill floats above the home indicator — success-green check + “Copied”. No persistent toast.">
          <Phone>
            <ChatExcerpt tokens={D} highlight={null}/>
            <CopiedPill tokens={D}/>
          </Phone>
        </StatePhone>
      </div>

      {/* FRAME B */}
      <SectionLabel text="Frame B — inline entity styles · dark / light"/>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 0, border: `1px solid ${D.border}`, borderRadius: 16, overflow: 'hidden' }}>
        {/* column headers */}
        <div style={{ padding: '14px 22px', background: D.base, borderBottom: `1px solid ${D.border}`, borderRight: `1px solid ${D.border}` }}>
          <span style={{ fontFamily: D.mono, fontSize: 11.5, letterSpacing: 0.6, color: D.sec }}>DARK</span>
        </div>
        <div style={{ padding: '14px 22px', background: L.page, borderBottom: `1px solid ${D.border}` }}>
          <span style={{ fontFamily: L.mono, fontSize: 11.5, letterSpacing: 0.6, color: L.sec }}>LIGHT</span>
        </div>
        {/* three rows × two themes */}
        {ROWS.map((r, i) => (
          <React.Fragment key={i}>
            <div style={{ padding: 22, background: D.page, borderRight: `1px solid ${D.border}`, borderBottom: i < 2 ? `1px solid ${D.border}` : 'none' }}>
              <StyleCell tokens={D} {...r}/>
            </div>
            <div style={{ padding: 22, background: L.page, borderBottom: i < 2 ? `1px solid ${D.border}` : 'none' }}>
              <StyleCell tokens={L} {...r}/>
            </div>
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Sheet/>);
