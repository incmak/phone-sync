// Twinotify's fixed Handoff Trace foundation. All colors are literal sRGB
// values so the native UI has no runtime palette dependency or hue drift.

export const TW_FONTS = {
  ui: 'sans-serif',
  uiMedium: 'sans-serif-medium',
  uiSemi: 'sans-serif-medium',
  uiBold: 'sans-serif',
  display: 'sans-serif-condensed',
  mono: 'monospace',
  monoMedium: 'monospace',
} as const;

const TW_LIGHT = {
  bg: '#E3E9E5',
  card: '#EDF2EE',
  fill: '#D4DED8',
  hover: '#C6D2CB',
  border: '#6E8176',
  borderHi: '#52645A',
  ink: '#17201C',
  ink2: '#34423B',
  ink3: '#52625A',
  ink4: '#75837C',
  accent: '#1F685A',
  accentHi: '#3B7C6E',
  accentLo: '#CADBD3',
  accentText: '#145446',
  switchOff: '#52625A',
} as const;

const TW_DARK = {
  bg: '#111815',
  card: '#17201C',
  fill: '#1E2924',
  hover: '#27352F',
  border: '#60766B',
  borderHi: '#789085',
  ink: '#EEF3EF',
  ink2: '#C9D2CD',
  ink3: '#98A69E',
  ink4: '#6F7D75',
  accent: '#7FAE9F',
  accentHi: '#9BBEAE',
  accentLo: '#263D34',
  accentText: '#A9CCBE',
  switchOff: '#98A69E',
} as const;

export interface SemanticDisplayPair {
  foreground: string;
  surface: string;
}

export type SemanticRole = 'ok' | 'info' | 'warn' | 'danger';
export type SemanticDisplay = Record<SemanticRole, SemanticDisplayPair>;

const TW_SEMANTIC_LIGHT: SemanticDisplay = {
  ok: { foreground: '#235C3C', surface: '#D2E4D6' },
  info: { foreground: '#205D78', surface: '#D0E2E9' },
  warn: { foreground: '#72520B', surface: '#E7DDC1' },
  danger: { foreground: '#963B38', surface: '#EBD6D3' },
};

const TW_SEMANTIC_DARK: SemanticDisplay = {
  ok: { foreground: '#79C894', surface: '#1B2A20' },
  info: { foreground: '#74BDE7', surface: '#172731' },
  warn: { foreground: '#F2C56C', surface: '#2A2418' },
  danger: { foreground: '#F07B76', surface: '#2E1D1C' },
};

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
  dark: boolean;
  fonts: typeof TW_FONTS;
  type: typeof TW_TYPE;
  space: typeof TW_SPACE;
  radius: typeof TW_RADIUS;
  sem: SemanticDisplay;
  shadow: typeof TW_SHADOW;
  shadowSm: typeof TW_SHADOW.sm | typeof TW_SHADOW.dSm;
  shadowMd: typeof TW_SHADOW.md | typeof TW_SHADOW.dMd;
  shadowLg: typeof TW_SHADOW.lg | typeof TW_SHADOW.dLg;
  bg: string; card: string; fill: string; hover: string;
  border: string; borderHi: string;
  switchOff: string;
  ink: string; ink2: string; ink3: string; ink4: string;
  accent: string; accentHi: string; accentLo: string; accentText: string;
}

export function twTheme({ dark = false }: { dark?: boolean } = {}): Theme {
  const colors = dark ? TW_DARK : TW_LIGHT;
  return {
    dark,
    fonts: TW_FONTS,
    type: TW_TYPE,
    space: TW_SPACE,
    radius: TW_RADIUS,
    sem: dark ? TW_SEMANTIC_DARK : TW_SEMANTIC_LIGHT,
    ...colors,
    shadow:    TW_SHADOW,
    shadowSm:  dark ? TW_SHADOW.dSm : TW_SHADOW.sm,
    shadowMd:  dark ? TW_SHADOW.dMd : TW_SHADOW.md,
    shadowLg:  dark ? TW_SHADOW.dLg : TW_SHADOW.lg,
  };
}
