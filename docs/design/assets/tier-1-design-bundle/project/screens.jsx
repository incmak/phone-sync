// Twinotify phone screens — ON-*, PAIR-*, HOM-*, SET-*, FIL-*
// Each screen receives (ctx) with app state + a navigate(screenId, role?) fn.
// Role tweak: 'A' = Initiator (shows QR), 'B' = Joiner (scans QR).
// Connection tweak: 'lan' | 'relay' | 'offline' | 'pairing'.

const PHONE_W = 360;
const PHONE_H = 780;
const PEER_FP = '7F2A 9C41 B8E0 3D56 A192 4C8B E770 15F9 9A2E 6B04 C857 2D13 F1A8 5E9C 3B47 D082';
const MY_FP   = '2E91 B054 C7A3 8D12 F69E 4B78 056C A3D9 71F2 9C85 40B1 E38A 5D67 C924 81FA 0B36';

// ═══════════════════════════════════════════════════════════════
// SHELL — status-bar chrome around each screen
// ═══════════════════════════════════════════════════════════════
function Screen({ ctx, children, bg, padding = 20, scroll = true, header }) {
  const t = ctx.theme;
  return (
    <div className="tw-screen" style={{
      width: '100%', height: '100%', display: 'flex', flexDirection: 'column',
      background: bg || t.bg, color: t.ink, fontFamily: TW_FONTS.ui,
      fontSize: 15, lineHeight: 1.45,
    }}>
      <TwStatusBar dark={t.dark} bg="transparent" />
      {header}
      <div style={{
        flex: 1,
        overflow: scroll ? 'auto' : 'hidden',
        padding: typeof padding === 'number' ? padding : `${padding}`,
        display: 'flex', flexDirection: 'column',
      }}>
        {children}
      </div>
      <TwNavBar />
    </div>
  );
}

function ScreenHeader({ ctx, title, onBack, trailing }) {
  const t = ctx.theme;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      padding: '8px 12px', minHeight: 48, flexShrink: 0,
      color: t.ink,
    }}>
      {onBack ? (
        <button onClick={onBack} style={{
          width: 40, height: 40, borderRadius: 20, border: 'none', background: 'transparent',
          color: t.ink, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}><TwIcon.chevronLeft /></button>
      ) : <div style={{ width: 12 }} />}
      <div style={{ flex: 1, fontSize: 17, fontWeight: 600, color: t.ink }}>{title}</div>
      {trailing}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-01 · WELCOME
// ═══════════════════════════════════════════════════════════════
function ScreenWelcome({ ctx }) {
  const t = ctx.theme;
  return (
    <Screen ctx={ctx}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 28, padding: '0 8px' }}>
        {/* Hero mark — animated overlapping rings */}
        <div style={{ position: 'relative', width: 200, height: 140, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="200" height="140" viewBox="0 0 200 140" fill="none">
            <circle cx="75" cy="70" r="52" stroke={t.ink} strokeWidth="2.4" />
            <circle cx="125" cy="70" r="52" stroke={t.accent} strokeWidth="2.4" />
            <circle cx="75" cy="70" r="8" fill={t.ink}/>
            <circle cx="125" cy="70" r="8" fill={t.accent}/>
          </svg>
          {/* Subtle pulse rings */}
          <div style={{ position: 'absolute', width: 120, height: 120, borderRadius: '50%', left: 15, border: `1px solid ${t.border}`, opacity: 0.5 }}/>
          <div style={{ position: 'absolute', width: 120, height: 120, borderRadius: '50%', right: 15, border: `1px solid ${t.border}`, opacity: 0.5 }}/>
        </div>

        <div style={{ textAlign: 'center', maxWidth: 300 }}>
          <TwWordmark size={24} />
          <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: -0.6, lineHeight: 1.15, marginTop: 28, color: t.ink, textWrap: 'pretty' }}>
            Your notifications,<br/>twinned between phones.
          </div>
          <div style={{ fontSize: 15, color: t.ink3, marginTop: 14, lineHeight: 1.5 }}>
            End-to-end encrypted. Peer-to-peer. No cloud storage, ever.
          </div>
        </div>
      </div>

      <div style={{ padding: '0 8px 8px' }}>
        <TwButton variant="primary" size="lg" fullWidth onClick={() => ctx.go('on-how')}>Continue</TwButton>
        <div style={{ textAlign: 'center', fontSize: 13, color: t.ink4, marginTop: 14 }}>
          Already have Twinotify on another phone? <span style={{ color: t.accentText, fontWeight: 500 }}>I have a code</span>
        </div>
      </div>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-02 · HOW IT WORKS
// ═══════════════════════════════════════════════════════════════
function ScreenHow({ ctx }) {
  const t = ctx.theme;
  const [step, setStep] = React.useState(0);
  const slides = [
    {
      t: 'Pair once with a QR',
      b: 'Show the QR on one phone, scan it from the other. Keys are generated on-device and never leave.',
      art: (
        <svg width="200" height="120" viewBox="0 0 200 120" fill="none">
          <rect x="20" y="20" width="80" height="80" rx="12" stroke={t.ink} strokeWidth="2" fill={t.fill}/>
          <rect x="34" y="34" width="52" height="52" rx="4" fill={t.ink}/>
          <rect x="40" y="40" width="10" height="10" rx="2" fill={t.bg}/>
          <rect x="70" y="40" width="10" height="10" rx="2" fill={t.bg}/>
          <rect x="40" y="70" width="10" height="10" rx="2" fill={t.bg}/>
          <rect x="56" y="56" width="8" height="8" fill={t.accent}/>
          <path d="M108 60 L128 60" stroke={t.accent} strokeWidth="2" strokeDasharray="3 3"/>
          <rect x="130" y="35" width="50" height="70" rx="8" stroke={t.accent} strokeWidth="2" fill={t.card}/>
        </svg>
      ),
    },
    {
      t: 'Mirror every notification',
      b: 'Messages, calls, OTPs — they appear on both phones the instant they arrive, with full reply.',
      art: (
        <svg width="200" height="120" viewBox="0 0 200 120" fill="none">
          <rect x="20" y="30" width="60" height="60" rx="8" stroke={t.ink} strokeWidth="2" fill={t.fill}/>
          <rect x="28" y="38" width="44" height="8" rx="2" fill={t.ink}/>
          <rect x="28" y="50" width="32" height="4" rx="2" fill={t.ink3}/>
          <path d="M88 60 L112 60" stroke={t.accent} strokeWidth="2.4" strokeLinecap="round"/>
          <path d="M105 54 L112 60 L105 66" stroke={t.accent} strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
          <rect x="120" y="30" width="60" height="60" rx="8" stroke={t.accent} strokeWidth="2" fill={t.card}/>
          <rect x="128" y="38" width="44" height="8" rx="2" fill={t.ink}/>
          <rect x="128" y="50" width="32" height="4" rx="2" fill={t.ink3}/>
        </svg>
      ),
    },
    {
      t: 'Private by default',
      b: 'Banking apps and OTPs are pre-blocked. Filter per-app, per-channel. You decide what crosses.',
      art: (
        <svg width="200" height="120" viewBox="0 0 200 120" fill="none">
          <path d="M100 20 L160 40 V70 C160 90 135 100 100 108 C65 100 40 90 40 70 V40 Z" stroke={t.ink} strokeWidth="2" fill={t.fill}/>
          <path d="M80 65 L95 80 L125 50" stroke={t.accent} strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      ),
    },
  ];
  const s = slides[step];
  return (
    <Screen ctx={ctx}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '4px 0 12px' }}>
        <TwWordmark size={16} />
        <button onClick={() => ctx.go('on-role')} style={{ border: 'none', background: 'transparent', color: t.ink3, fontSize: 14, fontWeight: 500, cursor: 'pointer' }}>Skip</button>
      </div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 28, textAlign: 'center' }}>
        <div>{s.art}</div>
        <div style={{ maxWidth: 300 }}>
          <div style={{ fontSize: 24, fontWeight: 600, letterSpacing: -0.4, color: t.ink, textWrap: 'pretty' }}>{s.t}</div>
          <div style={{ fontSize: 15, color: t.ink3, marginTop: 12, lineHeight: 1.5 }}>{s.b}</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 6, justifyContent: 'center', padding: '12px 0 20px' }}>
        {slides.map((_, i) => (
          <div key={i} style={{
            width: i === step ? 20 : 6, height: 6, borderRadius: 3,
            background: i === step ? t.accent : t.borderHi, transition: 'width .2s',
          }} />
        ))}
      </div>
      <TwButton variant="primary" size="lg" fullWidth onClick={() => step < slides.length - 1 ? setStep(step + 1) : ctx.go('on-role')}>
        {step < slides.length - 1 ? 'Next' : 'Get started'}
      </TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-03 · CHOOSE ROLE
