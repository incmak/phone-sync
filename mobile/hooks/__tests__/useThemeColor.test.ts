import { resolveColorScheme } from '../resolve-color-scheme';

describe('resolveColorScheme', () => {
  it('preserves dark mode and treats unavailable or unspecified schemes as light', () => {
    expect(resolveColorScheme('dark')).toBe('dark');
    expect(resolveColorScheme('light')).toBe('light');
    expect(resolveColorScheme('unspecified')).toBe('light');
    expect(resolveColorScheme(null)).toBe('light');
  });
});
