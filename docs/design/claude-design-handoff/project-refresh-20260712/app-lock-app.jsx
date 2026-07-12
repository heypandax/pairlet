// cc-pocket — App Lock gate · biometric front door shown at launch & on resume

// ── palette (dark + light) ────────────────────────────────────
const DARK = {
  base:'#0E0F11', surface:'#16181B', raised:'#1E2125', border:'#2A2E33',
  text:'#ECEDEE', sec:'#9BA1A6', muted:'#6B7177',
  accent:'#D97757', danger:'#E5604D',
};
const LIGHT = {
  base:'#FAF9F7', surface:'#FFFFFF', raised:'#FFFFFF', border:'#E4E1DB',
  text:'#1C1D1F', sec:'#5B6066', muted:'#8A8F96',
  accent:'#C15F3C', danger:'#C0492F',
};
const MONO = "'JetBrains Mono', ui-monospace, monospace";
const UI   = "'Inter', -apple-system, system-ui, sans-serif";

// ── app mark — the cc-pocket chevron+bar glyph, kept monochrome ──
function AppMark({ c, s = 26 }) {
  return (
    <svg width={s} height={s * (37/56)} viewBox="0 0 56 37" fill="none">
      <path d="M7 4 L23 18.5 L7 33" stroke={c} strokeWidth="7.5" strokeLinecap="round" strokeLinejoin="round"/>
      <rect x="33" y="3" width="15" height="31" rx="4" fill={c}/>
    </svg>
  );
}

// ── Face ID glyph — 1.5pt line icon, single security signifier ──
function FaceID({ c, w = 2.4, s = 92, disabled = false, className }) {
  return (
    <svg width={s} height={s} viewBox="0 0 64 64" fill="none" className={className}
         stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round">
      {/* corner brackets */}
      <path d="M6 20 V11 A5 5 0 0 1 11 6 H20"/>
      <path d="M44 6 H53 A5 5 0 0 1 58 11 V20"/>
      <path d="M6 44 V53 A5 5 0 0 1 11 58 H20"/>
      <path d="M44 58 H53 A5 5 0 0 1 58 53 V44"/>
      {/* eyes */}
      <path d="M24 25 V30"/>
      <path d="M40 25 V30"/>
      {/* nose */}
      <path d="M32 26 V36 H28.5"/>
      {/* mouth */}
      <path d="M25 41 Q32 46.5 39 41"/>
      {/* disabled slash */}
      {disabled && <path d="M12 12 L52 52" strokeWidth={w}/>}
    </svg>
  );
}

// ── state definitions ─────────────────────────────────────────
// each returns the copy + treatment for one frame of the gate
function config(state, C) {
  const base = {
    dim: false,
    facePulse: false,
    faceDisabled: false,
    faceColor: C.accent,
    status: 'Locked',
    statusColor: C.text,
    sub: 'Unlock to open your sessions',
    primary: { label: 'Unlock with Face ID', kind: 'filled' },
    secondary: { label: 'Enter passcode', kind: 'link' },
    disabled: false,
  };
  switch (state) {
    case 'idle':
      return base;
    case 'authenticating':
      return { ...base,
        dim: true, facePulse: true, disabled: true,
        statusColor: C.muted,
        sub: 'Unlock to open your sessions',
      };
    case 'failed':
      return { ...base,
        status: 'Not recognized · Try again',
        statusColor: C.danger,
        sub: 'Unlock to open your sessions',
        primary: { label: 'Try Face ID again', kind: 'filled' },
        secondary: { label: 'Enter passcode', kind: 'link' },
      };
    case 'fallback':
      return { ...base,
        faceColor: C.muted,               // accent leaves the glyph …
        status: 'Face ID failed · Enter your passcode',
        statusColor: C.danger,
        sub: 'Too many attempts',
        primary: { label: 'Enter passcode', kind: 'filled' },  // … and moves here
        secondary: { label: 'Try Face ID again', kind: 'link' },
      };
    case 'lockedout':
      return { ...base,
        faceColor: C.muted, faceDisabled: true,
        status: 'Face ID is locked',
        statusColor: C.text,
        sub: 'Enter your passcode to re-enable it',
        primary: { label: 'Enter passcode', kind: 'filled' },
        secondary: null,
      };
    default:
      return base;
  }
}

