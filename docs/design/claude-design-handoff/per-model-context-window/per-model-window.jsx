// cc-pocket — Per-model context window: view / set / clear · composer thread #4
// The typed denominator is now per-model (modelId -> tokens) + a demoted catch-all.
// Question: WHERE does the user set it, and how does Settings avoid reading broken?

const T = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', raisedHi:'#24282D', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
const WARN = '#E0A93B';
const HIT = 44;

// ── icons ─────────────────────────────────────────────────────
const ChevUp = ({ c=T.muted, s=12 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M3 9l4-4 4 4" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Chev = ({ c=T.muted, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M5 3l4 4-4 4" stroke={c} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Trash = ({ c=T.muted, s=16 }) => <svg width={s} height={s} viewBox="0 0 18 18" fill="none"><path d="M3.5 5h11M7.4 5V3.8A1.3 1.3 0 018.7 2.5h.6A1.3 1.3 0 0110.6 3.8V5M5.2 5l.6 8.4A1.4 1.4 0 007.2 14.7h3.6a1.4 1.4 0 001.4-1.3L12.8 5" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Info = ({ c=T.muted, s=15 }) => <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6.2" stroke={c} strokeWidth="1.4"/><path d="M8 7.2v3.4" stroke={c} strokeWidth="1.4" strokeLinecap="round"/><circle cx="8" cy="5.2" r="0.85" fill={c}/></svg>;
const Check = ({ c=T.accent, s=16 }) => <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M3.4 8.4l3 3 6.2-6.6" stroke={c} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const Pencil = ({ c=T.sec, s=15 }) => <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M10.6 3.2l2.2 2.2M3 11.4l7.1-7.1a1.2 1.2 0 011.7 0l.9.9a1.2 1.2 0 010 1.7L5.6 14 2.6 14.4 3 11.4z" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/></svg>;
const CornerArrow = ({ c=T.muted, s=13 }) => <svg width={s} height={s} viewBox="0 0 14 14" fill="none"><path d="M4 3v4.5A1.5 1.5 0 005.5 9H11M11 9l-2.2-2.2M11 9l-2.2 2.2" stroke={c} strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/></svg>;

const fmt = n => n.toLocaleString('en-US');

// ── shared atoms ──────────────────────────────────────────────
function SectionLabel({ children }){
  return <div style={{ fontFamily:T.ui, fontSize:11.5, fontWeight:600, letterSpacing:0.6, textTransform:'uppercase', color:T.muted, padding:'0 4px 9px' }}>{children}</div>;
}
function Card({ children, style }){
  return <div style={{ background:T.surface, border:`1px solid ${T.border}`, borderRadius:14, overflow:'hidden', ...style }}>{children}</div>;
}
function Divider(){ return <div style={{ height:1, background:T.border, margin:'0 14px' }}/>; }
function Hint({ children }){
  return <div style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.muted, padding:'10px 6px 0' }}>{children}</div>;
}
function Segmented({ value, onNote }){
  const opts = ['Auto','200K','1M'];
  return (
    <div style={{ display:'flex', gap:0, background:T.base, border:`1px solid ${T.border}`, borderRadius:10, padding:3 }}>
      {opts.map(o=>{
        const on = o===value;
        return <div key={o} style={{ flex:1, textAlign:'center', height:38, lineHeight:'38px', borderRadius:7,
          fontFamily:o==='Auto'?T.ui:T.mono, fontSize:13.5, fontWeight:on?600:500,
          color:on?T.text:T.sec, background:on?T.raisedHi:'transparent', boxShadow:on?`inset 0 0 0 1px ${T.border}`:'none' }}>{o}</div>;
      })}
    </div>
  );
}
function TokenField({ value, focused }){
  return (
    <div style={{ display:'flex', alignItems:'center', height:HIT, background:T.base, border:`1px solid ${focused?T.accent:T.border}`, borderRadius:10, padding:'0 12px', gap:8 }}>
      <span style={{ flex:1, fontFamily:T.mono, fontSize:14, color:value?T.text:T.muted }}>{value?fmt(value):'e.g. 128000'}</span>
      {focused && <span style={{ width:1.5, height:18, background:T.accent, animation:'none' }}/>}
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.muted }}>tokens</span>
    </div>
  );
}

