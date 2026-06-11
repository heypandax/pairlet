// cc-pocket — voice input · app (composer states S1–S6 + live demo + board)

const TARGET = 'move the timer scheduling above the close call so reconnect can run before the socket shuts';

// ── composer atoms ────────────────────────────────────────────
function IconBtn({ onClick, children, label, size=44, disabled }) {
  return (
    <button onClick={onClick} disabled={disabled} aria-label={label} className="v-press"
      style={{ all:'unset', boxSizing:'border-box', cursor:disabled?'default':'pointer', width:size, height:size, borderRadius:10, flexShrink:0,
        display:'flex', alignItems:'center', justifyContent:'center', opacity:disabled?0.4:1 }}>
      {children}
    </button>
  );
}

function RoundBtn({ onClick, children, bg, label, disabled }) {
  return (
    <button onClick={onClick} disabled={disabled} aria-label={label} className="v-press"
      style={{ all:'unset', boxSizing:'border-box', cursor:disabled?'default':'pointer', width:44, height:44, borderRadius:999, flexShrink:0,
        display:'flex', alignItems:'center', justifyContent:'center', background:bg||T.base, border: bg?'none':`1px solid ${T.border}`, opacity:disabled?0.5:1 }}>
      {children}
    </button>
  );
}

// the field with optional live-transcript treatment
function Field({ value, placeholder, live, caret, onChange }) {
  return (
    <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 14px', minHeight:44 }}>
      {live !== undefined ? (
        <div style={{ flex:1, padding:'10px 0', fontFamily:T.ui, fontSize:14.5, lineHeight:'21px' }}>
          <span style={{ color:T.text }}>{value.final}</span>
          <span style={{ color:T.muted }}>{value.partial}</span>
          {caret && <span className="v-blink" style={{ display:'inline-block', width:2, height:16, background:T.accent, borderRadius:2, marginLeft:1, transform:'translateY(3px)' }}/>}
        </div>
      ) : (
        <input value={value} onChange={e=>onChange&&onChange(e.target.value)} placeholder={placeholder}
          style={{ all:'unset', flex:1, fontFamily:T.ui, fontSize:14.5, color:T.text, padding:'11px 0', minWidth:0 }}/>
      )}
    </div>
  );
}

// ── RecordingBar (cancel + waveform + timer + done) ───────────
function RecordingBar({ seconds, onCancel, onDone, frozen, transcribing }) {
  return (
    <div className="v-morph" style={{ display:'flex', alignItems:'center', gap:9, padding:'10px 12px' }}>
      <IconBtn onClick={onCancel} label="Cancel recording"><XSmall c={T.muted} s={18}/></IconBtn>
      <div style={{ flex:1, display:'flex', alignItems:'center', gap:10, background:T.raised, border:`1px solid ${T.border}`, borderRadius:12, padding:'0 12px', minHeight:44 }}>
        {transcribing ? (
          <>
            <Spinner s={17} c={T.accent}/>
            <span style={{ fontFamily:T.ui, fontSize:13, color:T.sec, flex:1 }}>Transcribing…</span>
          </>
        ) : (
          <>
            <span className="v-pulse" style={{ width:8, height:8, borderRadius:999, background:T.danger, flexShrink:0, boxShadow:`0 0 8px ${T.danger}aa` }}/>
            <Waveform live={!frozen} frozen={frozen} h={28}/>
          </>
        )}
        <span style={{ fontFamily:T.mono, fontSize:12.5, color:T.sec, flexShrink:0, fontVariantNumeric:'tabular-nums' }}>{fmt(seconds)}</span>
      </div>
      <RoundBtn onClick={onDone} bg={T.accent} label="Done"><CheckMark c="#0E0F11" s={20}/></RoundBtn>
    </div>
  );
}

// ── idle / result composer (attach + field + mic|send) ────────
function IdleComposer({ text='', onChange, onMic, onSend }) {
  const has = text.trim().length > 0;
  return (
    <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px' }}>
      <IconBtn label="Attach"><Plus c={T.sec} s={22}/></IconBtn>
      <Field value={text} placeholder="Message Claude" onChange={onChange}/>
      {has
        ? <RoundBtn onClick={onSend} bg={T.accent} label="Send"><SendArrow c="#0E0F11" s={18}/></RoundBtn>
        : <RoundBtn onClick={onMic} label="Dictate"><Mic c={T.sec} s={22}/></RoundBtn>}
    </div>
  );
}