// ── button ────────────────────────────────────────────────────
function Btn({ spec, C, disabled }) {
  if (!spec) return null;
  if (spec.kind === 'filled') {
    return (
      <button style={{
        all: 'unset', boxSizing: 'border-box', width: '100%', height: 52,
        cursor: disabled ? 'default' : 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: C.accent, color: C.base, borderRadius: 13,
        fontFamily: UI, fontSize: 16, fontWeight: 700,
        opacity: disabled ? 0.4 : 1,
      }}>{spec.label}</button>
    );
  }
  // quiet text link
  return (
    <button style={{
      all: 'unset', boxSizing: 'border-box', width: '100%', height: 44,
      cursor: disabled ? 'default' : 'pointer',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: C.sec, fontFamily: UI, fontSize: 14, fontWeight: 500,
      opacity: disabled ? 0.4 : 1,
    }}>{spec.label}</button>
  );
}

// ── the gate ──────────────────────────────────────────────────
function AppLockScreen({ state = 'idle', theme = 'dark' }) {
  const C = theme === 'dark' ? DARK : LIGHT;
  const cfg = config(state, C);
  const wordmarkColor = cfg.dim ? C.muted : C.text;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: C.base }}>
      {/* centered lockup — the calm brand moment */}
      <div style={{
        flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', padding: '96px 32px 0',
        transition: 'opacity .2s',
      }}>
        {/* app mark + wordmark */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16, opacity: cfg.dim ? 0.6 : 1 }}>
          <AppMark c={cfg.dim ? C.muted : C.sec} s={30}/>
          <div style={{
            fontFamily: MONO, fontSize: 24, fontWeight: 500, letterSpacing: -0.5,
            color: wordmarkColor,
          }}>cc-pocket</div>
        </div>

        {/* Face ID glyph — single security signifier */}
        <div style={{ marginTop: 64, marginBottom: 40, display: 'flex' }}>
          <FaceID
            c={cfg.faceColor}
            disabled={cfg.faceDisabled}
            className={cfg.facePulse ? 'cc-face-pulse' : undefined}
          />
        </div>

        {/* status + subline */}
        <div style={{ textAlign: 'center' }}>
          <div style={{
            fontFamily: UI, fontSize: 21, fontWeight: 600, letterSpacing: -0.2,
            color: cfg.statusColor, lineHeight: '27px',
          }}>{cfg.status}</div>
          <div style={{
            fontFamily: UI, fontSize: 14.5, lineHeight: '21px', marginTop: 8,
            color: cfg.dim ? C.muted : C.sec, maxWidth: 260,
          }}>{cfg.sub}</div>
        </div>
      </div>

      {/* lower third — actions */}
      <div style={{ flexShrink: 0, padding: '0 24px 40px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
        <Btn spec={cfg.primary} C={C} disabled={cfg.disabled}/>
        <div style={{ height: cfg.secondary ? 'auto' : 44 }}>
          <Btn spec={cfg.secondary} C={C} disabled={cfg.disabled}/>
        </div>
      </div>
    </div>
  );
}

// ── board — frames laid out like the other cc-pocket screens ───
function Phone({ children, dark = true, scale = 0.82 }) {
  return (
    <div style={{ width: 402 * scale, height: 874 * scale, flexShrink: 0 }}>
      <div style={{ width: 402, height: 874, transform: `scale(${scale})`, transformOrigin: 'top left' }}>
        <IOSDevice dark={dark}>{children}</IOSDevice>
      </div>
    </div>
  );
}

