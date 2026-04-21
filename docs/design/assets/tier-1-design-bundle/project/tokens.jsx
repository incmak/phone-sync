// Twinotify design tokens
// Warm, calm, private — mint-teal single accent; mirror motif in logo.
// Light + dark parity. Accent hue is tweakable.

const TW_FONTS = {
  ui: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif',
  display: '"Inter", -apple-system, system-ui, sans-serif',
  mono: '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace',
};

// Hue registry — accent color picker in Tweaks cycles through these.
const TW_HUES = {
  mint:   180,  // default — calm, not clinical
  indigo: 265,
  amber:  65,
  rose:   15,
};

// Build a full palette for a given accent hue.
// Lightness and chroma are ALIGNED across hues for visual consistency.
function twBuildPalette(hue = 180) {
  const a = (L, C) => `oklch(${L} ${C} ${hue})`;
  return {
    // Accent ramp
    accent50:  a(0.97, 0.02),
    accent100: a(0.93, 0.04),
    accent200: a(0.86, 0.08),
    accent300: a(0.78, 0.11),
    accent400: a(0.70, 0.13),
    accent500: a(0.62, 0.14),   // primary
    accent600: a(0.54, 0.13),
    accent700: a(0.45, 0.11),
    accent800: a(0.36, 0.08),
    accent900: a(0.25, 0.05),
  };
}

// Neutrals — warm ink, not cold gray.
const TW_NEUTRALS = {
  // Light
  surface0:   '#f7f5f1',   // page
  surface1:   '#ffffff',   // card
  surface2:   '#f1efea',   // subtle fill
  surface3:   '#e8e5df',   // hover
  border:     '#e2ded6',
  borderHigh: '#cfcac0',
  ink:        '#1a1713',   // primary text
  ink2:       '#3d3832',   // secondary
  ink3:       '#6e685f',   // tertiary
  ink4:       '#9b968d',   // quaternary / hint

  // Dark
  dSurface0:  '#141210',
  dSurface1:  '#1c1a17',
  dSurface2:  '#252320',
  dSurface3:  '#2f2c28',
  dBorder:    '#2e2a25',
  dBorderHigh:'#3f3a33',
  dInk:       '#f4f1eb',
  dInk2:      '#c8c3ba',
  dInk3:      '#8c867c',
  dInk4:      '#5a564e',
};

// Semantic statuses — used for connection dots + error/success/info.
const TW_SEMANTIC = {
  ok:      'oklch(0.70 0.13 155)',   // LAN / success — green
  info:    'oklch(0.68 0.13 240)',   // Relay — blue
  warn:    'oklch(0.78 0.14 75)',    // Pairing — amber
  danger:  'oklch(0.62 0.18 25)',    // Offline / error — red
};

// Spacing (4px base)
const TW_SPACE = [0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80];

// Radii
const TW_RADIUS = { xs: 6, sm: 10, md: 14, lg: 20, xl: 28, pill: 999 };

// Shadows — warm, subtle. Not drop-shadow-heavy.
const TW_SHADOW = {
  sm: '0 1px 2px rgba(26,23,19,0.05), 0 1px 1px rgba(26,23,19,0.03)',
  md: '0 2px 6px rgba(26,23,19,0.06), 0 8px 24px rgba(26,23,19,0.06)',
  lg: '0 4px 12px rgba(26,23,19,0.08), 0 20px 48px rgba(26,23,19,0.10)',
  dSm: '0 1px 2px rgba(0,0,0,0.4)',
  dMd: '0 2px 6px rgba(0,0,0,0.3), 0 8px 24px rgba(0,0,0,0.4)',
  dLg: '0 4px 12px rgba(0,0,0,0.3), 0 20px 48px rgba(0,0,0,0.5)',
};

// Typography scale — text-scalable, no fixed heights
const TW_TYPE = {
  display:  { size: 32, weight: 600, lh: 1.15, tracking: -0.5 },
  title1:   { size: 22, weight: 600, lh: 1.2,  tracking: -0.3 },
  title2:   { size: 18, weight: 600, lh: 1.3,  tracking: -0.2 },
  body:     { size: 15, weight: 400, lh: 1.45, tracking: 0 },
  bodyMed:  { size: 15, weight: 500, lh: 1.45, tracking: 0 },
  caption:  { size: 13, weight: 400, lh: 1.4,  tracking: 0 },
  micro:    { size: 11, weight: 500, lh: 1.3,  tracking: 0.4 },
  mono:     { size: 13, weight: 500, lh: 1.4,  tracking: 0 },
};

// Theme object — pass this around. Respond to dark/light.
function twTheme({ hue = 180, dark = false } = {}) {
  const pal = twBuildPalette(hue);
  const n = TW_NEUTRALS;
  return {
    hue,
    dark,
    fonts: TW_FONTS,
    type: TW_TYPE,
    space: TW_SPACE,
    radius: TW_RADIUS,
    shadow: TW_SHADOW,
    sem: TW_SEMANTIC,
    pal,
    // Resolved semantic slots (auto-swap on dark)
    bg:        dark ? n.dSurface0 : n.surface0,
    card:      dark ? n.dSurface1 : n.surface1,
    fill:      dark ? n.dSurface2 : n.surface2,
    hover:     dark ? n.dSurface3 : n.surface3,
    border:    dark ? n.dBorder   : n.border,
    borderHi:  dark ? n.dBorderHigh : n.borderHigh,
    ink:       dark ? n.dInk      : n.ink,
    ink2:      dark ? n.dInk2     : n.ink2,
    ink3:      dark ? n.dInk3     : n.ink3,
    ink4:      dark ? n.dInk4     : n.ink4,
    accent:    pal.accent500,
    accentHi:  pal.accent400,
    accentLo:  dark ? pal.accent800 : pal.accent100,
    accentText: dark ? pal.accent200 : pal.accent700,
    shadowSm:  dark ? TW_SHADOW.dSm : TW_SHADOW.sm,
    shadowMd:  dark ? TW_SHADOW.dMd : TW_SHADOW.md,
    shadowLg:  dark ? TW_SHADOW.dLg : TW_SHADOW.lg,
  };
}

Object.assign(window, { TW_FONTS, TW_HUES, TW_NEUTRALS, TW_SEMANTIC, TW_SPACE, TW_RADIUS, TW_SHADOW, TW_TYPE, twBuildPalette, twTheme });
