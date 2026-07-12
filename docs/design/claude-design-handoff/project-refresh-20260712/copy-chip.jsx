// cc-pocket — Component sheet · hover a path/URL → floating copy chip
// One desktop chat pane, five stacked state demos of the same microinteraction.

const DARK = {
  page:'#08090A', base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  info:'#5B9BD5', success:'#4FB477', accent:'#D97757',
  chipShadow:'0 6px 18px -6px rgba(0,0,0,0.55)',
  paneShadow:'none',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const LIGHT = {
  page:'#EFEDE8', base:'#FAF9F7', surface:'#FFFFFF', raised:'#F1EFEB', border:'#E4E1DB',
  text:'#26292E', sec:'#5C6169', muted:'#8A8F97',
  info:'#3B7DC4', success:'#2E9E5B', accent:'#C4633F',
  chipShadow:'0 8px 22px -8px rgba(30,28,24,0.22)',
  paneShadow:'0 1px 0 rgba(0,0,0,0.02)',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const CopyIcon = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <rect x="5.4" y="5.4" width="8.6" height="8.6" rx="2.2" stroke={c} strokeWidth="1.5"/>
    <path d="M10.6 5.4V4a2 2 0 00-2-2H4a2 2 0 00-2 2v4.6a2 2 0 002 2h1.4" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const CheckIcon = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M3 8.4l3.1 3.1L13 4.6" stroke={c} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const ClaudeGlyph = ({ c, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <path d="M5 5l4.2 4.2L5 13.4" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M11 14h4" stroke={c} strokeWidth="1.8" strokeLinecap="round"/>
  </svg>
);

// ── the floating chip ─────────────────────────────────────────
function Chip({ value, copied, flip, wrap, tokens, onCopy }) {
  const T = tokens;
  const bridge = flip ? { top: '100%', paddingTop: 8 } : { bottom: '100%', paddingBottom: 8 };
  return (
    <span style={{ position: 'absolute', left: -4, zIndex: 40, ...bridge }}>
      <span style={{
        display: 'inline-flex', alignItems: 'flex-start', gap: 8,
        maxWidth: wrap ? 470 : 560,
        background: T.raised, border: `1px solid ${T.border}`, borderRadius: 8,
        padding: '6px 7px 6px 11px', boxShadow: T.chipShadow,
        whiteSpace: wrap ? 'normal' : 'nowrap',
      }}>
        <span style={{
          fontFamily: T.mono, fontSize: 13, lineHeight: '19px', color: T.text,
          wordBreak: wrap ? 'break-all' : 'normal', paddingTop: 1,
        }}>{value}</span>
        <button
          className="cc-copybtn"
          onClick={(e) => { e.stopPropagation(); onCopy(); }}
          aria-label={copied ? 'Copied' : 'Copy'}
          style={{
            '--bright': T.text,
            all: 'unset', boxSizing: 'border-box', cursor: 'pointer', flexShrink: 0,
            display: 'inline-flex', alignItems: 'center', gap: 5,
            height: 21, padding: copied ? '0 7px 0 5px' : '0 3px', borderRadius: 6,
          }}
        >
          {copied
            ? <><CheckIcon c={T.success} s={14}/><span style={{ fontFamily: T.ui, fontSize: 11.5, fontWeight: 600, color: T.success }}>Copied</span></>
            : <CopyIcon c={T.muted} s={15}/>}
        </button>
      </span>
    </span>
  );
}

// ── an inline recognised entity (path / url) ──────────────────
// kind: 'open' (openable local path/url, info blue solid underline)
//       'copy' (copy-only remote path, muted dotted underline, click = copy)
function Entity({ id, kind, display, value, wrap, flip, tokens, active, copied, onEnter, onLeave, onCopy }) {
  const T = tokens;
  const isCopyOnly = kind === 'copy';
  const textColor = isCopyOnly ? T.text : T.info;
  const uColor = isCopyOnly ? T.muted : T.info;
  return (
    <span
      style={{ position: 'relative', display: 'inline' }}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
    >
      <span
        onClick={() => { if (isCopyOnly) onCopy(id, value); }}
        style={{
          fontFamily: T.mono, fontSize: 13, color: textColor, cursor: 'pointer',
          padding: '1px 3px', margin: '0 -1px', borderRadius: 4,
          background: active ? T.raised : 'transparent',
          transition: 'background .12s ease',
          textDecorationLine: 'underline',
          textDecorationColor: uColor,
          textDecorationStyle: isCopyOnly ? 'dotted' : 'solid',
          textDecorationThickness: isCopyOnly ? '1.5px' : '1px',
          textUnderlineOffset: '3px',
        }}
      >{display}</span>
      {active && (
        <Chip value={value} copied={copied} flip={flip} wrap={wrap} tokens={T} onCopy={() => onCopy(id, value)}/>
      )}
    </span>
  );
}

// ── one chat-pane demo (manages hover + copy state) ───────────
function Demo({ tokens, pinned = null, copiedPinned = false, wrapIds = [], flipIds = [], alignTop = false, children }) {
  const [active, setActive] = React.useState(pinned);
  const [copiedId, setCopiedId] = React.useState(copiedPinned ? pinned : null);
  const timer = React.useRef(null);
  const doCopy = (id, text) => {
    try { navigator.clipboard && navigator.clipboard.writeText(text); } catch (e) {}
    setCopiedId(id);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopiedId(c => (c === id ? null : c)), 1600);
  };
  const T = tokens;
  const E = (props) => (
    <Entity
      {...props}
      tokens={T}
      wrap={wrapIds.includes(props.id)}
      flip={flipIds.includes(props.id)}
      active={active === props.id}
      copied={copiedId === props.id}
      onEnter={() => setActive(props.id)}
      onLeave={() => setActive(pinned)}
      onCopy={doCopy}
    />
  );
  return (
    <div style={{
      background: T.base, border: `1px solid ${T.border}`, borderRadius: 12,
      boxShadow: T.paneShadow, overflow: 'hidden',
    }}>
      {/* pane header — assistant identity strip */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '11px 18px', borderBottom: `1px solid ${T.border}` }}>
        <ClaudeGlyph c={T.accent} s={15}/>
        <span style={{ fontFamily: T.ui, fontSize: 12, fontWeight: 600, letterSpacing: 0.3, color: T.sec }}>Claude</span>
        <span style={{ flex: 1 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.muted }}>~/dev/cc-pocket</span>
      </div>
      {/* assistant prose */}
      <div style={{
        padding: alignTop ? '18px 22px 40px' : '22px 22px 26px',
        fontFamily: T.ui, fontSize: 15, lineHeight: '25px', color: T.text,
      }}>
        {children(E)}
      </div>
    </div>
  );
}

