import { PlatformColor, type ColorValue } from 'react-native';

// Twinotify's Material 3 foundation. Android's Monet palette is consumed only
// through semantic roles; the fixed Seam schemes are the atomic fallback.

export const TW_FONTS = {
  ui: 'sans-serif', uiMedium: 'sans-serif-medium', uiSemi: 'sans-serif-medium',
  uiBold: 'sans-serif', display: 'sans-serif-condensed', mono: 'monospace', monoMedium: 'monospace',
} as const;

export const MATERIAL_COLOR_ROLE_KEYS = [
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'surface', 'surfaceDim', 'surfaceBright', 'surfaceContainerLowest',
  'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest',
  'onSurface', 'onSurfaceVariant', 'outline', 'outlineVariant',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'inverseSurface', 'inverseOnSurface', 'inversePrimary', 'scrim',
] as const;

export type MaterialColorRole = typeof MATERIAL_COLOR_ROLE_KEYS[number];
export type MaterialColorScheme = Record<MaterialColorRole, ColorValue>;

const SEAM_LIGHT: MaterialColorScheme = {
  primary: '#1F685A', onPrimary: '#FFFFFF', primaryContainer: '#CADBD3', onPrimaryContainer: '#0A372E',
  secondary: '#52645A', onSecondary: '#FFFFFF', secondaryContainer: '#D4DED8', onSecondaryContainer: '#17201C',
  tertiary: '#515F48', onTertiary: '#FFFFFF', tertiaryContainer: '#D5E1C9', onTertiaryContainer: '#162011',
  surface: '#E3E9E5', surfaceDim: '#CBD3CE', surfaceBright: '#F5FAF6', surfaceContainerLowest: '#FFFFFF',
  surfaceContainerLow: '#EDF2EE', surfaceContainer: '#DCE4DF', surfaceContainerHigh: '#D4DED8', surfaceContainerHighest: '#C6D2CB',
  onSurface: '#17201C', onSurfaceVariant: '#34423B', outline: '#6E8176', outlineVariant: '#B7C5BD',
  error: '#963B38', onError: '#FFFFFF', errorContainer: '#EBD6D3', onErrorContainer: '#551713',
  inverseSurface: '#2B322E', inverseOnSurface: '#EEF3EF', inversePrimary: '#9BCDBD', scrim: '#000000',
};

const SEAM_DARK: MaterialColorScheme = {
  primary: '#9BBEAE', onPrimary: '#10382E', primaryContainer: '#263D34', onPrimaryContainer: '#B7DBCD',
  secondary: '#B6C8BE', onSecondary: '#22352C', secondaryContainer: '#344A40', onSecondaryContainer: '#D3E5DA',
  tertiary: '#BBCBAE', onTertiary: '#27351F', tertiaryContainer: '#3D4C34', onTertiaryContainer: '#D7E7CA',
  surface: '#111815', surfaceDim: '#111815', surfaceBright: '#35403A', surfaceContainerLowest: '#0C110F',
  surfaceContainerLow: '#17201C', surfaceContainer: '#1E2924', surfaceContainerHigh: '#27352F', surfaceContainerHighest: '#314039',
  onSurface: '#EEF3EF', onSurfaceVariant: '#C9D2CD', outline: '#789085', outlineVariant: '#405249',
  error: '#FFB4AB', onError: '#690005', errorContainer: '#690005', onErrorContainer: '#FFDAD6',
  inverseSurface: '#E0E5E1', inverseOnSurface: '#2B322E', inversePrimary: '#1F685A', scrim: '#000000',
};

export function fixedSeamScheme(dark: boolean): MaterialColorScheme {
  return dark ? SEAM_DARK : SEAM_LIGHT;
}

export function resolveMaterialScheme({ dark, dynamic }: { dark: boolean; dynamic?: Partial<MaterialColorScheme> }): MaterialColorScheme {
  const complete = MATERIAL_COLOR_ROLE_KEYS.every((role) => dynamic?.[role] !== undefined);
  return complete ? dynamic as MaterialColorScheme : fixedSeamScheme(dark);
}