// ═══════════════════════════════════════════════════════════════
function ScreenRole({ ctx }) {
  const t = ctx.theme;
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="" onBack={() => ctx.go('on-how')} />}>
      <div style={{ padding: '0 4px', flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: -0.5, color: t.ink, marginBottom: 8, textWrap: 'pretty' }}>
          Which phone is this?
        </div>
        <div style={{ fontSize: 15, color: t.ink3, marginBottom: 28 }}>
          Pick the one that matches what you're holding right now.
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <button onClick={() => { ctx.setRole('A'); ctx.go('on-relay'); }} style={{
            textAlign: 'left', background: ctx.role === 'A' ? t.accentLo : t.card,
            border: `1.5px solid ${ctx.role === 'A' ? t.accent : t.border}`,
            borderRadius: 16, padding: 18, cursor: 'pointer',
            display: 'flex', gap: 14, alignItems: 'center',
          }}>
            <div style={{ width: 48, height: 48, borderRadius: 12, background: t.fill, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              <TwIcon.qr style={{ color: t.ink }} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 16, fontWeight: 600, color: t.ink }}>This is my first phone</div>
              <div style={{ fontSize: 13, color: t.ink3, marginTop: 3 }}>I'll show a QR code for my other phone to scan.</div>
            </div>
            <div style={{ fontFamily: TW_FONTS.mono, fontSize: 11, fontWeight: 700, color: t.ink4, letterSpacing: 1 }}>A</div>
          </button>

          <button onClick={() => { ctx.setRole('B'); ctx.go('on-relay'); }} style={{
            textAlign: 'left', background: ctx.role === 'B' ? t.accentLo : t.card,
            border: `1.5px solid ${ctx.role === 'B' ? t.accent : t.border}`,
            borderRadius: 16, padding: 18, cursor: 'pointer',
            display: 'flex', gap: 14, alignItems: 'center',
          }}>
            <div style={{ width: 48, height: 48, borderRadius: 12, background: t.fill, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              <TwIcon.camera style={{ color: t.ink }}/>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 16, fontWeight: 600, color: t.ink }}>I have a code already</div>
              <div style={{ fontSize: 13, color: t.ink3, marginTop: 3 }}>I'll scan the QR from my other phone.</div>
            </div>
            <div style={{ fontFamily: TW_FONTS.mono, fontSize: 11, fontWeight: 700, color: t.ink4, letterSpacing: 1 }}>B</div>
          </button>
        </div>
      </div>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-04 · RELAY SETUP
