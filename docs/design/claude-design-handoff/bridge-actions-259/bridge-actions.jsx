// cc-pocket — Bridges card · action-area redesign
// Splits the action row into two tiers: process controls (chips) and one
// destructive text action in a hairline-divided footer. Card header + status
// line and the existing confirm sheet are untouched.

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', danger:'#E5604D',
  dangerTint:'rgba(229,96,77,0.10)', accentTint:'rgba(217,119,87,0.10)',
  mono:"'JetBrains Mono','PingFang SC','Noto Sans SC',ui-monospace,monospace",
  ui:"'Inter','Noto Sans SC','PingFang SC',-apple-system,system-ui,sans-serif",
};

// ── icons ─────────────────────────────────────────────────────
const Chevron = ({ d='left', c=T.sec, s=17, w=2 }) => {
  const p = { left:'M11 3L5 9l6 6', right:'M6 3l6 6-6 6' };
  return <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const Plus = ({ c=T.sec, s=19 }) => <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d="M10 4v12M4 10h12" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>;
// system warning triangle (settings-app.jsx), re-inked to danger
const Warn = ({ c=T.danger, s=13 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none" style={{ flexShrink:0 }}>
    <path d="M9 2.4l6.7 12.2H2.3L9 2.4z" stroke={c} strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M9 7v3.3" stroke={c} strokeWidth="1.5" strokeLinecap="round"/>
    <circle cx="9" cy="12.5" r="0.95" fill={c}/>
  </svg>
);
const Dot = ({ c, pulse }) => (
  <span className={pulse?'cc-pulse':''} style={{ width:6, height:6, borderRadius:999, background:c, boxShadow:pulse?`0 0 7px ${c}99`:'none', flexShrink:0 }}/>
);

// ── tier 1 · process control chip (whole control, never stretches) ──
function Chip({ label, tone='quiet', desktop }) {
  const accent = tone==='accent';
  return (
    <button style={{
      all:'unset', boxSizing:'border-box', cursor:'pointer', flexShrink:0, whiteSpace:'nowrap',
      height:desktop?32:36, padding:desktop?'0 13px':'0 15px', borderRadius:9,
      display:'inline-flex', alignItems:'center', justifyContent:'center',
      fontFamily:T.ui, fontSize:desktop?13:13.5, fontWeight:600, letterSpacing:0.2,
      color: accent?T.accent:T.text,
      background: accent?T.accentTint:T.raised,
      border:`1px solid ${accent?'rgba(217,119,87,0.42)':T.border}`,
    }}>{label}</button>
  );
}

// ── tier 2 · the one destructive action ───────────────────────
// text-level (no chip body) · danger ink · trailing 「…」 = "will ask first"
function Unbind({ desktop, hover }) {
  return (
    <button style={{
      all:'unset', boxSizing:'border-box', cursor:'pointer', whiteSpace:'nowrap',
      display:'inline-flex', alignItems:'center', gap:7,
      height:desktop?32:44, padding:desktop?'0 9px':'0 10px', borderRadius:8,
      background: hover?T.dangerTint:'transparent',
    }}>
      <Warn c={T.danger} s={desktop?12:13}/>
      <span style={{ fontFamily:T.ui, fontSize:desktop?13:13.5, fontWeight:600, color:T.danger, letterSpacing:0.2 }}>解除桥接…</span>
    </button>
  );
}

const PROCESS = {
  a:[['重启'],['停止'],['编辑']],
  b:[['启动','accent'],['编辑']],
  c:[],
};

// ── the redesigned action area ────────────────────────────────
function ActionArea({ st, desktop, hover, pad=14 }) {
  const chips = PROCESS[st];
  return (
    <div style={{ marginTop:desktop?11:12 }}>
      {chips.length>0 && (
        <div style={{ display:'flex', flexWrap:'wrap', gap:8, paddingBottom:desktop?11:12 }}>
          {chips.map(([l,tone])=><Chip key={l} label={l} tone={tone} desktop={desktop}/>)}
        </div>
      )}
      {st==='c' && (
        <p style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, margin:`0 0 ${desktop?10:11}px` }}>此桥接由你自行运行适配器。</p>
      )}
      <div style={{ height:1, background:T.border, margin:`0 -${pad}px` }}/>
      <div style={{ display:'flex', alignItems:'center', minHeight:desktop?42:44, marginRight:desktop?-9:-10 }}>
        <span style={{ flex:1 }}/>
        <Unbind desktop={desktop} hover={hover}/>
      </div>
    </div>
  );
}

