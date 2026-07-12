// cc-pocket — "Claude asks you" · the question card + molecules
// Uses globals from qm-core.jsx (T, QIcon, Radio, CheckboxCtrl, ChipTab, Check,
// Chevron, Pencil, TinyChip, PillBtn, MachineChip, A08/A18…). Do NOT re-declare.

// ── card header: badge + "Claude has a question" + progress ───
function CardHeader({ multi, progress }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
      <QIcon s={28}/>
      <span style={{ flex: 1, fontFamily: T.ui, fontSize: 13, color: T.sec }}>Claude has a question</span>
      {multi && (
        <span style={{ fontFamily: T.mono, fontSize: 11.5, color: T.muted, letterSpacing: 0.3, flexShrink: 0 }}>{progress}</span>
      )}
    </div>
  );
}

// ── one selectable option row (control + two-line text) ───────
function OptionRow({ multi, label, desc, selected, onClick }) {
  return (
    <div onClick={onClick} className="qm-opt qm-press" style={{
      display: 'flex', alignItems: 'flex-start', gap: 11, cursor: 'pointer',
      minHeight: 44, padding: '10px 12px', borderRadius: 12,
      background: selected ? A08 : T.base,
      border: `1px solid ${selected ? T.accent : T.border}`,
    }}>
      <span style={{ paddingTop: 1, display: 'flex' }}>{multi ? <CheckboxCtrl sel={selected}/> : <Radio sel={selected}/>}</span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ display: 'block', fontFamily: T.ui, fontSize: 14.5, fontWeight: 500, color: T.text, lineHeight: '19px' }}>{label}</span>
        <span style={{ display: 'block', fontFamily: T.ui, fontSize: 12.5, color: T.sec, lineHeight: '17px', marginTop: 2 }}>{desc}</span>
      </span>
    </div>
  );
}

// ── the automatic "Other…" row (expands to an inline field) ───
function OtherRow({ multi, selected, open, value, onToggle, caret }) {
  return (
    <div className="qm-opt" style={{
      borderRadius: 12, overflow: 'hidden',
      background: selected ? A08 : T.base,
      border: `1px solid ${selected ? T.accent : T.border}`,
    }}>
      <div onClick={onToggle} className="qm-press" style={{ display: 'flex', alignItems: 'center', gap: 11, cursor: 'pointer', minHeight: 44, padding: '10px 12px' }}>
        <span style={{ display: 'flex' }}>{multi ? <CheckboxCtrl sel={selected}/> : <Radio sel={selected}/>}</span>
        <span style={{ fontFamily: T.ui, fontSize: 14.5, fontWeight: 500, color: selected ? T.text : T.sec }}>Other…</span>
      </div>
      {open && (
        <div style={{ padding: '0 12px 11px 43px' }}>
          <div style={{ display: 'flex', alignItems: 'center', minHeight: 38, background: T.surface, border: `1px solid ${caret ? T.accent : T.border}`, borderRadius: 9, padding: '0 11px' }}>
            <span style={{ fontFamily: T.ui, fontSize: 13.5, color: value ? T.text : T.muted }}>{value || '输入你的答案…'}</span>
            {caret && <span className="qm-caret"/>}
          </div>
        </div>
      )}
    </div>
  );
}

// ── freeform reply field (replaces the options list) ──────────
function FreeformField({ value, caret }) {
  return (
    <div style={{ background: T.base, border: `1px solid ${caret ? T.accent : T.border}`, borderRadius: 12, padding: '12px 13px', minHeight: 92 }}>
      <span style={{ fontFamily: T.ui, fontSize: 14, lineHeight: '21px', color: value ? T.text : T.muted, whiteSpace: 'pre-wrap' }}>
        {value || '用自己的话回答…'}
        {caret && <span className="qm-caret"/>}
      </span>
    </div>
  );
}

// ── quiet link toggling structured ⇄ freeform ─────────────────
function ReplyLink({ back, onClick }) {
  return (
    <button onClick={onClick} className="qm-press" style={{
      all: 'unset', boxSizing: 'border-box', cursor: 'pointer',
      display: 'inline-flex', alignItems: 'center', gap: 6, marginTop: 12, padding: '2px 2px', minHeight: 24,
    }}>
      {back ? <Chevron d="left" c={T.sec} s={13} w={2}/> : <Pencil c={T.sec} s={13}/>}
      <span style={{ fontFamily: T.ui, fontSize: 13, fontWeight: 500, color: T.sec }}>{back ? 'Back to choices' : 'Reply in your own words…'}</span>
    </button>
  );
}

