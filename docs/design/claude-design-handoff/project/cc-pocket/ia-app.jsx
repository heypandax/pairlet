// cc-pocket — image attachment · app (live phone + variant board)

let _id = 100;
const uid = () => ++_id;
const mk = (p, state='ready', ar) => ({ id:uid(), p, state, ar });

// ════════════════════════════════════════════════════════════
// LIVE PHONE — real pick → compress → preview → send → view
// ════════════════════════════════════════════════════════════
function ChatDemo() {
  const [items, setItems] = React.useState([]);          // composer tray
  const [draft, setDraft] = React.useState('');
  const [stream, setStream] = React.useState([
    { type:'assistant', node:(<>I’m looking at the relay client. Send a screenshot of the error if you have one and I’ll match it to the stack trace.</>) },
  ]);
  const [viewer, setViewer] = React.useState(null);      // {images, index}
  const [animateTray, setAnimateTray] = React.useState(false);
  const scrollRef = React.useRef(null);
  const nextPalette = React.useRef(0);

  React.useEffect(() => { const el=scrollRef.current; if(el) el.scrollTop = el.scrollHeight; }, [stream]);

  const attach = () => {
    if (items.length >= 4) return;
    const p = nextPalette.current++ % PALETTES.length;
    const a = mk(p, 'compressing');
    setItems(prev => [...prev, a]);
    setAnimateTray(true);
    // simulate on-device compression
    setTimeout(() => setItems(prev => prev.map(x => x.id===a.id ? { ...x, state:'ready' } : x)), 1100);
  };
  const remove = (id) => setItems(prev => prev.filter(x => x.id !== id));

  const send = () => {
    const ready = items.filter(i => i.state === 'ready');
    if (!ready.length && !draft.trim()) return;
    const turn = { type:'user', images: ready.map(r => ({ ...r, ar: r.ar || 4/3 })), text: draft.trim() || null };
    setStream(prev => [...prev, turn]);
    setItems([]); setDraft(''); setAnimateTray(false);
    setTimeout(() => {
      setStream(prev => [...prev, { type:'assistant', node:(<>Got the {ready.length>1?`${ready.length} images`:'image'}. The traceback points at <code style={{fontFamily:T.mono,fontSize:12.5,background:T.surface,border:`1px solid ${T.border}`,borderRadius:5,padding:'1px 5px'}}>WsClient.reconnect()</code> — the socket closes before the backoff timer fires. Want me to add a guard?</>) }]);
    }, 900);
  };

  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      {/* header */}
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:44 }}>
          <button style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={T.sec} s={17}/></button>
          <span style={{ flex:1, fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Add relay websocket client</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
          <span className="ia-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:0 }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
        </div>
      </div>

      {/* stream */}
      <div ref={scrollRef} className="ia-scroll" style={{ flex:1, overflowY:'auto', padding:'16px 16px 18px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:18 }}>
          {stream.map((m,i)=> m.type==='user'
            ? <SentTurn key={i} turn={m} onOpen={(images,idx)=>setViewer({images,index:idx})}/>
            : <AssistantTurn key={i}>{m.node}</AssistantTurn>)}
        </div>
      </div>

      {/* composer */}
      <div style={{ flexShrink:0, paddingBottom:34 }}>
        <ComposerBar items={items} draft={draft} onChangeDraft={setDraft} onAttach={attach} onRemove={remove} onSend={send} animateTray={animateTray} atMax={items.length>=4}/>
      </div>

      {viewer && <Viewer images={viewer.images} index={viewer.index} onClose={()=>setViewer(null)} onIndex={(i)=>setViewer(v=>({...v,index:i}))}/>}
    </div>
  );
}

// ── device wrapper at a fixed showcase scale ──────────────────
function Phone({ children, scale = 0.82, shot = false }) {
  return (
    <div data-shot={shot?'1':undefined} style={{ width: 402*scale, height: 874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// STATIC COMPONENT CARDS (variant board)
// ════════════════════════════════════════════════════════════

// a slice of the phone screen (content width) for component-level display
function Slice({ children, label, h }) {
  return (
    <div>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:9 }}>{label}</div>
      <div style={{ width:390, height:h, background:T.base, border:`1px solid ${T.border}`, borderRadius:18, overflow:'hidden', display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
        {children}
      </div>
    </div>
  );
}

function noop(){}

// composer-only cards (tray states)
function TrayCard({ label, items, draft='' , hint }) {
  return (
    <Slice label={label} h={210}>
      <div style={{ flex:1, padding:'16px 16px 8px', display:'flex', flexDirection:'column', justifyContent:'flex-end' }}>
        {hint && <div style={{ fontFamily:T.ui, fontSize:13.5, lineHeight:'20px', color:T.sec }}>{hint}</div>}
      </div>
      <ComposerBar items={items} draft={draft} onChangeDraft={noop} onAttach={noop} onRemove={noop} onSend={noop}/>
    </Slice>
  );
}

// sent-turn cards
function TurnCard({ label, turn, after, h=300 }) {
  return (
    <Slice label={label} h={h}>
      <div style={{ flex:1, padding:'18px 16px', display:'flex', flexDirection:'column', gap:16, justifyContent:'flex-start' }}>
        <SentTurn turn={turn} onOpen={noop} onRetry={noop}/>
        {after && <AssistantTurn>{after}</AssistantTurn>}
      </div>
    </Slice>
  );
}

function SectionLabel({ children, n }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap:12, margin:'56px 0 22px' }}>
      <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent }}>{n}</span>
      <span style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted }}>{children}</span>
      <span style={{ flex:1, height:1, background:T.border }}/>
    </div>
  );
}

