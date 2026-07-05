// cc-pocket — "Claude asks you" · board (chat context + card-level variants)
// Uses globals from qm-core.jsx + qm-card.jsx + ios-frame.jsx.

// ── demo content (question text + options in zh; chrome stays EN) ──
const Q_STORAGE = {
  header: '存储方案',
  text: '重构后，登录令牌应该存到哪里？',
  multi: false,
  options: [
    { label: '系统钥匙串', desc: '交给 Keychain 保管，最安全' },
    { label: '加密配置文件', desc: '写入本地加密的 config 文件' },
    { label: '仅内存保存', desc: '只在本次会话保留，重启即清空' },
  ],
};
const Q_MULTI = [
  Q_STORAGE,
  {
    header: '过期策略',
    text: '令牌过期后应该怎么处理？',
    multi: false,
    options: [
      { label: '静默刷新', desc: '用 refresh token 自动续期' },
      { label: '跳转登录', desc: '让用户重新登录一次' },
      { label: '延长有效期', desc: '直接把有效期设为 30 天' },
    ],
  },
  {
    header: '错误处理',
    text: '鉴权失败时给用户什么提示？',
    multi: false,
    options: [
      { label: '内联提示', desc: '在登录表单下方显示错误' },
      { label: '弹窗提醒', desc: '用系统弹窗告知失败原因' },
    ],
  },
];
const Q_CLEANUP = {
  header: '顺带清理',
  text: '重构时顺带清理哪些内容？（可多选）',
  multi: true,
  options: [
    { label: '删除废弃接口', desc: '移除 v1 登录端点' },
    { label: '补充单元测试', desc: '为 token 刷新补测试' },
    { label: '更新文档', desc: '同步 README 的鉴权说明' },
    { label: '整理依赖', desc: '移除未使用的 auth 库' },
  ],
};
const ANSWERED_ITEMS = [
  { q: '存储方案', a: '系统钥匙串' },
  { q: '过期策略', a: '静默刷新' },
  { q: '顺带清理', a: '删除废弃接口、补充单元测试' },
];

// ── chat chrome ───────────────────────────────────────────────
function ChatHeader() {
  return (
    <div style={{ flexShrink: 0, background: T.base, paddingTop: 52, borderBottom: `1px solid ${T.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px 0 4px', height: 44 }}>
        <span style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}><Chevron d="left" c={T.sec} s={17} w={2}/></span>
        <span style={{ flex: 1, fontFamily: T.ui, fontSize: 15, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>重构 auth 模块</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 5, marginRight: 6, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 999, padding: '0 9px', height: 26, flexShrink: 0 }}>
          <span style={{ fontFamily: T.mono, fontSize: 11, color: T.sec }}>default</span>
          <Chevron d="down" c={T.muted} s={10} w={1.7}/>
        </span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '0 16px 9px' }}>
        <span className="qm-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: T.success, boxShadow: `0 0 7px ${T.success}99`, flexShrink: 0 }}/>
        <span style={{ fontFamily: T.mono, fontSize: 10.5, color: T.sec, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color: T.muted }}>~/proj/app/cc-pocket</span></span>
      </div>
    </div>
  );
}
const Label = ({ who }) => <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.5, color: T.muted, marginBottom: 7 }}>{who}</div>;
const M = ({ children }) => <span style={{ fontFamily: T.mono, fontSize: 12.5, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 5, padding: '1px 5px', color: T.text }}>{children}</span>;

// the exchange that leads up to the question
function SetupExchange({ short }) {
  return (
    <>
      {!short && (
        <>
          <Label who="你"/>
          <div style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '22px', color: T.text, marginBottom: 20 }}>重构 auth 模块，先把登录令牌的存储方式定下来</div>
        </>
      )}
      <Label who="Claude"/>
      <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>
        看了下现在的实现 —— 令牌是明文写在 <M>UserDefaults</M> 里的。动手之前，想先跟你确认几个方向。
      </div>
    </>
  );
}

function Composer({ dim, crop }) {
  return (
    <div style={{ flexShrink: 0, background: T.base, borderTop: `1px solid ${T.border}`, paddingBottom: crop ? 14 : 34, opacity: dim ? 0.45 : 1 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, padding: '12px 12px' }}>
        <span style={{ width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}><Plus c={T.sec}/></span>
        <div style={{ flex: 1, background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, display: 'flex', alignItems: 'center', padding: '0 14px', minHeight: 44 }}>
          <span style={{ fontFamily: T.ui, fontSize: 14.5, color: T.muted }}>发消息给 Claude…</span>
        </div>
        <span style={{ width: 44, height: 44, borderRadius: 999, background: T.surface, border: `1px solid ${T.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}><SendArrow c={T.muted}/></span>
      </div>
    </div>
  );
}

// ── full Chat screen: transcript · [dock] · composer ──────────
function ChatScreen({ dock, streamExtra }) {
  const scrollRef = React.useRef(null);
  React.useEffect(() => {
    const el = scrollRef.current; if (!el) return;
    const toBottom = () => { el.scrollTop = el.scrollHeight; };
    toBottom(); requestAnimationFrame(toBottom);
    const t = setTimeout(toBottom, 220);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(toBottom);
    return () => clearTimeout(t);
  }, []);
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <ChatHeader/>
      <div ref={scrollRef} className="qm-scroll" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '18px 16px 18px' }}>
        <SetupExchange/>
        {streamExtra && <div style={{ marginTop: 20 }}>{streamExtra}</div>}
      </div>
      {dock && <div style={{ flexShrink: 0, padding: '2px 0 10px' }}>{dock}</div>}
      <Composer/>
    </div>
  );
}