// ── SETTINGS · Context window section ─────────────────────────
// Catch-all controls unchanged in shape; a "Per-model windows" block added below.
function SettingsSection({ catchAll='200K', catchTokens, entries, note }){
  return (
    <div style={{ padding:'18px 16px 22px', display:'flex', flexDirection:'column' }}>
      <SectionLabel>Context window · catch-all</SectionLabel>
      <Segmented value={catchAll}/>
      <div style={{ height:10 }}/>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', padding:'2px 4px 8px' }}>
        <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec }}>Custom size</span>
      </div>
      <TokenField value={catchTokens}/>
      {note
        ? <div style={{ display:'flex', gap:8, alignItems:'flex-start', marginTop:11, padding:'10px 11px', background:'rgba(224,169,59,0.09)', border:`1px solid rgba(224,169,59,0.32)`, borderRadius:10 }}>
            <span style={{ marginTop:1 }}><Info c={WARN} s={15}/></span>
            <span style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:'#EAD9B0' }}>{note}</span>
          </div>
        : <Hint>Applies to any model without its own window. Auto reads the size from the model id.</Hint>}

      <div style={{ height:22 }}/>
      <SectionLabel>Per-model windows</SectionLabel>
      {entries.length===0
        ? <Card style={{ borderStyle:'dashed', background:'transparent' }}>
            <div style={{ padding:'16px 14px', display:'flex', flexDirection:'column', gap:5 }}>
              <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.sec, fontWeight:500 }}>No models have their own window</span>
              <span style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'18px', color:T.muted }}>Set one from a session’s Info sheet when a gateway model reports the wrong size. They’ll be listed here to review or clear.</span>
            </div>
          </Card>
        : <Card>
            {entries.map((e,i)=>(
              <React.Fragment key={e.id}>
                {i>0 && <Divider/>}
                <div style={{ display:'flex', alignItems:'center', height:56, padding:'0 6px 0 14px', gap:10 }}>
                  <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', gap:3 }}>
                    <span style={{ fontFamily:T.mono, fontSize:13, color:T.text, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{e.id}</span>
                    {e.shadows && <span style={{ fontFamily:T.ui, fontSize:11, color:WARN, display:'flex', alignItems:'center', gap:4 }}><span style={{ width:5, height:5, borderRadius:99, background:WARN }}/>overrides the catch-all</span>}
                    {e.stale && <span style={{ fontFamily:T.ui, fontSize:11, color:T.muted }}>not run recently</span>}
                  </div>
                  <span style={{ fontFamily:T.mono, fontSize:13, color:T.sec, flexShrink:0 }}>{fmt(e.tokens)}</span>
                  <button aria-label="Delete" style={{ all:'unset', cursor:'pointer', width:HIT, height:HIT, display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0 }}><Trash/></button>
                </div>
              </React.Fragment>
            ))}
          </Card>}
      {entries.length>0 && <Hint>Per-model values always win over the catch-all. Delete a row to make that model fall back to the catch-all again.</Hint>}
    </div>
  );
}