// ═══════════════════════════════════════════════════════════════
function ScreenRelay({ ctx }) {
  const t = ctx.theme;
  const [url, setUrl] = React.useState('wss://relay.twinotify.app');
  const [tested, setTested] = React.useState(null);
  const [testing, setTesting] = React.useState(false);
  const test = () => {
    setTesting(true); setTested(null);
    setTimeout(() => { setTesting(false); setTested('ok'); }, 900);
  };
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Relay server" onBack={() => ctx.go('on-role')} />}>
      <div style={{ fontSize: 15, color: t.ink3, marginBottom: 20, textWrap: 'pretty' }}>
        Twinotify uses a relay to tunnel encrypted messages when your phones aren't on the same network. We recommend the default.
      </div>
      <div style={{ marginBottom: 8, fontSize: 13, color: t.ink3, fontWeight: 500 }}>Relay URL</div>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        background: t.fill, border: `1.5px solid ${tested === 'ok' ? t.accent : t.border}`, borderRadius: 12,
        padding: '4px 8px 4px 14px', transition: 'border-color .18s',
      }}>
        <input value={url} onChange={(e) => { setUrl(e.target.value); setTested(null); }}
          style={{
            flex: 1, border: 'none', outline: 'none', background: 'transparent',
            fontFamily: TW_FONTS.mono, fontSize: 13, color: t.ink, padding: '12px 0',
          }} />
        <TwButton variant="secondary" size="sm" onClick={test} disabled={testing}
          style={{ height: 34, background: t.card }}>
          {testing ? <TwSpinner size={14} /> : (tested === 'ok' ? <><TwIcon.check style={{ color: t.sem.ok }} /> OK</> : 'Test')}
        </TwButton>
      </div>

      {tested === 'ok' && (
        <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: t.sem.ok }}>
          <TwStatusDot state="lan" size={7} /> Reached in 42 ms
        </div>
      )}

      <div style={{ marginTop: 28, padding: 14, background: t.fill, borderRadius: 12, border: `1px dashed ${t.border}` }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: t.ink3, marginBottom: 6, letterSpacing: 0.4, textTransform: 'uppercase' }}>Advanced</div>
        <div style={{ fontSize: 13, color: t.ink2 }}>Running your own relay? Paste its URL above. Self-hosted relays can be audited — source at twinotify.app/relay.</div>
      </div>

      <div style={{ flex: 1 }} />
      <TwButton variant="primary" size="lg" fullWidth disabled={!tested} onClick={() => ctx.go('on-perms')}>Continue</TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-05 · PERMISSIONS
// ═══════════════════════════════════════════════════════════════
function ScreenPerms({ ctx }) {
  const t = ctx.theme;
  const [granted, setGranted] = React.useState({ post: false, access: false, lan: false });
  const perms = [
    { k: 'post',   title: 'Post notifications', body: "So mirrored notifications can appear on this phone's lock screen.", icon: <TwIcon.bell /> },
    { k: 'access', title: 'Notification access', body: 'Required to capture notifications on your other phone for mirroring. Capture is never sent unencrypted.', icon: <TwIcon.shield /> },
    { k: 'lan',    title: 'Nearby devices / LAN', body: 'Lets your two phones find each other over Wi-Fi for faster, direct mirroring.', icon: <TwIcon.link /> },
  ];
  const allGranted = perms.every((p) => granted[p.k]);
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Permissions" onBack={() => ctx.go('on-relay')} />}>
      <div style={{ fontSize: 15, color: t.ink3, marginBottom: 20, textWrap: 'pretty' }}>
        Twinotify needs these three to mirror notifications. We request them one-at-a-time so you can read why.
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {perms.map((p) => (
          <div key={p.k} style={{
            background: t.card, border: `1px solid ${t.border}`, borderRadius: 14, padding: 16,
            display: 'flex', gap: 14, alignItems: 'flex-start',
          }}>
            <div style={{ width: 40, height: 40, borderRadius: 10, background: t.fill, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.ink, flexShrink: 0 }}>{p.icon}</div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 15, fontWeight: 600, color: t.ink }}>{p.title}</div>
              <div style={{ fontSize: 13, color: t.ink3, marginTop: 3, lineHeight: 1.45 }}>{p.body}</div>
            </div>
            {granted[p.k] ? (
              <div style={{ color: t.sem.ok, padding: 8 }}><TwIcon.check /></div>
            ) : (
              <TwButton variant="secondary" size="sm" onClick={() => setGranted({ ...granted, [p.k]: true })}>Grant</TwButton>
            )}
          </div>
        ))}
      </div>
      <div style={{ flex: 1 }} />
      <TwButton variant="primary" size="lg" fullWidth disabled={!allGranted} onClick={() => ctx.go('on-oem')}>
        Continue
      </TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-06 · OEM RELIABILITY
