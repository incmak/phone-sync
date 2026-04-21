// Twinotify primitives — shared UI atoms.
// Wordmark explores the mirror motif: two halves meet at the center,
// the doubled "i" becomes two dots. P-01..P-14 all live here.

// ─────────────────────────────────────────────────────────────
// LOGO / WORDMARK
// ─────────────────────────────────────────────────────────────

// Monogram mark — two mirror-halves forming a "T" pair.
// Stacked semicircles pivoted at center; reads as 'two devices linked'.
function TwLogo({ size = 32, color, accent, style, variant = 'pair' }) {
  const t = window.__twTheme;
  const fg = color || (t ? t.ink : '#1a1713');
  const ac = accent || (t ? t.accent : 'oklch(0.62 0.14 180)');
  if (variant === 'pair') {
    // Two interlocked rings whose intersection forms a vesica — the "twin" glyph
    return (
      <svg width={size} height={size} viewBox="0 0 32 32" style={style} fill="none">
        <circle cx="12" cy="16" r="9" stroke={fg} strokeWidth="2.2" />
        <circle cx="20" cy="16" r="9" stroke={ac} strokeWidth="2.2" />
      </svg>
    );
  }
  if (variant === 'dots') {
    // Doubled-i motif — two dots over a bar
    return (
      <svg width={size} height={size} viewBox="0 0 32 32" style={style} fill="none">
        <rect x="6" y="18" width="20" height="3" rx="1.5" fill={fg} />
        <circle cx="12" cy="11" r="3" fill={fg} />
        <circle cx="20" cy="11" r="3" fill={ac} />
      </svg>
    );
  }
  // monogram variant — T mirrored
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" style={style} fill="none">
      <path d="M4 8 H28 M10 8 V24" stroke={fg} strokeWidth="2.6" strokeLinecap="round" />
      <path d="M22 8 V24" stroke={ac} strokeWidth="2.6" strokeLinecap="round" />
    </svg>
  );
}

function TwWordmark({ size = 20, color, accent, style, variant = 'pair' }) {
  const t = window.__twTheme;
  const fg = color || (t ? t.ink : '#1a1713');
  const ac = accent || (t ? t.accent : 'oklch(0.62 0.14 180)');
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: size * 0.4, ...style }}>
      <TwLogo size={size * 1.4} color={fg} accent={ac} variant={variant} />
      <span style={{ fontFamily: TW_FONTS.display, fontSize: size, fontWeight: 600, letterSpacing: -0.4, color: fg }}>
        twin<span style={{ color: ac }}>otify</span>
      </span>
    </span>
  );
}

// ─────────────────────────────────────────────────────────────
// P-04 CONNECTION STATUS DOT
// ─────────────────────────────────────────────────────────────
function TwStatusDot({ state = 'lan', size = 10, style }) {
  const map = {
    lan:     { c: TW_SEMANTIC.ok,     pulse: false },
    relay:   { c: TW_SEMANTIC.info,   pulse: false },
    offline: { c: TW_SEMANTIC.danger, pulse: false },
    pairing: { c: TW_SEMANTIC.warn,   pulse: true  },
  };
  const { c, pulse } = map[state] || map.offline;
  return (
    <span style={{ display: 'inline-block', position: 'relative', width: size, height: size, ...style }}>
      <span style={{
        display: 'block', width: size, height: size, borderRadius: '50%', background: c,
        boxShadow: `0 0 0 3px color-mix(in oklch, ${c} 25%, transparent)`,
      }} />
      {pulse && (
        <span style={{
          position: 'absolute', inset: 0, borderRadius: '50%', background: c,
          animation: 'twPulse 1.6s ease-out infinite', opacity: 0.6,
        }} />
      )}
    </span>
  );
}

function TwStatusLabel({ state = 'lan' }) {
  const map = {
    lan:     'LAN · Direct',
    relay:   'Relay · Encrypted',
    offline: 'Offline',
    pairing: 'Pairing…',
  };
  return map[state] || 'Unknown';
}