// ── card (header + status line unchanged) ─────────────────────
const META = {
  a:{ name:'飞书 · 产品群机器人', dot:T.success, pulse:true,  line:'运行中 · 4h 12m · pid 48213' },
  b:{ name:'飞书 · 发布通知',     dot:T.muted,   pulse:false, line:'已停止 · 退出码 0 · 12 分钟前' },
  c:{ name:'飞书 · 值班告警',     dot:T.sec,     pulse:false, line:'已连接 · 自行运行 · 最近事件 2 分钟前' },
};
function CardHead({ m, desktop }) {
  return (
    <div>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ fontFamily:T.ui, fontSize:desktop?14.5:15.5, fontWeight:600, color:T.text, letterSpacing:0.1, minWidth:0, overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}>{m.name}</span>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:7, marginTop:5 }}>
        <Dot c={m.dot} pulse={m.pulse}/>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:0 }}>{m.line}</span>
      </div>
    </div>
  );
}
function BridgeCard({ st, w, desktop, hover }) {
  const pad = desktop?16:14;
  return (
    <div style={{ width:w, boxSizing:'border-box', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:`${desktop?13:13}px ${pad}px 3px` }}>
      <CardHead m={META[st]} desktop={desktop}/>
      <ActionArea st={st} desktop={desktop} hover={hover} pad={pad}/>
    </div>
  );
}

// ── deliverable 1 · Bridges screen (390) ─────────────────────
const HIT = 44;
function BridgesScreen() {
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', height:44, padding:'0 6px 0 4px' }}>
          <button style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, display:'flex', alignItems:'center', justifyContent:'center' }} aria-label="返回"><Chevron d="left"/></button>
          <span style={{ flex:1, fontFamily:T.ui, fontSize:15.5, fontWeight:600, color:T.text }}>桥接</span>
          <button style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, display:'flex', alignItems:'center', justifyContent:'center' }} aria-label="添加桥接"><Plus/></button>
        </div>
      </div>
      <div className="cc-scroll" style={{ flex:1, minHeight:0, overflow:'hidden', padding:'16px 14px 0' }}>
        <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.6, color:T.muted, padding:'0 2px 9px' }}>已连接</div>
        <div style={{ display:'flex', flexDirection:'column', gap:12 }}>
          <BridgeCard st="a" w="100%"/>
          <BridgeCard st="c" w="100%"/>
        </div>
        <p style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, margin:'14px 2px 0' }}>桥接把外部聊天工具接到本机守护进程。解除后需重新配对。</p>
      </div>
    </div>
  );
}

const FRAME = 390, PH = 724, SCALE = 0.9;
function Phone({ children }) {
  return (
    <div style={{ width:FRAME*SCALE, flexShrink:0 }}>
      <div style={{ width:FRAME, transform:`scale(${SCALE})`, transformOrigin:'top left', height:PH*SCALE }}>
        <IOSDevice dark width={FRAME} height={PH}><div style={{ position:'relative', height:'100%' }}>{children}</div></IOSDevice>
      </div>
    </div>
  );
}