function Board() {
  const wrap = { display:'flex', flexWrap:'wrap', gap:26 };
  const assistantAfter = (<>Thanks — the red underline in the editor is a type error, not a runtime one. I’ll fix the generic on <code style={{fontFamily:T.mono,fontSize:12.5,background:T.surface,border:`1px solid ${T.border}`,borderRadius:5,padding:'1px 5px'}}>Flow&lt;T&gt;</code>.</>);

  return (
    <div>
      {/* ① TRAY STATES */}
      <SectionLabel n="①">Composer · attached-images tray</SectionLabel>
      <div style={wrap}>
        <TrayCard label="1 image attached" hint="Looks good. Here’s the crash:" items={[mk(0)]}/>
        <TrayCard label="4 images · the payload cap" items={[mk(1),mk(2),mk(3),mk(4)]}/>
        <TrayCard label="compressing on device" hint="Attaching a screenshot…" items={[mk(2),mk(1,'compressing')]}/>
        <TrayCard label="too large · auto-excluded" items={[mk(0),mk(3,'rejected')]}/>
        <TrayCard label="empty · no tray (reference)" hint="Message Claude as usual — the tray only appears once an image is picked." items={[]}/>
      </div>

      {/* ② SENT TURNS */}
      <SectionLabel n="②">Message stream · sent turn with images</SectionLabel>
      <div style={wrap}>
        <TurnCard label="1 image · natural aspect" turn={{ images:[mk(0,'ready',4/3)], text:'this is the stack trace when the socket drops' }} after={assistantAfter}/>
        <TurnCard label="2 images · 2-up" turn={{ images:[mk(1,'ready'),mk(4,'ready')], text:'editor on the left, console on the right' }} after={assistantAfter}/>
        <TurnCard label="4 images · 2×2 grid" turn={{ images:[mk(0),mk(1),mk(2),mk(3)], text:'four repro screenshots, in order' }} h={340}/>
        <TurnCard label="send failed · retry" turn={{ images:[mk(2),mk(5)], text:'does this layout look right?', failed:true }} h={300}/>
      </div>

      {/* ③ VIEWER */}
      <SectionLabel n="③">Full-screen viewer</SectionLabel>
      <div style={{ ...wrap, alignItems:'flex-start' }}>
        <div>
          <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:9 }}>tap a sent thumbnail · index 2 / 4</div>
          <Phone scale={0.74}><ViewerStatic/></Phone>
        </div>
        <div style={{ maxWidth:300, paddingTop:30 }}>
          <div style={{ fontFamily:T.ui, fontSize:14, lineHeight:'22px', color:T.sec }}>
            Near-black backdrop, image centered and pinch/tap-to-zoom. Slim top bar: close at left, <span style={{ fontFamily:T.mono, color:T.text }}>2 / 4</span> index centered. Swipe left/right between the turn’s images, swipe down to dismiss. The live phone above launches this for real — tap any sent thumbnail.
          </div>
        </div>
      </div>
    </div>
  );
}

// a static, non-interactive viewer for the board
function ViewerStatic() {
  const images = [mk(0),mk(1),mk(2),mk(3)];
  return (
    <div style={{ position:'relative', height:'100%', background:'#08090A' }}>
      <Viewer images={images} index={1} onClose={noop} onIndex={noop}/>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
function Page() {
  return (
    <div style={{ maxWidth:1180, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Image attachment — full lifecycle</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:660, margin:'0 0 8px' }}>
        Pick up to 4 images from the system photo picker, auto-compressed on device, then preview → send → view full size. The thumbnails <em>are</em> the signal. The phone below is live — tap the attach icon to pick, watch it compress, send, then tap a sent thumbnail to open the viewer.
      </p>

      <div style={{ display:'flex', alignItems:'flex-start', gap:40, marginTop:40, flexWrap:'wrap' }}>
        <Phone shot><ChatDemo/></Phone>
        <div style={{ maxWidth:330, paddingTop:8 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>Try it</div>
          {[
            ['Tap the photo icon (left of the field)', 'A thumbnail drops into a tray above the input and compresses on-device.'],
            ['Add a few — up to 4', 'Tray scrolls; the attach icon tints terracotta to confirm.'],
            ['Send with no text', 'With ≥1 ready image the Send button is enabled even when the field is empty.'],
            ['Tap a sent thumbnail', 'Opens the full-screen viewer — swipe between images, swipe down to close.'],
          ].map(([t,d],i)=>(
            <div key={i} style={{ display:'flex', gap:12, marginBottom:16 }}>
              <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent, width:18, flexShrink:0 }}>{i+1}</span>
              <div>
                <div style={{ fontSize:13.5, fontWeight:600, color:T.text, marginBottom:2 }}>{t}</div>
                <div style={{ fontSize:12.5, lineHeight:'18px', color:T.sec }}>{d}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <Board/>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