// ═══════════════════════════════════════════════════════════════
function ScreenOEM({ ctx }) {
  const t = ctx.theme;
  const [fixed, setFixed] = React.useState([false, false]);
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Keep Twinotify alive" onBack={() => ctx.go('on-perms')} />}>
      <div style={{
        display: 'flex', gap: 8, alignItems: 'center', marginBottom: 18,
        background: t.fill, borderRadius: 10, padding: '10px 14px', border: `1px solid ${t.border}`,
      }}>
        <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor" style={{ color: t.ink }}><circle cx="10" cy="10" r="10" opacity=".06"/><path d="M10 5v4l2.5 1.5" stroke="currentColor" fill="none" strokeWidth="1.6" strokeLinecap="round" transform="rotate(12 10 10)"/></svg>
        <div style={{ fontSize: 13, color: t.ink2 }}>We detected <b style={{ color: t.ink }}>Samsung Galaxy S24</b>. Two fixes recommended.</div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {[
          { t: 'Disable battery optimization', b: 'Samsung kills background apps aggressively. This lets Twinotify stay connected.' },
          { t: 'Allow auto-start', b: 'Without this, Twinotify cannot restart after reboot.' },
        ].map((p, i) => (
          <div key={i} style={{ background: t.card, border: `1px solid ${t.border}`, borderRadius: 14, padding: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 14, fontWeight: 600, color: t.ink }}>{p.t}</div>
              <div style={{ fontSize: 13, color: t.ink3, marginTop: 3, lineHeight: 1.4 }}>{p.b}</div>
            </div>
            {fixed[i] ? (
              <div style={{ color: t.sem.ok }}><TwIcon.check /></div>
            ) : (
              <TwButton size="sm" variant="secondary" onClick={() => setFixed(fixed.map((x, j) => j === i ? true : x))}>Open</TwButton>
            )}
          </div>
        ))}
      </div>
      <div style={{ flex: 1 }} />
      <TwButton variant="ghost" size="md" fullWidth onClick={() => ctx.go('on-ready')} style={{ marginBottom: 6, color: t.ink3 }}>Skip for now</TwButton>
      <TwButton variant="primary" size="lg" fullWidth onClick={() => ctx.go('on-ready')}>Done</TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// ON-07 · READY
// ═══════════════════════════════════════════════════════════════
function ScreenReady({ ctx }) {
  const t = ctx.theme;
  return (
    <Screen ctx={ctx}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 24, padding: '0 8px', textAlign: 'center' }}>
        <div style={{ width: 96, height: 96, borderRadius: 28, background: t.accentLo, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="56" height="56" viewBox="0 0 56 56" fill="none"><path d="M14 28l10 10 18-20" stroke={t.accent} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/></svg>
        </div>
        <div>
          <div style={{ fontSize: 28, fontWeight: 600, letterSpacing: -0.5, color: t.ink, textWrap: 'pretty' }}>You're ready.</div>
          <div style={{ fontSize: 15, color: t.ink3, marginTop: 10, maxWidth: 280, lineHeight: 1.5 }}>
            Pair your other phone now to start mirroring notifications.
          </div>
        </div>
      </div>
      <TwButton variant="primary" size="lg" fullWidth onClick={() => ctx.go(ctx.role === 'B' ? 'pair-scan' : 'pair-qr')}>
        {ctx.role === 'B' ? 'Scan code' : 'Show my code'}
      </TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// PAIR-03 · SHOW QR (Device A)
// ═══════════════════════════════════════════════════════════════
function ScreenPairQR({ ctx }) {
  const t = ctx.theme;
  const [time, setTime] = React.useState(300);
  React.useEffect(() => { if (time <= 0) return; const id = setTimeout(() => setTime(time - 1), 1000); return () => clearTimeout(id); }, [time]);
  const mm = String(Math.floor(time / 60)).padStart(1, '0');
  const ss = String(time % 60).padStart(2, '0');
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Pair this phone" onBack={() => ctx.go('home')} trailing={
      <div style={{ padding: '6px 10px', fontFamily: TW_FONTS.mono, fontSize: 13, color: t.ink3, background: t.fill, borderRadius: 999, fontVariantNumeric: 'tabular-nums' }}>{mm}:{ss}</div>
    } />}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18, padding: '8px 0' }}>
        <div style={{ textAlign: 'center', maxWidth: 300 }}>
          <div style={{ fontSize: 20, fontWeight: 600, color: t.ink, letterSpacing: -0.2, textWrap: 'pretty' }}>
            Scan this from your other phone
          </div>
          <div style={{ fontSize: 14, color: t.ink3, marginTop: 6 }}>Open Twinotify on Phone B and tap <b style={{ color: t.ink }}>Scan</b>.</div>
        </div>
        <TwQR size={240} seed="twinotify-pair-token-a1b2c3d4" />
        <div style={{ width: '100%', padding: '0 8px' }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: t.ink4, letterSpacing: 0.5, textTransform: 'uppercase', marginBottom: 6 }}>
            Or paste this on Phone B
          </div>
          <TwCode block style={{ fontSize: 11, padding: '10px 12px' }}>
            twn:v1:wss://relay.twinotify.app/a1b2c3d4/k:QmE8x…p71N
          </TwCode>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', fontSize: 13, color: t.ink3, marginTop: 4 }}>
          <TwStatusDot state="pairing" size={8} />
          Waiting for Phone B…
        </div>
      </div>
      <div style={{ flex: 1 }} />
      <TwButton variant="secondary" fullWidth onClick={() => ctx.go('home')}>Cancel</TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// PAIR-05 · SCAN QR (Device B)
