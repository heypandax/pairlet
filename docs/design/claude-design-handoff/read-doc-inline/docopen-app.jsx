// cc-pocket — "Read the doc, right here" · component board
// Three touchpoints: inline path link, tool-card open affordance, FileViewer states.

const {
  DO_DARK: D, DO_LIGHT: L,
  ClaudeGlyph, PathLink, ToolCard, Phone, FileViewer,
} = window;

// ── scaffolding ───────────────────────────────────────────────
function SectionLabel({ text, sub }) {
  return (
    <div style={{ marginBottom:26 }}>
      <div style={{ display:'flex', alignItems:'center', gap:14, marginBottom:sub?10:0 }}>
        <span style={{ fontFamily:D.ui, fontSize:13, fontWeight:600, letterSpacing:1, textTransform:'uppercase', color:D.sec, whiteSpace:'nowrap' }}>{text}</span>
        <span style={{ flex:1, height:1, background:D.border }}/>
      </div>
      {sub && <div style={{ fontFamily:D.ui, fontSize:14, lineHeight:'22px', color:D.muted, maxWidth:760 }}>{sub}</div>}
    </div>
  );
}
function Spec({ n, title, note, tokens = D }) {
  const T = tokens;
  return (
    <div style={{ display:'flex', alignItems:'baseline', gap:10, marginBottom:12 }}>
      <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:600, color:T.accent, border:`1px solid ${T.border}`, borderRadius:6, padding:'2px 7px', flexShrink:0 }}>{n}</span>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, flexWrap:'wrap', minWidth:0 }}>
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{title}</span>
        <span style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.muted }}>{note}</span>
      </div>
    </div>
  );
}
// a phone-width assistant chat surface each specimen sits on
function ChatSurface({ tokens, children }) {
  const T = tokens;
  return (
    <div style={{ width:392, background:T.base, border:`1px solid ${T.border}`, borderRadius:18, padding:'18px 18px 20px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:12 }}>
        <ClaudeGlyph c={T.accent} s={13}/>
        <span style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.4, color:T.muted }}>CLAUDE</span>
      </div>
      {children}
    </div>
  );
}
const proseStyle = (T) => ({ fontFamily:T.ui, fontSize:15, lineHeight:'26px', color:T.text });

// ── COMPONENT 1 — inline openable path ────────────────────────
function Comp1({ tokens }) {
  const T = tokens;
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:22 }}>
      <div>
        <Spec n="a" title="Openable path, own line" note="Faint terracotta chip + doc glyph. Reads as “tap to open”, not a primary button." tokens={T}/>
        <ChatSurface tokens={T}>
          <div style={proseStyle(T)}>Done — I wrote the summary to:</div>
          <div style={{ marginTop:10 }}>
            <PathLink tokens={T} variant="pill">~/Desktop/report.md</PathLink>
          </div>
        </ChatSurface>
      </div>

      <div>
        <Spec n="b" title="Path mid-sentence" note="Same token, lighter weight: tinted underline + glyph, prose colour kept so it doesn’t shout." tokens={T}/>
        <ChatSurface tokens={T}>
          <div style={proseStyle(T)}>
            I saved the migration notes to{' '}
            <PathLink tokens={T} variant="inline">~/Desktop/report.md</PathLink>{' '}
            and left the raw diff next to it.
          </div>
        </ChatSurface>
      </div>

      <div>
        <Spec n="c" title="Pressed feedback" note="Brief terracotta wash on tap — the only moment it saturates. Reverts on release." tokens={T}/>
        <ChatSurface tokens={T}>
          <div style={proseStyle(T)}>
            Full write-up is in{' '}
            <PathLink tokens={T} variant="inline" pressed>~/Desktop/report.md</PathLink>{' '}
            if you want the details.
          </div>
        </ChatSurface>
      </div>

      <div>
        <Spec n="d" title="Inside a code block" note="Underline-only, no chip or glyph — signals openable without fighting the block’s own copy button." tokens={T}/>
        <ChatSurface tokens={T}>
          <div style={proseStyle(T)}>Open it from the shell if you prefer:</div>
          <div style={{ marginTop:10, background:T.page, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden' }}>
            <div style={{ display:'flex', alignItems:'center', padding:'6px 10px', borderBottom:`1px solid ${T.border}`, background:T.surface }}>
              <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>bash</span>
              <span style={{ flex:1 }}/>
              <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted }}>copy</span>
            </div>
            <pre style={{ margin:0, padding:'10px 12px', fontFamily:T.mono, fontSize:12, lineHeight:'20px', color:T.text, overflowX:'auto' }}>
{'$ open '}<PathLink tokens={T} variant="code">~/Desktop/report.md</PathLink>
            </pre>
          </div>
        </ChatSurface>
      </div>
    </div>
  );
}

// ── COMPONENT 2 — Write / Edit tool card ──────────────────────
function Comp2({ tokens }) {
  const T = tokens;
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:22 }}>
      <div>
        <Spec n="a" title="Write · collapsed" note="Path chip = open (doc glyph + open arrow). Divider, then chevron = expand. Two legible hit regions." tokens={T}/>
        <ChatSurface tokens={T}>
          <ToolCard tokens={T} tool="Write" path="~/project/report.md"/>
        </ChatSurface>
      </div>
      <div>
        <Spec n="b" title="Write · expanded" note="Tapping the name / chevron area reveals the written preview; the path chip is untouched and still opens." tokens={T}/>
        <ChatSurface tokens={T}>
          <ToolCard tokens={T} tool="Write" path="~/project/report.md" expanded/>
        </ChatSurface>
      </div>
      <div>
        <Spec n="c" title="Edit · path pressed" note="Terracotta wash confirms the open target under the thumb — distinct from the expand region." tokens={T}/>
        <ChatSurface tokens={T}>
          <ToolCard tokens={T} tool="Edit" path="~/project/auth/TokenStore.kt" pathPressed/>
        </ChatSurface>
      </div>
      <div>
        <Spec n="d" title="Edit · expanded diff" note="Expanded detail shows the −/+ hunk; the open affordance stays consistent with Write." tokens={T}/>
        <ChatSurface tokens={T}>
          <ToolCard tokens={T} tool="Edit" path="~/project/auth/TokenStore.kt" expanded/>
        </ChatSurface>
      </div>
    </div>
  );
}

