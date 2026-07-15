// cc-pocket — desktop · Skills & plugins browser (⌘K → "Browse skills & plugins")
// Read-only. Centered modal overlay in the Changes-browser language: ~960pt, r14, hairline, footer hint bar.
// Reuses desktop-core.jsx globals: T, I, Key, Dot.

// ── left-rail data ────────────────────────────────────────────
const SKILLS = [
  { id:'brand-voice',   name:'brand-voice',   desc:'Rewrite copy in the house style', tag:'project' },
  { id:'pdf-tools',     name:'pdf-tools',     desc:'Fill, merge, and split PDF files' },
  { id:'web-research',  name:'web-research',  desc:'Search and summarise sources' },
  { id:'commit-helper', name:'commit-helper', desc:'Draft conventional-commit messages', tag:'project' },
  { id:'sql-explain',   name:'sql-explain',   desc:'Annotate and optimise queries' },
  { id:'changelog',     name:'changelog',     desc:'Assemble release notes from merged PRs' },
  { id:'a11y-audit',    name:'a11y-audit',    desc:'Flag WCAG issues in a diff' },
  { id:'test-scaffold', name:'test-scaffold', desc:'Generate table-driven test stubs' },
];
const PLUGINS = [
  { id:'git-toolkit', name:'git-toolkit', desc:'Branch, commit, PR and rebase flow', tag:'v1.2.0' },
  { id:'linear',      name:'linear',      desc:'Read and update Linear issues',      tag:'v0.4.1' },
  { id:'postgres',    name:'postgres',    desc:'Query and inspect Postgres',         tag:'v2.0.3' },
  { id:'sentry',      name:'sentry',      desc:'Pull and triage Sentry events',      tag:'v1.1.0' },
];

// ── detail content (man-page style) ───────────────────────────
const DETAIL = {
  'brand-voice': {
    kind:'skill', name:'brand-voice',
    lede:'Rewrites drafted copy to match the Acme brand voice — clipped, concrete, second-person — and flags sentences that drift off-tone before you ship them.',
    facts:[
      ['scope','project','ui'],
      ['path','~/dev/api/.claude/skills/brand-voice','mono'],
      ['version','1.3.0','mono'],
      ['marketplace','—','muted'],
      ['author','acme-design','ui'],
      ['commands','—','muted'],
    ],
    bodyTitle:'SKILL.md',
    body:[
      'Use this skill whenever you produce user-facing text — release notes, error strings, onboarding copy. It does not touch code comments or commit messages.',
      'The voice is defined in `voice.md` alongside this file. Load it first, then rewrite the draft one sentence at a time, preferring the active voice and dropping hedges like `probably` and `just`.',
      'When a sentence cannot be salvaged, leave it untouched and add a `<!-- tone -->` note above it so a human can decide. Never invent product claims to fill a gap.',
    ],
    trimmed:true,
  },
  'git-toolkit': {
    kind:'plugin', name:'git-toolkit',
    lede:'A bundle of git-flavoured slash commands and a background hook that keeps the working tree summary fresh in context. Installed from the Anthropic marketplace.',
    facts:[
      ['scope','user','ui'],
      ['path','~/.claude/plugins/git-toolkit','mono'],
      ['version','1.2.0','mono'],
      ['marketplace','anthropics/claude-plugins','mono'],
      ['author','Anthropic','ui'],
      ['commands','__cmds__','cmds'],
    ],
    commands:['/commit','/pr','/review','/rebase'],
    bodyTitle:'README.md',
    body:[
      'Adds four commands to any session on this computer. `/commit` stages the current changes and writes a conventional-commit message from the diff; pass a scope to override the inferred one.',
      '`/pr` opens a pull request against the default branch, filling the body from the commits on the branch. `/rebase` runs an interactive rebase onto `main` and resolves trivial conflicts automatically.',
      'The plugin registers a `post-commit` hook that refreshes the tree summary — disable it in `settings.json` under `plugins.git-toolkit.hooks` if it gets noisy.',
    ],
    trimmed:true,
  },
};