// ═══════════════════════════════════════════════════════════════
function ScreenPairScan({ ctx }) {
  const t = ctx.theme;
  const [found, setFound] = React.useState(false);
  React.useEffect(() => { const id = setTimeout(() => setFound(true), 1800); return () => clearTimeout(id); }, []);
  React.useEffect(() => { if (found) { const id = setTimeout(() => ctx.go('pair-fp'), 900); return () => clearTimeout(id); } }, [found]);
  return (
    <Screen ctx={ctx} scroll={false} padding={0} bg="#0a0a0a">
      <div style={{ position: 'relative', flex: 1, background: '#0a0a0a', color: '#fff', overflow: 'hidden' }}>
        {/* Fake camera feed — noisy radial gradient */}
        <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 50% 60%, #2a2623 0%, #0f0d0b 70%)' }}/>
        <div style={{ position: 'absolute', inset: 0, opacity: 0.08, background: 'repeating-linear-gradient(0deg, #fff 0 1px, transparent 1px 3px)' }}/>

        {/* Top overlay */}
        <div style={{ position: 'absolute', top: 16, left: 12, right: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <button onClick={() => ctx.go('on-ready')} style={{ background: 'rgba(255,255,255,0.12)', border: 'none', color: '#fff', width: 40, height: 40, borderRadius: 20, cursor: 'pointer', backdropFilter: 'blur(10px)' }}><TwIcon.chevronLeft /></button>
          <button style={{ background: 'rgba(255,255,255,0.12)', border: 'none', color: '#fff', width: 40, height: 40, borderRadius: 20, cursor: 'pointer' }}>⚡</button>
        </div>

        {/* Viewfinder */}
        <div style={{ position: 'absolute', left: '50%', top: '44%', transform: 'translate(-50%,-50%)', width: 240, height: 240 }}>
          {['tl', 'tr', 'bl', 'br'].map((c, i) => {
            const col = found ? t.accent : '#fff';
            const edges = {
              tl: { top: 0, left: 0, borderTopWidth: 3, borderTopStyle: 'solid', borderTopColor: col, borderLeftWidth: 3, borderLeftStyle: 'solid', borderLeftColor: col, borderTopLeftRadius: 14 },
              tr: { top: 0, right: 0, borderTopWidth: 3, borderTopStyle: 'solid', borderTopColor: col, borderRightWidth: 3, borderRightStyle: 'solid', borderRightColor: col, borderTopRightRadius: 14 },
              bl: { bottom: 0, left: 0, borderBottomWidth: 3, borderBottomStyle: 'solid', borderBottomColor: col, borderLeftWidth: 3, borderLeftStyle: 'solid', borderLeftColor: col, borderBottomLeftRadius: 14 },
              br: { bottom: 0, right: 0, borderBottomWidth: 3, borderBottomStyle: 'solid', borderBottomColor: col, borderRightWidth: 3, borderRightStyle: 'solid', borderRightColor: col, borderBottomRightRadius: 14 },
            }[c];
            return <div key={c} style={{
              position: 'absolute', width: 34, height: 34,
              ...edges,
              transition: 'border-color .2s',
            }} />;
          })}
          {!found && (
            <div style={{ position: 'absolute', left: 20, right: 20, top: '50%', height: 2, background: `linear-gradient(90deg, transparent, ${t.accent}, transparent)`, animation: 'twScanner 2s linear infinite' }} />
          )}
          {found && (
            <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <div style={{ width: 64, height: 64, borderRadius: 32, background: t.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff' }}>
                <TwIcon.check />
              </div>
            </div>
          )}
        </div>

        <style>{`@keyframes twScanner { 0% { transform: translateY(-90px) } 100% { transform: translateY(90px) } }`}</style>

        <div style={{ position: 'absolute', bottom: 120, left: 0, right: 0, textAlign: 'center', padding: '0 32px' }}>
          <div style={{ fontSize: 17, fontWeight: 600 }}>{found ? 'Code received' : 'Point at the QR on your other phone'}</div>
          {!found && <div style={{ fontSize: 14, opacity: 0.7, marginTop: 6 }}>Keep it steady inside the frame</div>}
        </div>

        <button style={{
          position: 'absolute', bottom: 40, left: 24, right: 24,
          height: 48, borderRadius: 24, border: 'none',
          background: 'rgba(255,255,255,0.12)', color: '#fff',
          fontSize: 14, fontWeight: 600, cursor: 'pointer', backdropFilter: 'blur(10px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
        }}><TwIcon.keyboard /> Enter code manually</button>
      </div>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// PAIR-07/08 · CONFIRM FINGERPRINT
// ═══════════════════════════════════════════════════════════════
function ScreenPairFP({ ctx }) {
  const t = ctx.theme;
  const [pending, setPending] = React.useState(false);
  const peerName = ctx.role === 'A' ? 'Pixel 9 Pro' : 'Samsung S24';
  const myName = ctx.role === 'A' ? 'Samsung S24' : 'Pixel 9 Pro';
  const shownFP = ctx.role === 'A' ? PEER_FP : MY_FP;
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Verify the match" onBack={() => ctx.go(ctx.role === 'A' ? 'pair-qr' : 'pair-scan')} />}>
      <div style={{ fontSize: 15, color: t.ink3, marginBottom: 12, textWrap: 'pretty' }}>
        Compare these 16 blocks with what <b style={{ color: t.ink }}>{peerName}</b> is showing. They must match exactly — that's how we know no one's in the middle.
      </div>

      <TwFingerprint hex={shownFP} highlightGroups={[0, 5, 10, 15]} columns={4} />

      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 18, padding: '10px 14px', background: t.fill, borderRadius: 10 }}>
        <div style={{ width: 28, height: 28, borderRadius: 14, background: t.accentLo, color: t.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: TW_FONTS.mono, fontWeight: 700, fontSize: 13 }}>{ctx.role}</div>
        <div style={{ flex: 1, fontSize: 13, color: t.ink2 }}>
          <b style={{ color: t.ink }}>{myName}</b><br/>
          <span style={{ color: t.ink3 }}>Keys generated on-device · Never uploaded</span>
        </div>
      </div>

      <div style={{ flex: 1 }} />

      {pending ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 14, background: t.fill, borderRadius: 12, marginBottom: 10 }}>
          <TwSpinner size={16} />
          <div style={{ fontSize: 14, color: t.ink2 }}>Transferring signature…</div>
        </div>
      ) : null}

      <div style={{ display: 'flex', gap: 10 }}>
        <TwButton variant="secondary" fullWidth destructive onClick={() => ctx.go('pair-fail')}>
          Don't match
        </TwButton>
        <TwButton variant="primary" fullWidth onClick={() => { setPending(true); setTimeout(() => ctx.go('pair-success'), 1200); }}>
          They match
        </TwButton>
      </div>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// PAIR-10 · SUCCESS
// ═══════════════════════════════════════════════════════════════
function ScreenPairSuccess({ ctx }) {
  const t = ctx.theme;
  const peerName = ctx.role === 'A' ? 'Pixel 9 Pro' : 'Samsung S24';
  return (
    <Screen ctx={ctx}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 20, padding: '0 8px', textAlign: 'center' }}>
        <svg width="160" height="100" viewBox="0 0 160 100" fill="none">
          <circle cx="60" cy="50" r="38" stroke={t.ink} strokeWidth="2.4"/>
          <circle cx="100" cy="50" r="38" stroke={t.accent} strokeWidth="2.4"/>
          <path d="M70 50l8 8 16-16" stroke={t.accent} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
        </svg>
        <div>
          <div style={{ fontSize: 26, fontWeight: 600, letterSpacing: -0.4, color: t.ink, textWrap: 'pretty' }}>Twinned.</div>
          <div style={{ fontSize: 15, color: t.ink3, marginTop: 10, maxWidth: 260, lineHeight: 1.5 }}>
            This phone is now paired with <b style={{ color: t.ink }}>{peerName}</b>. Notifications will start mirroring immediately.
          </div>
        </div>
      </div>
      <TwButton variant="primary" size="lg" fullWidth onClick={() => ctx.go('home')}>Done</TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// PAIR-11 · PAIR FAILED
// ═══════════════════════════════════════════════════════════════
function ScreenPairFail({ ctx }) {
  const t = ctx.theme;
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="" onBack={() => ctx.go('on-role')} />}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 20, padding: '0 8px', textAlign: 'center' }}>
        <div style={{ width: 88, height: 88, borderRadius: 24, background: `color-mix(in oklch, ${t.sem.danger} 12%, ${t.card})`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.sem.danger }}>
          <TwIcon.alert style={{ width: 36, height: 36 }}/>
        </div>
        <div>
          <div style={{ fontSize: 24, fontWeight: 600, letterSpacing: -0.4, color: t.ink, textWrap: 'pretty' }}>Fingerprints didn't match</div>
          <div style={{ fontSize: 14, color: t.ink3, marginTop: 10, maxWidth: 280, lineHeight: 1.5 }}>
            Pairing aborted. This can happen with a failed scan, or if something was intercepting your connection. Try again on a trusted network.
          </div>
        </div>
      </div>
      <TwButton variant="primary" fullWidth onClick={() => ctx.go('on-role')}>Start over</TwButton>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// HOM-01 · HOME