// ── theme column wrapper for C1 / C2 ──────────────────────────
function ThemeColumn({ tokens, label, wrapped, children }) {
  const T = tokens;
  const inner = (
    <div>
      <div style={{ fontFamily:D.mono, fontSize:11, letterSpacing:1, textTransform:'uppercase', color: wrapped ? L.sec : D.sec, marginBottom:18 }}>{label}</div>
      {children}
    </div>
  );
  if (!wrapped) return inner;
  return (
    <div style={{ background:L.page, border:`1px solid ${D.border}`, borderRadius:16, padding:'26px 26px 30px' }}>{inner}</div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Board() {
  return (
    <div style={{ width:1320, margin:'0 auto', padding:'56px 48px 110px', fontFamily:D.ui }}>
      {/* header */}
      <div style={{ marginBottom:52 }}>
        <div style={{ fontFamily:D.mono, fontSize:11.5, letterSpacing:1.4, textTransform:'uppercase', color:D.accent, marginBottom:14 }}>cc-pocket · feature sheet</div>
        <div style={{ fontFamily:D.ui, fontSize:32, fontWeight:700, letterSpacing:-0.5, color:D.text, marginBottom:12 }}>Read the doc, right here</div>
        <div style={{ fontFamily:D.ui, fontSize:15, lineHeight:'24px', color:D.sec, maxWidth:780 }}>
          When Claude finishes by writing a markdown report, the path shouldn’t be dead text on the phone. Every regex-matched
          path lights up <span style={{ color:D.text }}>optimistically</span> — the phone can’t verify the file exists first, so the
          affordance stays calm and low-commitment (“tap to try opening”), never a guaranteed-valid CTA. A failed tap lands in the
          file viewer’s error state, not the chat. Terracotta appears only on the tap affordance and the primary action.
        </div>
      </div>

      {/* COMPONENT 1 */}
      <SectionLabel text="Component 1 · Tappable path in a message" sub="A file path in the assistant’s markdown stream becomes an affordance. Long-press still opens the existing copy sheet — this only adds tap-to-open."/>
      <div style={{ display:'flex', gap:40, flexWrap:'wrap', alignItems:'flex-start', marginBottom:72 }}>
        <ThemeColumn tokens={D} label="Dark"><Comp1 tokens={D}/></ThemeColumn>
        <ThemeColumn tokens={L} label="Light" wrapped><Comp1 tokens={L}/></ThemeColumn>
      </div>

      {/* COMPONENT 2 */}
      <SectionLabel text="Component 2 · Open from a Write / Edit card" sub="One card, two jobs, resolved spatially: the path chip opens the file; the tool-name and chevron area expand the preview. No hidden long-press."/>
      <div style={{ display:'flex', gap:40, flexWrap:'wrap', alignItems:'flex-start', marginBottom:72 }}>
        <ThemeColumn tokens={D} label="Dark"><Comp2 tokens={D}/></ThemeColumn>
        <ThemeColumn tokens={L} label="Light" wrapped><Comp2 tokens={L}/></ThemeColumn>
      </div>

      {/* COMPONENT 3 */}
      <SectionLabel text="Component 3 · Reading in the file viewer" sub="The existing full-screen viewer, confirmed as a reading surface — plus the new error/export fallback when an optimistic tap can’t reach the file, and the brief loading state. Dark only."/>
      <div style={{ display:'flex', gap:44, flexWrap:'wrap', alignItems:'flex-start' }}>
        <Comp3Phone n="a" title="Reading state" note="Comfortable line length, heading rhythm, a real report scrolling. Reading, not editing.">
          <FileViewer mode="read" fileName="report.md"/>
        </Comp3Phone>
        <Comp3Phone n="b" title="Error → export" note="Optimistic tap failed: calm reason, one-line explainer, single terracotta primary action. Approving once opens it inline here — no separate app.">
          <FileViewer mode="error" fileName="report.md"/>
        </Comp3Phone>
        <Comp3Phone n="c" title="Loading" note="Brief fetch while the file streams from the paired computer.">
          <FileViewer mode="loading" fileName="report.md"/>
        </Comp3Phone>
      </div>
    </div>
  );
}

function Comp3Phone({ n, title, note, children }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:16 }}>
      <div style={{ maxWidth:384 }}>
        <div style={{ display:'flex', alignItems:'baseline', gap:10, marginBottom:5 }}>
          <span style={{ fontFamily:D.mono, fontSize:11, fontWeight:600, color:D.accent, border:`1px solid ${D.border}`, borderRadius:6, padding:'2px 7px' }}>{n}</span>
          <span style={{ fontFamily:D.ui, fontSize:14, fontWeight:600, color:D.text }}>{title}</span>
        </div>
        <div style={{ fontFamily:D.ui, fontSize:12.5, lineHeight:'18px', color:D.muted, paddingLeft:2 }}>{note}</div>
      </div>
      <Phone>{children}</Phone>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Board/>);
