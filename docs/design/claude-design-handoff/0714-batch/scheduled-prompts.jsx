// cc-pocket — Component sheet · Scheduled prompts ("schedule send")
// A: schedule-send bottom sheet (over chat)   B: scheduled tasks page   C: usage-limit auto-continue banner

const D = {
  page:'#08090A', base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', success:'#4FB477', warning:'#E0A93B', danger:'#E5604D',
  mono:"'JetBrains Mono',ui-monospace,monospace",
  ui:"'Inter',-apple-system,system-ui,sans-serif",
};
// warm terracotta chip fill (low-alpha), used for active pills / repeat badge
const accentSoft = 'rgba(217,119,87,0.16)';
const warnSoft   = 'rgba(224,169,59,0.13)';
const dangerSoft = 'rgba(229,96,77,0.14)';

// ── icons ─────────────────────────────────────────────────────
const IconClock = ({ c = D.sec, s = 18 }) => (
  <svg width={s} height={s} viewBox="0 0 20 20" fill="none">
    <circle cx="10" cy="10" r="7.4" stroke={c} strokeWidth="1.5"/>
    <path d="M10 5.6V10l3 1.9" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const IconRepeat = ({ c = D.accent, s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 18 18" fill="none">
    <path d="M3 8V7a3 3 0 013-3h6l-1.6-1.6M15 10v1a3 3 0 01-3 3H6l1.6 1.6" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const IconHourglass = ({ c = D.warning, s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none">
    <path d="M4 2h8M4 14h8M4.6 2c0 3 2.4 4.4 3.4 5 1-.6 3.4-2 3.4-5M4.6 14c0-3 2.4-4.4 3.4-5 1 .6 3.4 2 3.4 5" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);
const IconChevron = ({ d = 'right', c = D.muted, s = 16, w = 1.9 }) => {
  const p = { right:'M7 4l6 6-6 6', down:'M4 7l6 6 6-6', left:'M13 4l-6 6 6 6' };
  return <svg width={s} height={s} viewBox="0 0 20 20" fill="none"><path d={p[d]} stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round"/></svg>;
};
const IconX = ({ c = D.muted, s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke={c} strokeWidth="1.7" strokeLinecap="round"/></svg>
);
const IconCheck = ({ c = D.success, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M3 8.4l3.1 3.1L13 4.6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
);
const IconWarnTri = ({ c = D.warning, s = 15 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none"><path d="M8 2.2l6 10.4H2L8 2.2z" stroke={c} strokeWidth="1.4" strokeLinejoin="round"/><path d="M8 6.6v3M8 11.2v.01" stroke={c} strokeWidth="1.5" strokeLinecap="round"/></svg>
);
const Plus = ({ c = D.sec }) => (<svg width="18" height="18" viewBox="0 0 18 18" fill="none"><path d="M9 3.5v11M3.5 9h11" stroke={c} strokeWidth="1.9" strokeLinecap="round"/></svg>);
const SendArrow = ({ c = D.base }) => (<svg width="18" height="18" viewBox="0 0 18 18" fill="none"><path d="M9 14.5V4M9 4l-4.2 4.2M9 4l4.2 4.2" stroke={c} strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round"/></svg>);

// ── phone frame ───────────────────────────────────────────────
function Phone({ children, w = 384, h = 812 }) {
  return (
    <div style={{
      width: w, height: h, borderRadius: 46, background: '#000', position: 'relative',
      overflow: 'hidden', boxShadow: '0 40px 90px -30px rgba(0,0,0,0.75), 0 0 0 1px #26292E', fontFamily: D.ui,
    }}>
      <div style={{ position: 'absolute', top: 11, left: '50%', transform: 'translateX(-50%)', width: 118, height: 34, borderRadius: 22, background: '#000', zIndex: 80 }}/>
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 54, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 30px', zIndex: 70 }}>
        <span style={{ fontFamily: '-apple-system, system-ui', fontSize: 15, fontWeight: 600, color: '#fff' }}>9:41</span>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <svg width="18" height="11" viewBox="0 0 18 11"><rect x="0" y="7" width="3" height="4" rx=".6" fill="#fff"/><rect x="4.5" y="4.6" width="3" height="6.4" rx=".6" fill="#fff"/><rect x="9" y="2.3" width="3" height="8.7" rx=".6" fill="#fff"/><rect x="13.5" y="0" width="3" height="11" rx=".6" fill="#fff"/></svg>
          <svg width="25" height="12" viewBox="0 0 25 12"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke="#fff" strokeOpacity=".4" fill="none"/><rect x="2" y="2" width="16" height="8" rx="1.6" fill="#fff"/><path d="M23 4v4c.7-.3 1.2-1 1.2-2S23.7 4.3 23 4z" fill="#fff" fillOpacity=".5"/></svg>
        </div>
      </div>
      {children}
    </div>
  );
}

// ── chat backdrop (used behind the sheet, and full for banner screen) ──
function ChatBackdrop({ children, dim = false }) {
  return (
    <div style={{ position: 'absolute', inset: 0, background: D.base, paddingTop: 54, display: 'flex', flexDirection: 'column' }}>
      <div style={{ flexShrink: 0, padding: '10px 16px 9px', borderBottom: `1px solid ${D.border}` }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, height: 30 }}>
          <IconChevron d="left" c={D.sec} s={18}/>
          <span style={{ flex: 1, fontFamily: D.ui, fontSize: 15, fontWeight: 600, color: D.text, marginLeft: 2 }}>Refactor auth module</span>
          <span style={{ fontFamily: D.mono, fontSize: 11, color: D.sec, border: `1px solid ${D.border}`, borderRadius: 999, padding: '3px 9px' }}>default</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 8 }}>
          <span className="cc-pulse" style={{ width: 6, height: 6, borderRadius: 999, background: D.success, boxShadow: `0 0 7px ${D.success}99` }}/>
          <span style={{ fontFamily: D.mono, fontSize: 10.5, color: D.sec }}>Lidapeng-MacBook&nbsp;·&nbsp;<span style={{ color: D.muted }}>~/dev/api</span></span>
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'hidden', padding: '18px 16px', opacity: dim ? 0.5 : 1 }}>
        <div style={{ fontFamily: D.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.4, color: D.muted, marginBottom: 6 }}>You</div>
        <div style={{ fontFamily: D.ui, fontSize: 15, lineHeight: '22px', color: D.text }}>run the full integration suite against staging and summarize any failures</div>
        <div style={{ marginTop: 18, fontFamily: D.ui, fontSize: 11, fontWeight: 600, letterSpacing: 0.4, color: D.muted, marginBottom: 6 }}>Claude</div>
        <div style={{ fontFamily: D.ui, fontSize: 14.5, lineHeight: '22px', color: D.text }}>
          On it. I’ll kick off <span style={{ fontFamily: D.mono, fontSize: 12.5, background: D.surface, border: `1px solid ${D.border}`, borderRadius: 5, padding: '1px 5px' }}>gradle :it:test</span> against the staging profile and collect any failing traces.
        </div>
      </div>
      {children}
    </div>
  );
}

// ── composer bar (static) ─────────────────────────────────────
function Composer({ above }) {
  return (
    <div style={{ flexShrink: 0, background: D.base, borderTop: `1px solid ${D.border}`, paddingBottom: 34, position: 'relative' }}>
      {above}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, padding: 12 }}>
        <div style={{ width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}><Plus c={D.sec}/></div>
        <div style={{ flex: 1, background: D.surface, border: `1px solid ${D.border}`, borderRadius: 12, display: 'flex', alignItems: 'center', padding: '0 14px', minHeight: 44 }}>
          <span style={{ fontFamily: D.ui, fontSize: 14.5, color: D.muted }}>Message Claude…</span>
        </div>
        <div style={{ width: 44, height: 44, borderRadius: 999, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: D.surface, border: `1px solid ${D.border}` }}><SendArrow c={D.muted}/></div>
      </div>
    </div>
  );
}

// ══ SCREEN A — schedule-send bottom sheet ═════════════════════
const CHIPS = [ {k:'30m', abs:'23:00'}, {k:'1h', abs:'23:30'}, {k:'3h', abs:'01:30'}, {k:'8h', abs:'06:30'} ];

function Chip({ label, active }) {
  return (
    <div style={{
      flex: 1, height: 44, borderRadius: 11, display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: active ? accentSoft : D.surface,
      border: `1px solid ${active ? D.accent : D.border}`,
      fontFamily: D.mono, fontSize: 14, fontWeight: 600, letterSpacing: 0.2,
      color: active ? D.accent : D.sec,
    }}>{label}</div>
  );
}

function Toggle({ on }) {
  return (
    <div style={{ width: 46, height: 28, borderRadius: 999, background: on ? D.accent : D.raised, border: `1px solid ${on ? D.accent : D.border}`, position: 'relative', flexShrink: 0, transition: 'background .2s' }}>
      <div style={{ position: 'absolute', top: 2.5, left: on ? 20 : 2.5, width: 22, height: 22, borderRadius: 999, background: '#fff', boxShadow: '0 1px 3px rgba(0,0,0,0.4)' }}/>
    </div>
  );
}

// compact inline date-time wheel (3 columns, center selection band)
function WheelPicker() {
  const col = (vals, sel, wpx) => (
    <div style={{ flex: wpx, position: 'relative', height: 118, overflow: 'hidden' }}>
      <div style={{ position: 'absolute', top: '50%', left: 0, right: 0, transform: 'translateY(-50%)', display: 'flex', flexDirection: 'column', gap: 0 }}>
        {vals.map((v, i) => (
          <div key={i} style={{
            height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontFamily: D.mono, fontSize: i === sel ? 17 : 15, fontWeight: i === sel ? 600 : 400,
            color: i === sel ? D.text : (Math.abs(i - sel) === 1 ? D.sec : D.muted),
            opacity: Math.abs(i - sel) > 2 ? 0.35 : 1,
          }}>{v}</div>
        ))}
      </div>
    </div>
  );
  return (
    <div style={{ position: 'relative', margin: '4px 0 2px', background: D.base, border: `1px solid ${D.border}`, borderRadius: 12, padding: '0 8px' }}>
      {/* center selection band */}
      <div style={{ position: 'absolute', top: '50%', left: 8, right: 8, height: 34, transform: 'translateY(-50%)', background: D.raised, borderRadius: 9, border: `1px solid ${D.border}` }}/>
      <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
        {col(['Tue 14','Wed 15','Thu 16','Fri 17','Sat 18'], 2, 1.5)}
        {col(['06','07','08','09','10'], 2, 1)}
        {col([':50',':55',':00',':05',':10'], 2, 1)}
      </div>
    </div>
  );
}

function ScheduleSheet({ variant }) {
  const custom = variant === 'custom';
  const repeat = variant === 'repeat';
  const activeChip = repeat ? 1 : (variant === 'default' ? 1 : -1);
  const resolved = custom ? 'Thu Jul 16, 08:00' : '23:30';
  return (
    <>
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.58)', zIndex: 40 }}/>
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 50,
        background: D.surface, borderTop: `1px solid ${D.border}`, borderTopLeftRadius: 22, borderTopRightRadius: 22,
        boxShadow: '0 -20px 50px -20px rgba(0,0,0,0.6)', paddingBottom: 30,
      }}>
        {/* grab handle */}
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 2px' }}>
          <span style={{ width: 40, height: 5, borderRadius: 999, background: D.border }}/>
        </div>
        {/* title */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '10px 20px 12px' }}>
          <IconClock c={D.text} s={19}/>
          <span style={{ fontFamily: D.ui, fontSize: 18, fontWeight: 700, color: D.text, letterSpacing: -0.2 }}>Schedule send</span>
        </div>
        {/* quoted message preview */}
        <div style={{ margin: '0 20px 16px', borderLeft: `2px solid ${D.border}`, paddingLeft: 12 }}>
          <div style={{ fontFamily: D.ui, fontSize: 13.5, lineHeight: '20px', color: D.sec, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
            run the full integration suite against staging and summarize any failures with links to the traces
          </div>
        </div>
        {/* quick-pick chips */}
        <div style={{ display: 'flex', gap: 9, padding: '0 20px 14px' }}>
          {CHIPS.map((c, i) => <Chip key={c.k} label={c.k} active={i === activeChip}/>)}
        </div>
        {/* custom time row */}
        <div style={{ margin: '0 20px', borderTop: `1px solid ${D.border}` }}>
          <div style={{ display: 'flex', alignItems: 'center', minHeight: 52 }}>
            <span style={{ flex: 1, fontFamily: D.ui, fontSize: 15.5, color: custom ? D.text : D.sec }}>Custom time…</span>
            {custom
              ? <span style={{ fontFamily: D.mono, fontSize: 13, color: D.accent, marginRight: 6 }}>Thu Jul 16</span>
              : null}
            <IconChevron d={custom ? 'down' : 'right'} c={custom ? D.accent : D.muted} s={18}/>
          </div>
          {custom && <WheelPicker/>}
        </div>
        {/* repeat daily row */}
        <div style={{ margin: '0 20px', borderTop: `1px solid ${D.border}`, borderBottom: `1px solid ${D.border}` }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 56 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontFamily: D.ui, fontSize: 15.5, color: D.text }}>Repeat daily</div>
              {repeat && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 3 }}>
                  <IconRepeat c={D.accent} s={13}/>
                  <span style={{ fontFamily: D.mono, fontSize: 12, color: D.accent }}>every day at 23:30</span>
                </div>
              )}
            </div>
            <Toggle on={repeat}/>
          </div>
        </div>
        {/* footer */}
        <div style={{ padding: '18px 20px 4px' }}>
          <div style={{ height: 52, borderRadius: 13, background: D.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
            {repeat && <IconRepeat c={D.base} s={15}/>}
            <span style={{ fontFamily: D.ui, fontSize: 16, fontWeight: 600, color: D.base }}>
              {repeat ? 'Schedule daily at 23:30' : `Schedule for ${resolved}`}
            </span>
          </div>
          <div style={{ textAlign: 'center', padding: '16px 0 2px' }}>
            <span style={{ fontFamily: D.ui, fontSize: 15, fontWeight: 500, color: D.sec }}>Cancel</span>
          </div>
        </div>
      </div>
    </>
  );
}