// ── SESSION INFO · the window row (write surface) ─────────────
// Only the context-window block of the sheet — the rest of the sheet is unchanged.
function SessionInfoBlock({ modelId, used, cap, source, editing, editTokens }){
  const pct = Math.round(used/cap*100);
  const color = pct>=95?'#E5604D':pct>=80?WARN:T.sec;
  return (
    <div style={{ background:T.surface, borderTop:`1px solid ${T.border}`, padding:'16px 18px 20px' }}>
      <div style={{ width:40, height:5, borderRadius:99, background:T.border, margin:'-6px auto 16px' }}/>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:14 }}>
        <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Context window</span>
        <span style={{ flex:1 }}/>
        <span style={{ fontFamily:T.mono, fontSize:11.5, color:T.sec, background:T.raised, border:`1px solid ${T.border}`, borderRadius:99, padding:'3px 9px', maxWidth:150, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{modelId}</span>
      </div>

      {/* the bar (already exists) */}
      <div style={{ display:'flex', justifyContent:'space-between', alignItems:'baseline', marginBottom:7 }}>
        <span style={{ fontFamily:T.mono, fontSize:13, color:T.text }}>~{fmt(used)}</span>
        <span style={{ fontFamily:T.mono, fontSize:13, color:T.sec }}>/ {fmt(cap)}</span>
      </div>
      <div style={{ height:7, borderRadius:99, background:T.base, border:`1px solid ${T.border}`, overflow:'hidden' }}>
        <div style={{ width:`${pct}%`, height:'100%', background:color }}/>
      </div>

      {/* the write affordance — this is the new part */}
      <div style={{ height:14 }}/>
      {editing
        ? <div style={{ background:T.base, border:`1px solid ${T.accent}`, borderRadius:12, padding:'13px 13px 14px' }}>
            <div style={{ fontFamily:T.ui, fontSize:12, color:T.sec, marginBottom:9 }}>Window for <span style={{ fontFamily:T.mono, color:T.text }}>{modelId}</span></div>
            <TokenField value={editTokens} focused/>
            <div style={{ display:'flex', gap:8, marginTop:12 }}>
              <button style={{ all:'unset', cursor:'pointer', flex:1, height:HIT, borderRadius:10, background:T.accent, textAlign:'center', lineHeight:`${HIT}px`, fontFamily:T.ui, fontSize:14, fontWeight:600, color:T.base }}>Save for this model</button>
              <button style={{ all:'unset', cursor:'pointer', height:HIT, padding:'0 16px', borderRadius:10, border:`1px solid ${T.border}`, lineHeight:`${HIT}px`, fontFamily:T.ui, fontSize:14, color:T.sec }}>Cancel</button>
            </div>
          </div>
        : <button style={{ all:'unset', cursor:'pointer', display:'flex', alignItems:'center', gap:11, width:'100%', height:HIT, padding:'0 13px', boxSizing:'border-box', background:T.base, border:`1px solid ${T.border}`, borderRadius:12 }}>
            {source==='inherit' ? <CornerArrow c={T.muted} s={15}/> : <Pencil c={T.sec} s={15}/>}
            <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', gap:2, textAlign:'left' }}>
              <span style={{ fontFamily:T.ui, fontSize:13.5, color:T.text, fontWeight:500 }}>{source==='inherit'?'Set a window for this model':'Edit this model’s window'}</span>
              <span style={{ fontFamily:T.ui, fontSize:11.5, color:T.muted }}>
                {source==='inherit'
                  ? <>Now using the catch-all · <span style={{ fontFamily:T.mono }}>{fmt(cap)}</span></>
                  : <>This model’s own value · <span style={{ fontFamily:T.mono, color:WARN }}>{fmt(cap)}</span></>}
              </span>
            </div>
            <Chev c={T.muted}/>
          </button>}
    </div>
  );
}

// ── phone shells ──────────────────────────────────────────────
function NavBar({ title, sub }){
  return (
    <div style={{ height:52, flexShrink:0, background:T.base, borderBottom:`1px solid ${T.border}`, display:'flex', alignItems:'center', justifyContent:'center', flexDirection:'column' }}>
      <span style={{ fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>{title}</span>
      {sub && <span style={{ fontFamily:T.ui, fontSize:10.5, color:T.muted }}>{sub}</span>}
    </div>
  );
}
function SettingsScreen({ children }){
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:T.base, overflow:'hidden' }}>
      <div style={{ height:44, flexShrink:0 }}/>
      <NavBar title="Settings"/>
      <div style={{ flex:1, overflow:'hidden' }}>{children}</div>
    </div>
  );
}
function SheetScreen({ children }){
  return (
    <div style={{ height:'100%', display:'flex', flexDirection:'column', background:'rgba(6,7,8,0.72)', justifyContent:'flex-end' }}>
      <div style={{ height:44 }}/>
      <div style={{ flex:1 }}/>
      {children}
    </div>
  );
}
function Phone({ frame=390, scale=0.9, h=560, dark=true, children }){
  return (
    <div style={{ width:frame*scale, flexShrink:0 }}>
      <div style={{ width:frame, transform:`scale(${scale})`, transformOrigin:'top left', height:h/scale }}>
        <IOSDevice dark={dark} width={frame} height={h}><div style={{ position:'relative', height:'100%' }}>{children}</div></IOSDevice>
      </div>
    </div>
  );
}