// ── caption above each demo ───────────────────────────────────
function Caption({ n, title, note, tokens }) {
  const T = tokens;
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 12 }}>
      <span style={{
        fontFamily: T.mono, fontSize: 11, fontWeight: 600, color: T.accent,
        border: `1px solid ${T.border}`, borderRadius: 6, padding: '2px 7px', flexShrink: 0,
      }}>{n}</span>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap', minWidth: 0 }}>
        <span style={{ fontFamily: T.ui, fontSize: 14, fontWeight: 600, color: T.text }}>{title}</span>
        <span style={{ fontFamily: T.ui, fontSize: 12.5, color: T.muted, lineHeight: '18px' }}>{note}</span>
      </div>
    </div>
  );
}

// ── the sheet ─────────────────────────────────────────────────
function Sheet() {
  const D = DARK, L = LIGHT;
  return (
    <div style={{
      width: 1000, margin: '0 auto', padding: '56px 40px 96px',
      fontFamily: D.ui,
    }}>
      {/* sheet header */}
      <div style={{ marginBottom: 44 }}>
        <div style={{ fontFamily: D.mono, fontSize: 11.5, letterSpacing: 1.4, textTransform: 'uppercase', color: D.accent, marginBottom: 14 }}>
          cc-pocket · component sheet
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 30, fontWeight: 700, letterSpacing: -0.4, color: D.text, marginBottom: 12 }}>
          Path &amp; URL hover-to-copy chip
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 15, lineHeight: '24px', color: D.sec, maxWidth: 720 }}>
          The transcript already recognises paths and URLs. Hovering one lifts the span and floats a chip
          showing the <span style={{ color: D.text }}>full normalized value</span> — the resolved absolute
          path, or the URL with glued punctuation stripped — plus one-click copy. The chip always displays
          exactly what will be copied.
        </div>
        {/* legend */}
        <div style={{ display: 'flex', gap: 22, marginTop: 22, flexWrap: 'wrap' }}>
          <Legend tokens={D} kind="open" label="Openable — info blue, solid underline · click opens"/>
          <Legend tokens={D} kind="copy" label="Copy-only — ambient text, dotted grey · click copies"/>
        </div>
      </div>

      {/* ── DARK DEMOS ───────────────────────────────────────── */}
      <SectionLabel tokens={D} text="Dark theme"/>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 40 }}>
        <div>
          <Caption tokens={D} n="01" title="Rest" note="Three recognised entities inline, no chip yet."/>
          <Demo tokens={D} pinned={null}>
            {(E) => (<>
              I traced the wrapping bug to the renderer. The layout math lives in{' '}
              <E id="d1a" kind="open" display="src/render/measure.ts" value="/Users/panda/dev/cc-pocket/src/render/measure.ts"/>,
              and the golden fixtures it diffs against sit on the build box at{' '}
              <E id="d1b" kind="copy" display="/mnt/ci/artifacts/wrap-2ed.json" value="/mnt/ci/artifacts/wrap-2ed.json"/>.
              The upstream write-up is at{' '}
              <E id="d1c" kind="open" display="github.com/anthropics/cc-pocket/issues/482" value="https://github.com/anthropics/cc-pocket/issues/482"/>.
            </>)}
          </Demo>
        </div>

        <div>
          <Caption tokens={D} n="02" title="Span hover" note="Hovered span lifts to raised; chip floats 8px above, left-aligned to span start."/>
          <Demo tokens={D} pinned="d2a">
            {(E) => (<>
              The failing assertion is in{' '}
              <E id="d2a" kind="open" display="src/protocol/StreamParser.kt" value="/Users/panda/dev/cc-pocket/src/protocol/StreamParser.kt"/>{' '}
              — the buffer isn&apos;t flushed until the terminating blank line arrives, so a split frame is
              counted twice.
            </>)}
          </Demo>
        </div>

        <div>
          <Caption tokens={D} n="03" title="Normalization" note="Inline span is a relative path; the chip shows the resolved absolute — the expansion explains itself."/>
          <Demo tokens={D} pinned="d3a">
            {(E) => (<>
              I kept the rationale next to the specs — see{' '}
              <E id="d3a" kind="open" display="docs/design/UI-DESIGN.md" value="/Users/panda/dev/cc-pocket/docs/design/UI-DESIGN.md"/>{' '}
              for the underline and chip decisions.
            </>)}
          </Demo>
        </div>

        <div>
          <Caption tokens={D} n="04" title="Copied" note="Click copy — or click anywhere on a copy-only span. Icon morphs to a green check + “Copied”, in the chip. Reverts after a beat."/>
          <Demo tokens={D} pinned="d4a" copiedPinned>
            {(E) => (<>
              The prod fixtures aren&apos;t in the repo — grab them from the shared volume at{' '}
              <E id="d4a" kind="copy" display="/mnt/ci/artifacts/wrap-golden-v7.json" value="/mnt/ci/artifacts/wrap-golden-v7.json"/>{' '}
              before you run the diff.
            </>)}
          </Demo>
        </div>

        <div>
          <Caption tokens={D} n="05" title="Long value · flipped" note="A very long URL wraps to two mono lines inside the chip; near the top of the pane it flips below the span. Glued parentheses stripped."/>
          <Demo tokens={D} pinned="d5a" wrapIds={['d5a']} flipIds={['d5a']} alignTop>
            {(E) => (<>
              The error spike is all in one trace (
              <E id="d5a" kind="open" display="console.cloud.example.com/logs/query?…&trace=8f3a9c210b4e7d65" value="https://console.cloud.example.com/logs/query?project=cc-pocket-prod&window=24h&severity=error&trace=8f3a9c210b4e7d65&region=us-east1"/>
              ) — every failure carries the same request id.
            </>)}
          </Demo>
        </div>
      </div>

      {/* ── LIGHT DEMOS ──────────────────────────────────────── */}
      <div style={{ height: 56 }}/>
      <SectionLabel tokens={D} text="Light theme — parity check"/>

      <div style={{
        background: L.page, border: `1px solid ${D.border}`, borderRadius: 16,
        padding: '36px 34px', display: 'flex', flexDirection: 'column', gap: 40,
      }}>
        <div>
          <Caption tokens={L} n="02" title="Span hover" note="Same lift and chip on the light surface."/>
          <Demo tokens={L} pinned="l2a">
            {(E) => (<>
              The failing assertion is in{' '}
              <E id="l2a" kind="open" display="src/protocol/StreamParser.kt" value="/Users/panda/dev/cc-pocket/src/protocol/StreamParser.kt"/>{' '}
              — the buffer isn&apos;t flushed until the terminating blank line arrives, so a split frame is
              counted twice.
            </>)}
          </Demo>
        </div>

        <div>
          <Caption tokens={L} n="04" title="Copied" note="Success green holds up against white; feedback still lives in the chip."/>
          <Demo tokens={L} pinned="l4a" copiedPinned>
            {(E) => (<>
              The prod fixtures aren&apos;t in the repo — grab them from the shared volume at{' '}
              <E id="l4a" kind="copy" display="/mnt/ci/artifacts/wrap-golden-v7.json" value="/mnt/ci/artifacts/wrap-golden-v7.json"/>{' '}
              before you run the diff.
            </>)}
          </Demo>
        </div>
      </div>
    </div>
  );
}

function Legend({ tokens, kind, label }) {
  const T = tokens;
  const isCopy = kind === 'copy';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
      <span style={{
        fontFamily: T.mono, fontSize: 12.5, color: isCopy ? T.text : T.info,
        textDecorationLine: 'underline', textDecorationColor: isCopy ? T.muted : T.info,
        textDecorationStyle: isCopy ? 'dotted' : 'solid',
        textDecorationThickness: isCopy ? '1.5px' : '1px', textUnderlineOffset: '3px',
      }}>path/url</span>
      <span style={{ fontFamily: T.ui, fontSize: 12.5, color: T.sec }}>{label}</span>
    </div>
  );
}

function SectionLabel({ tokens, text }) {
  const T = tokens;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
      <span style={{ fontFamily: T.ui, fontSize: 12.5, fontWeight: 600, letterSpacing: 1, textTransform: 'uppercase', color: T.sec }}>{text}</span>
      <span style={{ flex: 1, height: 1, background: T.border }}/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Sheet/>);