/** Android 12+ system tonal resources. Twinotify's current min SDK is 34. */
export function androidMonetScheme(dark: boolean): MaterialColorScheme {
  const color = (name: string) => PlatformColor(`@android:color/${name}`);
  return dark ? {
    primary: color('system_accent1_200'), onPrimary: color('system_accent1_800'), primaryContainer: color('system_accent1_700'), onPrimaryContainer: color('system_accent1_100'),
    secondary: color('system_accent2_200'), onSecondary: color('system_accent2_800'), secondaryContainer: color('system_accent2_700'), onSecondaryContainer: color('system_accent2_100'),
    tertiary: color('system_accent3_200'), onTertiary: color('system_accent3_800'), tertiaryContainer: color('system_accent3_700'), onTertiaryContainer: color('system_accent3_100'),
    surface: color('system_neutral1_900'), surfaceDim: color('system_neutral1_900'), surfaceBright: color('system_neutral1_700'), surfaceContainerLowest: color('system_neutral1_1000'),
    surfaceContainerLow: color('system_neutral1_900'), surfaceContainer: color('system_neutral1_800'), surfaceContainerHigh: color('system_neutral1_700'), surfaceContainerHighest: color('system_neutral1_600'),
    onSurface: color('system_neutral1_100'), onSurfaceVariant: color('system_neutral2_200'), outline: color('system_neutral2_400'), outlineVariant: color('system_neutral2_700'),
    error: '#F2B8B5', onError: '#601410', errorContainer: '#8C1D18', onErrorContainer: '#F9DEDC',
    inverseSurface: color('system_neutral1_100'), inverseOnSurface: color('system_neutral1_800'), inversePrimary: color('system_accent1_600'), scrim: '#000000',
  } : {
    primary: color('system_accent1_600'), onPrimary: color('system_accent1_0'), primaryContainer: color('system_accent1_100'), onPrimaryContainer: color('system_accent1_900'),
    secondary: color('system_accent2_600'), onSecondary: color('system_accent2_0'), secondaryContainer: color('system_accent2_100'), onSecondaryContainer: color('system_accent2_900'),
    tertiary: color('system_accent3_600'), onTertiary: color('system_accent3_0'), tertiaryContainer: color('system_accent3_100'), onTertiaryContainer: color('system_accent3_900'),
    surface: color('system_neutral1_10'), surfaceDim: color('system_neutral1_100'), surfaceBright: color('system_neutral1_0'), surfaceContainerLowest: color('system_neutral1_0'),
    surfaceContainerLow: color('system_neutral1_10'), surfaceContainer: color('system_neutral1_50'), surfaceContainerHigh: color('system_neutral1_100'), surfaceContainerHighest: color('system_neutral1_200'),
    onSurface: color('system_neutral1_900'), onSurfaceVariant: color('system_neutral2_700'), outline: color('system_neutral2_500'), outlineVariant: color('system_neutral2_200'),
    error: '#B3261E', onError: '#FFFFFF', errorContainer: '#F9DEDC', onErrorContainer: '#410E0B',
    inverseSurface: color('system_neutral1_800'), inverseOnSurface: color('system_neutral1_50'), inversePrimary: color('system_accent1_200'), scrim: '#000000',
  };
}

export interface SemanticDisplayPair { foreground: string; surface: string }
export type SemanticRole = 'ok' | 'info' | 'warn' | 'danger';
export type SemanticDisplay = Record<SemanticRole, SemanticDisplayPair>;
const TW_SEMANTIC_LIGHT: SemanticDisplay = {
  ok: { foreground: '#235C3C', surface: '#D2E4D6' }, info: { foreground: '#205D78', surface: '#D0E2E9' },
  warn: { foreground: '#72520B', surface: '#E7DDC1' }, danger: { foreground: '#963B38', surface: '#EBD6D3' },
};
const TW_SEMANTIC_DARK: SemanticDisplay = {
  ok: { foreground: '#79C894', surface: '#1B2A20' }, info: { foreground: '#74BDE7', surface: '#172731' },
  warn: { foreground: '#F2C56C', surface: '#2A2418' }, danger: { foreground: '#F07B76', surface: '#2E1D1C' },
};

export const TW_SPACE = [0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80] as const;
export const TW_RADIUS = { xs: 6, sm: 10, md: 14, lg: 20, xl: 28, pill: 999 } as const;
export const TW_TYPE = {
  display: { fontSize: 32, fontWeight: '600' as const, lineHeight: 37, letterSpacing: -0.5 },
  title1: { fontSize: 22, fontWeight: '600' as const, lineHeight: 26, letterSpacing: -0.3 },
  title2: { fontSize: 18, fontWeight: '600' as const, lineHeight: 23, letterSpacing: -0.2 },
  body: { fontSize: 15, fontWeight: '400' as const, lineHeight: 22 }, bodyMed: { fontSize: 15, fontWeight: '500' as const, lineHeight: 22 },
  caption: { fontSize: 13, fontWeight: '400' as const, lineHeight: 18 }, micro: { fontSize: 11, fontWeight: '500' as const, lineHeight: 14, letterSpacing: 0.4 },
  mono: { fontSize: 13, fontWeight: '500' as const, lineHeight: 18 },
} as const;

export interface Theme {
  dark: boolean; colors: MaterialColorScheme; fonts: typeof TW_FONTS; type: typeof TW_TYPE; space: typeof TW_SPACE; radius: typeof TW_RADIUS; sem: SemanticDisplay;
  bg: string; card: string; fill: string; hover: string; border: string; borderHi: string; switchOff: string;
  ink: string; ink2: string; ink3: string; ink4: string; accent: string; accentHi: string; accentLo: string; accentText: string;
}

export function twTheme({ dark = false, colors }: { dark?: boolean; colors?: MaterialColorScheme } = {}): Theme {
  const scheme = colors ?? fixedSeamScheme(dark);
  const legacy = dark ? {
    bg: '#111815', card: '#17201C', fill: '#1E2924', hover: '#27352F', border: '#60766B', borderHi: '#789085',
    ink: '#EEF3EF', ink2: '#C9D2CD', ink3: '#98A69E', ink4: '#6F7D75', accent: '#7FAE9F', accentHi: '#9BBEAE',
    accentLo: '#263D34', accentText: '#A9CCBE', switchOff: '#98A69E',
  } : {
    bg: '#E3E9E5', card: '#EDF2EE', fill: '#D4DED8', hover: '#C6D2CB', border: '#6E8176', borderHi: '#52645A',
    ink: '#17201C', ink2: '#34423B', ink3: '#52625A', ink4: '#75837C', accent: '#1F685A', accentHi: '#3B7C6E',
    accentLo: '#CADBD3', accentText: '#145446', switchOff: '#52625A',
  };
  return {
    dark, colors: scheme, fonts: TW_FONTS, type: TW_TYPE, space: TW_SPACE, radius: TW_RADIUS, sem: dark ? TW_SEMANTIC_DARK : TW_SEMANTIC_LIGHT,
    ...legacy,
  };
}