// ─────────────────────────────────────────────────────────────
// P-05 DEVICE CHIP
// ─────────────────────────────────────────────────────────────
function TwDeviceChip({ name = 'Pixel 9 Pro', role, status, compact, style }) {
  const t = window.__twTheme;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 8,
      padding: compact ? '4px 10px' : '6px 12px',
      background: t.fill, border: `1px solid ${t.border}`, borderRadius: 999,
      fontFamily: TW_FONTS.ui, fontSize: 13, color: t.ink, fontWeight: 500,
      ...style,
    }}>
      <svg width="14" height="16" viewBox="0 0 14 16" fill="none" stroke="currentColor" strokeWidth="1.6">
        <rect x="1" y="1" width="12" height="14" rx="2" />
        <line x1="6" y1="12.2" x2="8" y2="12.2" strokeLinecap="round" />
      </svg>
      <span>{name}</span>
      {role && <span style={{ color: t.ink4, fontSize: 11, fontWeight: 500, letterSpacing: 0.3 }}>{role}</span>}
      {status && <TwStatusDot state={status} size={7} />}
    </span>
  );
}

// ─────────────────────────────────────────────────────────────
// P-06 APP CHIP
// ─────────────────────────────────────────────────────────────
function TwAppChip({ app, size = 'md', showBadge, style }) {
  const t = window.__twTheme;
  const px = { sm: 24, md: 32, lg: 40 }[size] || 32;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10, ...style }}>
      <span style={{
        width: px, height: px, borderRadius: px * 0.25, flexShrink: 0,
        background: app.color, display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: '#fff', fontWeight: 700, fontSize: px * 0.45, fontFamily: TW_FONTS.display,
        position: 'relative',
      }}>
        {app.glyph}
        {showBadge && (
          <span style={{
            position: 'absolute', top: -2, right: -2, minWidth: 14, height: 14, padding: '0 4px',
            borderRadius: 7, background: TW_SEMANTIC.danger, color: '#fff',
            fontSize: 9, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: `1.5px solid ${t.card}`,
          }}>{showBadge}</span>
        )}
      </span>
      {size !== 'sm' && (
        <span style={{ fontFamily: TW_FONTS.ui, fontSize: size === 'lg' ? 15 : 13, color: t.ink, fontWeight: 500 }}>
          {app.name}
        </span>
      )}
    </span>
  );
}

// Sample app registry — placeholders styled by color+glyph
const TW_APPS = {
  signal:    { name: 'Signal',     glyph: 'S',  color: '#3a76f0' },
  whatsapp:  { name: 'WhatsApp',   glyph: 'W',  color: '#25d366' },
  slack:     { name: 'Slack',      glyph: '#',  color: '#4a154b' },
  gmail:     { name: 'Gmail',      glyph: 'M',  color: '#ea4335' },
  linear:    { name: 'Linear',     glyph: 'L',  color: '#5e6ad2' },
  github:    { name: 'GitHub',     glyph: 'G',  color: '#1a1a1a' },
  spotify:   { name: 'Spotify',    glyph: '♪',  color: '#1db954' },
  calendar:  { name: 'Calendar',   glyph: '◷',  color: '#4285f4' },
  maps:      { name: 'Maps',       glyph: '◉',  color: '#34a853' },
  chase:     { name: 'Chase',      glyph: 'C',  color: '#0a5aa4' },
  authy:     { name: 'Authy',      glyph: '✓',  color: '#ec1c24' },
  uber:      { name: 'Uber',       glyph: 'U',  color: '#1a1a1a' },
  instagram: { name: 'Instagram',  glyph: '📷', color: '#e4405f' },
  discord:   { name: 'Discord',    glyph: 'D',  color: '#5865f2' },
  telegram:  { name: 'Telegram',   glyph: '✈',  color: '#2aabee' },
};