// ══ SCREEN B — scheduled tasks page ═══════════════════════════
function TaskCard({ prompt, meta, when, repeat, failed }) {
  return (
    <div style={{ position: 'relative', background: D.surface, border: `1px solid ${D.border}`, borderRadius: 14, padding: '14px 15px' }}>
      <div style={{ display: 'flex', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 5 }}>
            {repeat && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, background: accentSoft, borderRadius: 6, padding: '2px 6px' }}>
                <IconRepeat c={D.accent} s={11}/>
                <span style={{ fontFamily: D.mono, fontSize: 10, fontWeight: 600, color: D.accent, letterSpacing: 0.3 }}>DAILY</span>
              </span>
            )}
          </div>
          <div style={{ fontFamily: D.ui, fontSize: 14.5, lineHeight: '20px', color: D.text, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{prompt}</div>
          <div style={{ fontFamily: D.mono, fontSize: 11, color: D.muted, marginTop: 7, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{meta}</div>
          {failed && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginTop: 8 }}>
              <IconWarnTri c={D.danger} s={13}/>
              <span style={{ fontFamily: D.mono, fontSize: 11, color: D.danger }}>last run failed · usage limit</span>
            </div>
          )}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'space-between', flexShrink: 0 }}>
          <div style={{ padding: 2 }}><IconX c={D.muted} s={15}/></div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontFamily: D.mono, fontSize: 13, fontWeight: 600, color: D.accent, whiteSpace: 'nowrap' }}>{when}</div>
            {repeat && <div style={{ fontFamily: D.mono, fontSize: 10.5, color: D.muted, marginTop: 2 }}>next 23:30</div>}
          </div>
        </div>
      </div>
    </div>
  );
}

