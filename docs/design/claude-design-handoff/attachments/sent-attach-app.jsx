// cc-pocket — chat · sent attachments · app (dark chat, video player, light chat)

// ── full-screen video player overlay ──────────────────────────
function VideoPlayer({ t = DARK, total = 42, onClose }) {
  const [playing, setPlaying] = React.useState(false);
  const [elapsed, setElapsed] = React.useState(9);
  React.useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => setElapsed(e => (e + 0.1 >= total ? 0 : e + 0.1)), 100);
    return () => clearInterval(id);
  }, [playing, total]);
  const pct = elapsed / total;

  return (
    <div style={{ position:'absolute', inset:0, zIndex:60, background:'rgba(6,7,8,0.94)', display:'flex', flexDirection:'column' }}>
      {/* top bar */}
      <div style={{ position:'absolute', top:0, left:0, right:0, paddingTop:50, height:94, display:'flex', alignItems:'center', padding:'50px 8px 0', zIndex:3 }}>
        <button onClick={onClose} className="sa-press" aria-label="Close" style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <CloseX c="#fff" s={20}/>
        </button>
        <div style={{ flex:1, textAlign:'center', fontFamily:T.mono, fontSize:12, color:'rgba(255,255,255,0.6)' }}>clip.mov</div>
        <div style={{ width:44 }}/>
      </div>

      {/* video frame */}
      <div style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center', padding:'0 12px' }}>
        <div onClick={()=>setPlaying(p=>!p)} style={{ position:'relative', width:'100%', borderRadius:12, overflow:'hidden', cursor:'pointer' }}>
          <div style={{ width:'100%', aspectRatio:'16 / 9', background:'linear-gradient(150deg,#1b1410 0%,#3a2a20 46%,#7a5238 100%)', position:'relative' }}>
            <div style={{ position:'absolute', left:'30%', top:'26%', width:'50%', height:'50%', transform:'translate(-50%,-50%)', background:'radial-gradient(circle, rgba(255,200,150,0.4) 0%, transparent 68%)' }}/>
          </div>
          {!playing && (
            <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center', justifyContent:'center' }}>
              <div style={{ width:60, height:60, borderRadius:999, background:'rgba(12,10,9,0.5)', backdropFilter:'blur(6px)', WebkitBackdropFilter:'blur(6px)', border:'1px solid rgba(255,255,255,0.3)', display:'flex', alignItems:'center', justifyContent:'center' }}>
                <PlayTri c="#fff" s={26}/>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* controls */}
      <div style={{ padding:'0 20px 40px', display:'flex', flexDirection:'column', gap:14 }}>
        <div style={{ display:'flex', alignItems:'center', gap:14 }}>
          <button onClick={()=>setPlaying(p=>!p)} className="sa-press" aria-label={playing?'Pause':'Play'} style={{ all:'unset', cursor:'pointer', width:34, height:34, display:'flex', alignItems:'center', justifyContent:'center' }}>
            {playing ? <PauseGlyph c="#fff" s={22}/> : <PlayTri c="#fff" s={22}/>}
          </button>
          <span style={{ fontFamily:T.mono, fontSize:12, color:'rgba(255,255,255,0.85)', width:34 }}>{mmss(elapsed)}</span>
          {/* scrubber */}
          <div onClick={(e)=>{ const r=e.currentTarget.getBoundingClientRect(); setElapsed(total*Math.max(0,Math.min(1,(e.clientX-r.left)/r.width))); }}
            style={{ flex:1, height:22, display:'flex', alignItems:'center', cursor:'pointer' }}>
            <div style={{ position:'relative', width:'100%', height:4, borderRadius:999, background:'rgba(255,255,255,0.2)' }}>
              <div style={{ position:'absolute', left:0, top:0, bottom:0, width:`${pct*100}%`, borderRadius:999, background:T.accent }}/>
              <div style={{ position:'absolute', left:`${pct*100}%`, top:'50%', transform:'translate(-50%,-50%)', width:13, height:13, borderRadius:999, background:'#fff', boxShadow:'0 1px 4px rgba(0,0,0,0.5)' }}/>
            </div>
          </div>
          <span style={{ fontFamily:T.mono, fontSize:12, color:'rgba(255,255,255,0.5)', width:34, textAlign:'right' }}>{mmss(total)}</span>
        </div>
      </div>
    </div>
  );
}

