// cc-pocket — multi-agent · app (blocks ①–④ + board)

// ════════════════ shared: dim chat backdrop behind sheets ════════════════
function ChatBackdrop({ agent='claude' }) {
  return (
    <div style={{ position:'absolute', inset:0, background:T.base, display:'flex', flexDirection:'column' }}>
      <div style={{ paddingTop:52, borderBottom:`1px solid ${T.border}`, flexShrink:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'0 14px', height:44 }}>
          <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text, flex:1 }}>Fix relay reconnect</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 14px 9px' }}>
          <Dot c={T.success} pulse s={6}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec }}>~/proj/app/cc-pocket</span>
        </div>
      </div>
      <div style={{ flex:1, padding:14, display:'flex', flexDirection:'column', gap:14, opacity:0.5 }}>
        <div style={{ fontFamily:T.ui, fontSize:13, color:T.text }}>The reconnect loop dies after the 3rd retry.</div>
        <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, height:40 }}/>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// ① NEW-SESSION SHEET — agent select + mode
// ════════════════════════════════════════════════════════════
function AgentOption({ agent, selected, onClick }) {
  const a = AGENTS[agent];
  return (
    <div onClick={onClick} className="ma-press" style={{
      flex:1, cursor:'pointer', borderRadius:13, padding:'13px 13px',
      background: selected ? a.tint : T.surface,
      border: `1.5px solid ${selected ? a.color : T.border}`,
      position:'relative',
    }}>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ width:30, height:30, borderRadius:8, background: selected?a.tint:T.raised, border:`1px solid ${selected?a.tintBorder:T.border}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>
          <AgentGlyph agent={agent} s={17}/>
        </span>
        <span style={{ fontFamily:T.ui, fontSize:15.5, fontWeight:700, color:T.text }}>{a.name}</span>
        <span style={{ flex:1 }}/>
        {selected && <span style={{ width:18, height:18, borderRadius:999, background:a.color, display:'flex', alignItems:'center', justifyContent:'center' }}>{I.check('#0E0F11',13)}</span>}
      </div>
      <div style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, marginTop:8 }}>{a.tagline}</div>
    </div>
  );
}

const CLAUDE_MODES = [
  { key:'ask', label:'Ask each step', tech:'default', desc:'Asks before any sensitive tool', color:T.sec },
  { key:'edits', label:'Accept edits', tech:'acceptEdits', desc:'File edits run; commands still ask', color:T.success },
  { key:'plan', label:'Plan first', tech:'plan', desc:'Read-only; proposes a plan, runs nothing', color:T.info },
  { key:'auto', label:'Full auto', tech:'bypassPermissions', desc:'Runs everything without asking', color:T.warning, danger:true },
];

function ModeRow({ m, selected, onClick }) {
  return (
    <div onClick={onClick} className="ma-press" style={{
      cursor:'pointer', borderRadius:11, padding:'11px 12px', marginBottom:8,
      background:T.surface, border:`1.5px solid ${selected ? m.color : T.border}`,
    }}>
      <div style={{ display:'flex', alignItems:'center', gap:9 }}>
        <Dot c={m.color} s={8}/>
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.text }}>{m.label}</span>
        {m.danger && <span style={{ display:'flex' }}>{I.warn(T.warning,14)}</span>}
        <span style={{ flex:1 }}/>
        <MChip>{m.tech}</MChip>
        {selected && <span style={{ marginLeft:4, display:'flex' }}>{I.check(m.color,15)}</span>}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12, color:T.sec, marginTop:5, paddingLeft:17 }}>{m.desc}</div>
    </div>
  );
}

const CODEX_PRESETS = [
  { key:'cautious', name:'Cautious', desc:'Asks every step, can only read', ask:'every', fs:'read', color:T.sec },
  { key:'balanced', name:'Balanced', desc:'Asks when needed, writes in the workspace', ask:'needed', fs:'workspace', color:T.codexAccent, rec:true },
  { key:'auto', name:'Autonomous', desc:'Never asks, writes in the workspace', ask:'never', fs:'workspace', color:T.warning },
  { key:'full', name:'Full auto', desc:'Never asks, full system access', ask:'never', fs:'full', color:T.danger, danger:true },
];

function PresetRow({ p, selected, onClick }) {
  const col = p.danger ? T.danger : (p.key==='balanced' ? AGENTS.codex.color : (selected?AGENTS.codex.color:T.border));
  return (
    <div onClick={onClick} className="ma-press" style={{
      cursor:'pointer', borderRadius:11, padding:'11px 12px', marginBottom:8,
      background: selected ? (p.danger?'rgba(229,96,77,0.07)':AGENTS.codex.tint) : T.surface,
      border:`1.5px solid ${selected ? col : T.border}`,
    }}>
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        <span style={{ fontFamily:T.ui, fontSize:14, fontWeight:600, color: p.danger?T.danger:T.text }}>{p.name}</span>
        {p.danger && <span style={{ display:'flex' }}>{I.warn(T.danger,14)}</span>}
        {p.rec && <span style={{ fontFamily:T.ui, fontSize:9.5, fontWeight:600, color:AGENTS.codex.color, border:`1px solid ${AGENTS.codex.tintBorder}`, borderRadius:999, padding:'1px 7px' }}>RECOMMENDED</span>}
        <span style={{ flex:1 }}/>
        {selected && <span style={{ display:'flex' }}>{I.check(p.danger?T.danger:AGENTS.codex.color,15)}</span>}
      </div>
      <div style={{ fontFamily:T.ui, fontSize:12, color:T.sec, margin:'5px 0 8px' }}>{p.desc}</div>
      <div style={{ display:'flex', gap:6 }}>
        <MChip c={p.danger&&p.ask==='never'?T.danger:T.sec}>ask: {p.ask}</MChip>
        <MChip c={p.fs==='full'?T.danger:T.sec}>fs: {p.fs}</MChip>
      </div>
    </div>
  );
}

function NewSessionSheet({ agent='claude', claudeMode='ask', codexPreset='balanced' }) {
  return (
    <Sheet title="New session" subtitle="~/proj/app/cc-pocket">
      <SLabel>Agent</SLabel>
      <div style={{ display:'flex', gap:10, marginBottom:18 }}>
        <AgentOption agent="claude" selected={agent==='claude'}/>
        <AgentOption agent="codex" selected={agent==='codex'}/>
      </div>
      <SLabel>Mode</SLabel>
      {agent==='claude'
        ? CLAUDE_MODES.map(m => <ModeRow key={m.key} m={m} selected={claudeMode===m.key}/>)
        : CODEX_PRESETS.map(p => <PresetRow key={p.key} p={p} selected={codexPreset===p.key}/>)
      }
      <div style={{ display:'flex', alignItems:'center', gap:8, marginTop:6, paddingTop:12, borderTop:`1px solid ${T.border}` }}>
        {I.shield(T.muted,15)}
        <span style={{ fontFamily:T.ui, fontSize:12, color:T.muted }}>You can change this anytime.</span>
      </div>
    </Sheet>
  );
}

// ════════════════════════════════════════════════════════════
// ② CODEX TWO-AXIS PERMISSIONS
// ════════════════════════════════════════════════════════════
function Segmented({ options, value, accent=AGENTS.codex.color }) {
  return (
    <div style={{ display:'flex', gap:3, padding:3, background:T.surface, border:`1px solid ${T.border}`, borderRadius:10 }}>
      {options.map(o => {
        const on = o.v===value;
        const danger = o.danger && on;
        return (
          <span key={o.v} style={{ flex:1, textAlign:'center', borderRadius:7, padding:'7px 4px',
            background: on ? (danger?T.danger:accent) : 'transparent',
            color: on ? '#0E0F11' : T.sec, fontFamily:T.ui, fontSize:11.5, fontWeight:600 }}>{o.l}</span>
        );
      })}
    </div>
  );
}

function CodexPermSheet({ expanded=false, selected='balanced' }) {
  return (
    <Sheet title="Codex permissions" subtitle="~/proj/app/cc-pocket">
      <div style={{ marginBottom:6 }}><AgentTag agent="codex"/></div>
      <SLabel style={{ marginTop:14 }}>Preset</SLabel>
      {CODEX_PRESETS.map(p => <PresetRow key={p.key} p={p} selected={selected===p.key}/>)}

      {/* advanced expander */}
      <div style={{ marginTop:6, border:`1px solid ${T.border}`, borderRadius:11, overflow:'hidden' }}>
        <div style={{ display:'flex', alignItems:'center', gap:8, padding:'12px 13px', background:T.surface }}>
          <span style={{ display:'flex', transform:expanded?'rotate(0deg)':'rotate(-90deg)', transition:'transform .2s' }}>{I.chevD(T.sec,15)}</span>
          <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>Advanced</span>
          <span style={{ flex:1 }}/>
          <span style={{ fontFamily:T.ui, fontSize:11, color:T.muted }}>{expanded?'two axes':'set raw axes'}</span>
        </div>
        {expanded && (
          <div style={{ padding:'14px 13px', borderTop:`1px solid ${T.border}`, display:'flex', flexDirection:'column', gap:16 }}>
            <div>
              <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:8 }}>
                <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>Approval</span>
                <MChip c={AGENTS.codex.color}>ask</MChip>
              </div>
              <Segmented value="needed" options={[{v:'every',l:'Every step'},{v:'needed',l:'When needed'},{v:'never',l:'Never'}]}/>
            </div>
            <div>
              <div style={{ display:'flex', alignItems:'center', gap:7, marginBottom:8 }}>
                <span style={{ fontFamily:T.ui, fontSize:12.5, fontWeight:600, color:T.text }}>Sandbox</span>
                <MChip c={AGENTS.codex.color}>fs</MChip>
              </div>
              <Segmented value="workspace" options={[{v:'read',l:'Read-only'},{v:'workspace',l:'Workspace'},{v:'full',l:'Full',danger:true}]}/>
            </div>
            <div style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>Preset: <span style={{ color:T.text, fontWeight:600 }}>Balanced</span> · diverge to make it <span style={{ fontFamily:T.mono }}>Custom</span>.</div>
          </div>
        )}
      </div>
    </Sheet>
  );
}

function CodexDangerConfirm() {
  return (
    <Sheet>
      <div style={{ background:'rgba(229,96,77,0.08)', border:`1.5px solid ${T.danger}`, borderRadius:14, padding:'18px 16px' }}>
        <div style={{ display:'flex', alignItems:'center', gap:10, marginBottom:12 }}>
          <span style={{ width:38, height:38, borderRadius:10, background:'rgba(229,96,77,0.14)', border:`1px solid ${T.danger}66`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>{I.warn(T.danger,20)}</span>
          <span style={{ fontFamily:T.ui, fontSize:16, fontWeight:700, color:T.text }}>Full access</span>
        </div>
        <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.sec, marginBottom:12 }}>
          Codex can run anything, anywhere — with no confirmation. Only enable this in a directory you trust.
        </div>
        <div style={{ fontFamily:T.mono, fontSize:11.5, color:T.text, background:T.base, border:`1px solid ${T.border}`, borderRadius:8, padding:'9px 11px', marginBottom:16 }}>~/proj/app/cc-pocket</div>
        <div style={{ display:'flex', gap:10 }}>
          <button style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', height:46, lineHeight:'46px', borderRadius:11, border:`1px solid ${T.border}`, fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text }}>Cancel</button>
          <button style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', height:46, lineHeight:'46px', borderRadius:11, background:T.danger, color:'#0E0F11', fontFamily:T.ui, fontSize:14.5, fontWeight:700 }}>Enable</button>
        </div>
      </div>
    </Sheet>
  );
}

// ════════════════════════════════════════════════════════════
// ③ AGENT-AWARE MODEL PICKER
// ════════════════════════════════════════════════════════════
function ModelRow({ name, hint, id, selected, color, switching }) {
  return (
    <div className="ma-press" style={{ display:'flex', alignItems:'center', gap:11, padding:'12px 13px', borderRadius:11, cursor:'pointer', background:T.surface, border:`1px solid ${selected?color:T.border}`, marginBottom:8 }}>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ display:'flex', alignItems:'center', gap:8 }}>
          <span style={{ fontFamily:T.ui, fontSize:14.5, fontWeight:600, color:T.text }}>{name}</span>
          <span style={{ fontFamily:T.ui, fontSize:12, color:T.sec }}>{hint}</span>
        </div>
        <div style={{ fontFamily:T.mono, fontSize:10.5, color:T.muted, marginTop:3 }}>{id}</div>
      </div>
      {switching
        ? <span style={{ display:'flex', alignItems:'center', gap:6 }}><span className="ma-spin" style={{ width:13, height:13, border:`1.6px solid ${T.border}`, borderTopColor:color, borderRadius:999 }}/><span style={{ fontFamily:T.mono, fontSize:10, color:T.muted }}>switching…</span></span>
        : selected ? <span style={{ display:'flex' }}>{I.check(color,17)}</span> : null}
    </div>
  );
}

function ModelSheet({ agent='claude', switching }) {
  const a = AGENTS[agent];
  return (
    <Sheet title={agent==='claude' ? 'Claude model' : 'Codex model'} subtitle="~/proj/app/cc-pocket">
      <div style={{ marginBottom:14 }}><AgentTag agent={agent}/></div>
      {agent==='claude' ? (
        <>
          <ModelRow name="Opus" hint="most capable" id="claude-opus-4-6" color={a.color} selected={false}/>
          <ModelRow name="Sonnet" hint="balanced" id="claude-sonnet-4-5" color={a.color} selected={true}/>
          <ModelRow name="Haiku" hint="fastest" id="claude-haiku-4" color={a.color} selected={false}/>
        </>
      ) : (
        <>
          <ModelRow name="GPT-5.1-Codex" hint="most capable" id="gpt-5.1-codex" color={a.color} selected={true}/>
          <ModelRow name="GPT-5.1-Codex-mini" hint="faster" id="gpt-5.1-codex-mini" color={a.color} selected={false} switching={switching}/>
          <ModelRow name="GPT-5-Codex" hint="prior gen" id="gpt-5-codex" color={a.color} selected={false}/>
          <SLabel style={{ marginTop:16 }}>Reasoning effort</SLabel>
          <Segmented value="high" accent={a.color} options={[{v:'low',l:'Low'},{v:'med',l:'Medium'},{v:'high',l:'High'},{v:'xhigh',l:'Xhigh'}]}/>
        </>
      )}
    </Sheet>
  );
}

// ════════════════════════════════════════════════════════════
// ④ CODEX FILE-CHANGE DIFF APPROVAL
// ════════════════════════════════════════════════════════════
function DiffLines({ lines }) {
  return (
    <pre style={{ margin:0, padding:'10px 0', fontFamily:T.mono, fontSize:10.5, lineHeight:'17px', overflowX:'auto' }}>
      {lines.map((l,i)=>{
        const sign=l[0];
        const bg = sign==='+'?'rgba(79,180,119,0.12)':sign==='-'?'rgba(229,96,77,0.12)':'transparent';
        const col = sign==='+'?T.success:sign==='-'?T.danger:T.muted;
        return <div key={i} style={{ background:bg, padding:'0 12px', display:'flex', gap:9 }}>
          <span style={{ color:col, width:7, flexShrink:0 }}>{sign===' '?'':sign}</span>
          <span style={{ color: sign===' '?T.sec:col, whiteSpace:'pre' }}>{l.slice(1)}</span>
        </div>;
      })}
    </pre>
  );
}

function CountRing({ amber }) {
  const col = amber ? T.warning : T.accent;
  return (
    <svg width="30" height="30" viewBox="0 0 30 30" style={{ transform:'rotate(-90deg)' }}>
      <circle cx="15" cy="15" r="12" stroke={T.border} strokeWidth="2.4" fill="none"/>
      <circle cx="15" cy="15" r="12" stroke={col} strokeWidth="2.4" fill="none" strokeLinecap="round" strokeDasharray="75.4" strokeDashoffset={amber?60:22}/>
    </svg>
  );
}

function DiffSheet({ variant='single' }) {
  return (
    <Sheet>
      <div style={{ display:'flex', alignItems:'center', gap:9, marginBottom:13 }}>
        <AgentTag agent="codex"/>
        <span style={{ flex:1 }}/>
        <CountRing amber={variant==='timeout'}/>
      </div>
      <div style={{ display:'flex', alignItems:'center', gap:9, marginBottom:13 }}>
        <span style={{ width:34, height:34, borderRadius:10, background:AGENTS.codex.tint, border:`1px solid ${AGENTS.codex.tintBorder}`, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}>{I.write(AGENTS.codex.color,18)}</span>
        <div>
          <div style={{ fontFamily:T.ui, fontSize:10.5, color:T.sec }}>Codex wants to edit files</div>
          <div style={{ fontFamily:T.ui, fontSize:15, fontWeight:700, color:T.text }}>{variant==='multi'?'3 files':'Edit file'}</div>
        </div>
      </div>

      {variant==='multi' ? (
        <div style={{ display:'flex', flexDirection:'column', gap:8, marginBottom:14 }}>
          {[['WsClient.kt','+14','−3',true],['Backoff.kt','+6','−1',false],['ReconnectTest.kt','+22','−0',false]].map(([f,a,d,open],i)=>(
            <div key={i} style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden' }}>
              <div style={{ display:'flex', alignItems:'center', gap:8, padding:'9px 11px' }}>
                <span style={{ display:'flex', transform:open?'none':'rotate(-90deg)' }}>{I.chevD(T.sec,13)}</span>
                <span style={{ fontFamily:T.mono, fontSize:11, color:T.text, flex:1, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>src/relay/{f}</span>
                <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.success }}>{a}</span>
                <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.danger }}>{d}</span>
              </div>
              {open && <div style={{ borderTop:`1px solid ${T.border}` }}><DiffLines lines={[' fun reconnect() {','-  scope.launch { open() }','+  appScope.launch {','+    delay(backoff); open()','+  }',' }']}/></div>}
            </div>
          ))}
        </div>
      ) : (
        <>
          <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:9 }}>
            <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.text, flex:1, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', direction:'rtl', textAlign:'left' }}>~/proj/app/cc-pocket/src/relay/WsClient.kt</span>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.success }}>+5</span>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.danger }}>−1</span>
          </div>
          <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:10, overflow:'hidden', marginBottom:14 }}>
            <DiffLines lines={ variant==='long'
              ? [' fun reconnect() {','-  scope.launch { open() }','+  appScope.launch {','+    delay(backoff)','+    open()']
              : [' fun reconnect() {','-  scope.launch { open() }','+  appScope.launch {','+    delay(backoff); open()','+  }',' }'] }/>
            {variant==='long' && <div style={{ borderTop:`1px solid ${T.border}`, padding:'9px 12px', display:'flex', alignItems:'center', gap:7, cursor:'pointer' }}>
              <span style={{ fontFamily:T.mono, fontSize:10.5, color:AGENTS.codex.color }}>… 42 more lines</span>{I.chevD(AGENTS.codex.color,13)}
            </div>}
          </div>
        </>
      )}

      <div style={{ display:'flex', alignItems:'center', gap:6, fontFamily:T.mono, fontSize:10.5, color:T.muted, marginBottom:14 }}>
        <span>~/proj/app/cc-pocket</span><span>·</span>{I.branch(T.muted,11)}<span>main</span>
      </div>

      {/* always-allow affordance */}
      <div style={{ display:'flex', alignItems:'center', gap:9, padding:'10px 12px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:10, marginBottom:11 }}>
        <span style={{ width:18, height:18, borderRadius:5, border:`1.5px solid ${T.muted}`, flexShrink:0 }}/>
        <span style={{ fontFamily:T.ui, fontSize:12.5, color:T.sec }}>Always allow edits this session</span>
      </div>
      <div style={{ display:'flex', gap:10 }}>
        <button style={{ all:'unset', cursor:'pointer', flex:1, textAlign:'center', height:48, lineHeight:'48px', borderRadius:12, border:`1px solid ${T.danger}66`, color:T.danger, fontFamily:T.ui, fontSize:15, fontWeight:600 }}>Deny</button>
        <button style={{ all:'unset', cursor:'pointer', flex:1.3, textAlign:'center', height:48, lineHeight:'48px', borderRadius:12, background:T.accent, color:'#0E0F11', fontFamily:T.ui, fontSize:15, fontWeight:700 }}>Allow once</button>
      </div>
    </Sheet>
  );
}

// frame helper: sheet over backdrop
function Frame({ agent='claude', children }) {
  return <div style={{ position:'relative', height:'100%' }}><ChatBackdrop agent={agent}/>{children}</div>;
}

// ════════════════════════════════════════════════════════════
// BOARD
// ════════════════════════════════════════════════════════════
function Page() {
  return (
    <div style={{ maxWidth:1320, margin:'0 auto', padding:'56px 48px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · multi-agent</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px' }}>Claude · Codex — two backends, one app</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:700, margin:'0 0 4px' }}>
        A session is bound to one agent for its life. <b style={{color:T.text}}>Claude</b> is the default and keeps the app accent (terracotta). <b style={{color:'#3FB5AC'}}>Codex</b> gets a calm teal identity — <span style={{ fontFamily:T.mono, fontSize:13 }}>#3FB5AC</span> — used everywhere it surfaces.
      </p>

      {/* ① */}
      <Divider sub="Pick the backend and the execution mode in one sheet. Claude shows its 4 modes; selecting Codex swaps the mode section to Codex presets.">① New session · agent + mode</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="1a" label="Claude selected (4 modes)" note="Default. Agent selector up top; Claude pre-selected and outlined in terracotta. The 4 Claude modes below, Full auto carries a warning glyph.">
          <Phone><Frame agent="claude"><NewSessionSheet agent="claude" claudeMode="ask"/></Frame></Phone>
        </Cell>
        <Cell tag="1b" label="Codex selected (presets)" note="Mode section swaps to the Codex two-axis presets, each with plain-language copy + the underlying axis chips. Balanced is the recommended default.">
          <Phone><Frame agent="codex"><NewSessionSheet agent="codex" codexPreset="balanced"/></Frame></Phone>
        </Cell>
        <Cell tag="1c" label="Full auto / danger row" note="The risky Claude mode highlighted — warning glyph + amber outline when selected.">
          <Phone><Frame agent="claude"><NewSessionSheet agent="claude" claudeMode="auto"/></Frame></Phone>
        </Cell>
      </div>

      {/* identity mini-set */}
      <Divider sub="How an agent is surfaced consistently. Recommendation (shown primary): keep the common Claude case visually QUIET — tag ONLY Codex; an untagged session is Claude by convention. Less chrome on the 90% path.">Agent identity · mini-set</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:24, alignItems:'flex-start' }}>
        <div style={{ width:360, background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, padding:18 }}>
          <div style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text, marginBottom:14 }}>Recommended · tag only Codex</div>
          {/* a) header connection bar */}
          <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, marginBottom:6 }}>a · chat header connection bar</div>
          <div style={{ display:'flex', alignItems:'center', gap:7, padding:'9px 11px', background:T.base, border:`1px solid ${T.border}`, borderRadius:10, marginBottom:14 }}>
            <Dot c={T.success} pulse s={6}/>
            <span style={{ fontFamily:T.mono, fontSize:11, color:T.sec, flex:1 }}>~/proj/app · sonnet-4.5</span>
            <AgentTag agent="codex" size="sm"/>
          </div>
          {/* b) session-list row */}
          <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, marginBottom:6 }}>b · session-list row</div>
          <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:10, padding:'10px 12px', marginBottom:8 }}>
            <div style={{ display:'flex', alignItems:'center', gap:8 }}>
              <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Refactor auth module</span>
            </div>
            <div style={{ fontFamily:T.mono, fontSize:10, color:T.muted, marginTop:5 }}>💬 12 · ⑂ main · 2h</div>
          </div>
          <div style={{ background:T.base, border:`1px solid ${T.border}`, borderRadius:10, padding:'10px 12px', marginBottom:14 }}>
            <div style={{ display:'flex', alignItems:'center', gap:8 }}>
              <span style={{ fontFamily:T.ui, fontSize:13.5, fontWeight:600, color:T.text }}>Port parser to Rust</span>
              <AgentTag agent="codex" size="sm"/>
            </div>
            <div style={{ fontFamily:T.mono, fontSize:10, color:T.muted, marginTop:5 }}>💬 5 · ⑂ feat/rs · 1h</div>
          </div>
          {/* c) session-info row */}
          <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, marginBottom:6 }}>c · session-info sheet row</div>
          <div style={{ display:'flex', alignItems:'center', padding:'11px 12px', background:T.base, border:`1px solid ${T.border}`, borderRadius:10 }}>
            <span style={{ fontFamily:T.ui, fontSize:13, color:T.sec, flex:1 }}>Agent</span>
            <AgentTag agent="codex" size="md"/>
          </div>
        </div>

        <div style={{ width:300, paddingTop:6 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>Why tag-only-Codex</div>
          <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.sec }}>
            Most sessions are Claude. Tagging every one adds noise to the common path; tagging <b style={{color:'#3FB5AC'}}>only Codex</b> makes the exception pop and keeps Claude calm. The agent is always explicit in the session-info sheet (row c), so nothing is hidden — the list just stays quiet.
            <div style={{ marginTop:14, paddingTop:14, borderTop:`1px solid ${T.border}` }}>
              <span style={{ color:T.text, fontWeight:600 }}>Alternative:</span> tag both agents everywhere (fully explicit) — heavier, but unambiguous for teams running a 50/50 mix.
            </div>
          </div>
          <div style={{ marginTop:18 }}>
            <div style={{ fontFamily:T.ui, fontSize:11, color:T.muted, marginBottom:8 }}>both-tagged variant</div>
            <div style={{ display:'flex', gap:8 }}><AgentTag agent="claude" size="sm"/><AgentTag agent="codex" size="sm"/></div>
          </div>
        </div>
      </div>

      {/* ② */}
      <Divider sub="Codex permission is two orthogonal axes. Named presets are primary; Advanced reveals the raw axes; dangerous combinations get a loud danger treatment + required confirm.">② Codex permissions · two axes</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="2a" label="Presets (Balanced)" note="Four presets as rows; each shows the underlying axes as mono chips (ask / fs). Balanced recommended; Full auto is danger-colored.">
          <Phone><Frame agent="codex"><CodexPermSheet expanded={false} selected="balanced"/></Frame></Phone>
        </Cell>
        <Cell tag="2b" label="Advanced expanded" note="Two segmented rows — Approval × Sandbox — for any combination; the preset name becomes Custom when they diverge.">
          <Phone><Frame agent="codex"><CodexPermSheet expanded={true} selected="balanced"/></Frame></Phone>
        </Cell>
        <Cell tag="2c" label="Danger preset selected" note="Full auto (never ask + full access) selected: red outline, warning glyph, danger axis chips.">
          <Phone><Frame agent="codex"><CodexPermSheet expanded={false} selected="full"/></Frame></Phone>
        </Cell>
        <Cell tag="2d" label="Required danger confirm" note="Reuses the BypassConfirm pattern — a loud danger card naming the exact directory, Cancel / Enable.">
          <Phone><Frame agent="codex"><CodexDangerConfirm/></Frame></Phone>
        </Cell>
      </div>

      {/* ③ */}
      <Divider sub="Opened from session quick-actions; the list and header follow the session's agent. Codex adds a reasoning-effort control.">③ Model picker · agent-aware</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="3a" label="Claude model" note="Opus / Sonnet / Haiku — name + hint + full mono model id; current selection has a terracotta check.">
          <Phone><Frame agent="claude"><ModelSheet agent="claude"/></Frame></Phone>
        </Cell>
        <Cell tag="3b" label="Codex model + effort" note="Codex models in the same row style, plus a Low / Medium / High / Xhigh reasoning-effort segmented control.">
          <Phone><Frame agent="codex"><ModelSheet agent="codex"/></Frame></Phone>
        </Cell>
        <Cell tag="3c" label="Mid-switch" note="A row showing the subtle muted “switching…” affordance while the daemon swaps models.">
          <Phone><Frame agent="codex"><ModelSheet agent="codex" switching={true}/></Frame></Phone>
        </Cell>
      </div>

      {/* ④ */}
      <Divider sub="Extends the existing permission sheet for Codex patch approvals — a real diff viewer on the phone. Actions stay Deny / Allow once / Always allow this session.">④ Codex file-change · diff approval</Divider>
      <div style={{ display:'flex', flexWrap:'wrap', gap:34 }}>
        <Cell tag="4a" label="Single-file diff" note="Codex identity chip + “wants to edit files”, file path + +N/−M, a line-based diff (red/green tinted rows), branch line, countdown ring.">
          <Phone><Frame agent="codex"><DiffSheet variant="single"/></Frame></Phone>
        </Cell>
        <Cell tag="4b" label="Long diff (truncated)" note="Very long diffs truncate with a teal “… 42 more lines” expander.">
          <Phone><Frame agent="codex"><DiffSheet variant="long"/></Frame></Phone>
        </Cell>
        <Cell tag="4c" label="Multi-file patch" note="Collapsible per-file headers (path + per-file +/−); first file expanded.">
          <Phone><Frame agent="codex"><DiffSheet variant="multi"/></Frame></Phone>
        </Cell>
        <Cell tag="4d" label="Near-timeout (amber ring)" note="The countdown ring shifts terracotta → amber as it nears zero.">
          <Phone><Frame agent="codex"><DiffSheet variant="timeout"/></Frame></Phone>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