function TasksNav() {
  return (
    <div style={{ flexShrink: 0, paddingTop: 54, background: D.base, borderBottom: `1px solid ${D.border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', height: 48, padding: '0 8px 0 6px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, width: 90 }}>
          <IconChevron d="left" c={D.accent} s={20}/>
          <span style={{ fontFamily: D.ui, fontSize: 15, color: D.accent }}>Settings</span>
        </div>
        <span style={{ flex: 1, textAlign: 'center', fontFamily: D.ui, fontSize: 16, fontWeight: 600, color: D.text }}>Scheduled tasks</span>
        <span style={{ width: 90 }}/>
      </div>
    </div>
  );
}

function TasksPage({ variant }) {
  const stale = variant === 'stale';
  const empty = variant === 'empty';
  return (
    <div style={{ position: 'absolute', inset: 0, background: D.base, display: 'flex', flexDirection: 'column' }}>
      <TasksNav/>
      {stale && (
        <div style={{ margin: '14px 16px 0', background: warnSoft, border: `1px solid ${D.warning}55`, borderRadius: 12, padding: '12px 14px', display: 'flex', gap: 10, alignItems: 'flex-start' }}>
          <span style={{ marginTop: 1 }}><IconWarnTri c={D.warning} s={16}/></span>
          <div>
            <div style={{ fontFamily: D.ui, fontSize: 13.5, fontWeight: 600, color: D.text, lineHeight: '18px' }}>Update cc-pocket on your computer</div>
            <div style={{ fontFamily: D.ui, fontSize: 12.5, color: D.sec, lineHeight: '18px', marginTop: 2 }}>The daemon is too old to run schedules. Existing tasks are paused.</div>
          </div>
        </div>
      )}
      {empty ? (
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '0 40px', gap: 14 }}>
          <div style={{ width: 52, height: 52, borderRadius: 14, background: D.surface, border: `1px solid ${D.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <IconClock c={D.muted} s={24}/>
          </div>
          <div style={{ fontFamily: D.ui, fontSize: 15.5, fontWeight: 600, color: D.sec, textAlign: 'center' }}>Nothing scheduled</div>
          <div style={{ fontFamily: D.ui, fontSize: 13.5, lineHeight: '20px', color: D.muted, textAlign: 'center' }}>
            Long-press <span style={{ color: D.sec }}>send</span> in any chat to queue a prompt for later.
          </div>
        </div>
      ) : (
        <div className="cc-scroll" style={{ flex: 1, overflowY: 'auto', padding: '14px 16px 22px', display: 'flex', flexDirection: 'column', gap: 11, opacity: stale ? 0.55 : 1 }}>
          <TaskCard prompt="run the full integration suite against staging and summarize any failures with links to the traces" meta="~/dev/api · claude · resumes session" when="in 6h 20m"/>
          <TaskCard prompt="pull latest, run the migration check, and post a summary to the standup thread" meta="~/dev/api · claude · resumes session" when="daily" repeat/>
          <TaskCard prompt="continue the refactor of the auth module where we left off" meta="~/proj/web · claude · resumes session" when="in 8m" failed/>
          <TaskCard prompt="draft release notes from the merged PRs since the last tag" meta="~/dev/api · claude · new session" when="in 2d 4h"/>
        </div>
      )}
    </div>
  );
}

// ══ SCREEN C — usage-limit auto-continue banner ═══════════════
function LimitBanner({ variant }) {
  const confirmed = variant === 'confirmed';
  return (
    <div style={{ padding: '10px 12px 2px' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 11, minHeight: 52, padding: '0 8px 0 14px',
        background: confirmed ? D.surface : warnSoft,
        border: `1px solid ${confirmed ? D.border : D.warning + '55'}`, borderRadius: 12,
      }}>
        <span style={{ flexShrink: 0 }}>{confirmed ? <IconCheck c={D.success} s={17}/> : <IconHourglass c={D.warning} s={17}/>}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          {confirmed ? (
            <div style={{ fontFamily: D.ui, fontSize: 13.5, fontWeight: 600, color: D.text }}>Will continue at 21:21</div>
          ) : (
            <div style={{ fontFamily: D.ui, fontSize: 13.5, fontWeight: 600, color: D.text }}>Usage limit hit <span style={{ color: D.sec, fontWeight: 400 }}>— resets 21:20</span></div>
          )}
        </div>
        {confirmed ? (
          <div style={{ padding: '0 8px', height: 32, display: 'flex', alignItems: 'center' }}>
            <span style={{ fontFamily: D.ui, fontSize: 13.5, fontWeight: 600, color: D.accent }}>Undo</span>
          </div>
        ) : (
          <div style={{ flexShrink: 0, height: 34, borderRadius: 9, background: D.accent, display: 'flex', alignItems: 'center', padding: '0 13px' }}>
            <span style={{ fontFamily: D.ui, fontSize: 13.5, fontWeight: 600, color: D.base }}>Auto-continue</span>
          </div>
        )}
      </div>
    </div>
  );
}

function BannerScreen({ variant }) {
  return (
    <ChatBackdrop>
      <Composer above={<LimitBanner variant={variant}/>}/>
    </ChatBackdrop>
  );
}

// ── labelled phone ────────────────────────────────────────────
function StatePhone({ n, title, note, children }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 15 }}>
      <div style={{ maxWidth: 384 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 5 }}>
          <span style={{ fontFamily: D.mono, fontSize: 11, fontWeight: 600, color: D.accent, border: `1px solid ${D.border}`, borderRadius: 6, padding: '2px 7px' }}>{n}</span>
          <span style={{ fontFamily: D.ui, fontSize: 14, fontWeight: 600, color: D.text }}>{title}</span>
        </div>
        <div style={{ fontFamily: D.ui, fontSize: 12.5, lineHeight: '18px', color: D.muted, paddingLeft: 2 }}>{note}</div>
      </div>
      <Phone>{children}</Phone>
    </div>
  );
}