// ── layout helpers ────────────────────────────────────────────
function Column({ tag, rec, title, blurb, verdict, children }){
  return (
    <section style={{ flex:'1 1 320px', minWidth:300, maxWidth:420 }}>
      <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:7 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:rec?T.accent:T.sec, letterSpacing:0.5 }}>{tag}</span>
        {rec && <span style={{ fontFamily:T.mono, fontSize:9.5, fontWeight:700, color:T.base, background:T.accent, borderRadius:4, padding:'2px 6px', letterSpacing:0.5 }}>RECOMMENDED</span>}
      </div>
      <h3 style={{ fontFamily:T.ui, fontSize:16, fontWeight:700, color:T.text, letterSpacing:-0.2, margin:'0 0 6px' }}>{title}</h3>
      <p style={{ fontFamily:T.ui, fontSize:13, lineHeight:'20px', color:T.sec, margin:'0 0 14px' }}>{blurb}</p>
      <div style={{ border:`1px solid ${rec?T.accent:T.border}`, borderRadius:16, padding:14, background:'#0B0C0D' }}>{children}</div>
      <p style={{ fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:verdict.good?T.success:T.muted, margin:'12px 0 0' }}>
        <strong style={{ color:verdict.good?T.success:T.accent }}>{verdict.good?'Wins · ':'Loses · '}</strong>{verdict.text}
      </p>
    </section>
  );
}
function StateCard({ n, label, note, children }){
  return (
    <div style={{ display:'flex', flexDirection:'column', width:390*0.88 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:8, marginBottom:8 }}>
        <span style={{ fontFamily:T.mono, fontSize:11, fontWeight:700, color:T.accent }}>{n}</span>
        <span style={{ fontFamily:T.ui, fontSize:13, fontWeight:600, color:T.text }}>{label}</span>
      </div>
      <Phone frame={390} scale={0.88} h={560}>{children}</Phone>
      <p style={{ fontFamily:T.ui, fontSize:12, lineHeight:'18px', color:T.muted, margin:'10px 2px 0' }}>{note}</p>
    </div>
  );
}

// ── data ──────────────────────────────────────────────────────
const ENTRIES = [
  { id:'deepseek-chat', tokens:65536, shadows:true },
  { id:'qwen-2.5-max', tokens:131072 },
  { id:'mixtral-8x22b', tokens:65536, stale:true },
];