// ── doc chrome ────────────────────────────────────────────────
const Eyebrow = ({ children }) => <p style={{ fontFamily:T.mono, fontSize:11.5, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 14px' }}>{children}</p>;
const H2 = ({ n, children }) => (
  <div style={{ display:'flex', alignItems:'baseline', gap:12, marginBottom:8 }}>
    <span style={{ fontFamily:T.mono, fontSize:12, fontWeight:700, color:T.accent }}>{n}</span>
    <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:0 }}>{children}</h2>
  </div>
);
const Lead = ({ children, w=880 }) => <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:w, margin:'0 0 26px', textWrap:'pretty' }}>{children}</p>;
const Note = ({ k, children, tone=T.sec, w=362 }) => (
  <div style={{ maxWidth:w, borderLeft:`1px solid ${T.border}`, paddingLeft:11, marginTop:14 }}>
    <div style={{ fontFamily:T.mono, fontSize:10, fontWeight:600, letterSpacing:0.7, color:T.muted, textTransform:'uppercase', marginBottom:4 }}>{k}</div>
    <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:tone, textWrap:'pretty' }}>{children}</div>
  </div>
);
const StateTag = ({ id, label }) => (
  <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:10 }}>
    <span style={{ fontFamily:T.mono, fontSize:10.5, fontWeight:600, color:T.accent, border:`1px solid ${T.border}`, borderRadius:5, padding:'2px 6px' }}>{id}</span>
    <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>{label}</span>
  </div>
);
const ST_LABEL = {
  a:'running · managed adapter',
  b:'stopped · managed adapter',
  c:'self-run adapter',
};

// ── deliverable 2 · close-up sheet ───────────────────────────
// "before" reference: four same-tier chips, revoke differing only in ink
function BeforeRow() {
  return (
    <div style={{ width:362, boxSizing:'border-box', background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:'13px 14px 14px' }}>
      <CardHead m={META.a}/>
      <div style={{ display:'flex', flexWrap:'wrap', gap:8, marginTop:12 }}>
        <Chip label="重启"/><Chip label="停止"/><Chip label="编辑"/>
        <button style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', height:36, padding:'0 15px', borderRadius:9, display:'inline-flex', alignItems:'center', fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.danger, background:T.raised, border:`1px solid ${T.border}`, letterSpacing:0.2 }}>撤销</button>
      </div>
    </div>
  );
}

// existing confirm sheet — silhouette only, not redesigned
function SheetGhost() {
  return (
    <div style={{ width:238, background:T.surface, border:`1px solid ${T.border}`, borderRadius:'16px 16px 6px 6px', padding:'16px 16px 14px', display:'flex', flexDirection:'column', gap:11, opacity:0.72 }}>
      <div style={{ display:'flex', justifyContent:'center' }}><Warn c={T.danger} s={26}/></div>
      <div style={{ display:'flex', flexDirection:'column', gap:7, alignItems:'center' }}>
        <div style={{ height:9, width:150, borderRadius:3, background:T.raised, border:`1px solid ${T.border}` }}/>
        <div style={{ height:6, width:196, borderRadius:999, background:T.border }}/>
        <div style={{ height:6, width:168, borderRadius:999, background:T.border }}/>
      </div>
      <div style={{ display:'flex', gap:8, marginTop:2 }}>
        <div style={{ flex:1, height:34, borderRadius:9, border:`1px solid ${T.border}`, background:T.raised }}/>
        <div style={{ flex:1, height:34, borderRadius:9, background:T.danger, opacity:0.9 }}/>
      </div>
    </div>
  );
}

function CloseUp() {
  return (
    <div style={{ display:'flex', flexWrap:'wrap', gap:32 }}>
      {['a','b','c'].map(st=>(
        <div key={st} style={{ width:362 }}>
          <StateTag id={st} label={ST_LABEL[st]}/>
          <BridgeCard st={st} w={362}/>
          <Note k={st==='a'?'tier 1 · three process chips':st==='b'?'tier 1 · start carries accent':'tier 1 · empty → hint takes the slot'}>
            {st==='a' && <>重启 · 停止 · 编辑 fit one FlowRow at 390dp. Each chip is a whole control: <code style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}>flex-shrink:0</code>, no grow, <code style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}>nowrap</code> — a two-glyph label can never break.</>}
            {st==='b' && <>启动 replaces 重启 / 停止 and takes accent ink on a 10% terracotta wash — the only accent in the card. Solid fills stay reserved for the sheet's confirm button.</>}
            {st==='c' && <>No adapter to restart, so the process row is absent and 「此桥接由你自行运行适配器。」 sits above the hairline instead. The footer never moves: the destructive action is at the same place in all three states.</>}
          </Note>
        </div>
      ))}
    </div>
  );
}