function SectionLabel({ text, sub }) {
  return (
    <div style={{ marginBottom: 26 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontFamily: D.ui, fontSize: 12.5, fontWeight: 600, letterSpacing: 1, textTransform: 'uppercase', color: D.sec }}>{text}</span>
        <span style={{ flex: 1, height: 1, background: D.border }}/>
      </div>
      {sub && <div style={{ fontFamily: D.ui, fontSize: 13.5, lineHeight: '20px', color: D.muted, marginTop: 10, maxWidth: 720 }}>{sub}</div>}
    </div>
  );
}

// ── board ─────────────────────────────────────────────────────
function Board() {
  return (
    <div style={{ width: 1400, margin: '0 auto', padding: '56px 48px 96px', fontFamily: D.ui }}>
      <div style={{ marginBottom: 48 }}>
        <div style={{ fontFamily: D.mono, fontSize: 11.5, letterSpacing: 1.4, textTransform: 'uppercase', color: D.accent, marginBottom: 14 }}>cc-pocket · component sheet</div>
        <div style={{ fontFamily: D.ui, fontSize: 30, fontWeight: 700, letterSpacing: -0.4, color: D.text, marginBottom: 12 }}>Scheduled prompts — schedule send</div>
        <div style={{ fontFamily: D.ui, fontSize: 15, lineHeight: '24px', color: D.sec, maxWidth: 780 }}>
          The daemon can now run a prompt at a future time — bedtime queuing, or auto-continuing once a usage-limit
          window resets. Three surfaces: a <span style={{ color: D.text }}>schedule-send sheet</span> (long-press the composer’s send button),
          a <span style={{ color: D.text }}>Scheduled tasks</span> management page in Settings, and a one-tap
          <span style={{ color: D.text }}> auto-continue banner</span> that appears above the composer when a turn hits the limit.
        </div>
      </div>

      <SectionLabel text="Screen A — schedule-send sheet" sub="Bottom sheet over Chat with a scrim behind. Quick-pick chips are single-select (terracotta when active); “Custom time…” expands into an inline wheel; “Repeat daily” resolves a local time. The primary button always carries the resolved absolute time."/>
      <div style={{ display: 'flex', gap: 44, flexWrap: 'wrap', marginBottom: 64 }}>
        <StatePhone n="A1" title="Default" note="1h chip pre-selected. Custom collapsed, repeat off. Button reads the resolved absolute time.">
          <ChatBackdrop dim/><ScheduleSheet variant="default"/>
        </StatePhone>
        <StatePhone n="A2" title="Custom time expanded" note="Chips deselected; the row opens an inline day / hour / minute wheel. Button switches to the picked date + time.">
          <ChatBackdrop dim/><ScheduleSheet variant="custom"/>
        </StatePhone>
        <StatePhone n="A3" title="Repeat on" note="Toggle turns terracotta with a resolved-time caption; button becomes a daily schedule with the repeat glyph.">
          <ChatBackdrop dim/><ScheduleSheet variant="repeat"/>
        </StatePhone>
      </div>

      <SectionLabel text="Screen B — scheduled tasks page" sub="Pushed from Settings. Each card: prompt excerpt, mono metadata (cwd · model · session behaviour), and a terracotta relative countdown with a trailing ✕. Repeating tasks carry a DAILY badge; a failed run shows a danger caption."/>
      <div style={{ display: 'flex', gap: 44, flexWrap: 'wrap', marginBottom: 64 }}>
        <StatePhone n="B1" title="Populated" note="Mixed list: one-shot countdowns, a repeating task with the DAILY badge, and a task whose last run failed on a usage limit.">
          <TasksPage variant="populated"/>
        </StatePhone>
        <StatePhone n="B2" title="Empty" note="Calm, illustration-free hint pointing back to the entry point — long-press send in any chat.">
          <TasksPage variant="empty"/>
        </StatePhone>
        <StatePhone n="B3" title="Stale daemon" note="Warning banner when the computer’s cc-pocket is too old to run schedules; existing tasks dim and pause.">
          <TasksPage variant="stale"/>
        </StatePhone>
      </div>

      <SectionLabel text="Screen C — usage-limit auto-continue banner" sub="A slim, warning-tinted card docked above the composer — reads in one second and never shifts the composer’s position. Tapping Auto-continue flips it in place to a confirmed state with an Undo action."/>
      <div style={{ display: 'flex', gap: 44, flexWrap: 'wrap' }}>
        <StatePhone n="C1" title="Offer" note="Hourglass + limit / reset time on the left, compact terracotta Auto-continue button on the right.">
          <BannerScreen variant="offer"/>
        </StatePhone>
        <StatePhone n="C2" title="Scheduled / confirmed" note="Flips in place: check + “Will continue at 21:21”, with a plain-text Undo. Same height — no layout shift.">
          <BannerScreen variant="confirmed"/>
        </StatePhone>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Board/>);