// ── inline error chip ─────────────────────────────────────────
function ErrorComposer({ partial, onRetry, onChange, onSend }) {
  return (
    <div style={{ padding:'10px 12px' }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, margin:'0 2px 9px' }}>
        <span style={{ display:'flex', alignItems:'center', gap:7, background:'rgba(229,96,77,0.10)', border:`1px solid ${T.danger}55`, borderRadius:999, padding:'5px 11px' }}>
          <WarnTri c={T.danger} s={13}/>
          <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:500, color:T.danger }}>Couldn’t transcribe — try again</span>
        </span>
      </div>
      <div style={{ display:'flex', alignItems:'flex-end', gap:8 }}>
        <IconBtn label="Attach"><Plus c={T.sec} s={22}/></IconBtn>
        <Field value={partial} placeholder="Message Claude" onChange={onChange}/>
        <RoundBtn onClick={onRetry} label="Retry dictation"><Mic c={T.accent} s={22}/></RoundBtn>
      </div>
    </div>
  );
}

// ── mic-permission sheet (PermissionSheet language) ───────────
function MicPermissionSheet({ onClose }) {
  return (
    <div style={{ position:'absolute', inset:0, zIndex:40 }}>
      <div onClick={onClose} style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.55)' }}/>
      <div className="v-sheet" style={{ position:'absolute', left:0, right:0, bottom:0, background:T.raised,
        borderTopLeftRadius:20, borderTopRightRadius:20, borderTop:`1px solid ${T.border}`,
        borderLeft:`1px solid ${T.border}`, borderRight:`1px solid ${T.border}`, paddingBottom:34 }}>
        <div style={{ display:'flex', justifyContent:'center', padding:'8px 0 4px' }}>
          <div style={{ width:38, height:5, borderRadius:999, background:T.border }}/>
        </div>
        <div style={{ padding:'12px 20px 6px' }}>
          <div style={{ width:50, height:50, borderRadius:14, background:'rgba(217,119,87,0.12)', border:`1px solid rgba(217,119,87,0.3)`, display:'flex', alignItems:'center', justifyContent:'center', marginBottom:16 }}>
            <ShieldMic c={T.accent} s={26}/>
          </div>
          <div style={{ fontFamily:T.ui, fontSize:19, fontWeight:700, color:T.text, letterSpacing:-0.2 }}>Microphone access needed</div>
          <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'21px', color:T.sec, marginTop:7 }}>
            cc-pocket uses the mic to dictate prompts. Audio is transcribed for this message only — nothing is recorded or stored.
          </div>
          <button onClick={onClose} className="v-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', width:'100%', height:50, marginTop:18,
            display:'flex', alignItems:'center', justifyContent:'center', background:T.accent, color:'#0E0F11', borderRadius:12, fontFamily:T.ui, fontSize:16, fontWeight:700 }}>
            Open Settings
          </button>
          <button onClick={onClose} className="v-press" style={{ all:'unset', boxSizing:'border-box', cursor:'pointer', width:'100%', height:44, marginTop:6,
            display:'flex', alignItems:'center', justifyContent:'center', color:T.sec, fontFamily:T.ui, fontSize:15, fontWeight:500 }}>
            Not now
          </button>
        </div>
      </div>
    </div>
  );
}

// ── composer host — switches by state ─────────────────────────
function Composer({ state, ctx }) {
  if (state === 'recording' || state === 'transcribing')
    return <RecordingBar seconds={ctx.seconds} onCancel={ctx.onCancel} onDone={ctx.onDone} frozen={state==='transcribing'} transcribing={state==='transcribing'}/>;
  if (state === 'error')
    return <ErrorComposer partial={ctx.partialText||''} onRetry={ctx.onMic} onChange={ctx.onChange} onSend={ctx.onSend}/>;
  // idle | result
  return <IdleComposer text={ctx.text||''} onChange={ctx.onChange} onMic={ctx.onMic} onSend={ctx.onSend}/>;
}

