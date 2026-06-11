// cc-pocket — execution permissions · app (live phone + state board)

// sample permission requests
const REQ_SAFE = { title:'Run command', tool:'Bash', cmd:'git status', rule:'git status', dir:'~/proj/app/cc-pocket', branch:'main', total:30 };
const REQ_DANGER = { title:'Run command', tool:'Bash', cmd:'git push --force origin main', rule:'git push --force', danger:true, dangerNote:'force-push to any branch', dir:'~/proj/app/cc-pocket', branch:'main', total:30 };

// ════════════════════════════════════════════════════════════
// LIVE PHONE — badge → mode sheet → permission prompt, wired
// ════════════════════════════════════════════════════════════
function LiveDemo() {
  const [modeKey, setModeKey] = React.useState('default');
  const [rules, setRules] = React.useState([]);
  const [sheet, setSheet] = React.useState(null);       // null | 'mode' | 'perm'
  const [modeStep, setModeStep] = React.useState('list'); // list | confirm | switching
  const [perm, setPerm] = React.useState(null);          // the active request
  const [seconds, setSeconds] = React.useState(30);
  const [stream, setStream] = React.useState([
    { type:'assistant', node:(<>I’ll check the working tree before I change anything. Send a message and I’ll get started.</>) },
  ]);
  const [draft, setDraft] = React.useState('');
  const scrollRef = React.useRef(null);

  React.useEffect(()=>{ const el=scrollRef.current; if(el) el.scrollTop=el.scrollHeight; }, [stream, perm]);

  // countdown while the permission sheet is open
  React.useEffect(()=>{
    if (sheet!=='perm' || !perm) return;
    if (seconds<=0) { closePerm('denied'); return; }
    const t=setTimeout(()=>setSeconds(s=>s-1), 1000);
    return ()=>clearTimeout(t);
  }, [sheet, perm, seconds]);

  const openMode = () => { setModeStep('list'); setSheet('mode'); };

  const selectMode = (key) => {
    if (key==='bypass') { setModeStep('confirm'); return; }
    // switching to a more permissive mode restarts the session
    const order = ['plan','default','acceptEdits'];
    const moreOpen = order.indexOf(key) > order.indexOf(modeKey);
    if (moreOpen) {
      setModeStep('switching'); setModeKey(key);
      setTimeout(()=>{ setModeStep('list'); setSheet(null); }, 1400);
    } else { setModeKey(key); setSheet(null); }
  };
  const confirmFull = () => {
    setModeStep('switching'); setModeKey('bypass');
    setTimeout(()=>{ setModeStep('list'); setSheet(null); }, 1400);
  };

  const send = () => {
    const text = draft.trim(); if (!text) return;
    setStream(prev=>[...prev, { type:'user', text }]); setDraft('');
    // Claude tries to run a tool → permission request (unless full-auto)
    setTimeout(()=>{
      if (modeKey==='bypass') {
        setStream(prev=>[...prev, { type:'assistant', node:(<>Running <span style={mono}>git status</span> automatically (full auto).</>) }]);
      } else {
        setSeconds(30); setPerm(REQ_SAFE); setSheet('perm');
      }
    }, 700);
  };

  const closePerm = (verdict, rule) => {
    setSheet(null);
    if (verdict==='always' && rule) {
      setRules(prev => prev.includes(rule)?prev:[...prev, rule]);
      setStream(prev=>[...prev, { type:'chip', rule }]);
    }
    setTimeout(()=>setPerm(null), 200);
  };

  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:T.base, overflow:'hidden' }}>
      <ChatTopBar modeKey={modeKey} rules={rules.length} onBadge={openMode}/>

      <div ref={scrollRef} className="ep-scroll" style={{ flex:1, overflowY:'auto', padding:'16px 16px 18px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:16 }}>
          {stream.map((m,i)=>{
            if (m.type==='assistant') return <div key={i} style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>{m.node}</div>;
            if (m.type==='user') return (
              <div key={i}>
                <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:6 }}>YOU</div>
                <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>{m.text}</div>
              </div>
            );
            if (m.type==='chip') return <AllowChip key={i} rule={m.rule}/>;
            return null;
          })}
        </div>
      </div>

      {/* composer */}
      <div style={{ flexShrink:0, background:T.surface, borderTop:`1px solid ${T.border}`, paddingBottom:34 }}>
        <div style={{ display:'flex', alignItems:'flex-end', gap:8, padding:'10px 12px' }}>
          <div style={{ flex:1, background:T.base, border:`1px solid ${T.border}`, borderRadius:12, display:'flex', alignItems:'center', padding:'0 14px', minHeight:44 }}>
            <input value={draft} onChange={e=>setDraft(e.target.value)} onKeyDown={e=>{ if(e.key==='Enter') send(); }}
              placeholder="Message Claude…" style={{ all:'unset', flex:1, fontFamily:T.ui, fontSize:14.5, color:T.text, padding:'11px 0', minWidth:0 }}/>
          </div>
          <button onClick={send} disabled={!draft.trim()} className="ep-press" aria-label="Send"
            style={{ all:'unset', cursor:draft.trim()?'pointer':'default', width:44, height:44, borderRadius:999, flexShrink:0, display:'flex', alignItems:'center', justifyContent:'center',
              background:draft.trim()?T.accent:T.base, border:draft.trim()?'none':`1px solid ${T.border}` }}>
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={draft.trim()?'#0E0F11':T.muted} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>
          </button>
        </div>
      </div>

      {sheet && <Scrim onClick={()=>{ if(modeStep!=='switching'){ setSheet(null);} }}/>}
      {sheet==='mode' && (
        <ModeSheet current={modeKey} step={modeStep} rules={rules}
          onSelect={selectMode} onConfirmFull={confirmFull} onCancelFull={()=>setModeStep('list')}
          onClearRule={(i)=>setRules(prev=>prev.filter((_,j)=>j!==i))} onClearAll={()=>setRules([])}/>
      )}
      {sheet==='perm' && perm && (
        <PermissionSheet req={perm} variant="default" seconds={seconds}
          onDeny={()=>closePerm('denied')} onOnce={()=>closePerm('once')} onAlways={()=>closePerm('always', perm.rule)}/>
      )}
    </div>
  );
}
const mono = { fontFamily:T.mono, fontSize:12.5, background:T.surface, border:`1px solid ${T.border}`, borderRadius:5, padding:'1px 5px' };