// ═══════════════════════════════════════════════════════════════
function ScreenHome({ ctx }) {
  const t = ctx.theme;
  const [mirrorOn, setMirrorOn] = React.useState(true);
  const state = ctx.connection;
  const peerName = ctx.role === 'A' ? 'Pixel 9 Pro' : 'Samsung S24';

  const statusCopy = {
    lan: { title: 'Direct on LAN', body: 'Encrypted peer-to-peer over Wi-Fi. Fastest path.' },
    relay: { title: 'Over relay', body: 'Relay-tunneled. Still end-to-end encrypted.' },
    offline: { title: 'Offline', body: 'We can\'t reach your other phone right now.' },
    pairing: { title: 'Reconnecting…', body: 'Renegotiating keys with your peer.' },
  }[state];

  const recent = [
    { app: TW_APPS.signal,   title: 'Alex Wu',  preview: 'I just sent the invoice', dir: 'down', ago: '2m' },
    { app: TW_APPS.gmail,    title: 'Stripe',   preview: 'Payout of $4,280 completed', dir: 'down', ago: '18m' },
    { app: TW_APPS.linear,   title: 'Maya K.',  preview: 'Moved TWN-128 → In Review', dir: 'up', ago: '1h' },
  ];

  return (
    <Screen ctx={ctx}>
      {/* Top bar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingBottom: 4 }}>
        <TwWordmark size={17} />
        <button onClick={() => ctx.go('settings')} style={{ border: 'none', background: 'transparent', width: 40, height: 40, borderRadius: 20, cursor: 'pointer', color: t.ink, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <TwIcon.settings />
        </button>
      </div>

      {/* Hero status card */}
      <div style={{
        marginTop: 10,
        background: state === 'offline' ? `color-mix(in oklch, ${t.sem.danger} 6%, ${t.card})` : state === 'lan' ? t.card : t.card,
        border: `1px solid ${state === 'offline' ? `color-mix(in oklch, ${t.sem.danger} 30%, ${t.border})` : t.border}`,
        borderRadius: 20, padding: 20, position: 'relative', overflow: 'hidden',
      }}>
        {/* Subtle gradient stripe */}
        <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 2, background: state === 'offline' ? t.sem.danger : state === 'pairing' ? t.sem.warn : state === 'relay' ? t.sem.info : t.sem.ok }}/>

        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 14 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <TwStatusDot state={state} size={9} />
              <div style={{ fontSize: 12, fontWeight: 600, color: t.ink3, letterSpacing: 0.5, textTransform: 'uppercase' }}>
                <TwStatusLabel state={state} />
              </div>
            </div>
            <div style={{ fontSize: 22, fontWeight: 600, letterSpacing: -0.3, color: t.ink }}>{statusCopy.title}</div>
            <div style={{ fontSize: 13, color: t.ink3, marginTop: 3, maxWidth: 240 }}>{statusCopy.body}</div>
          </div>
          <TwSwitch checked={mirrorOn && state !== 'offline'} onChange={setMirrorOn} size="lg" disabled={state === 'offline'} />
        </div>

        {/* Pair row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px', background: t.fill, borderRadius: 12, border: `1px solid ${t.border}` }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 24, height: 24, borderRadius: 12, background: t.ink, color: t.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: TW_FONTS.mono, fontWeight: 700, fontSize: 11 }}>
              {ctx.role}
            </div>
            <svg width="16" height="10" viewBox="0 0 16 10" fill="none"><path d="M1 5h14M11 1l4 4-4 4" stroke={t.ink3} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>
            <div style={{ width: 24, height: 24, borderRadius: 12, background: t.accent, color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: TW_FONTS.mono, fontWeight: 700, fontSize: 11 }}>
              {ctx.role === 'A' ? 'B' : 'A'}
            </div>
          </div>
          <div style={{ flex: 1, fontSize: 13, color: t.ink2, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            <b style={{ color: t.ink }}>{peerName}</b> · {state === 'offline' ? 'last seen 3 min ago' : 'online'}
          </div>
          <button onClick={() => ctx.go('pair-fp')} style={{ border: 'none', background: 'transparent', color: t.ink3, cursor: 'pointer', padding: 4 }}>
            <TwIcon.chevronRight />
          </button>
        </div>

        {/* Metrics row */}
        <div style={{ display: 'flex', gap: 12, marginTop: 14, fontFamily: TW_FONTS.mono, fontVariantNumeric: 'tabular-nums' }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, color: t.ink4, fontWeight: 500, letterSpacing: 0.4, textTransform: 'uppercase' }}>Today</div>
            <div style={{ fontSize: 20, fontWeight: 600, color: t.ink, marginTop: 2 }}>127</div>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, color: t.ink4, fontWeight: 500, letterSpacing: 0.4, textTransform: 'uppercase' }}>Latency</div>
            <div style={{ fontSize: 20, fontWeight: 600, color: t.ink, marginTop: 2 }}>42<span style={{ fontSize: 12, color: t.ink3, marginLeft: 2 }}>ms</span></div>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, color: t.ink4, fontWeight: 500, letterSpacing: 0.4, textTransform: 'uppercase' }}>Blocked</div>
            <div style={{ fontSize: 20, fontWeight: 600, color: t.ink, marginTop: 2 }}>8</div>
          </div>
        </div>
      </div>

      {/* Inline state-specific cards */}
      {state === 'offline' && (
        <div style={{ marginTop: 12 }}>
          <TwBanner tone="danger" title="Your other phone hasn't responded" body="Check that it's online and Twinotify is running. We'll keep retrying." action={<TwButton size="sm" variant="secondary">Retry now</TwButton>} />
        </div>
      )}
      {state === 'pairing' && (
        <div style={{ marginTop: 12 }}>
          <TwBanner tone="warn" title="Temporarily out of sync" body="Reconnecting with fresh keys. Should be a few seconds." />
        </div>
      )}
      {state === 'relay' && (
        <div style={{ marginTop: 12 }}>
          <TwBanner tone="info" title="Not on the same Wi-Fi" body="We'll keep you on the relay until your phones can find each other again." compact />
        </div>
      )}

      {/* Recent activity */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 24, marginBottom: 8 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: t.ink2, letterSpacing: -0.1 }}>Recent</div>
        <button style={{ border: 'none', background: 'transparent', color: t.ink3, fontSize: 13, fontWeight: 500, cursor: 'pointer' }}>See all</button>
      </div>
      <div style={{ background: t.card, border: `1px solid ${t.border}`, borderRadius: 16, overflow: 'hidden' }}>
        {recent.map((r, i) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', gap: 12, padding: '14px 14px',
            borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
          }}>
            <TwAppChip app={r.app} size="sm" />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div style={{ fontSize: 14, fontWeight: 600, color: t.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.title}</div>
                <div style={{ color: r.dir === 'up' ? t.sem.info : t.ink4, flexShrink: 0 }}>
                  {r.dir === 'up' ? <TwIcon.arrowUp /> : <TwIcon.arrowDown />}
                </div>
              </div>
              <div style={{ fontSize: 13, color: t.ink3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.preview}</div>
            </div>
            <div style={{ fontSize: 12, color: t.ink4, fontFamily: TW_FONTS.mono, fontVariantNumeric: 'tabular-nums' }}>{r.ago}</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
        <TwButton variant="secondary" size="sm" fullWidth onClick={() => ctx.go('filter')}>App filter</TwButton>
        <TwButton variant="secondary" size="sm" fullWidth onClick={() => ctx.go('settings')}>Settings</TwButton>
      </div>
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// SETTINGS (SET-01)
// ═══════════════════════════════════════════════════════════════
function ScreenSettings({ ctx }) {
  const t = ctx.theme;
  const peerName = ctx.role === 'A' ? 'Pixel 9 Pro' : 'Samsung S24';
  const groups = [
    {
      t: 'Pairing',
      items: [
        { icon: <TwIcon.pair />, title: 'Paired device', sub: `${peerName} · ${ctx.connection === 'offline' ? 'offline' : 'online'}`, go: 'set-pair' },
        { icon: <TwIcon.qr />, title: 'Add another device', sub: 'Up to 4 paired devices', go: 'pair-qr' },
      ],
    },
    {
      t: 'Sync',
      items: [
        { icon: <TwIcon.link />, title: 'Relay server', sub: 'relay.twinotify.app · 42 ms' },
        { icon: <TwIcon.battery />, title: 'Always-connected', sub: 'Off · Uses FCM when idle', toggle: false, onChange: () => {} },
        { icon: <TwIcon.refresh />, title: 'Prefer LAN', sub: 'Direct when possible', toggle: true },
      ],
    },
    {
      t: 'Privacy',
      items: [
        { icon: <TwIcon.shield />, title: 'App filter', sub: '28 mirrored · 14 blocked', go: 'filter' },
        { icon: <TwIcon.bell />, title: 'Lock-screen preview', sub: 'Hidden until unlocked' },
      ],
    },
    {
      t: 'About',
      items: [
        { icon: <TwIcon.alert />, title: 'Reliability audit', sub: '2 fixes recommended', warn: true },
        { title: 'Version', sub: '1.0.0 (build 204) · Open source' },
      ],
    },
  ];
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Settings" onBack={() => ctx.go('home')} />}>
      {groups.map((g) => (
        <div key={g.t} style={{ marginBottom: 22 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: t.ink4, letterSpacing: 0.6, textTransform: 'uppercase', marginBottom: 8, padding: '0 4px' }}>{g.t}</div>
          <div style={{ background: t.card, border: `1px solid ${t.border}`, borderRadius: 14, overflow: 'hidden' }}>
            {g.items.map((it, i) => (
              <div key={i} onClick={it.go ? () => ctx.go(it.go) : undefined} style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', minHeight: 52,
                borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
                cursor: it.go ? 'pointer' : 'default',
              }}>
                {it.icon && (
                  <div style={{
                    width: 32, height: 32, borderRadius: 9,
                    background: it.warn ? `color-mix(in oklch, ${t.sem.warn} 18%, ${t.fill})` : t.fill,
                    color: it.warn ? t.sem.warn : t.ink, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                  }}>{it.icon}</div>
                )}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, color: t.ink }}>{it.title}</div>
                  {it.sub && <div style={{ fontSize: 12, color: t.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{it.sub}</div>}
                </div>
                {it.toggle !== undefined ? <TwSwitch checked={it.toggle} onChange={it.onChange} /> : it.go ? <TwIcon.chevronRight style={{ color: t.ink4 }} /> : null}
              </div>
            ))}
          </div>
        </div>
      ))}
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// SET-02 · PAIRING DETAILS
// ═══════════════════════════════════════════════════════════════
function ScreenSetPair({ ctx }) {
  const t = ctx.theme;
  const peerName = ctx.role === 'A' ? 'Pixel 9 Pro' : 'Samsung S24';
  const [confirmOpen, setConfirm] = React.useState(false);
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="Paired device" onBack={() => ctx.go('settings')} />}>
      <div style={{ textAlign: 'center', padding: '20px 0 28px' }}>
        <div style={{ width: 88, height: 88, borderRadius: 24, background: t.accentLo, margin: '0 auto 14px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
            <rect x="10" y="8" width="28" height="32" rx="4" stroke={t.accent} strokeWidth="2"/>
            <circle cx="24" cy="36" r="1.6" fill={t.accent}/>
          </svg>
        </div>
        <div style={{ fontSize: 22, fontWeight: 600, color: t.ink, letterSpacing: -0.2 }}>{peerName}</div>
        <div style={{ fontSize: 13, color: t.ink3, marginTop: 4 }}>Paired Apr 11 · 10 days ago</div>
      </div>

      <div style={{ fontSize: 11, fontWeight: 700, color: t.ink4, letterSpacing: 0.5, textTransform: 'uppercase', marginBottom: 8, padding: '0 4px' }}>Peer fingerprint</div>
      <TwFingerprint hex={PEER_FP} highlightGroups={[0, 5, 10, 15]} />
      <div style={{ fontSize: 12, color: t.ink3, marginTop: 8, padding: '0 4px' }}>
        If you ever reinstall, check that these 64 characters are unchanged.
      </div>

      <div style={{ flex: 1 }} />

      <TwButton variant="destructive" size="md" fullWidth icon={<TwIcon.unpair />} onClick={() => setConfirm(true)}>
        Unpair
      </TwButton>

      {confirmOpen && (
        <div onClick={() => setConfirm(false)} style={{ position: 'absolute', inset: 0, background: 'rgba(26,23,19,0.5)', display: 'flex', alignItems: 'flex-end', zIndex: 100, animation: 'twFadeIn .15s' }}>
          <div onClick={(e) => e.stopPropagation()} style={{ background: t.card, borderTopLeftRadius: 20, borderTopRightRadius: 20, width: '100%', padding: 24, animation: 'twFadeInSheet .25s cubic-bezier(.3,1,.4,1)' }}>
            <div style={{ width: 36, height: 4, background: t.borderHi, borderRadius: 2, margin: '0 auto 18px' }} />
            <div style={{ fontSize: 19, fontWeight: 600, color: t.ink, textWrap: 'pretty' }}>Unpair from {peerName}?</div>
            <div style={{ fontSize: 14, color: t.ink3, marginTop: 8, lineHeight: 1.5 }}>
              Your peer keys will be cleared and yours will be rotated. You'll need to pair again to resume mirroring.
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 22 }}>
              <TwButton variant="secondary" fullWidth onClick={() => setConfirm(false)}>Cancel</TwButton>
              <TwButton variant="primary" destructive fullWidth onClick={() => { setConfirm(false); ctx.go('on-role'); }}>Unpair</TwButton>
            </div>
          </div>
        </div>
      )}
    </Screen>
  );
}

