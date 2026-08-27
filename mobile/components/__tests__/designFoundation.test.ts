import fs from 'node:fs';
import path from 'node:path';

import { TW_FONTS, twTheme } from '../tokens';

const LIGHT = {
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

const DARK = {
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

const SEMANTIC = {
  light: {
    ok: { foreground: '#235C3C', surface: '#D2E4D6' },
    info: { foreground: '#205D78', surface: '#D0E2E9' },
    warn: { foreground: '#72520B', surface: '#E7DDC1' },
    danger: { foreground: '#963B38', surface: '#EBD6D3' },
  },
  dark: {
    ok: { foreground: '#79C894', surface: '#1B2A20' },
    info: { foreground: '#74BDE7', surface: '#172731' },
    warn: { foreground: '#F2C56C', surface: '#2A2418' },
    danger: { foreground: '#F07B76', surface: '#2E1D1C' },
  },
} as const;

describe('Handoff Trace design foundation', () => {
  test('uses the exact fixed mineral and verdigris tokens in both modes', () => {
    expect(twTheme({ dark: false })).toMatchObject(LIGHT);
    expect(twTheme({ dark: true })).toMatchObject(DARK);
  });

  test('selects the exact foreground and surface pair for every semantic role', () => {
    expect(twTheme({ dark: false }).sem).toEqual(SEMANTIC.light);
    expect(twTheme({ dark: true }).sem).toEqual(SEMANTIC.dark);
  });

  test('uses Android-native families for every font role', () => {
    expect(TW_FONTS).toEqual({
      ui: 'sans-serif',
      uiMedium: 'sans-serif-medium',
      uiSemi: 'sans-serif-medium',
      uiBold: 'sans-serif',
      display: 'sans-serif-condensed',
      mono: 'monospace',
      monoMedium: 'monospace',
    });
  });

  test('pairs every uiBold family use with an explicit 700 weight', () => {
    const mobileRoot = path.resolve(__dirname, '../..');
    const roots = [path.join(mobileRoot, 'app'), path.join(mobileRoot, 'components')];
    const sourceFiles: string[] = [];
    const visit = (directory: string) => {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const absolutePath = path.join(directory, entry.name);
        if (entry.isDirectory()) {
          if (entry.name !== '__tests__') visit(absolutePath);
        } else if (/\.tsx?$/.test(entry.name)) {
          sourceFiles.push(absolutePath);
        }
      }
    };
    roots.forEach(visit);

    for (const sourceFile of sourceFiles) {
      const source = fs.readFileSync(sourceFile, 'utf8');
      const familyUses = source.match(/fontFamily:\s*theme\.fonts\.uiBold/g) ?? [];
      const explicitUses = source.match(
        /fontFamily:\s*theme\.fonts\.uiBold,\s*fontWeight:\s*'700'/g,
      ) ?? [];
      expect({ sourceFile: path.relative(mobileRoot, sourceFile), explicitUses: explicitUses.length })
        .toEqual({ sourceFile: path.relative(mobileRoot, sourceFile), explicitUses: familyUses.length });
    }
  });

  test('does not ship runtime palette or downloaded font dependencies', () => {
    const mobileRoot = path.resolve(__dirname, '../..');
    const packageJson = fs.readFileSync(path.join(mobileRoot, 'package.json'), 'utf8');
    const packageLock = fs.readFileSync(path.join(mobileRoot, 'package-lock.json'), 'utf8');
    const googleFontScope = ['@expo', 'google-fonts'].join('-');
    const forbidden = [
      `${googleFontScope}/inter`,
      `${googleFontScope}/jetbrains-mono`,
      ['cu', 'lori'].join(''),
    ];

    for (const dependency of forbidden) {
      expect(packageJson).not.toContain(dependency);
      expect(packageLock).not.toContain(dependency);
    }
  });

  test('renders immediately without a font or persisted-theme startup gate', () => {
    const mobileRoot = path.resolve(__dirname, '../..');
    const rootLayout = fs.readFileSync(path.join(mobileRoot, 'app/_layout.tsx'), 'utf8');
    const themeProvider = fs.readFileSync(path.join(mobileRoot, 'components/Theme.tsx'), 'utf8');

    expect(fs.existsSync(path.join(mobileRoot, 'components/useFonts.ts'))).toBe(false);
    const fontHookName = ['useApp', 'Fonts'].join('');
    expect(rootLayout).not.toContain(fontHookName);
    expect(rootLayout).not.toMatch(/fontsLoaded|return null/);
    expect(themeProvider).not.toMatch(/AsyncStorage|darkOverride|setHue|STORAGE_KEY|return null/);
  });

  test('keeps every semantic consumer on explicit foreground and surface pairs', () => {
    const mobileRoot = path.resolve(__dirname, '../..');
    const consumers = [
      'app/onboarding/perms.tsx',
      'app/onboarding/relay.tsx',
      'app/pair/fail.tsx',
      'app/pair/fingerprint.tsx',
      'components/primitives/TwAppChip.tsx',
      'components/primitives/TwBanner.tsx',
      'components/primitives/TwButton.tsx',
      'components/primitives/TwCard.tsx',
      'components/primitives/TwStatusDot.tsx',
    ];

    for (const relativePath of consumers) {
      const source = fs.readFileSync(path.join(mobileRoot, relativePath), 'utf8');
      expect(source).not.toMatch(/import\s+\{[^}]*TW_SEMANTIC/);
      expect(source).not.toMatch(/theme\.sem\.(?:ok|info|warn|danger)(?!\.(?:foreground|surface))/);
    }
  });
});