// ── the chat screen (theme-aware) ─────────────────────────────
function ChatStream({ t, onOpenVideo }) {
  return (
    <div style={{ position:'relative', height:'100%', display:'flex', flexDirection:'column', background:t.base }}>
      {/* header */}
      <div style={{ flexShrink:0, paddingTop:52, borderBottom:`1px solid ${t.border}` }}>
        <div style={{ display:'flex', alignItems:'center', gap:4, padding:'0 8px 0 4px', height:44 }}>
          <button style={{ all:'unset', cursor:'pointer', width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center' }}><Chevron d="left" c={t.sec} s={17}/></button>
          <span style={{ flex:1, fontFamily:t.ui, fontSize:15, fontWeight:600, color:t.text }}>Reconnect backoff guard</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap:7, padding:'0 16px 9px' }}>
          <span className="sa-pulse" style={{ width:6, height:6, borderRadius:999, background:t.success }}/>
          <span style={{ fontFamily:t.mono, fontSize:10.5, color:t.sec, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', minWidth:0 }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color:t.muted }}>~/proj/app/cc-pocket</span></span>
        </div>
      </div>

      {/* stream */}
      <div className="sa-scroll" style={{ flex:1, overflowY:'auto', padding:'18px 16px 20px' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:20 }}>
          <UserTurn t={t}>
            <FileChipSent t={t} name="Q3-metrics-report.pdf" size="2.4 MB" kind="pdf" path="@inbox/report.pdf"/>
            <VideoCardSent t={t} dur={42} name="clip.mov" size="12.8 MB" path="@inbox/clip.mov" onOpen={onOpenVideo}/>
            <ImageThumbSent t={t} p={1} path="@inbox/console.png"/>
            <div style={{ fontFamily:t.ui, fontSize:14.5, lineHeight:'22px', color:t.text, marginTop:2 }}>here’s the crash report, a screen recording of the repro, and the console.</div>
          </UserTurn>

          <Assistant t={t}>
            Got all three in the workspace. Reading <PathChip t={t}>@inbox/report.pdf</PathChip> now — the traceback matches <PathChip t={t}>WsClient.reconnect()</PathChip>, and the recording confirms the socket closes before the backoff timer fires. Want me to add the guard?
          </Assistant>

          <UserTurn t={t}>
            <FileChipSent t={t} name="stacktrace.log" size="—" kind="code" path="@inbox/stacktrace.log" failed onRetry={()=>{}}/>
          </UserTurn>
        </div>
      </div>
    </div>
  );
}

// ── device wrapper ────────────────────────────────────────────
function Phone({ children, scale = 0.82, dark = true }) {
  return (
    <div style={{ width: 402*scale, height: 874*scale, flexShrink:0 }}>
      <div style={{ width:402, height:874, transform:`scale(${scale})`, transformOrigin:'top left' }}>
        <IOSDevice dark={dark}>{children}</IOSDevice>
      </div>
    </div>
  );
}

function DarkChatPhone() {
  const [video, setVideo] = React.useState(false);
  return (
    <Phone dark>
      <div style={{ position:'relative', height:'100%' }}>
        <ChatStream t={DARK} onOpenVideo={()=>setVideo(true)}/>
        {video && <VideoPlayer t={DARK} total={42} onClose={()=>setVideo(false)}/>}
      </div>
    </Phone>
  );
}
function LightChatPhone() {
  return (
    <Phone dark={false}>
      <ChatStream t={LIGHT} onOpenVideo={()=>{}}/>
    </Phone>
  );
}
function VideoPhone() {
  return (
    <Phone dark>
      <div style={{ position:'relative', height:'100%', background:DARK.base }}>
        <VideoPlayer t={DARK} total={42} onClose={()=>{}}/>
      </div>
    </Phone>
  );
}

// ── page ──────────────────────────────────────────────────────
function Col({ label, sub, children }) {
  return (
    <div>
      <div style={{ fontFamily:T.mono, fontSize:11, color:T.muted, marginBottom:10 }}>{label}</div>
      {children}
      {sub && <div style={{ maxWidth:300, marginTop:16, fontFamily:T.ui, fontSize:12.5, lineHeight:'19px', color:T.sec }}>{sub}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth:1280, margin:'0 auto', padding:'56px 48px 110px' }}>
      <p style={{ fontFamily:T.mono, fontSize:12, color:T.accent, letterSpacing:1, textTransform:'uppercase', margin:'0 0 16px' }}>cc-pocket · chat</p>
      <h1 style={{ fontSize:30, fontWeight:700, letterSpacing:-0.5, margin:'0 0 8px' }}>Sent attachments &amp; the workspace loop</h1>
      <p style={{ fontSize:15, lineHeight:'23px', color:T.sec, maxWidth:680, margin:'0 0 44px' }}>
        Once uploaded, each attachment renders inside the user’s turn with a mono path line showing exactly how the agent will reference it — then the reply quotes that same path back. Files, video, and images share the caption grammar: <span style={{ fontFamily:T.mono, fontSize:13 }}>✓ size · in workspace</span>. Tap the video to open the player.
      </p>

      <div style={{ display:'flex', alignItems:'flex-start', gap:48, flexWrap:'wrap' }}>
        <Col label="dark · delivered + failed + reply" sub="File chip, 16:9 video, and image thumb stack in one turn. Each shows its @inbox path; the assistant quotes it back. The last turn is the failed-delivery variant — danger hairline, tap to retry.">
          <DarkChatPhone/>
        </Col>
        <Col label="video player · idle → tap to play" sub="Dimmed scrim over the frame, close × at top-left, play/pause, and a mono scrubber with elapsed / total. Drag or tap the track to seek.">
          <VideoPhone/>
        </Col>
        <Col label="light · #FAF9F7 / #FFFFFF / #C15F3C" sub="The same screen on the light variant — surface cards on warm off-white, terracotta accent, #E4E1DB hairlines.">
          <LightChatPhone/>
        </Col>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