// ═══════════════════════════════════════════════════════════════
// FIL-01 · APP FILTER
// ═══════════════════════════════════════════════════════════════
function ScreenFilter({ ctx }) {
  const t = ctx.theme;
  const [tab, setTab] = React.useState('all');
  const [apps, setApps] = React.useState({
    signal: true, whatsapp: true, slack: true, gmail: true, linear: true,
    github: true, spotify: true, calendar: true, maps: false,
    chase: false, authy: false, uber: true, instagram: true, discord: true, telegram: true,
  });
  const tabs = [
    { k: 'all', label: 'All', n: Object.keys(apps).length },
    { k: 'mirrored', label: 'Mirrored', n: Object.values(apps).filter(Boolean).length },
    { k: 'blocked', label: 'Blocked', n: Object.values(apps).filter((v) => !v).length },
  ];
  const visible = Object.keys(apps).filter((k) => {
    if (tab === 'mirrored') return apps[k];
    if (tab === 'blocked') return !apps[k];
    return true;
  });
  return (
    <Screen ctx={ctx} header={<ScreenHeader ctx={ctx} title="App filter" onBack={() => ctx.go('settings')} />} padding={0}>
      <div style={{ padding: '0 20px' }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, background: t.fill, border: `1px solid ${t.border}`,
          borderRadius: 12, padding: '10px 12px', marginBottom: 16,
        }}>
          <TwIcon.search style={{ color: t.ink3 }} />
          <input placeholder="Search apps" style={{
            flex: 1, border: 'none', outline: 'none', background: 'transparent',
            fontFamily: TW_FONTS.ui, fontSize: 14, color: t.ink,
          }} />
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
          {tabs.map((tab_) => (
            <button key={tab_.k} onClick={() => setTab(tab_.k)} style={{
              flex: 1, border: `1px solid ${tab === tab_.k ? t.ink : t.border}`,
              background: tab === tab_.k ? t.ink : t.card,
              color: tab === tab_.k ? t.bg : t.ink,
              padding: '8px 10px', borderRadius: 999, fontSize: 13, fontWeight: 600, cursor: 'pointer',
              fontFamily: TW_FONTS.ui,
            }}>
              {tab_.label} <span style={{ opacity: 0.6, marginLeft: 2 }}>{tab_.n}</span>
            </button>
          ))}
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 20px 20px' }}>
        {tab === 'all' && (
          <div style={{ marginBottom: 12, padding: '10px 12px', background: `color-mix(in oklch, ${t.sem.ok} 8%, ${t.card})`, border: `1px solid color-mix(in oklch, ${t.sem.ok} 25%, ${t.border})`, borderRadius: 10, fontSize: 12, color: t.ink2, display: 'flex', alignItems: 'center', gap: 8 }}>
            <TwIcon.shield style={{ color: t.sem.ok, width: 16, height: 16 }}/>
            <span>3 banking apps pre-blocked · <b style={{ color: t.ink }}>hash verified</b></span>
          </div>
        )}
        <div style={{ background: t.card, border: `1px solid ${t.border}`, borderRadius: 14, overflow: 'hidden' }}>
          {visible.map((k, i) => (
            <div key={k} style={{
              display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px',
              borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
            }}>
              <TwAppChip app={TW_APPS[k]} size="sm" />
              <div style={{ flex: 1, fontSize: 14, fontWeight: 500, color: t.ink }}>{TW_APPS[k].name}</div>
              <TwSwitch checked={apps[k]} onChange={(v) => setApps({ ...apps, [k]: v })} />
            </div>
          ))}
        </div>
      </div>
    </Screen>
  );
}

Object.assign(window, {
  ScreenWelcome, ScreenHow, ScreenRole, ScreenRelay, ScreenPerms, ScreenOEM, ScreenReady,
  ScreenPairQR, ScreenPairScan, ScreenPairFP, ScreenPairSuccess, ScreenPairFail,
  ScreenHome, ScreenSettings, ScreenSetPair, ScreenFilter,
  PEER_FP, MY_FP, PHONE_W, PHONE_H,
});
