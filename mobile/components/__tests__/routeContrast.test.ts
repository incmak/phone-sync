import { twTheme } from '../tokens';

function luminance(hex: string): number {
  const values = hex.slice(1).match(/.{2}/g)?.map((part) => parseInt(part, 16) / 255) ?? [];
  const linear = values.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (lighter + 0.05) / (darker + 0.05);
}

describe('route control contrast', () => {
  test.each([false, true])('keeps small accent text readable when dark=%s', (dark) => {
    const theme = twTheme({ hue: 180, dark });
    expect(contrast(theme.accentText, theme.bg)).toBeGreaterThanOrEqual(4.5);
  });

  test.each([false, true])('keeps the off switch track distinct when dark=%s', (dark) => {
    const theme = twTheme({ hue: 180, dark });
    expect(contrast(theme.switchOff, theme.bg)).toBeGreaterThanOrEqual(3);
  });
});