function Page(){
  return (
    <div style={{ maxWidth:1360, margin:'0 auto', padding:'56px 44px 120px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · settings + session info · composer thread #4</p>
      <h1 style={{ fontFamily:T.ui, fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 10px', color:T.text }}>Setting the context window per model</h1>
      <p style={{ fontFamily:T.ui, fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:900, margin:'0 0 6px' }}>
        The typed denominator is now a property of the <strong style={{ color:T.text }}>model</strong>, not the phone: a per-model table (<span style={{ fontFamily:T.mono, fontSize:13 }}>modelId → tokens</span>) with the old single value demoted to a <strong style={{ color:T.text }}>catch-all</strong> for models with no entry of their own. Per-model always wins. The trap: if that precedence is invisible, a user who set 256K for <span style={{ fontFamily:T.mono, fontSize:13 }}>deepseek-chat</span> taps <span style={{ fontFamily:T.mono, fontSize:13 }}>[200K]</span> in global Settings, sees nothing change, and the setting reads broken. The fix is to make precedence legible <em>at the moment of interaction</em> — and to put the WRITE where a concrete model and its wrong number are both on screen.
      </p>

      <div style={{ display:'flex', flexWrap:'wrap', gap:34, marginTop:44, alignItems:'flex-start' }}>
        <Column tag="OPTION A" title="Model picker owns the write"
          blurb="Each alias / gateway row in the picker sheet gets a small window sub-line you can tap to set its size."
          verdict={{ good:false, text:'the picker exists to switch models fast; hanging an editable numeric field off every row bloats it, and it exposes windows for models you may never run this session. The wrong number isn’t even visible there — you’d be setting a denominator blind.' }}>
          <Phone frame={390} scale={0.6} h={560}><PickerFrame/></Phone>
        </Column>

        <Column tag="OPTION B" rec title="Split — Session Info writes, Settings audits"
          blurb="The write lives in Session Info, beside the very bar it corrects, for the one concrete model in scope. Settings keeps the catch-all and gains a read-only, deletable list of every per-model entry."
          verdict={{ good:true, text:'you edit the denominator while looking at the number that’s wrong; Settings stays global (catch-all + audit) and never pretends to own a model it can’t see; precedence gets stated inline the instant the two collide.' }}>
          <Phone frame={390} scale={0.6} h={560}>
            <SheetScreen><SessionInfoBlock modelId="deepseek-chat" used={168000} cap={262144} source="own"/></SheetScreen>
          </Phone>
        </Column>

        <Column tag="OPTION C" title="Session Info owns everything"
          blurb="Move the catch-all controls into Session Info too, so all window config lives in one sheet."
          verdict={{ good:false, text:'the catch-all is global and must be reachable with no session running — Session Info only opens from a live session, so this strands it. Two different scopes (global vs. this model) don’t belong in one per-session sheet.' }}>
          <Phone frame={390} scale={0.6} h={560}>
            <SheetScreen><SessionInfoBlock modelId="deepseek-chat" used={168000} cap={262144} source="own"/></SheetScreen>
          </Phone>
        </Column>
      </div>

      <p style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'23px', color:T.text, maxWidth:900, margin:'40px 0 0' }}>
        <strong style={{ color:T.accent }}>Recommendation — Option B, the split.</strong> Writes go where the model is concrete and its bar is visible (Session Info); the global catch-all and the auditable list stay in global Settings; precedence is spoken aloud, inline, the moment a catch-all tap would otherwise do nothing.
      </p>

      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:20, fontWeight:700, color:T.text, letterSpacing:-0.3, margin:'0 0 6px' }}>Option B · the five states</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, maxWidth:900, margin:'0 0 30px' }}>
          States 1–3 are the global Settings section; 4–5 are the Session Info write surface. The audit list is view-and-delete only — no search, sort, or bulk edit.
        </p>
        <div style={{ display:'flex', flexWrap:'wrap', gap:38, alignItems:'flex-start' }}>
          <StateCard n="1" label="Settings · empty table"
            note="Zero per-model entries — the common case. The block states where entries come from instead of nagging; the catch-all above behaves exactly as it does today.">
            <SettingsScreen><SettingsSection catchAll="200K" entries={[]}/></SettingsScreen>
          </StateCard>

          <StateCard n="2" label="Settings · entries present, one shadowing"
            note="Three saved windows, monospace token counts, each deletable at a 44pt target. deepseek-chat is flagged amber ‘overrides the catch-all’; a stale model is marked ‘not run recently’ but still clearable.">
            <SettingsScreen><SettingsSection catchAll="200K" entries={ENTRIES}/></SettingsScreen>
          </StateCard>

          <StateCard n="3" label="Settings · the conflict moment"
            note="User taps the [200K] catch-all segment while deepseek-chat holds its own value. Instead of silently doing nothing, an inline amber note names the models the change will NOT reach — the precedence is spoken exactly when it bites.">
            <SettingsScreen><SettingsSection catchAll="200K" entries={ENTRIES}
              note={<>Saved. This won’t affect <span style={{ fontFamily:T.mono, color:'#EAD9B0' }}>deepseek-chat</span> or 2 other models — they use their own window. Manage them below.</>}/></SettingsScreen>
          </StateCard>

          <StateCard n="4" label="Session Info · model inheriting catch-all"
            note="deepseek-chat with no entry yet: the bar divides by the catch-all, and the row says so — ‘Now using the catch-all · 200,000’ with a ‘Set a window for this model’ action. Tapping it opens the editor.">
            <SheetScreen><SessionInfoBlock modelId="deepseek-chat" used={92000} cap={200000} source="inherit"/></SheetScreen>
          </StateCard>

          <StateCard n="5" label="Session Info · own value, mid-edit"
            note="Editing deepseek-chat’s own window, input focused with the terracotta caret. Save writes the per-model row (and it appears in the Settings audit list); the amber cap makes clear this now overrides the catch-all.">
            <SheetScreen><SessionInfoBlock modelId="deepseek-chat" used={168000} cap={262144} source="own" editing editTokens={262144}/></SheetScreen>
          </StateCard>
        </div>
      </section>

      <section style={{ marginTop:56, borderTop:`1px solid ${T.border}`, paddingTop:34, maxWidth:900 }}>
        <h2 style={{ fontFamily:T.ui, fontSize:18, fontWeight:700, color:T.text, margin:'0 0 12px' }}>Why precedence is stated, not hinted</h2>
        <p style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec, margin:'0 0 12px' }}>
          The earlier bug felt broken because the catch-all tap was a no-op with no explanation. Two guards prevent its return: <strong style={{ color:T.text }}>(a)</strong> every shadowing model is visibly listed under the catch-all with an ‘overrides’ flag, so the override is never a secret; <strong style={{ color:T.text }}>(b)</strong> the instant a catch-all edit is shadowed, the section says which models it won’t reach and points at the list. The rule — <em>per-model wins; delete the row to fall back</em> — is shown as UI state, not buried in a hint.
        </p>
        <p style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'21px', color:T.muted, margin:0 }}>
          <strong style={{ color:T.accent }}>Scope stays honest:</strong> Settings has no ‘current model’, so it never offers to write one — only to review and clear. The write lives in Session Info, the one surface where a model is concrete and its denominator is already on screen (the gauge from thread #3 taps straight into it).
        </p>
      </section>
    </div>
  );
}

