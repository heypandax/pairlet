// cc-pocket — Chat screen
// A developer's live conversation with Claude Code.

const T = {
  base:    '#0E0F11',
  surface: '#16181B',
  raised:  '#1E2125',
  border:  '#2A2E33',
  text:    '#ECEDEE',
  sec:     '#9BA1A6',
  muted:   '#6B7177',
  accent:  '#D97757',
  success: '#4FB477',
  warning: '#E0A93B',
  danger:  '#E5604D',
  // calm, on-system syntax tones
  synKw:   '#9BA1A6',   // keywords → secondary
  synStr:  '#7FB59A',   // strings → soft desaturated green (success family)
  synAnno: '#9BA1A6',   // annotations → secondary (keep terracotta as the single ember)
  mono:    "'JetBrains Mono', ui-monospace, monospace",
  ui:      "'Inter', -apple-system, system-ui, sans-serif",
};

// ── icons (simple) ────────────────────────────────────────────
const Chevron = ({ d = 'left', c = T.sec, s = 16, w = 2 }) => {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6', down:'M3 6l6 6 6-6', up:'M3 12l6-6 6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
// (⋯ "more" icon removed — no defined menu in v1)
const Plus = ({ c = T.sec }) => (
  <svg width="18" height="18" viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>
);
const Terminal = ({ c = T.sec, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <rect x="1.2" y="2.4" width="13.6" height="11.2" rx="2.6" stroke={c} strokeWidth="1.3"/>
    <path d="M4.3 6.2l2.1 1.9-2.1 1.9" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M8.4 10.2h3.3" stroke={c} strokeWidth="1.3" strokeLinecap="round"/>
  </svg>
);
const Copy = ({ c = T.muted, s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <rect x="5.5" y="5.5" width="8.5" height="8.5" rx="2.2" stroke={c} strokeWidth="1.3"/>
    <path d="M10.5 5.5V4a2 2 0 00-2-2H4a2 2 0 00-2 2v4.5a2 2 0 002 2h1.5" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const Spinner = ({ c = T.accent, s = 14 }) => (
  <svg className="cc-spin" width={s} height={s} viewBox="0 0 16 16" fill="none">
    <circle cx="8" cy="8" r="6" stroke={T.border} strokeWidth="1.7"/>
    <path d="M8 2a6 6 0 016 6" stroke={c} strokeWidth="1.7" strokeLinecap="round"/>
  </svg>
);
const SendArrow = ({ c = '#0E0F11' }) => (
  <svg width="18" height="18" viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>
);

// ── header ────────────────────────────────────────────────────
function Header() {
  return (
    <div style={{ flexShrink: 0, background: T.base, paddingTop: 52, borderBottom: `1px solid ${T.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px 0 4px', height: 44 }}>
        <button style={iconBtn} aria-label="Back"><Chevron d="left" c={T.sec} s={17}/></button>
        <span style={{
          flex: 1, fontFamily: T.ui, fontSize: 15, fontWeight: 600, color: T.text,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0,
        }}>Refactor auth module</span>
        <button style={modePill} aria-label="Permission mode">
          <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec }}>default</span>
          <Chevron d="down" c={T.muted} s={10} w={1.7}/>
        </button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '0 16px 9px' }}>
        <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 7px ${T.success}99`, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.sec, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color: T.muted }}>~/proj/app/cc-pocket</span>
        </span>
      </div>
    </div>
  );
}
const iconBtn = { all: 'unset', boxSizing: 'border-box', cursor: 'pointer', width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 };
const modePill = { all: 'unset', boxSizing: 'border-box', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 5, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 999, padding: '0 9px', height: 26, flexShrink: 0 };

// ── speaker label ─────────────────────────────────────────────
const SpeakerLabel = ({ who }) => (
  <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.4, color: T.muted, marginBottom: 6 }}>{who}</div>
);

// inline mono token inside prose
const M = ({ children }) => (
  <span style={{ fontFamily: T.mono, fontSize: 12.5, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 5, padding: '1px 5px', color: T.text }}>{children}</span>
);

// ── thinking row (collapsed) ──────────────────────────────────
function ThinkingRow() {
  const [open, setOpen] = React.useState(false);
  return (
    <div>
      <button onClick={() => setOpen(o => !o)} style={{
        all: 'unset', boxSizing: 'border-box', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8,
        minHeight: 32, color: T.muted,
      }}>
        <span style={{ transform: open ? 'rotate(90deg)' : 'none', display: 'flex' }}><Chevron d="right" c={T.muted} s={12} w={1.8}/></span>
        <span style={{ fontFamily: T.ui, fontSize: 12.5, color: T.muted, fontStyle: 'italic' }}>Thought for 5s</span>
      </button>
      {open && (
        <div style={{ borderLeft: `1px solid ${T.border}`, margin: '2px 0 2px 5px', padding: '4px 0 4px 14px' }}>
          <div style={{ fontFamily: T.ui, fontSize: 12.5, lineHeight: '19px', color: T.muted, fontStyle: 'italic' }}>
            The parser buffers across <span style={{ fontFamily: T.mono, fontStyle: 'normal' }}>feed()</span> calls, so the test needs to split a single SSE event over two chunks and assert it’s only emitted once the terminating blank line arrives.
          </div>
        </div>
      )}
    </div>
  );
}

// ── code block ────────────────────────────────────────────────
const KW = ['fun', 'val', 'var', 'return', 'class', 'object'];
function tokColor(kind) {
  return { kw: T.synKw, str: T.synStr, anno: T.synAnno, punct: T.muted, txt: T.text }[kind] || T.text;
}
// each line is an array of [text, kind]
const KOTLIN = [
  [['@Test', 'anno']],
  [['fun ', 'kw'], ['`parses events split across chunks`', 'txt'], ['() {', 'punct']],
  [['    val ', 'kw'], ['parser = StreamParser()', 'txt']],
  [['    parser.feed(', 'txt'], ['"data: {\\"ty"', 'str'], [')', 'punct']],
  [['    val ', 'kw'], ['events = parser.feed(', 'txt'], ['"pe\\":\\"msg\\"}\\n\\n"', 'str'], [')', 'punct']],
  [['    assertEquals(', 'txt'], ['1', 'str'], [', events.size)', 'txt']],
  [['}', 'punct']],
];
function CodeBlock() {
  const [copied, setCopied] = React.useState(false);
  return (
    <div style={{ background: T.base, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden', marginTop: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '7px 10px 7px 12px', borderBottom: `1px solid ${T.border}`, background: T.surface }}>
        <span style={{ fontFamily: T.mono, fontSize: 11, color: T.muted, letterSpacing: 0.2 }}>kotlin</span>
        <span style={{ flex: 1 }}/>
        <button onClick={() => { setCopied(true); }} aria-label="Copy" style={{
          all: 'unset', boxSizing: 'border-box', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 5,
          height: 24, padding: '0 6px', borderRadius: 6,
        }}>
          {copied
            ? <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.success }}>copied</span>
            : <Copy c={T.muted} s={14}/>}
        </button>
      </div>
      <pre style={{ margin: 0, padding: '11px 13px', fontFamily: T.mono, fontSize: 12, lineHeight: '19px', overflowX: 'auto' }}>
        {KOTLIN.map((line, i) => (
          <div key={i} style={{ whiteSpace: 'pre', color: T.text }}>
            {line.length === 0 ? ' ' : line.map(([t, k], j) => <span key={j} style={{ color: tokColor(k) }}>{t}</span>)}
          </div>
        ))}
      </pre>
    </div>
  );
}

// ── bash tool-event row (running) ─────────────────────────────
function BashRow() {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 9, marginTop: 12,
      background: T.surface, border: `1px solid ${T.border}`, borderRadius: 10,
      padding: '0 11px', minHeight: 40,
    }}>
      <Terminal c={T.sec} s={15}/>
      <span style={{ fontFamily: T.ui, fontSize: 12.5, fontWeight: 600, color: T.text, flexShrink: 0 }}>Bash</span>
      <span style={{ fontFamily: T.mono, fontSize: 12, color: T.sec, flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>gradle :protocol:test</span>
      <Spinner c={T.accent} s={14}/>
    </div>
  );
}

// ── slash commands ────────────────────────────────────────────
const SLASH_COMMANDS = [
  { cmd: '/commit',  hint: ' <message>', source: 'built-in', desc: 'Stage changes and commit with a generated or given message.' },
  { cmd: '/compact', hint: '',           source: 'built-in', desc: 'Compact the conversation to free up context.' },
  { cmd: '/diff',    hint: '',           source: 'built-in', desc: 'Show working-tree changes since the last commit.' },
  { cmd: '/test',    hint: ' [filter]',  source: 'project',  desc: 'Run the gradle test suite, optionally filtered.' },
  { cmd: '/review',  hint: '',           source: 'user',     desc: 'Review the current diff for bugs and style issues.' },
  { cmd: '/explain', hint: ' <path>',    source: 'skill',    desc: 'Walk through what a file or function does.' },
  { cmd: '/pr',      hint: ' <title>',   source: 'project',  desc: 'Open a pull request for the current branch.' },
];

// visible when input starts with "/" and has no space yet; hidden while voice is active
function SlashPanel({ query, onPick }) {
  const q = query.slice(1).toLowerCase();
  const matches = SLASH_COMMANDS.filter(c => c.cmd.slice(1).toLowerCase().startsWith(q));
  if (!matches.length) return null;
  return (
    <div className="cc-scroll" style={{
      position: 'absolute', left: 12, right: 12, bottom: '100%', marginBottom: 8,
      background: T.raised, border: `1px solid ${T.border}`, borderRadius: 12,
      maxHeight: 240, overflowY: 'auto', zIndex: 20,
      boxShadow: '0 -8px 28px rgba(0,0,0,0.45)',
    }}>
      {matches.map((c, i) => (
        <div key={c.cmd}
          onClick={() => onPick(c.cmd)}
          style={{ padding: '9px 13px', cursor: 'pointer', borderBottom: i < matches.length - 1 ? `1px solid ${T.border}` : 'none' }}
          onPointerDown={e => e.preventDefault()}
        >
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span style={{ fontFamily: T.mono, fontSize: 13, fontWeight: 600, color: T.accent }}>{c.cmd}</span>
            {c.hint && <span style={{ fontFamily: T.mono, fontSize: 12, color: T.muted }}>{c.hint}</span>}
            <span style={{ flex: 1 }}></span>
            <span style={{ fontFamily: T.mono, fontSize: 10, color: T.muted, flexShrink: 0, whiteSpace: 'nowrap' }}>{c.source}</span>
          </div>
          <div style={{ fontFamily: T.ui, fontSize: 11, lineHeight: '15px', color: T.muted, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.desc}</div>
        </div>
      ))}
    </div>
  );
}

// ── composer ──────────────────────────────────────────────────
function Composer({ value, onChange, generating, onStop, onSend, voiceActive = false }) {
  const showStop = generating;
  const active = showStop || value.trim();
  // slash autocomplete: "/" prefix, no space typed yet, voice not recording/transcribing
  const slashOpen = !voiceActive && value.startsWith('/') && !value.includes(' ');
  return (
    <div style={{ flexShrink: 0, background: T.base, borderTop: `1px solid ${T.border}`, paddingBottom: 34, position: 'relative' }}>
      {slashOpen && <SlashPanel query={value} onPick={(cmd) => onChange(cmd)}/>}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, padding: '12px 12px 12px' }}>
        <button style={{ ...iconBtn, width: 40, height: 40 }} aria-label="Attach"><Plus c={T.sec}/></button>
        <div style={{ flex: 1, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, display: 'flex', alignItems: 'center', padding: '0 6px 0 14px', minHeight: 44 }}>
          <input
            value={value}
            onChange={e => onChange(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !generating) onSend(); }}
            placeholder="Message Claude…"
            style={{ all: 'unset', flex: 1, fontFamily: T.ui, fontSize: 14.5, color: T.text, padding: '11px 0', minWidth: 0 }}
          />
        </div>
        <button
          onClick={() => showStop ? onStop() : onSend()}
          disabled={!active}
          aria-label={showStop ? 'Stop' : 'Send'}
          style={{
            all: 'unset', boxSizing: 'border-box', cursor: active ? 'pointer' : 'default',
            width: 44, height: 44, borderRadius: 999, flexShrink: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: (!showStop && value.trim()) ? T.accent : T.surface,
            border: (!showStop && value.trim()) ? 'none' : `1px solid ${T.border}`,
          }}
        >
          {showStop
            ? <svg width="16" height="16" viewBox="0 0 16 16"><rect x="3.5" y="3.5" width="9" height="9" rx="2.2" fill={T.accent}/></svg>
            : <SendArrow c={value.trim() ? '#0E0F11' : T.muted}/>}
        </button>
      </div>
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────
function ChatScreen() {
  const [draft, setDraft] = React.useState('');
  const [generating, setGenerating] = React.useState(true);
  const [atBottom, setAtBottom] = React.useState(false);
  const scrollRef = React.useRef(null);

  const onScroll = () => {
    const el = scrollRef.current; if (!el) return;
    setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 36);
  };
  const jump = () => {
    const el = scrollRef.current; if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };
  React.useEffect(() => {
    // land at the live edge — re-run after layout + webfont load settle
    const el = scrollRef.current; if (!el) return;
    const toBottom = () => { el.scrollTop = el.scrollHeight; onScroll(); };
    toBottom();
    requestAnimationFrame(toBottom);
    const t = setTimeout(toBottom, 220);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(toBottom);
    return () => clearTimeout(t);
  }, []);

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base, position: 'relative' }}>
      <Header/>
      <div ref={scrollRef} onScroll={onScroll} className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '18px 16px 22px' }}>
        {/* prior exchange (session history) */}
        <SpeakerLabel who="You"/>
        <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '22px', color: T.text }}>where does the SSE stream get assembled?</div>
        <div style={{ marginTop: 16 }}>
          <SpeakerLabel who="Claude"/>
          <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
            It’s in <M>StreamParser.feed()</M> in the protocol module — partial frames buffer there until a blank line terminates the event.
          </div>
        </div>
        <div style={{ height: 1, background: T.border, margin: '20px 0' }}/>

        {/* current turn */}
        <SpeakerLabel who="You"/>
        <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '22px', color: T.text }}>add a unit test for the stream parser</div>

        {/* thinking */}
        <div style={{ marginTop: 20 }}><ThinkingRow/></div>

        {/* assistant turn */}
        <div style={{ marginTop: 16 }}>
          <SpeakerLabel who="Claude"/>
          <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
            I’ll add a focused test for <M>StreamParser</M> that feeds a chunked SSE response and asserts the reassembled events. Two cases to start with:
          </div>
          <ul style={{ margin: '10px 0 0', paddingLeft: 20, fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
            <li style={{ marginBottom: 4 }}>a happy path where one message arrives across several chunks, and</li>
            <li>a split-token case where a JSON delimiter straddles a chunk boundary.</li>
          </ul>
          <CodeBlock/>
          <BashRow/>
          <div style={{ marginTop: 14, fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
            Running the suite now to confirm the new test fails before I wire up the fix{generating && <span className="cc-caret"/>}
          </div>
          <div style={{ marginTop: 10, fontFamily: T.mono, fontSize: 11, color: T.muted, letterSpacing: 0.3 }}>↑1.2k&nbsp;&nbsp;↓340</div>
        </div>
      </div>

      {/* jump to latest */}
      {!atBottom && (
        <button onClick={jump} style={{
          all: 'unset', boxSizing: 'border-box', cursor: 'pointer',
          position: 'absolute', left: '50%', transform: 'translateX(-50%)', bottom: 104,
          display: 'flex', alignItems: 'center', gap: 6, height: 32, padding: '0 14px',
          background: T.raised, border: `1px solid ${T.border}`, borderRadius: 999,
          boxShadow: '0 6px 20px rgba(0,0,0,0.45)',
        }}>
          <Chevron d="down" c={T.sec} s={13} w={2}/>
          <span style={{ fontFamily: T.ui, fontSize: 12.5, fontWeight: 500, color: T.sec }}>Jump to latest</span>
        </button>
      )}

      <Composer
        value={draft} onChange={setDraft}
        generating={generating}
        onStop={() => setGenerating(false)}
        onSend={() => { if (draft.trim()) { setDraft(''); } }}
      />
    </div>
  );
}

function App() {
  const [scale, setScale] = React.useState(1);
  React.useEffect(() => {
    const fit = () => setScale(Math.min(1, (window.innerHeight - 36) / 874, (window.innerWidth - 36) / 402));
    fit(); window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);
  return (
    <div style={{ width: 402 * scale, height: 874 * scale }}>
      <div style={{ width: 402, height: 874, transform: `scale(${scale})`, transformOrigin: 'top left' }}>
        <IOSDevice dark><ChatScreen/></IOSDevice>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
