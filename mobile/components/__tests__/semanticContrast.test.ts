import fs from 'node:fs';

import { resolveButtonColors, type TwButtonVariant } from '../primitives/TwButton';
import { type SemanticRole, twTheme } from '../tokens';

function luminance(hex: string): number {
  const values = hex.slice(1).match(/.{2}/g)?.map((part) => parseInt(part, 16) / 255) ?? [];
  const linear = values.map((value) => value <= 0.04045
    ? value / 12.92
    : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (lighter + 0.05) / (darker + 0.05);
}

const ROLES: readonly SemanticRole[] = ['ok', 'info', 'warn', 'danger'];
const BASE_SURFACES = ['bg', 'card', 'fill'] as const;
const EXPECTED = {
  light: {
    ok: [5.93, 6.39, 6.95, 5.71],
    info: [5.43, 5.88, 6.40, 5.26],
    warn: [5.30, 5.83, 6.33, 5.21],
    danger: [5.07, 5.72, 6.22, 5.11],
  },
  dark: {
    ok: [7.51, 9.01, 8.34, 7.51],
    info: [7.42, 8.73, 8.08, 7.28],
    warn: [9.52, 11.14, 10.30, 9.28],
    danger: [5.96, 6.69, 6.19, 5.58],
  },
} as const;

type Check = {
  foreground: string;
  background: string;
  foregroundHex: string;
  backgroundHex: string;
  kind: 'text' | 'graphic';
  ratio: number;
  threshold: number;
  pass: boolean;
};

function rounded(value: number, places: number): number {
  return Number(value.toFixed(places));
}

function buildChecks(dark: boolean): Check[] {
  const theme = twTheme({ dark });
  const checks: Check[] = [];
  const add = (
    foreground: string,
    background: string,
    foregroundHex: string,
    backgroundHex: string,
    kind: Check['kind'],
    threshold: number,
  ) => {
    const ratio = rounded(contrast(foregroundHex, backgroundHex), 4);
    checks.push({
      foreground,
      background,
      foregroundHex,
      backgroundHex,
      kind,
      ratio,
      threshold,
      pass: ratio >= threshold,
    });
  };

  add('accentText', 'bg', theme.accentText, theme.bg, 'text', 7);
  add('ink3', 'bg', theme.ink3, theme.bg, 'text', 4.5);
  for (const variant of [
    'primary',
    'accent',
    'secondary',
    'ghost',
    'destructive',
  ] as const satisfies readonly TwButtonVariant[]) {
    const colors = resolveButtonColors(theme, variant);
    if (colors.backgroundColor === 'transparent') {
      for (const surface of BASE_SURFACES) {
        add(
          `button.${variant}.text`,
          `button.${variant}.fill.${surface}`,
          String(colors.textColor),
          theme[surface],
          'text',
          4.5,
        );
      }
    } else {
      add(
        `button.${variant}.text`,
        `button.${variant}.fill`,
        String(colors.textColor),
        String(colors.backgroundColor),
        'text',
        4.5,
      );
    }
  }

  for (const boundary of ['border', 'borderHi', 'switchOff'] as const) {
    for (const surface of BASE_SURFACES) {
      add(
        boundary,
        surface,
        theme[boundary],
        theme[surface],
        'graphic',
        boundary === 'borderHi' ? 4.38 : 3,
      );
    }
  }

  for (const role of ROLES) {
    const pair = theme.sem[role];
    add(`${role}.foreground`, `${role}.surface`, pair.foreground, pair.surface, 'text', 4.5);
    for (const surface of BASE_SURFACES) {
      add(`${role}.foreground`, surface, pair.foreground, theme[surface], 'text', 4.5);
    }
    for (const text of ['ink', 'ink2'] as const) {
      add(text, `${role}.surface`, theme[text], pair.surface, 'text', 4.5);
    }
  }

  return checks.sort((a, b) =>
    a.foreground.localeCompare(b.foreground) || a.background.localeCompare(b.background));
}

describe('semantic display contrast', () => {
  test.each([false, true])('passes every required text and graphic pair when dark=%s', (dark) => {
    expect(buildChecks(dark).filter((check) => !check.pass)).toEqual([]);
  });

  test.each([false, true])('enforces the approved 7:1 accent text floor when dark=%s', (dark) => {
    const accentCheck = buildChecks(dark).find(
      (check) => check.foreground === 'accentText' && check.background === 'bg',
    );
    expect(accentCheck).toMatchObject({ kind: 'text', threshold: 7, pass: true });
  });

  test.each([false, true])('matches the approved semantic ratios when dark=%s', (dark) => {
    const theme = twTheme({ dark });
    const expected = EXPECTED[dark ? 'dark' : 'light'];

    for (const role of ROLES) {
      const pair = theme.sem[role];
      const ratios = [
        contrast(pair.foreground, pair.surface),
        contrast(pair.foreground, theme.bg),
        contrast(pair.foreground, theme.card),
        contrast(pair.foreground, theme.fill),
      ].map((ratio) => rounded(ratio, 2));
      expect(ratios).toEqual(expected[role]);
    }
  });

  test('writes deterministic machine-readable evidence when requested', () => {
    const outputPath = process.env.HANDOFF_CONTRAST_EVIDENCE;
    if (!outputPath) return;

    const modes = [false, true].map((dark) => {
      const theme = twTheme({ dark });
      return {
        mode: dark ? 'dark' : 'light',
        tokens: {
          bg: theme.bg,
          card: theme.card,
          fill: theme.fill,
          hover: theme.hover,
          border: theme.border,
          borderHi: theme.borderHi,
          ink: theme.ink,
          ink2: theme.ink2,
          ink3: theme.ink3,
          ink4: theme.ink4,
          accent: theme.accent,
          accentHi: theme.accentHi,
          accentLo: theme.accentLo,
          accentText: theme.accentText,
          switchOff: theme.switchOff,
          sem: theme.sem,
        },
        checks: buildChecks(dark),
      };
    });

    fs.writeFileSync(outputPath, `${JSON.stringify({
      schema: 'twinotify.handoff-contrast.v1',
      modes,
    }, null, 2)}\n`);
  });
});
