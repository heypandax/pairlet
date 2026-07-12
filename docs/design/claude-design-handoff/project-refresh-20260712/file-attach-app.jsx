// cc-pocket — chat composer · file & video attach flow · app

let _fid = 200;
const fid = () => ++_fid;

const INITIAL = [
  { id: fid(), name: 'Q3-metrics-report.pdf',   kind: 'pdf',  size: 4.2,  state: 'uploading', pct: 0.64 },
  { id: fid(), name: 'sessions_export_2026.csv', kind: 'csv',  size: 1.1,  state: 'queued',    pct: 0 },
  { id: fid(), name: 'WsClient.reconnect.tsx',    kind: 'code', size: 0.03, state: 'failed',    pct: 0 },
];

function ChatScreen() {
  const [files, setFiles] = React.useState(INITIAL);
  const [sheetOpen, setSheetOpen] = React.useState(true);
  const [draft, setDraft] = React.useState('');
  const scrollRef = React.useRef(null);

  React.useEffect(() => { const el = scrollRef.current; if (el) el.scrollTop = el.scrollHeight; }, []);

  const cancel = (id) => setFiles(prev => prev.filter(f => f.id !== id));
  const retry  = (id) => setFiles(prev => prev.map(f => f.id === id ? { ...f, state:'uploading', pct:0.05 } : f));
  const pick   = (k) => { /* would open the native picker */ setSheetOpen(false); };

  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:T.base }}>
      {/* header */}
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${T.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:44 }}>
          <button style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={T.sec} s={17}/></button>
          <span style={{ flex:1, fontFamily:T.ui, fontSize:15, fontWeight:600, color:T.text }}>Reconnect backoff guard</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
          <span className="fa-pulse" style={{ width:6, height:6, borderRadius:999, background:T.success }}/>
          <span style={{ fontFamily:T.mono, fontSize:10.5, color:T.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:0 }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:T.muted }}>~/proj/app/cc-pocket</span></span>
        </div>
      </div>

      {/* stream */}
      <div ref={scrollRef} className="fa-scroll" style={{ flex:1, overflowY:'auto', padding:'16px 16px 18px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:18 }}>
          <div style={{ fontFamily:T.ui, fontSize:14.5, lineHeight:'22px', color:T.text }}>
            To match your logs to the stack trace I’ll need the raw files. Drop in the crash report, the session export, and the client source — I’ll read them straight from the workspace.
          </div>
        </div>
      </div>

      {/* composer */}
      <div style={{ flexShrink:0, paddingBottom:34 }}>
        <FileComposer
          files={files} sheetOpen={sheetOpen} draft={draft} onDraft={setDraft}
          onToggleSheet={()=>setSheetOpen(o=>!o)} onPick={pick}
          onCancel={cancel} onRetry={retry}/>
      </div>
    </div>
  );
}

// ── device wrapper ────────────────────────────────────────────
function Phone({ children, scale = 0.82 }) {
  return (
    <div style={{ width: 402*scale, height: 874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark>{children}</IOSDevice>
      </div>
    </div>
  );
}

function Page() {
  const steps = [
    ['Tap “+” to open the attach sheet', 'Photo · File · Video, side by side. File takes PDF, CSV, code, and Office docs; Video takes clips.'],
    ['Each pick becomes a chip', 'Filename in mono, middle-truncated, with its size. The ring around the type icon fills as chunks land on the paired Mac.'],
    ['Queued waits its turn', 'One transfer at a time — the CSV holds at a dotted track until the PDF finishes.'],
    ['A failed chunk tints danger', 'Tap the chip to retry; the “×” dismisses it. Send stays in its waiting state until every upload settles.'],
  ];
  return (
    <div style={{ maxWidth:1180, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Attach files &amp; video</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:660, margin:'0 0 8px' }}>
        Beyond images, the composer now takes generic files and video. Each attachment chunk-uploads to the paired computer and lands in this session’s workspace inbox — the agent then reads it by path. The chip’s progress ring makes “it’s in the workspace now” obvious without the jargon.
      </p>

      <div style={{ display:'flex', alignItems:'flex-start', gap:40, marginTop:40, flexWrap:'wrap' }}>
        <Phone><ChatScreen/></Phone>
        <div style={{ maxWidth:340, paddingTop:8 }}>
          <div style={{ fontSize:12, fontWeight:600, letterSpacing:1.2, textTransform:'uppercase', color:T.muted, marginBottom:14 }}>On this screen</div>
          {steps.map(([t,d],i)=>(
            <div key={i} style={{ display:'flex', gap:12, marginBottom:16 }}>
              <span style={{ fontFamily:T.mono, fontSize:12, color:T.accent, width:18, flexShrink:0 }}>{i+1}</span>
              <div>
                <div style={{ fontSize:13.5, fontWeight:600, color:T.text, marginBottom:2 }}>{t}</div>
                <div style={{ fontSize:12.5, lineHeight:'18px', color:T.sec }}>{d}</div>
              </div>
            </div>
          ))}
          <div style={{ marginTop:6, padding:'12px 14px', background:T.surface, border:`1px solid ${T.border}`, borderRadius:12 }}>
            <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, lineHeight:'17px' }}>
              workspace inbox<br/>
              <span style={{ color:T.sec }}>~/proj/app/cc-pocket/</span><span style={{ color:T.accent }}>.cc-inbox/</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