function Cell({ n, label, note, dark = true, children }) {
  const C = DARK;
  return (
    <div style={{ width: 402 * 0.82 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 9, marginBottom: 11 }}>
        <span style={{ fontFamily: MONO, fontSize: 12, color: DARK.accent }}>{n}</span>
        <span style={{ fontFamily: UI, fontSize: 14, fontWeight: 600, color: DARK.text }}>{label}</span>
      </div>
      <Phone dark={dark}>{children}</Phone>
      {note && <div style={{ fontFamily: UI, fontSize: 12.5, lineHeight: '18px', color: DARK.sec, marginTop: 12, maxWidth: 402 * 0.82 }}>{note}</div>}
    </div>
  );
}

function Page() {
  return (
    <div style={{ maxWidth: 1320, margin: '0 auto', padding: '56px 44px 110px' }}>
      <p style={{ fontFamily: MONO, fontSize: 12, color: DARK.accent, letterSpacing: 1, textTransform: 'uppercase', margin: '0 0 16px' }}>cc-pocket · app lock</p>
      <h1 style={{ fontFamily: UI, fontSize: 30, fontWeight: 700, letterSpacing: -0.5, color: DARK.text, margin: '0 0 10px' }}>The front door</h1>
      <p style={{ fontFamily: UI, fontSize: 15, lineHeight: '23px', color: DARK.sec, maxWidth: 720, margin: 0 }}>
        A biometric gate shown at launch and on every resume from background — before any session is visible. The OS draws its own Face&nbsp;ID sheet on top; this is the branded backdrop and the fallbacks for when biometrics don&rsquo;t pass. Restraint over chrome: mostly monochrome, with the terracotta accent spent on exactly one focal point per frame.
      </p>

      {/* dark states */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, marginTop: 44 }}>
        <Cell n="01" label="Idle · ready" note="Resting gate. The OS may auto-present its Face ID sheet on top; the unlock button covers the case where it was dismissed.">
          <AppLockScreen state="idle"/>
        </Cell>
        <Cell n="02" label="Authenticating" note="The gate as backdrop while the OS sheet is up: lockup dimmed toward muted, glyph in a slow pending pulse, actions disabled — no spinner, since the OS sheet is the activity.">
          <AppLockScreen state="authenticating"/>
        </Cell>
        <Cell n="03" label="Failed once" note="A gentle retry, not an alarm. Status turns soft-danger; the glyph is unchanged and the button invites another attempt.">
          <AppLockScreen state="failed"/>
        </Cell>
        <Cell n="04" label="Passcode fallback" note="After repeated failures the accent leaves the glyph and moves to a prominent Enter passcode button — now the primary way forward. Face ID demotes to a quiet link.">
          <AppLockScreen state="fallback"/>
        </Cell>
        <Cell n="05" label="Biometry locked out" note="Face ID disabled by the OS: the glyph is muted with a slash, copy names the only exit, and passcode is the sole action.">
          <AppLockScreen state="lockedout"/>
        </Cell>
      </div>

      {/* light theme */}
      <div style={{ height: 1, background: DARK.border, margin: '56px 0 40px' }}/>
      <p style={{ fontFamily: MONO, fontSize: 12, color: DARK.muted, letterSpacing: 1, textTransform: 'uppercase', margin: '0 0 8px' }}>light theme</p>
      <p style={{ fontFamily: UI, fontSize: 14, lineHeight: '21px', color: DARK.sec, maxWidth: 620, margin: '0 0 32px' }}>
        The same idle gate on the warm light base — the accent softens to <span style={{ fontFamily: MONO, fontSize: 12.5, color: DARK.text }}>#C15F3C</span> to hold contrast.
      </p>
      <div style={{ display: 'flex', gap: 40 }}>
        <Cell n="01" label="Idle · ready · light" dark={false} note="Warm off-white base, hairline elevation preserved.">
          <AppLockScreen state="idle" theme="light"/>
        </Cell>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<Page/>);