// ── device wrapper ────────────────────────────────────────────
function Phone({ children, scale=0.82, shot=false }) {
  return (
    <div data-shot={shot?'1':undefined} style={{ width:402*scale, height:874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// STATIC BOARD
// ════════════════════════════════════════════════════════════
function Cap({ children }) { return <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:9 }}>{children}</div>; }
function SectionLabel({ n, children }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:12, margin:'58px 0 22px' }}>
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{n}</span>
      <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
      <span style={{ flex:1, height:1, background:T.border }}/>
    </div>
  );
}

// a 390-wide slice with a dimmed chat behind a bottom sheet
function SheetSlice({ children, h=470, label }) {
  return (
    <div>
      <Cap>{label}</Cap>
      <div style={{ position:'relative', width:390, height:h, background:T.base, border:`1px solid ${T.border}`, borderRadius:18, overflow:'hidden' }}>
        <div style={{ position:'absolute', inset:0, background:'rgba(0,0,0,0.5)' }}/>
        {children}
      </div>
    </div>
  );
}

function noop(){}

function Board() {
  const wrap = { display:'flex', flexWrap:'wrap', gap:26, alignItems:'flex-start' };

  return (
    <div>
      {/* ① BADGE — 4 colours */}
      <SectionLabel n="①">Persistent mode badge · the colour system</SectionLabel>
      <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
        {MODES.map(m=>(
          <div key={m.key}>
            <Cap>{m.tech}{m.key==='bypass'?' · ⚠ guarded':''}</Cap>
            <div style={{ width:390, border:`1px solid ${T.border}`, borderRadius:16, overflow:'hidden' }}>
              <ChatTopBar modeKey={m.key} host={false}/>
            </div>
          </div>
        ))}
        <div>
          <Cap>with session allow-rules · tap → review</Cap>
          <div style={{ width:390, border:`1px solid ${T.border}`, borderRadius:16, overflow:'hidden' }}>
            <ChatTopBar modeKey="acceptEdits" rules={3} host={false}/>
          </div>
        </div>
      </div>

      {/* ② MODE SHEET states */}
      <SectionLabel n="②">Mode-switch sheet · states</SectionLabel>
      <div style={wrap}>
        <SheetSlice label="default · level 1 selected" h={510}>
          <ModeSheet current="default" step="list" rules={[]} onSelect={noop}/>
        </SheetSlice>
        <SheetSlice label="“Full auto” confirm · guarded" h={340}>
          <ModeSheet current="default" step="confirm" rules={[]} onCancelFull={noop} onConfirmFull={noop}/>
        </SheetSlice>
        <SheetSlice label="switching… · restarting session" h={510}>
          <ModeSheet current="acceptEdits" step="switching" rules={[]} onSelect={noop}/>
        </SheetSlice>
        <SheetSlice label="allow-rules review · visible & revocable" h={560}>
          <ModeSheet current="acceptEdits" step="list" rules={['Bash git status','Edits in ~/proj/app/cc-pocket','Bash npm test']} onClear={noop} onClearAll={noop} onSelect={noop} onClearRule={noop}/>
        </SheetSlice>
        <SheetSlice label="start-session picker · from “+ New session” · none pre-selected" h={500}>
          <StartSessionSheet step="list" onPick={noop}/>
        </SheetSlice>
        <div style={{ maxWidth:390, alignSelf:'flex-end', paddingBottom:8 }}>
          <Cap>start-session picker · notes</Cap>
          <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec, background:T.surface, border:`1px solid ${T.border}`, borderRadius:12, padding:'13px 15px' }}>
            Opens before the session starts. Picking <span style={{ color:T.text, fontWeight:600 }}>Full auto</span> routes through the same guarded
            “Enable full auto?” confirm step. Dismissing the sheet starts the session at
            <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}> default</span> — “Ask each step” — so the safety invariant holds.
          </div>
        </div>
      </div>

      {/* ③ PERMISSION states */}
      <SectionLabel n="③">Permission prompt · 3-way + remember</SectionLabel>
      <div style={wrap}>
        <SheetSlice label="default · Deny / Allow once / Always allow" h={420}>
          <PermissionSheet req={REQ_SAFE} variant="default" seconds={23} onDeny={noop} onOnce={noop} onAlways={noop}/>
        </SheetSlice>
        <SheetSlice label="dangerous tool · terracotta moves to the safe choice" h={430}>
          <PermissionSheet req={REQ_DANGER} variant="default" seconds={19} onDeny={noop} onOnce={noop} onAlways={noop}/>
        </SheetSlice>
        <SheetSlice label="timeout · auto-denied" h={390}>
          <PermissionSheet req={REQ_SAFE} variant="timeout" onDismiss={noop}/>
        </SheetSlice>
        <div>
          <Cap>after “Always allow” · chip drops into the stream</Cap>
          <div style={{ width:390, background:T.base, border:`1px solid ${T.border}`, borderRadius:18, padding:'18px 16px', display:'flex', flexDirection:'column', gap:16 }}>
            <div>
              <div style={{ fontFamily:T.ui, fontSize:11, fontWeight:600, letterSpacing:0.5, color:T.muted, marginBottom:6 }}>YOU</div>
              <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>check the working tree</div>
            </div>
            <AllowChip rule="git status"/>
            <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>Clean working tree on <span style={mono}>main</span>. Nothing staged — want me to start the relay client scaffold?</div>
          </div>
        </div>
      </div>

      {/* P2 — deferred states */}
      <div style={{ marginTop:34, display:'flex', alignItems:'flex-start', gap:12, maxWidth:680,
        background:T.surface, border:`1px dashed ${T.border}`, borderRadius:12, padding:'14px 16px' }}>
        <span style={{ fontFamily:T.mono, fontSize:11, color:T.warning, border:`1px solid ${T.warning}55`, borderRadius:999, padding:'2px 9px', flexShrink:0 }}>P2</span>
        <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec }}>
          <span style={{ color:T.text, fontWeight:600 }}>Peer-cancelled (“Request withdrawn”) — needs protocol support.</span> Dropped from v1: the daemon
          doesn’t yet notify the phone when a pending request is cancelled on the computer, so the sheet can’t distinguish withdrawal from timeout. Re-add once the relay sends a <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.text }}>request_cancelled</span> event.
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
function Page() {
  return (
    <div style={{ maxWidth:1180, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Execution permissions — how free Claude is</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:'0 0 8px' }}>
        A four-level autonomy ladder, always visible as a header badge, switchable from a sheet, with the per-prompt approval upgraded so trust is granted <em>incrementally</em> and stays visible &amp; revocable. The phone below is live — tap the mode badge to switch levels, or send a message to trigger a permission request.
      </p>

      <div style={{ display:'flex', alignItems:'flex-start', gap:40, marginTop:40, flexWrap:'wrap' }}>
        <Phone shot><LiveDemo/></Phone>
        <div style={{ maxWidth:330, paddingTop:8 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>Try it</div>
          {[
            ['Tap the mode badge (header)', 'Opens the execution-mode sheet — pick any of the four levels.'],
            ['Choose “Full auto”', 'Guarded by a confirm step; more-permissive switches show “restarting session…”.'],
            ['Send a message', 'Claude tries to run git status → the upgraded permission prompt appears.'],
            ['Tap “Always allow”', 'A confirmation chip drops into the stream and the badge gains a “· N rules” counter.'],
            ['Re-open the badge', 'The remembered rules are listed — each removable, with “Clear all”.'],
          ].map(([t,d],i)=>(
            <div key={i} style={{ display:'flex', gap:12, marginBottom:16 }}>
              <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent, width:18, flexShrink:0 }}>{i+1}</span>
              <div>
                <div style={{ fontSize:13.5, fontWeight:600, color:T.text, marginBottom:2 }}>{t}</div>
                <div style={{ fontSize:12.5, lineHeight:'18px', color:T.sec }}>{d}</div>
              </div>
            </div>
          ))}
          <div style={{ marginTop:8, padding:'12px 14px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:12 }}>
            <div style={{ fontSize:12, fontWeight:600, color:T.text, marginBottom:6 }}>Safety invariants</div>
            <div style={{ fontSize:12, lineHeight:'18px', color:T.sec }}>
              · New sessions always start at <strong style={{color:T.text}}>Ask each step</strong>.<br/>
              · <strong style={{color:T.text}}>Full auto</strong> is this-session-only + double-confirmed.<br/>
              · “Always allow” shows the <strong style={{color:T.text}}>exact rule</strong> it creates, and it’s revocable.
            </div>
          </div>
        </div>
      </div>

      <Board/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