// ── keyboard-open screen: card owns input, composer hidden ────
function KeyboardScreen({ dock }) {
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: T.base }}>
      <ChatHeader/>
      <div className="qm-scroll" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '16px 16px 12px' }}>
        <SetupExchange short/>
      </div>
      <div style={{ flexShrink: 0, padding: '2px 0 8px' }}>{dock}</div>
      <IOSKeyboard dark/>
    </div>
  );
}

// ── a cropped "docked above composer" frame for card variants ─
function DockFrame({ children, composer = true }) {
  return (
    <div style={{ width: 402, background: T.base, borderRadius: 26, border: `1px solid ${T.border}`, overflow: 'hidden', boxShadow: '0 20px 50px rgba(0,0,0,0.4)' }}>
      <div style={{ padding: '18px 0 10px' }}>{children}</div>
      {composer && <Composer crop/>}
    </div>
  );
}
// a cropped stream frame (bottom of chat) for in-stream states
function StreamFrame({ children }) {
  return (
    <div style={{ width: 402, background: T.base, borderRadius: 26, border: `1px solid ${T.border}`, overflow: 'hidden', boxShadow: '0 20px 50px rgba(0,0,0,0.4)' }}>
      <div style={{ padding: '18px 16px 14px' }}>{children}</div>
      <Composer crop/>
    </div>
  );
}