// ── inline `code` renderer ────────────────────────────────────
function Prose({ text }) {
  const parts = text.split(/(`[^`]+`)/g);
  return (
    <span>
      {parts.map((p, i) => p.startsWith('`') && p.endsWith('`')
        ? <span key={i} style={{ fontFamily:T.mono, fontSize:12, color:T.text, background:T.raised, border:`1px solid ${T.border}`, borderRadius:4, padding:'1px 5px' }}>{p.slice(1,-1)}</span>
        : <React.Fragment key={i}>{p}</React.Fragment>)}
    </span>
  );
}

// ── left rail ─────────────────────────────────────────────────
function RailHeader({ children }) {
  return <div style={{ padding:'16px 16px 7px', fontFamily:T.ui, fontSize:10, fontWeight:600, letterSpacing:1.4, color:T.muted, textTransform:'uppercase' }}>{children}</div>;
}
function RailRow({ row, selected }) {
  return (
    <div className={selected ? '' : 'dk-row'} style={{ position:'relative', display:'flex', alignItems:'center', gap:10, padding:'8px 14px 9px', cursor:'pointer',
      background: selected ? 'rgba(217,119,87,0.12)' : 'transparent' }}>
      {selected && <span style={{ position:'absolute', left:0, top:6, bottom:6, width:2, borderRadius:2, background:T.accent }}/>}
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontFamily:T.mono, fontSize:13, fontWeight:500, color: selected ? T.text : T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{row.name}</div>
        <div style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted, marginTop:2, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{row.desc}</div>
      </div>
      {row.tag && (
        <span style={{ flexShrink:0, fontFamily:T.mono, fontSize:10, fontWeight:500,
          color: row.tag==='project' ? T.accent : T.sec,
          border:`1px solid ${row.tag==='project' ? T.accent+'55' : T.border}`, borderRadius:999, padding:'1px 7px' }}>{row.tag}</span>
      )}
    </div>
  );
}
function Rail({ selected }) {
  return (
    <div className="dk-scroll" style={{ width:280, flexShrink:0, borderRight:`1px solid ${T.border}`, overflowY:'auto', background:T.surface }}>
      <RailHeader>Skills</RailHeader>
      {SKILLS.map(s => <RailRow key={s.id} row={s} selected={s.id===selected}/>)}
      <RailHeader>Plugins</RailHeader>
      {PLUGINS.map(p => <RailRow key={p.id} row={p} selected={p.id===selected}/>)}
      <div style={{ height:10 }}/>
    </div>
  );
}

// ── right detail pane (man-page) ──────────────────────────────
function FactRow({ k, v, kind, cmds }) {
  return (
    <div style={{ display:'flex', alignItems:'flex-start', gap:16, padding:'6px 0' }}>
      <span style={{ width:96, flexShrink:0, fontFamily:T.mono, fontSize:12, color:T.muted, textAlign:'right' }}>{k}</span>
      {kind==='cmds'
        ? <span style={{ display:'flex', flexWrap:'wrap', gap:6 }}>{cmds.map(c => <span key={c} style={{ fontFamily:T.mono, fontSize:12, color:T.accent, background:'rgba(217,119,87,0.10)', border:`1px solid ${T.accent}44`, borderRadius:6, padding:'1px 7px' }}>{c}</span>)}</span>
        : <span style={{ fontFamily: kind==='mono' ? T.mono : T.ui, fontSize: kind==='mono' ? 12.5 : 13, color: kind==='muted' ? T.muted : T.text, wordBreak:'break-all' }}>{v}</span>}
    </div>
  );
}
function DetailPane({ id }) {
  const d = DETAIL[id];
  return (
    <div className="dk-scroll" style={{ flex:1, minWidth:0, overflowY:'auto' }}>
      <div style={{ padding:'26px 32px 30px', maxWidth:600 }}>
        {/* headline */}
        <div style={{ display:'flex', alignItems:'center', gap:10, marginBottom:12 }}>
          <span style={{ fontFamily:T.mono, fontSize:25, fontWeight:600, color:T.text, letterSpacing:-0.4 }}>{d.name}</span>
          <span style={{ fontFamily:T.ui, fontSize:10.5, fontWeight:600, letterSpacing:1, textTransform:'uppercase', color:T.sec, border:`1px solid ${T.border}`, borderRadius:999, padding:'2px 9px' }}>{d.kind}</span>
        </div>
        {/* lede */}
        <div style={{ fontFamily:T.ui, fontSize:15, lineHeight:'24px', color:T.sec, marginBottom:22 }}>{d.lede}</div>
        {/* facts table */}
        <div style={{ borderTop:`1px solid ${T.border}`, borderBottom:`1px solid ${T.border}`, padding:'10px 0' }}>
          {d.facts.map(([k,v,kind]) => <FactRow key={k} k={k} v={v} kind={kind} cmds={d.commands}/>)}
        </div>
        {/* body excerpt */}
        <div style={{ margin:'26px 0 12px', display:'flex', alignItems:'center', gap:10 }}>
          <span style={{ fontFamily:T.mono, fontSize:11, letterSpacing:0.6, color:T.muted }}>{d.bodyTitle}</span>
          <span style={{ flex:1, height:1, background:T.border }}/>
        </div>
        {d.body.map((p, i) => (
          <p key={i} style={{ fontFamily:T.ui, fontSize:14, lineHeight:'23px', color:T.text, margin:'0 0 14px' }}><Prose text={p}/></p>
        ))}
        {d.trimmed && (
          <div style={{ fontFamily:T.ui, fontSize:12.5, fontStyle:'italic', color:T.muted, marginTop:4 }}>trimmed — open the file on the computer for the rest</div>
        )}
      </div>
    </div>
  );
}

// ── state panes (loading / empty) ─────────────────────────────
function CenterState({ children }) {
  return <div style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', gap:16, padding:'0 48px' }}>{children}</div>;
}
function LoadingPane() {
  return (
    <CenterState>
      <svg className="dk-spin" width="30" height="30" viewBox="0 0 30 30" fill="none"><circle cx="15" cy="15" r="11" stroke={T.border} strokeWidth="2.6"/><path d="M15 4a11 11 0 0111 11" stroke={T.accent} strokeWidth="2.6" strokeLinecap="round"/></svg>
      <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.muted }}>Reading skills from Lidapeng-MBP…</span>
    </CenterState>
  );
}
function EmptyPane({ icon, title, sub }) {
  return (
    <CenterState>
      <span style={{ width:56, height:56, borderRadius:15, background:T.surface, border:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center' }}>{icon}</span>
      <div style={{ fontFamily:T.ui, fontSize:16, fontWeight:600, color:T.sec }}>{title}</div>
      <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.muted, textAlign:'center', maxWidth:340 }}>{sub}</div>
    </CenterState>
  );
}

// ── overlay chrome ────────────────────────────────────────────
// mode: 'skill' | 'plugin' | 'loading' | 'stale' | 'none'
function Overlay({ mode }) {
  const populated = mode==='skill' || mode==='plugin';
  const selected = mode==='skill' ? 'brand-voice' : mode==='plugin' ? 'git-toolkit' : null;
  const count = (mode==='stale'||mode==='none'||mode==='loading') ? null : '23 skills · 4 plugins';
  return (
    <div style={{ width:960, height:620, background:T.base, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden',
      display:'flex', flexDirection:'column', boxShadow:'0 40px 100px -30px rgba(0,0,0,0.8)' }}>
      {/* header */}
      <div style={{ height:52, flexShrink:0, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', gap:12, padding:'0 16px 0 20px' }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Skills &amp; plugins</span>
        {count && <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.muted }}>{count}</span>}
        <span style={{ flex:1 }}/>
        <button className="dk-row" style={{ all:'unset', cursor:'pointer', width:30, height:30, borderRadius:8, display:'flex', alignItems:'center', justifyContent:'center' }} aria-label="Close">{I.x(T.sec,16)}</button>
      </div>
      {/* body */}
      <div style={{ flex:1, minHeight:0, display:'flex' }}>
        {populated ? (
          <>
            <Rail selected={selected}/>
            <DetailPane id={selected}/>
          </>
        ) : mode==='loading' ? <LoadingPane/>
        : mode==='stale' ? <EmptyPane icon={I.warn(T.warning,26)} title="Can’t browse skills" sub="The cc-pocket daemon on this computer is too old. Update it to read installed skills and plugins."/>
        : <EmptyPane icon={I.bolt(T.muted,24)} title="Nothing installed" sub="No skills or plugins found on Lidapeng-MBP. Add a SKILL.md under .claude/skills or install a plugin from a marketplace."/>}
      </div>
      {/* footer hint bar */}
      <div style={{ height:40, flexShrink:0, borderTop:`1px solid ${T.border}`, background:T.surface, display:'flex', alignItems:'center', gap:8, padding:'0 18px' }}>
        <Key>↑↓</Key><span style={{ fontFamily:T.ui, fontSize:12, color:T.muted }}>switch entry</span>
        <span style={{ fontFamily:T.ui, fontSize:12, color:T.border }}>·</span>
        <Key>esc</Key><span style={{ fontFamily:T.ui, fontSize:12, color:T.muted }}>close</span>
      </div>
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Backdrop({ children }) {
  return (
    <div style={{ position:'relative', borderRadius:14, overflow:'hidden', background:'radial-gradient(120% 90% at 50% 0%, #121417 0%, #0B0C0D 60%, #08090A 100%)', padding:'44px 0', display:'flex', justifyContent:'center' }}>
      <div style={{ position:'absolute', inset:0, background:'rgba(8,9,10,0.5)' }}/>
      <div style={{ position:'relative' }}>{children}</div>
    </div>
  );
}
function Divider({ children, sub }) {
  return (
    <div style={{ margin:'56px 0 22px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:12 }}>
        <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, letterSpacing:1, textTransform:'uppercase', color:T.sec }}>{children}</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      {sub && <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.muted, marginTop:10, maxWidth:760 }}>{sub}</div>}
    </div>
  );
}

function Board() {
  return (
    <div style={{ width:1120, margin:'0 auto', padding:'56px 40px 96px' }}>
      <div style={{ fontFamily:T.mono, fontSize:11.5, letterSpacing:1.4, textTransform:'uppercase', color:T.accent, marginBottom:14 }}>cc-pocket · desktop</div>
      <div style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.4, color:T.text, marginBottom:12 }}>Skills &amp; plugins browser</div>
      <div style={{ fontFamily:T.ui, fontSize:15, lineHeight:'24px', color:T.sec, maxWidth:780 }}>
        Opened from the ⌘K palette — a read-only look at the Claude Code extensions installed on the paired computer. A
        centered modal in the same language as the Changes diff browser: two panes split by a hairline, a man-page detail
        view on the right, and a keyboard-hint footer.
      </div>

      <Divider sub="Left rail groups SKILLS and PLUGINS; the selected row gets a soft terracotta wash. The detail pane reads like a well-set man page — mono headline, a lede, an aligned facts table, then the SKILL.md body with inline code spans.">Skill selected — rich frontmatter</Divider>
      <Backdrop><Overlay mode="skill"/></Backdrop>

      <Divider sub="Same chrome, a plugin selected: the facts table carries a version and marketplace, and the commands row renders the plugin’s slash commands as terracotta chips.">Plugin selected — version &amp; commands</Divider>
      <Backdrop><Overlay mode="plugin"/></Backdrop>

      <Divider sub="Transient and empty states keep the header and footer bars; only the body swaps.">Loading &amp; empty states</Divider>
      <div style={{ display:'flex', flexDirection:'column', gap:32 }}>
        <div><div style={stateLabel}>Loading</div><Backdrop><Overlay mode="loading"/></Backdrop></div>
        <div><div style={stateLabel}>Stale daemon</div><Backdrop><Overlay mode="stale"/></Backdrop></div>
        <div><div style={stateLabel}>Nothing installed</div><Backdrop><Overlay mode="none"/></Backdrop></div>
      </div>
    </div>
  );
}
const stateLabel = { fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:10, letterSpacing:0.4 };

ReactDOM.createRoot(document.getElementById('root')).render(<Board/>);
