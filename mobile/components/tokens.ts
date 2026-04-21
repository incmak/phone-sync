// Twinotify design tokens — React Native port of tokens.jsx
// oklch colors are pre-computed to hex via culori at module load time.

import { converter, formatHex } from 'culori';

export const TW_FONTS = {
  ui: 'Inter_400Regular',
  uiMedium: 'Inter_500Medium',
  uiSemi: 'Inter_600SemiBold',
  uiBold: 'Inter_700Bold',
  mono: 'JetBrainsMono_400Regular',
  monoMedium: 'JetBrainsMono_500Medium',
} as const;

export const TW_HUES = {
  mint: 180,
  indigo: 265,
  amber: 65,
  rose: 15,
} as const;

export type TwHue = keyof typeof TW_HUES;

// oklch → hex via culori (replaces browser-only CSS oklch strings)
const toRgb = converter('rgb');

const oklchToHex = (l: number, c: number, h: number): string => {
  const rgb = toRgb({ mode: 'oklch', l, c, h });
  return rgb ? (formatHex(rgb) ?? '#000000') : '#000000';
};

export function twBuildPalette(hue = 180) {
  return {
    accent50:  oklchToHex(0.97, 0.02, hue),
    accent100: oklchToHex(0.93, 0.04, hue),
    accent200: oklchToHex(0.86, 0.08, hue),
    accent300: oklchToHex(0.78, 0.11, hue),
    accent400: oklchToHex(0.70, 0.13, hue),
    accent500: oklchToHex(0.62, 0.14, hue),   // primary
    accent600: oklchToHex(0.54, 0.13, hue),
    accent700: oklchToHex(0.45, 0.11, hue),
    accent800: oklchToHex(0.36, 0.08, hue),
    accent900: oklchToHex(0.25, 0.05, hue),
  };
}

// Warm neutrals — not cold gray
export const TW_NEUTRALS = {
  // Light
  surface0:    '#f7f5f1',
  surface1:    '#ffffff',
  surface2:    '#f1efea',
  surface3:    '#e8e5df',
  border:      '#e2ded6',
  borderHigh:  '#cfcac0',
  ink:         '#1a1713',
  ink2:        '#3d3832',
  ink3:        '#6e685f',
  ink4:        '#9b968d',
  // Dark
  dSurface0:   '#141210',
  dSurface1:   '#1c1a17',
  dSurface2:   '#252320',
  dSurface3:   '#2f2c28',
  dBorder:     '#2e2a25',
  dBorderHigh: '#3f3a33',
  dInk:        '#f4f1eb',
  dInk2:       '#c8c3ba',
  dInk3:       '#8c867c',
  dInk4:       '#5a564e',
} as const;

// Semantic statuses — pre-computed hex (no browser oklch support needed)
export const TW_SEMANTIC = {
  ok:     oklchToHex(0.70, 0.13, 155),   // green
  info:   oklchToHex(0.68, 0.13, 240),   // blue
  warn:   oklchToHex(0.78, 0.14, 75),    // amber
  danger: oklchToHex(0.62, 0.18, 25),    // red
} as const;

// Spacing: 4px base grid
export const TW_SPACE = [0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80] as const;

export const TW_RADIUS = { xs: 6, sm: 10, md: 14, lg: 20, xl: 28, pill: 999 } as const;

// RN shadow: iOS uses shadowColor/Offset/Opacity/Radius; Android uses elevation
export const TW_SHADOW = {
  sm: {
    shadowColor: '#1a1713', shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.08, shadowRadius: 2, elevation: 1,
  },
  md: {
    shadowColor: '#1a1713', shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.10, shadowRadius: 8, elevation: 3,
  },
  lg: {
    shadowColor: '#1a1713', shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.14, shadowRadius: 16, elevation: 8,
  },
  dSm: {
    shadowColor: '#000', shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.4, shadowRadius: 2, elevation: 1,
  },
  dMd: {
    shadowColor: '#000', shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.45, shadowRadius: 8, elevation: 3,
  },
  dLg: {
    shadowColor: '#000', shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.5, shadowRadius: 16, elevation: 8,
  },
} as const;

/** Convert #rrggbb (6-char hex) + opacity 0..1 to a 'rgba(r,g,b,a)' string safe on all RN platforms. */
export function hexWithAlpha(hex: string, opacity: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.substring(0, 2), 16);
  const g = parseInt(h.substring(2, 4), 16);
  const b = parseInt(h.substring(4, 6), 16);
  const a = Math.max(0, Math.min(1, opacity));
  return `rgba(${r},${g},${b},${a})`;
}

// Typography: lineHeight in pixels (not multiplier) for RN
export const TW_TYPE = {
  display: { fontSize: 32, fontWeight: '600' as const, lineHeight: 37, letterSpacing: -0.5 },
  title1:  { fontSize: 22, fontWeight: '600' as const, lineHeight: 26, letterSpacing: -0.3 },
  title2:  { fontSize: 18, fontWeight: '600' as const, lineHeight: 23, letterSpacing: -0.2 },
  body:    { fontSize: 15, fontWeight: '400' as const, lineHeight: 22 },
  bodyMed: { fontSize: 15, fontWeight: '500' as const, lineHeight: 22 },
  caption: { fontSize: 13, fontWeight: '400' as const, lineHeight: 18 },
  micro:   { fontSize: 11, fontWeight: '500' as const, lineHeight: 14, letterSpacing: 0.4 },
  mono:    { fontSize: 13, fontWeight: '500' as const, lineHeight: 18 },
} as const;

export interface Theme {
  hue: number;
  dark: boolean;
  fonts: typeof TW_FONTS;
  type: typeof TW_TYPE;
  space: typeof TW_SPACE;
  radius: typeof TW_RADIUS;
  sem: typeof TW_SEMANTIC;
  pal: ReturnType<typeof twBuildPalette>;
  shadow: typeof TW_SHADOW;
  shadowSm: typeof TW_SHADOW.sm | typeof TW_SHADOW.dSm;
  shadowMd: typeof TW_SHADOW.md | typeof TW_SHADOW.dMd;
  shadowLg: typeof TW_SHADOW.lg | typeof TW_SHADOW.dLg;
  bg: string; card: string; fill: string; hover: string;
  border: string; borderHi: string;
  ink: string; ink2: string; ink3: string; ink4: string;
  accent: string; accentHi: string; accentLo: string; accentText: string;
}

export function twTheme({ hue = 180, dark = false } = {}): Theme {
  const pal = twBuildPalette(hue);
  const n = TW_NEUTRALS;
  return {
    hue, dark,
    fonts: TW_FONTS,
    type: TW_TYPE,
    space: TW_SPACE,
    radius: TW_RADIUS,
    sem: TW_SEMANTIC,
    pal,
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
    shadow:    TW_SHADOW,
    shadowSm:  dark ? TW_SHADOW.dSm : TW_SHADOW.sm,
    shadowMd:  dark ? TW_SHADOW.dMd : TW_SHADOW.md,
    shadowLg:  dark ? TW_SHADOW.dLg : TW_SHADOW.lg,
  };
}