// tiny model-picker frame for Option A only
function PickerFrame(){
  const rows = [
    { id:'opus', sub:'claude-opus-4', win:'1M', on:false },
    { id:'sonnet', sub:'claude-sonnet-4', win:'1M', on:true },
    { id:'haiku', sub:'claude-haiku-4', win:'200K', on:false },
  ];
  const gw = [ { id:'deepseek-chat', win:'256K set' }, { id:'qwen-2.5-max', win:'128K set' } ];
  return (
    <SheetScreen>
      <div style={{ background:T.surface, borderTop:`1px solid ${T.border}`, borderTopLeftRadius:18, borderTopRightRadius:18, padding:'14px 16px 22px' }}>
        <div style={{ width:40, height:5, borderRadius:99, background:T.border, margin:'0 auto 16px' }}/>
        <SectionLabel>Model</SectionLabel>
        {rows.map(r=>(
          <div key={r.id} style={{ display:'flex', alignItems:'center', height:52, gap:12 }}>
            <div style={{ flex:1 }}>
              <div style={{ fontFamily:T.ui, fontSize:14.5, color:T.text, fontWeight:500 }}>{r.id}</div>
              <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted }}>{r.sub} · {r.win}</div>
            </div>
            {r.on && <Check/>}
          </div>
        ))}
        <div style={{ height:8 }}/>
        <SectionLabel>Gateway models</SectionLabel>
        {gw.map(r=>(
          <div key={r.id} style={{ display:'flex', alignItems:'center', height:52, gap:12 }}>
            <div style={{ flex:1 }}>
              <div style={{ fontFamily:T.mono, fontSize:13.5, color:T.text }}>{r.id}</div>
              <div style={{ fontFamily:T.ui, fontSize:11, color:WARN }}>window · {r.win}</div>
            </div>
            <Chev c={T.muted}/>
          </div>
        ))}
      </div>
    </SheetScreen>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