// ── footer: Skip (tertiary) ····· Answer (primary) ────────────
function CardFooter({ canAnswer, onSkip, onAnswer }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', marginTop: 16 }}>
      <button onClick={onSkip} className="qm-press" style={{
        all: 'unset', boxSizing: 'border-box', cursor: 'pointer',
        height: 44, padding: '0 8px', display: 'flex', alignItems: 'center',
        fontFamily: T.ui, fontSize: 14.5, fontWeight: 500, color: T.muted,
      }}>Skip</button>
      <span style={{ flex: 1 }}/>
      <button onClick={canAnswer ? onAnswer : undefined} disabled={!canAnswer} className="qm-press" style={{
        all: 'unset', boxSizing: 'border-box', cursor: canAnswer ? 'pointer' : 'default',
        height: 44, padding: '0 24px', borderRadius: 12,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: canAnswer ? T.accent : T.surface,
        border: canAnswer ? 'none' : `1px solid ${T.border}`,
        color: canAnswer ? '#0E0F11' : T.muted,
        fontFamily: T.ui, fontSize: 15, fontWeight: 700,
      }}>Answer</button>
    </div>
  );
}

// ══ the docked question card — one component drives every state ══
function QuestionCard({ questions, init = {}, keyboardMode = null, docked = true }) {
  const multiQ = questions.length > 1;
  const [active, setActive] = React.useState(init.active || 0);
  const [mode, setMode] = React.useState(init.mode || 'options'); // 'options' | 'freeform'
  const [ans, setAns] = React.useState(() => {
    const base = {};
    questions.forEach((q, i) => {
      const seed = (init.answers && init.answers[i]) || {};
      base[i] = {
        picked: new Set(seed.picked || []),
        other: seed.other !== undefined ? seed.other : null, // null = not chosen
        otherOpen: !!seed.otherOpen,
        freeform: seed.freeform || '',
      };
    });
    return base;
  });

  const q = questions[active];
  const a = ans[active];

  const setActiveAns = (patch) => setAns(prev => ({ ...prev, [active]: { ...prev[active], ...patch } }));

  const pick = (idx) => {
    const picked = new Set(a.picked);
    if (q.multi) { picked.has(idx) ? picked.delete(idx) : picked.add(idx); setActiveAns({ picked }); }
    else { setActiveAns({ picked: new Set([idx]), other: null, otherOpen: false }); }
  };
  const toggleOther = () => {
    const on = a.other !== null;
    if (q.multi) setActiveAns({ other: on ? null : '', otherOpen: !on });
    else setActiveAns({ other: on ? null : '', otherOpen: !on, picked: new Set() });
  };

  // per-question completeness
  const done = (i) => {
    const x = ans[i];
    if (mode === 'freeform' && i === active) return x.freeform.trim().length > 0;
    return x.picked.size > 0 || (x.other !== null && String(x.other).trim().length > 0);
  };
  const canAnswer = questions.every((_, i) => done(i));
  const progress = `${active + 1}/${questions.length}`;

  return (
    <div style={{
      position: 'relative', margin: docked ? '0 12px' : 0,
      background: T.raised, border: `1px solid ${T.border}`, borderRadius: 16,
      boxShadow: '0 14px 40px rgba(0,0,0,0.5), 0 -6px 28px rgba(217,119,87,0.05)',
    }}>
      {/* soft terracotta attention cue — a centered top hairline */}
      <span style={{ position: 'absolute', top: 0, left: 16, right: 16, height: 2, borderRadius: '0 0 3px 3px', background: 'linear-gradient(90deg, transparent, rgba(217,119,87,0.75), transparent)' }}/>

      <div style={{ padding: '14px 15px 15px' }}>
        <CardHeader multi={multiQ} progress={progress}/>

        {/* chip-tabs (multi-question only) */}
        {multiQ && (
          <div className="qm-scroll" style={{ display: 'flex', gap: 7, overflowX: 'auto', margin: '12px 0 2px', paddingBottom: 1 }}>
            {questions.map((qq, i) => (
              <ChipTab key={i} label={qq.header} active={i === active} done={done(i)} onClick={() => setActive(i)}/>
            ))}
          </div>
        )}

        {/* question prose */}
        <div style={{ fontFamily: T.ui, fontSize: 15.5, fontWeight: 600, color: T.text, lineHeight: '21px', margin: multiQ ? '12px 0 12px' : '13px 0 12px' }}>
          {q.text}
        </div>

        {mode === 'options' ? (
          <>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {q.options.map((o, i) => (
                <OptionRow key={i} multi={q.multi} label={o.label} desc={o.desc} selected={a.picked.has(i)} onClick={() => pick(i)}/>
              ))}
              <OtherRow multi={q.multi} selected={a.other !== null} open={a.otherOpen} value={a.other || ''} onToggle={toggleOther} caret={keyboardMode === 'other'}/>
            </div>
            <ReplyLink onClick={() => setMode('freeform')}/>
          </>
        ) : (
          <>
            <FreeformField value={a.freeform} caret={keyboardMode === 'freeform'}/>
            <ReplyLink back onClick={() => setMode('options')}/>
          </>
        )}

        <CardFooter canAnswer={canAnswer} onSkip={() => {}} onAnswer={() => {}}/>
      </div>
    </div>
  );
}