// ── deliverable 3 · desktop pane (480) ───────────────────────
function DesktopPane() {
  return (
    <div style={{ width:564, boxSizing:'border-box', background:T.base, border:`1px solid ${T.border}`, borderRadius:12, padding:'18px 20px 20px', flexShrink:0 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:14 }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>桥接</span>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>设置 › 桥接 · 480dp pane</span>
      </div>
      <div style={{ display:'flex', flexDirection:'column', gap:12 }}>
        {['a','b','c'].map(st=>(
          <div key={st} style={{ display:'flex', gap:12, alignItems:'flex-start' }}>
            <span style={{ fontFamily:T.mono, fontSize:10.5, fontWeight:600, color:T.muted, border:`1px solid ${T.border}`, borderRadius:5, padding:'2px 5px', marginTop:13, flexShrink:0 }}>{st}</span>
            <div style={{ flexShrink:0 }}><BridgeCard st={st} w={480} desktop hover={st==='b'}/></div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── page ──────────────────────────────────────────────────────
const RULES = [
  ['hairline tier break', 'A full-bleed 1px rule at the card\u2019s inner edges closes the process group and opens a footer zone. Not a gap — a boundary the eye reads as "different kind of thing".'],
  ['no chip body', 'The destructive action drops the border and the raised fill. It is text, not a control of the same species — so a thumb scanning four pill silhouettes finds only three.'],
  ['trailing 「…」', '解除桥接… — the system\u2019s ellipsis means "this opens a sheet". The promise that you get asked first is in the label itself, on mobile and desktop alike.'],
  ['danger ink + 12pt triangle', 'Danger red is the ink and the glyph, never the fill. Filled danger belongs to one place only: the confirm button inside the sheet.'],
  ['bottom-right, always', 'Same corner in every state, below the divider, on a 44dp target — far from the chip cluster your thumb aims at.'],
];

function Page() {
  return (
    <div style={{ maxWidth:1400, margin:'0 auto', padding:'56px 44px 120px' }}>
      <Eyebrow>cc-pocket · 设置 › 桥接 · card action area</Eyebrow>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, color:T.text, margin:'0 0 12px' }}>解除桥接 is not the fourth chip</h1>
      <Lead w={900}>
        The row shipped four identical outline chips — 重启 / 停止 / 编辑 / 撤销 — where the last one destroys the bridge credential and kills the adapter, and the only thing marking it is red ink. Worse, 「撤销」 reads as "undo my last step": the word promises reversal and performs the opposite. Two problems, so two fixes. <strong style={{ color:T.text }}>Tier</strong>: the three process controls stay chips; the destructive one leaves the chip species entirely and moves below a hairline. <strong style={{ color:T.text }}>Name</strong>: 「解除桥接…」 — unbind the bridge — with the ellipsis carrying the promise that you will be asked first.
      </Lead>
      <Lead w={900}>
        I considered a 「…」 overflow menu and rejected it: it hides the consequence one tap deeper, adds a menu surface the card doesn’t otherwise need, and on the self-run card the menu would hold exactly one item. The divided footer keeps the action honest and visible while making it impossible to mistake for a sibling.
      </Lead>

      <div style={{ display:'grid', gridTemplateColumns:'repeat(auto-fit,minmax(248px,1fr))', gap:'18px 26px', maxWidth:1300, borderTop:`1px solid ${T.border}`, paddingTop:22, marginBottom:58 }}>
        {RULES.map(([k,v])=>(
          <div key={k}>
            <div style={{ fontFamily:T.mono, fontSize:10.5, fontWeight:600, letterSpacing:0.6, color:T.accent, textTransform:'uppercase', marginBottom:5 }}>{k}</div>
            <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec, textWrap:'pretty' }}>{v}</div>
          </div>
        ))}
      </div>

      <section style={{ marginBottom:66 }}>
        <H2 n="01">Bridges screen · 390dp</H2>
        <Lead>Two cards, states (a) and (c). Header and status line are exactly as shipped; only the area under them changed. Note that the footer sits at the same height in both cards, so the destructive action forms its own quiet vertical column down the list instead of hiding at the end of a variable-length chip run.</Lead>
        <div style={{ display:'flex', flexWrap:'wrap', gap:44, alignItems:'flex-start' }}>
          <Phone><BridgesScreen/></Phone>
          <div style={{ maxWidth:420, paddingTop:6 }}>
            <Note k="what stayed" w={420}>Card skeleton, name, status line, chip metrics, and the FlowRow wrap behaviour for process controls. At 390dp the three Chinese chips still fit one row; a fourth control would still wrap as a whole unit rather than stretch.</Note>
            <Note k="what moved" w={420} tone={T.text}>撤销 left the chip row, was renamed 解除桥接…, and now lives in a hairline-divided footer, right-aligned, 44dp tall.</Note>
            <Note k="self-run card" w={420}>State (c) has no process controls at all. The hint line 「此桥接由你自行运行适配器。」 explains why the row is empty, and the destructive action is the card’s only control — which is precisely when it must not look like a chip you can casually poke.</Note>
          </div>
        </div>
      </section>

      <section style={{ marginBottom:66 }}>
        <H2 n="02">Action area · all three states, true size</H2>
        <Lead>Cards at their real 362dp width so the treatment can be judged at 1:1. Below, the current row for contrast, and the promise the ellipsis makes.</Lead>
        <CloseUp/>

        <div style={{ display:'flex', flexWrap:'wrap', gap:44, marginTop:52, alignItems:'flex-start' }}>
          <div>
            <StateTag id="现状" label="shipped · four same-tier chips"/>
            <BeforeRow/>
            <Note k="why it fails" tone={T.sec}>Four pills, one shape, one size, one border — the destructive one is distinguished by a colour a colour-blind user may not resolve, and by a verb that means "undo". Nothing about it says a sheet is coming, so the sheet arrives as a surprise interrogation rather than a confirmation.</Note>
          </div>
          <div>
            <StateTag id="→" label="the ellipsis keeps its promise"/>
            <div style={{ display:'flex', alignItems:'center', gap:18, flexWrap:'wrap' }}>
              <div style={{ width:250, background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:'0 14px 3px' }}>
                <div style={{ height:1, background:T.border, margin:'0 -14px' }}/>
                <div style={{ display:'flex', alignItems:'center', minHeight:44, marginRight:-10 }}>
                  <span style={{ flex:1 }}/><Unbind/>
                </div>
              </div>
              <Chevron d="right" c={T.muted} s={20} w={1.8}/>
              <SheetGhost/>
            </div>
            <Note k="unchanged" w={520}>The confirm sheet is not redesigned — shown greeked for reference only. The card’s job is to make its arrival feel promised: 「…」 plus danger ink plus the tier break add up to "this will ask you first", so the sheet reads as the answer to the tap rather than an obstacle.</Note>
            <Note k="label · en mirror" w={520} tone={T.text}>zh 「解除桥接…」 · en “Disconnect…” — both keep the ellipsis. 撤销 / “Revoke” retire; the credential wording lives inside the sheet where there is room to explain it.</Note>
          </div>
        </div>
      </section>

      <section>
        <H2 n="03">Desktop pane · 480dp</H2>
        <Lead>Same two tiers, same order, same corner — the grouping is one idea across platforms, only metrics tighten (chips 32dp, footer 42dp). State (b) is shown hovered.</Lead>
        <div style={{ display:'flex', flexWrap:'wrap', gap:40, alignItems:'flex-start' }}>
          <DesktopPane/>
          <div style={{ maxWidth:420, paddingTop:6 }}>
            <Note k="confirms first · desktop" w={420} tone={T.text}>The desktop action must carry the same promise. It keeps 「…」 verbatim and opens a centred confirm dialog — same danger glyph, bridge name in mono, two consequence lines, cancel + filled-danger confirm. No pointer platform exception: hover only adds a 10% danger wash and the pointer cursor, it never becomes a one-click destroy.</Note>
            <Note k="why no wider layout" w={420}>At 480dp there is room to put the destructive action inline on the right of the chip row, separated by a vertical rule. I didn’t: the horizontal divider is what teaches the two tiers, and a control that changes tier grammar between phone and desktop has to be re-learned twice.</Note>
            <Note k="keyboard" w={420}>Tab order runs chips → destructive last. Enter on the destructive action opens the dialog with 取消 focused, so the destructive path always needs one deliberate move.</Note>
          </div>
        </div>
      </section>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