// ── full chat screen with a given composer state ──────────────
function ChatScreen({ state, ctx, liveValue, caret, sheet, extraTurns = [] }) {
  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      <ChatHeader/>
      <ChatStream extra={extraTurns}/>
      {/* live transcript overlay sits in the field while recording */}
      <div style={{ flexShrink:0, paddingBottom:34, background:T.surface, borderTop:`1px solid ${T.border}` }}>
        {(state==='recording') && (
          <div style={{ padding:'10px 12px 0' }}>
            <Field live value={liveValue} caret={caret}/>
          </div>
        )}
        <Composer state={state} ctx={ctx}/>
      </div>
      {sheet && <MicPermissionSheet onClose={ctx.onCloseSheet}/>}
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// LIVE DEMO
// ════════════════════════════════════════════════════════════
function LiveDemo() {
  const [state, setState] = React.useState('idle');   // idle|recording|transcribing|error
  const [seconds, setSeconds] = React.useState(0);
  const [text, setText] = React.useState('');
  const [live, setLive] = React.useState({ final:'', partial:'' });
  const [sent, setSent] = React.useState([]);          // turns sent on ✓
  const [sheet, setSheet] = React.useState(false);
  const timer = React.useRef(null);
  const streamers = React.useRef([]);

  const clearAll = () => { clearInterval(timer.current); streamers.current.forEach(clearTimeout); streamers.current=[]; };

  const startRecording = () => {
    setText(''); setLive({ final:'', partial:'' }); setSeconds(0); setState('recording');
    timer.current = setInterval(() => setSeconds(s => s + 1), 1000);
    // stream the target sentence word-by-word: partial -> final
    const words = TARGET.split(' ');
    let fin = '';
    words.forEach((w, i) => {
      const tp = setTimeout(() => {
        setLive({ final: fin, partial: (fin?' ':'')+w });
        const tf = setTimeout(() => { fin = (fin?fin+' ':'') + w; setLive({ final: fin, partial:'' }); }, 180);
        streamers.current.push(tf);
      }, 420 + i*280);
      streamers.current.push(tp);
    });
  };

  const onMic = () => {
    // 1-in-easy: show permission sheet first time? keep deterministic: go straight to record
    startRecording();
  };
  const onDone = () => {
    clearAll();
    setState('transcribing');
    // v1: ✓ = confirm AND send — transcript posts as the prompt immediately
    setTimeout(() => {
      setSent(prev => [...prev, TARGET]);
      setLive({ final:'', partial:'' }); setText('');
      setState('idle');
    }, 1100);
  };
  const onCancel = () => { clearAll(); setLive({ final:'', partial:'' }); setText(''); setState('idle'); };
  const onSend = () => { clearAll(); setText(''); setLive({ final:'', partial:'' }); setState('idle'); };

  React.useEffect(() => () => clearAll(), []);

  const ctx = { seconds, text, onChange:setText, onMic, onDone, onCancel, onSend, onCloseSheet:()=>setSheet(false) };
  return <ChatScreen state={state} ctx={ctx} liveValue={live} caret={state==='recording'} sheet={sheet} extraTurns={sent}/>;
}

// ── device wrapper ────────────────────────────────────────────
function Phone({ children, scale=0.82 }) {
  return (
    <div style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// VARIANT BOARD — S1..S6 (static, deterministic)
// ════════════════════════════════════════════════════════════
const VARIANTS = [
  { id:'S1', label:'Idle · mic visible', desc:'Empty field shows the mic where Send would be. Tap to dictate.', state:'idle', ctx:{ text:'' } },
  { id:'S2', label:'Recording', desc:'Waveform animates, timer runs, transcript streams into the field — partial in muted, finalized in primary, terracotta caret at the live end.', state:'recording', seconds:7,
    live:{ final:'move the timer scheduling above the', partial:' close call' }, caret:true },
  { id:'S3', label:'Transcribing', desc:(<>After “done”, waveform freezes and a spinner + “Transcribing…” covers final-transcript lag. On success the transcript <span style={{color:'#ECEDEE',fontWeight:600}}>sends immediately</span> — it lands as a new user turn in the stream (try the live phone above).</>), state:'transcribing', seconds:9 },
  { id:'S4', label:'Result · review', removed:true, annotation:'(removed in v1 — transcript sends on ✓)', desc:'v1 ships ✓ = confirm AND send. There is no editable-review stop between transcription and the prompt going out — the explicit confirm is the ✓ tap itself.' },
  { id:'S5', label:'Error · retry', desc:'Transcription failed; bar collapses to a danger chip + retry mic. Any partial text is kept.', state:'error', ctx:{ partialText:'move the timer scheduling above the' } },
  { id:'S6', label:'Mic permission', desc:'First tap without permission raises a compact sheet in the PermissionSheet language.', state:'idle', ctx:{ text:'' }, sheet:true },
];

function StaticVariant(v) {
  const ctx = { seconds:v.seconds||0, ...(v.ctx||{}), onChange:()=>{}, onMic:()=>{}, onDone:()=>{}, onCancel:()=>{}, onSend:()=>{}, onCloseSheet:()=>{} };
  return <ChatScreen state={v.state} ctx={ctx} liveValue={v.live||{final:'',partial:''}} caret={v.caret} sheet={v.sheet}/>;
}

function Board() {
  return (
    <div style={{ display:'flex', flexWrap:'wrap', gap:30, marginTop:34 }}>
      {VARIANTS.map(v => (
        <div key={v.id} style={{ width:402*0.82 }}>
          <div style={{ display:'flex', alignItems:'baseline', gap:9, marginBottom:10 }}>
            <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{v.id}</span>
            <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{v.label}</span>
            {v.removed && <span style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>{v.annotation}</span>}
          </div>
          {v.removed ? (
            <div style={{ border:`1px dashed ${T.border}`, borderRadius:14, padding:'18px 16px', background:'rgba(22,24,27,0.4)' }}>
              <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec }}>{v.desc}</div>
              {/* where the transcript goes instead: a sent user turn */}
              <div style={{ marginTop:14, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, padding:'13px 14px' }}>
                <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:7, whiteSpace:'nowrap' }}>
                  <span style={{ fontFamily:T.mono, fontSize:11, color:T.accent, flexShrink:0 }}>S3 ✓</span>
                  <svg width="26" height="10" viewBox="0 0 26 10" fill="none" style={{ flexShrink:0 }}><path d="M1 5h21M22 5l-4-3.4M22 5l-4 3.4" stroke="#6B7177" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>
                  <span style={{ fontFamily:T.ui, fontSize:11, color:T.muted, flexShrink:0 }}>sent as the prompt</span>
                </div>
                <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:6 }}>YOU</div>
                <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.text }}>{TARGET}</div>
              </div>
            </div>
          ) : (
            <Phone scale={0.82}><StaticVariant {...v}/></Phone>
          )}
          <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.sec, marginTop:11, maxWidth:402*0.82 }}>{v.removed ? null : v.desc}</div>
        </div>
      ))}
    </div>
  );
}