// ══ after submit: compact answered row inside the message stream ══
function AnsweredRow({ items, defaultOpen = false }) {
  const [open, setOpen] = React.useState(defaultOpen);
  const chips = items.map(it => it.a);
  return (
    <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, overflow: 'hidden' }}>
      <div onClick={() => setOpen(o => !o)} className="qm-press" style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '10px 12px', cursor: 'pointer' }}>
        <QIcon s={22}/>
        <span style={{ fontFamily: T.ui, fontSize: 13, color: T.sec, flexShrink: 0 }}>Answered {items.length} question{items.length > 1 ? 's' : ''}</span>
        <span style={{ flex: 1, display: 'flex', gap: 5, overflow: 'hidden', minWidth: 0 }}>
          {chips.slice(0, 2).map((c, i) => <TinyChip key={i}>{c}</TinyChip>)}
          {chips.length > 2 && <TinyChip muted>+{chips.length - 2}</TinyChip>}
        </span>
        <span style={{ display: 'flex', flexShrink: 0, transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }}><Chevron d="down" c={T.muted} s={13}/></span>
      </div>
      {open && (
        <div style={{ borderTop: `1px solid ${T.border}`, padding: '6px 12px 10px' }}>
          {items.map((it, i) => (
            <div key={i} style={{ padding: '7px 0', borderBottom: i < items.length - 1 ? `1px solid ${T.border}` : 'none' }}>
              <div style={{ fontFamily: T.ui, fontSize: 11.5, color: T.muted, marginBottom: 3 }}>{it.q}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                <Check c={T.accent} s={12} w={2}/>
                <span style={{ fontFamily: T.ui, fontSize: 13.5, color: T.text }}>{it.a}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ══ withdrawn: the card is replaced by a quiet, auto-fading line ══
function WithdrawnNotice() {
  return (
    <div className="qm-fade" style={{ display: 'flex', alignItems: 'center', gap: 9, margin: '0 12px', padding: '11px 4px' }}>
      <span style={{ width: 6, height: 6, borderRadius: 999, background: T.muted, flexShrink: 0 }}/>
      <span style={{ fontFamily: T.ui, fontSize: 13, color: T.muted }}>Claude moved on — answers no longer needed</span>
    </div>
  );
}

// ══ cross-machine inbox row (compact) ══
function InboxRow({ machine, os = 'mac', status = 'online', preview }) {
  return (
    <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 12, padding: '11px 12px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 9 }}>
        <MachineChip name={machine} os={os} status={status} mono={11.5} glyph={13}/>
        <span style={{ flex: 1 }}/>
        <span style={{ fontFamily: T.ui, fontSize: 10.5, color: T.muted, border: `1px solid ${T.border}`, borderRadius: 999, padding: '2px 8px', flexShrink: 0 }}>question</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <QIcon s={24}/>
        <span style={{ flex: 1, minWidth: 0, fontFamily: T.ui, fontSize: 13.5, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{preview}</span>
        <PillBtn>Answer</PillBtn>
      </div>
    </div>
  );
}

Object.assign(window, {
  CardHeader, OptionRow, OtherRow, FreeformField, ReplyLink, CardFooter,
  QuestionCard, AnsweredRow, WithdrawnNotice, InboxRow,
});