// ─────────────────────────────────────────────────────────────
// P-07 FINGERPRINT DISPLAY
// 16 groups of 4 uppercase hex, grouped; highlights color-code groups.
// ─────────────────────────────────────────────────────────────
function TwFingerprint({ hex, columns = 4, highlightGroups = [], style }) {
  const t = window.__twTheme;
  // normalize to 16 groups of 4
  const raw = (hex || '').replace(/\s/g, '').toUpperCase();
  const groups = Array.from({ length: 16 }, (_, i) => (raw.slice(i * 4, i * 4 + 4) || '0000').padEnd(4, '0'));
  return (
    <div style={{
      fontFamily: TW_FONTS.mono, fontSize: 15, fontVariantNumeric: 'tabular-nums',
      color: t.ink, letterSpacing: 0.5, lineHeight: 1.6,
      display: 'grid', gridTemplateColumns: `repeat(${columns}, 1fr)`, gap: '4px 14px',
      background: t.fill, padding: '16px 18px', borderRadius: 12, border: `1px solid ${t.border}`,
      ...style,
    }}>
      {groups.map((g, i) => (
        <span key={i} style={{
          color: highlightGroups.includes(i) ? t.accent : t.ink,
          fontWeight: highlightGroups.includes(i) ? 700 : 500,
        }}>{g}</span>
      ))}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// P-12 MONO / CODE BLOCK
// ─────────────────────────────────────────────────────────────
function TwCode({ children, block, style }) {
  const t = window.__twTheme;
  const base = {
    fontFamily: TW_FONTS.mono, fontSize: 13, color: t.ink,
    background: t.fill, border: `1px solid ${t.border}`, borderRadius: 8,
  };
  if (block) return (
    <div style={{ ...base, padding: '12px 14px', lineHeight: 1.5, wordBreak: 'break-all', ...style }}>{children}</div>
  );
  return <span style={{ ...base, padding: '2px 6px', ...style }}>{children}</span>;
}

// ─────────────────────────────────────────────────────────────
// QR CODE placeholder (styled mirror-symmetric mosaic, not a real QR)
// ─────────────────────────────────────────────────────────────
function TwQR({ size = 200, seed = 'twinotify', style, accent = true }) {
  const t = window.__twTheme;
  const fg = t.ink;
  const ac = accent ? t.accent : t.ink;
  // Deterministic 21x21 bit grid from seed
  const N = 25;
  let h = 0; for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0;
  const bits = Array.from({ length: N * N }, (_, i) => {
    const x = (h ^ (i * 2654435761)) >>> 0;
    return (x % 97) < 48;
  });
  // Clear finder positions
  const clear = (cx, cy) => {
    for (let y = 0; y < 7; y++) for (let x = 0; x < 7; x++) bits[(cy + y) * N + (cx + x)] = false;
  };
  clear(0, 0); clear(N - 7, 0); clear(0, N - 7);
  const cell = size / N;
  const finders = [[0, 0], [N - 7, 0], [0, N - 7]];
  return (
    <div style={{ width: size, height: size, background: t.card, padding: size * 0.04, borderRadius: 14, border: `1px solid ${t.border}`, ...style }}>
      <svg width="100%" height="100%" viewBox={`0 0 ${size} ${size}`}>
        {bits.map((b, i) => b ? (
          <rect key={i} x={(i % N) * cell} y={Math.floor(i / N) * cell} width={cell} height={cell} rx={cell * 0.15} fill={fg} />
        ) : null)}
        {finders.map(([cx, cy], i) => (
          <g key={i}>
            <rect x={cx * cell} y={cy * cell} width={cell * 7} height={cell * 7} rx={cell * 1.2} fill="none" stroke={i === 0 ? ac : fg} strokeWidth={cell} />
            <rect x={(cx + 2) * cell} y={(cy + 2) * cell} width={cell * 3} height={cell * 3} rx={cell * 0.6} fill={i === 0 ? ac : fg} />
          </g>
        ))}
        {/* Center mirror-logo */}
        <g transform={`translate(${size / 2 - cell * 2.5} ${size / 2 - cell * 2.5})`}>
          <rect width={cell * 5} height={cell * 5} rx={cell} fill={t.card} />
          <circle cx={cell * 1.8} cy={cell * 2.5} r={cell * 1.2} fill="none" stroke={fg} strokeWidth={cell * 0.5} />
          <circle cx={cell * 3.2} cy={cell * 2.5} r={cell * 1.2} fill="none" stroke={ac} strokeWidth={cell * 0.5} />
        </g>
      </svg>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// BUTTON
// ─────────────────────────────────────────────────────────────
function TwButton({ variant = 'primary', size = 'md', icon, children, onClick, disabled, destructive, fullWidth, style }) {
  const t = window.__twTheme;
  const sizes = {
    sm: { py: 8,  px: 14, fs: 13, h: 36 },
    md: { py: 12, px: 18, fs: 15, h: 48 },
    lg: { py: 14, px: 22, fs: 16, h: 56 },
  };
  const s = sizes[size];
  let base = {};
  if (variant === 'primary') {
    base = { background: destructive ? TW_SEMANTIC.danger : t.ink, color: destructive ? '#fff' : t.bg, border: 'none' };
  } else if (variant === 'accent') {
    base = { background: t.accent, color: '#fff', border: 'none' };
  } else if (variant === 'secondary') {
    base = { background: t.fill, color: t.ink, border: `1px solid ${t.border}` };
  } else if (variant === 'ghost') {
    base = { background: 'transparent', color: t.ink, border: 'none' };
  } else if (variant === 'destructive') {
    base = { background: 'transparent', color: TW_SEMANTIC.danger, border: `1px solid color-mix(in oklch, ${TW_SEMANTIC.danger} 40%, ${t.border})` };
  }
  return (
    <button onClick={onClick} disabled={disabled} style={{
      ...base,
      height: s.h, padding: `0 ${s.px}px`,
      fontFamily: TW_FONTS.ui, fontSize: s.fs, fontWeight: 600, letterSpacing: -0.1,
      borderRadius: 12, cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.4 : 1,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      width: fullWidth ? '100%' : undefined,
      transition: 'transform .08s, opacity .12s',
      ...style,
    }}
    onMouseDown={(e) => !disabled && (e.currentTarget.style.transform = 'scale(0.98)')}
    onMouseUp={(e) => (e.currentTarget.style.transform = '')}
    onMouseLeave={(e) => (e.currentTarget.style.transform = '')}>
      {icon}{children}
    </button>
  );
}

// ─────────────────────────────────────────────────────────────
// CARD
// ─────────────────────────────────────────────────────────────
function TwCard({ children, padding = 20, style, tone = 'default', interactive }) {
  const t = window.__twTheme;
  const tones = {
    default: { bg: t.card, border: t.border },
    raised:  { bg: t.card, border: t.border, shadow: t.shadowSm },
    fill:    { bg: t.fill, border: 'transparent' },
    accent:  { bg: t.accentLo, border: `1px solid color-mix(in oklch, ${t.accent} 25%, transparent)` },
    warn:    { bg: `color-mix(in oklch, ${TW_SEMANTIC.warn} 10%, ${t.card})`, border: `color-mix(in oklch, ${TW_SEMANTIC.warn} 30%, ${t.border})` },
    danger:  { bg: `color-mix(in oklch, ${TW_SEMANTIC.danger} 8%, ${t.card})`, border: `color-mix(in oklch, ${TW_SEMANTIC.danger} 30%, ${t.border})` },
  };
  const s = tones[tone];
  return (
    <div style={{
      background: s.bg,
      border: `1px solid ${s.border}`,
      borderRadius: 16,
      padding,
      boxShadow: s.shadow || 'none',
      cursor: interactive ? 'pointer' : undefined,
      ...style,
    }}>{children}</div>
  );
}

// ─────────────────────────────────────────────────────────────
// SWITCH
// ─────────────────────────────────────────────────────────────
function TwSwitch({ checked, onChange, size = 'md', disabled }) {
  const t = window.__twTheme;
  const w = size === 'lg' ? 52 : 44;
  const h = size === 'lg' ? 30 : 26;
  const d = h - 6;
  return (
    <button onClick={() => !disabled && onChange?.(!checked)} disabled={disabled}
      style={{
        width: w, height: h, borderRadius: h / 2, border: 'none', padding: 0,
        background: checked ? t.accent : t.borderHi,
        position: 'relative', cursor: disabled ? 'default' : 'pointer',
        transition: 'background .18s', opacity: disabled ? 0.5 : 1,
      }}>
      <span style={{
        position: 'absolute', top: 3, left: checked ? w - d - 3 : 3,
        width: d, height: d, borderRadius: '50%', background: '#fff',
        transition: 'left .18s cubic-bezier(.3,1,.4,1)',
        boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
      }} />
    </button>
  );
}

// ─────────────────────────────────────────────────────────────
// ICONS (thin line, 24x24)
// ─────────────────────────────────────────────────────────────
const TwIcon = {
  chevronRight: (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M7 4l6 6-6 6"/></svg>,
  chevronLeft:  (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M13 4l-6 6 6 6"/></svg>,
  check:        (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M4 10l4 4 8-8"/></svg>,
  x:            (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" {...p}><path d="M5 5l10 10M15 5L5 15"/></svg>,
  copy:         (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="6" y="6" width="10" height="10" rx="2"/><path d="M4 12H3a1 1 0 01-1-1V3a1 1 0 011-1h8a1 1 0 011 1v1"/></svg>,
  search:       (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" {...p}><circle cx="8" cy="8" r="5.5"/><path d="M12 12l4 4"/></svg>,
  settings:     (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><circle cx="10" cy="10" r="2.5"/><path d="M10 2v2M10 16v2M2 10h2M16 10h2M4.5 4.5l1.4 1.4M14.1 14.1l1.4 1.4M4.5 15.5l1.4-1.4M14.1 5.9l1.4-1.4"/></svg>,
  bell:         (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M5 8a5 5 0 0110 0v3l1.5 3h-13L5 11V8z"/><path d="M8 17a2 2 0 004 0"/></svg>,
  shield:       (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M10 2l7 3v5c0 4-3 7-7 8-4-1-7-4-7-8V5l7-3z"/></svg>,
  battery:      (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" {...p}><rect x="2" y="7" width="14" height="6" rx="1.5"/><rect x="17" y="9" width="1.5" height="2" rx=".5" fill="currentColor"/></svg>,
  link:         (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M8 10a3 3 0 004 0l3-3a3 3 0 00-4-4l-1 1"/><path d="M10 8a3 3 0 00-4 0l-3 3a3 3 0 004 4l1-1"/></svg>,
  qr:           (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" {...p}><rect x="3" y="3" width="5" height="5" rx="1"/><rect x="12" y="3" width="5" height="5" rx="1"/><rect x="3" y="12" width="5" height="5" rx="1"/><rect x="12" y="12" width="2" height="2"/><rect x="15" y="15" width="2" height="2"/><rect x="12" y="15" width="2" height="2"/><rect x="15" y="12" width="2" height="2"/></svg>,
  camera:       (p) => <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M3 7h2l1.5-2h7L15 7h2a1 1 0 011 1v8a1 1 0 01-1 1H3a1 1 0 01-1-1V8a1 1 0 011-1z"/><circle cx="10" cy="11.5" r="3"/></svg>,
  unpair:       (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" {...p}><path d="M7 5l-2 2a2.8 2.8 0 000 4l2 2"/><path d="M11 13l2-2a2.8 2.8 0 000-4l-2-2"/><path d="M3 15L15 3"/></svg>,
  arrowDown:    (p) => <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M7 2v10M3 8l4 4 4-4"/></svg>,
  arrowUp:      (p) => <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M7 12V2M3 6l4-4 4 4"/></svg>,
  alert:        (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" {...p}><path d="M9 2l7.5 13h-15L9 2z"/><path d="M9 7v4M9 13v.01" strokeWidth="1.8"/></svg>,
  pair:         (p) => <svg width="22" height="22" viewBox="0 0 22 22" fill="none" stroke="currentColor" strokeWidth="1.6" {...p}><circle cx="8" cy="11" r="5"/><circle cx="14" cy="11" r="5"/></svg>,
  refresh:      (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M15 4v4h-4"/><path d="M3 14v-4h4"/><path d="M14 8a5.5 5.5 0 00-10-1M4 10a5.5 5.5 0 0010 1"/></svg>,
  keyboard:     (p) => <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.5" {...p}><rect x="1.5" y="5" width="15" height="9" rx="1.5"/><path d="M4 8h.01M7 8h.01M10 8h.01M13 8h.01M5 11h8" strokeLinecap="round"/></svg>,
};

// ─────────────────────────────────────────────────────────────
// EMPTY STATE
// ─────────────────────────────────────────────────────────────
function TwEmpty({ title, body, cta, art }) {
  const t = window.__twTheme;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', gap: 14, padding: 32 }}>
      {art || (
        <svg width="64" height="64" viewBox="0 0 64 64" fill="none">
          <circle cx="24" cy="32" r="14" stroke={t.ink4} strokeWidth="1.6" strokeDasharray="3 4"/>
          <circle cx="40" cy="32" r="14" stroke={t.accent} strokeWidth="1.6"/>
        </svg>
      )}
      <div style={{ fontSize: 17, fontWeight: 600, color: t.ink }}>{title}</div>
      {body && <div style={{ fontSize: 14, color: t.ink3, maxWidth: 280, lineHeight: 1.5 }}>{body}</div>}
      {cta}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// ERROR / BANNER CARD
// ─────────────────────────────────────────────────────────────
function TwBanner({ tone = 'info', title, body, action, compact, icon }) {
  const t = window.__twTheme;
  const colorMap = {
    info:    { c: TW_SEMANTIC.info,   bg: `color-mix(in oklch, ${TW_SEMANTIC.info} 10%, ${t.card})` },
    warn:    { c: TW_SEMANTIC.warn,   bg: `color-mix(in oklch, ${TW_SEMANTIC.warn} 12%, ${t.card})` },
    danger:  { c: TW_SEMANTIC.danger, bg: `color-mix(in oklch, ${TW_SEMANTIC.danger} 8%, ${t.card})` },
    ok:      { c: TW_SEMANTIC.ok,     bg: `color-mix(in oklch, ${TW_SEMANTIC.ok} 10%, ${t.card})` },
  };
  const m = colorMap[tone];
  return (
    <div style={{
      display: 'flex', gap: 12, padding: compact ? '10px 14px' : '14px 16px',
      background: m.bg, borderRadius: 12,
      border: `1px solid color-mix(in oklch, ${m.c} 30%, ${t.border})`,
    }}>
      <div style={{ color: m.c, flexShrink: 0, marginTop: 1 }}>
        {icon || <TwIcon.alert />}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: t.ink, marginBottom: body ? 2 : 0 }}>{title}</div>
        {body && <div style={{ fontSize: 13, color: t.ink2, lineHeight: 1.45 }}>{body}</div>}
        {action && <div style={{ marginTop: 8 }}>{action}</div>}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// LIST ROW
// ─────────────────────────────────────────────────────────────
function TwRow({ leading, title, subtitle, trailing, onClick, style }) {
  const t = window.__twTheme;
  return (
    <div onClick={onClick} style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: '14px 4px', minHeight: 48,
      cursor: onClick ? 'pointer' : undefined,
      ...style,
    }}>
      {leading}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 500, color: t.ink, lineHeight: 1.3 }}>{title}</div>
        {subtitle && <div style={{ fontSize: 13, color: t.ink3, marginTop: 2, lineHeight: 1.35, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{subtitle}</div>}
      </div>
      {trailing}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// GLOBAL CSS (keyframes etc.)
// ─────────────────────────────────────────────────────────────
if (typeof document !== 'undefined' && !document.getElementById('tw-global')) {
  const s = document.createElement('style');
  s.id = 'tw-global';
  s.textContent = `
    @keyframes twPulse { 0% { transform: scale(1); opacity: .6 } 100% { transform: scale(2.4); opacity: 0 } }
    @keyframes twSpin  { to { transform: rotate(360deg) } }
    @keyframes twFadeIn { from { opacity: 0; transform: translateY(4px) } to { opacity: 1; transform: none } }
    @keyframes twFadeInSheet { from { transform: translateY(100%) } to { transform: translateY(0) } }
    @keyframes twShimmer { 0% { background-position: -200px 0 } 100% { background-position: 200px 0 } }
    .tw-spin { animation: twSpin 1s linear infinite }
    /* phone screen reset */
    .tw-screen * { box-sizing: border-box }
    .tw-screen button { font-family: inherit }
    .tw-screen::-webkit-scrollbar { width: 0 }
  `;
  document.head.appendChild(s);
}

// Android-style status bar for screen mocks
function TwStatusBar({ dark, bg }) {
  const c = dark ? '#fff' : '#1a1713';
  return (
    <div style={{
      height: 28, background: bg || 'transparent', color: c,
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 18px', fontFamily: TW_FONTS.ui, fontSize: 13, fontWeight: 600,
      flexShrink: 0,
    }}>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>9:41</span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <svg width="16" height="11" viewBox="0 0 16 11" fill="currentColor"><rect x="0" y="8" width="2.5" height="3"/><rect x="3.5" y="6" width="2.5" height="5"/><rect x="7" y="3.5" width="2.5" height="7.5"/><rect x="10.5" y="1" width="2.5" height="10"/></svg>
        <svg width="14" height="11" viewBox="0 0 14 11" fill="currentColor"><path d="M7 2c2 0 3.8.8 5.2 2L14 2.4C12.2 0.9 9.7 0 7 0S1.8.9 0 2.4L1.8 4C3.2 2.8 5 2 7 2zm0 4c1.1 0 2.1.4 2.9 1.2L11.6 5.6C10.3 4.6 8.7 4 7 4s-3.3.6-4.6 1.6l1.7 1.6C4.9 6.4 5.9 6 7 6z"/><circle cx="7" cy="9" r="1.5"/></svg>
        <svg width="22" height="11" viewBox="0 0 22 11" fill="none" stroke="currentColor" strokeWidth="1"><rect x=".5" y=".5" width="19" height="10" rx="2"/><rect x="2" y="2" width="14" height="7" rx="1" fill="currentColor"/><rect x="20" y="3.5" width="1.2" height="4" rx=".5" fill="currentColor"/></svg>
      </span>
    </div>
  );
}

function TwNavBar() {
  // Gesture pill at bottom of Android screens
  return (
    <div style={{ height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <div style={{ width: 120, height: 4, borderRadius: 2, background: 'currentColor', opacity: 0.3 }} />
    </div>
  );
}

function TwSpinner({ size = 18, color }) {
  const t = window.__twTheme;
  const c = color || t.accent;
  return (
    <svg width={size} height={size} viewBox="0 0 18 18" className="tw-spin">
      <circle cx="9" cy="9" r="7" fill="none" stroke={c} strokeWidth="2" strokeLinecap="round" strokeDasharray="20 60"/>
    </svg>
  );
}

Object.assign(window, {
  TwLogo, TwWordmark, TwStatusDot, TwStatusLabel, TwDeviceChip, TwAppChip, TW_APPS,
  TwFingerprint, TwCode, TwQR, TwButton, TwCard, TwSwitch, TwIcon, TwEmpty, TwBanner, TwRow,
  TwStatusBar, TwNavBar, TwSpinner,
});