// ════════════════════════════════════════════════════════════
function Page() {
  return (
    <div style={{ maxWidth:1240, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat composer</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Voice input — dictation</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:660, margin:'0 0 8px' }}>
        Speak a prompt instead of thumb-typing it. Tap the mic → the composer morphs into a recording bar with a live waveform → speech streams into the field → tap ✓ and the transcript sends as your prompt. <span style={{ color:'#ECEDEE' }}>✓ is the explicit confirm — cancel (✕) is always one tap away.</span> The phone below is live: tap the mic, watch the transcript stream, tap ✓ and see it land in the stream.
      </p>

      <div style={{ display:'flex', alignItems:'flex-start', gap:40, marginTop:36, flexWrap:'wrap' }}>
        <Phone scale={0.92}><LiveDemo/></Phone>
        <div style={{ maxWidth:320, paddingTop:8 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>Flow</div>
          {[
            ['Tap the mic','Sits where Send would be while the field is empty. Composer morphs into the recording bar.'],
            ['Speak','Waveform tracks your level; the timer runs; words stream into the field — muted until each segment finalizes.'],
            ['Tap ✓ done','Brief “Transcribing…” if the final pass lags — then the transcript sends as your prompt.'],
            ['It lands in the stream','A user turn drops into the conversation immediately. ✕ cancels anytime before ✓.'],
          ].map(([t,d],i)=>(
            <div key={i} style={{ display:'flex', gap:12, marginBottom:16 }}>
              <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent, width:18, flexShrink:0 }}>{i+1}</span>
              <div>
                <div style={{ fontSize:13.5, fontWeight:600, color:T.text, marginBottom:2 }}>{t}</div>
                <div style={{ fontSize:12.5, lineHeight:'18px', color:T.sec }}>{d}</div>
              </div>
            </div>
          ))}
          <div style={{ marginTop:8, paddingTop:14, borderTop:`1px solid ${T.border}`, fontFamily:T.mono, fontSize:11, color:T.muted, lineHeight:'17px' }}>
            haptic tick on record start/stop · system STT (SF&nbsp;Speech / Android STT)
          </div>
        </div>
      </div>

      <div style={{ display:'flex', alignItems:'center', gap:12, margin:'58px 0 0' }}>
        <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>Composer states · S1–S6</span>
        <span style={{ flex:1, height:1, background:T.border }}/>
      </div>
      <Board/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