// ── phone wrappers ────────────────────────────────────────────
function Phone({ children, scale = 1 }) {
  return (
    <div style={{ width: 402 * scale, height: 874 * scale, flexShrink: 0 }}>
      <div style={{ width: 402, height: 874, transform: `scale(${scale})`, transformOrigin: 'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

// ── board scaffolding ─────────────────────────────────────────
function Divider({ children, sub }) {
  return (
    <div style={{ margin: '64px 0 28px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontFamily: T.mono, fontSize: 12, fontWeight: 700, letterSpacing: 1.2, textTransform: 'uppercase', color: T.accent }}>{children}</span>
        <span style={{ flex: 1, height: 1, background: T.border }}/>
      </div>
      {sub && <div style={{ fontFamily: T.ui, fontSize: 13.5, lineHeight: '21px', color: T.sec, marginTop: 11, maxWidth: 760 }}>{sub}</div>}
    </div>
  );
}
function Cell({ tag, label, note, children }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 9, marginBottom: 11 }}>
        {tag && <span style={{ fontFamily: T.mono, fontSize: 12, color: T.accent }}>{tag}</span>}
        <span style={{ fontFamily: T.ui, fontSize: 14, fontWeight: 600, color: T.text }}>{label}</span>
      </div>
      {children}
      {note && <div style={{ fontFamily: T.ui, fontSize: 12.5, lineHeight: '18px', color: T.sec, marginTop: 12, maxWidth: 402 }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth: 1320, margin: '0 auto', padding: '56px 48px 130px' }}>
      <p style={{ fontFamily: T.mono, fontSize: 12, color: T.accent, letterSpacing: 1, textTransform: 'uppercase', margin: '0 0 16px' }}>cc-pocket · chat · ask</p>
      <h1 style={{ fontFamily: T.ui, fontSize: 31, fontWeight: 700, letterSpacing: -0.5, color: T.text, margin: '0 0 10px' }}>Claude asks you</h1>
      <p style={{ fontFamily: T.ui, fontSize: 15, lineHeight: '23px', color: T.sec, maxWidth: 720, margin: 0 }}>
        The <span style={{ color: T.text }}>AskUserQuestion</span> module — Claude pauses a running turn to ask 1–4 multiple-choice questions and waits for your reply.
        It docks above the composer so the conversation stays readable, and it reads deliberately calmer than the permission sheet:
        accent-tinted, no countdown, no warning colors. Answering collapses it into a quiet transcript row and the turn resumes.
      </p>

      {/* ① full context */}
      <Divider sub="A single, single-select question docked above the composer. The stream scrolls above it so you can re-read the context before choosing; the composer stays available underneath.">① Default · in the Chat screen</Divider>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 44, alignItems: 'flex-start' }}>
        <Phone><ChatScreen dock={<QuestionCard questions={[Q_STORAGE]}/>}/></Phone>
        <div style={{ maxWidth: 320, paddingTop: 6 }}>
          <Anno t="Attention cue" d="A soft terracotta top-hairline on the raised #1E2125 card — a quiet arrival signal, never a countdown."/>
          <Anno t="Header" d="Question-mark badge (terracotta on 12% terracotta) + “Claude has a question”. Progress caption appears only on multi-question calls."/>
          <Anno t="Options" d="44pt rows on base #0E0F11 with a radio each. Selected = terracotta border + 8% fill + filled control. An automatic “Other…” row expands to a field."/>
          <Anno t="Freeform escape" d="“Reply in your own words…” swaps the options for a multiline field."/>
          <Anno t="Footer" d="Filled terracotta Answer (disabled until every question has a selection) with a muted Skip that politely declines."/>
        </div>
      </div>

      {/* ② card-level variants */}
      <Divider sub="The same card across the states it moves through — shown docked above a composer sliver.">② Card states</Divider>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, alignItems: 'flex-start' }}>
        <Cell tag="2" label="Multi-question · 2 of 3" note="Three chip-tabs from each question’s short header. Q1 is answered (accent tint + check), Q2 is active (filled) with one option chosen, Q3 still pending. Progress reads 2/3.">
          <DockFrame><QuestionCard questions={Q_MULTI} init={{ active: 1, answers: { 0: { picked: [0] }, 1: { picked: [0] } } }}/></DockFrame>
        </Cell>
        <Cell tag="3" label="Multi-select · Other expanded" note="Checkboxes instead of radios; 2 of 4 checked. The “Other…” row is expanded with a typed value. Answer is enabled — the question has selections.">
          <DockFrame><QuestionCard questions={[Q_CLEANUP]} init={{ answers: { 0: { picked: [0, 1], other: '顺便统一一下日志格式', otherOpen: true } } }}/></DockFrame>
        </Cell>
        <Cell tag="4" label="Freeform mode" note="The options collapse away for a multiline field carrying the user’s draft. “Back to choices” returns to the structured list.">
          <DockFrame><QuestionCard questions={[Q_STORAGE]} init={{ mode: 'freeform', answers: { 0: { freeform: '先用 Keychain，但给 CI 环境留一个用环境变量注入的入口，方便无头测试。' } } }}/></DockFrame>
        </Cell>
      </div>

      {/* ③ keyboard open */}
      <Divider sub="When the card owns text input, it lifts with the IME and the composer is hidden — the card is the only thing between the stream and the keyboard. Safe areas are respected.">③ Keyboard open · card owns input</Divider>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, alignItems: 'flex-start' }}>
        <Cell tag="3·kb" label="Other… field focused" note="State 3 with the keyboard up. The inline “Other…” field is focused (accent border + caret); the composer is gone.">
          <Phone scale={0.82}><KeyboardScreen dock={<QuestionCard questions={[Q_CLEANUP]} keyboardMode="other" init={{ answers: { 0: { picked: [0, 1], other: '顺便统一一下日志格式', otherOpen: true } } }}/>}/></Phone>
        </Cell>
        <Cell tag="4·kb" label="Freeform field focused" note="State 4 with the keyboard up. The multiline field owns focus and lifts above the IME.">
          <Phone scale={0.82}><KeyboardScreen dock={<QuestionCard questions={[Q_STORAGE]} keyboardMode="freeform" init={{ mode: 'freeform', answers: { 0: { freeform: '先用 Keychain，但给 CI 环境留一个用环境变量注入的入口。' } } }}/>}/></Phone>
        </Cell>
      </div>

      {/* ④ answered */}
      <Divider sub="After Answer, the card collapses into a compact, tappable row inside the message stream and the turn resumes. Chosen labels appear as tiny chips; tapping expands the full question → answer list.">④ Answered · transcript row</Divider>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, alignItems: 'flex-start' }}>
        <Cell tag="5" label="Collapsed in stream" note="“Answered 3 questions” + the first chosen labels as chips, then +N. Visually quiet — it does not compete with the resumed reply below it.">
          <StreamFrame>
            <Label who="Claude"/>
            <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text, marginBottom: 14 }}>看了下现在的实现 —— 令牌是明文写在 <M>UserDefaults</M> 里的。动手之前，想先跟你确认几个方向。</div>
            <AnsweredRow items={ANSWERED_ITEMS}/>
            <div style={{ marginTop: 16 }}>
              <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text }}>好，按这些方向来：令牌迁到 Keychain、过期走静默刷新，同时删掉 v1 端点<span className="qm-caret"/></div>
            </div>
          </StreamFrame>
        </Cell>
        <Cell tag="5b" label="Expanded" note="Tapped open — each question with its chosen answer. Tap again to collapse.">
          <StreamFrame>
            <Label who="Claude"/>
            <div style={{ fontFamily: T.ui, fontSize: 14.5, lineHeight: '22px', color: T.text, marginBottom: 14 }}>动手之前，想先跟你确认几个方向。</div>
            <AnsweredRow items={ANSWERED_ITEMS} defaultOpen/>
          </StreamFrame>
        </Cell>
      </div>

      {/* ⑤ withdrawn + ⑥ inbox */}
      <Divider sub="Two edge surfaces: the question can be withdrawn before you answer, and it can arrive from another machine in the cross-device inbox.">⑤ Withdrawn · ⑥ Cross-machine inbox</Divider>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, alignItems: 'flex-start' }}>
        <Cell tag="6" label="Withdrawn" note="If Claude no longer needs the answer, the card is replaced in place by a one-line muted notice that quietly fades. No action required.">
          <DockFrame><WithdrawnNotice/></DockFrame>
        </Cell>
        <Cell tag="7" label="Cross-machine inbox row" note="Matches the attention-inbox row family: monochrome machine chip + first question preview + an accent Answer affordance. Calmer than an approval — no countdown ring.">
          <div style={{ width: 402, padding: '16px 16px 18px', background: T.base, borderRadius: 26, border: `1px solid ${T.border}`, boxShadow: '0 20px 50px rgba(0,0,0,0.4)' }}>
            <div style={{ fontFamily: T.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.7, color: T.muted, textTransform: 'uppercase', margin: '2px 2px 10px' }}>Needs you</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <InboxRow machine="mac-studio" os="mac" preview="令牌过期后应该怎么处理？"/>
              <InboxRow machine="devbox-linux" os="linux" preview="重构时顺带清理哪些内容？"/>
            </div>
          </div>
        </Cell>
      </div>
    </div>
  );
}

// small annotation block used beside the hero
function Anno({ t, d }) {
  return (
    <div style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
      <span style={{ width: 5, height: 5, borderRadius: 999, background: T.accent, marginTop: 7, flexShrink: 0 }}/>
      <div>
        <div style={{ fontFamily: T.ui, fontSize: 13, fontWeight: 600, color: T.text, marginBottom: 2 }}>{t}</div>
        <div style={{ fontFamily: T.ui, fontSize: 12.5, lineHeight: '18px', color: T.sec }}>{d}</div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
