import fs from 'node:fs';
import path from 'node:path';
import React from 'react';
import { StyleSheet } from 'react-native';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { twTheme } from '../../tokens';
import { TwSwitch } from '../TwSwitch';

let mockThemeDark = false;
jest.mock('../../Theme', () => ({
  ThemeProvider: ({ children }: { children: React.ReactNode }) => children,
  useTheme: () => jest.requireActual('../../tokens').twTheme({ dark: mockThemeDark }),
}));

type RenderNode = {
  children?: RenderNode[];
  props: { style: unknown };
};

function renderedThumbColor(scheme: 'light' | 'dark') {
  mockThemeDark = scheme === 'dark';
  const tree = render(<ThemeProvider><TwSwitch checked accessibilityLabel="Enable mirroring" /></ThemeProvider>).toJSON() as unknown as RenderNode;
  const thumb = tree.children?.[0]?.children?.[0];
  if (!thumb) throw new Error('switch thumb did not render');
  return (StyleSheet.flatten(thumb.props.style) as Record<string, unknown>).backgroundColor;
}

describe('TwSwitch accessibility and target contract', () => {
  test('uses the Material 3 switch geometry inside a 48dp target', () => {
    render(<ThemeProvider><TwSwitch checked accessibilityLabel="Enable mirroring" /></ThemeProvider>);

    const control = screen.getByRole('switch', { name: 'Enable mirroring' });
    const targetStyle = StyleSheet.flatten(control.props.style);
    const trackStyle = StyleSheet.flatten(screen.getByTestId('tw-switch-track').props.style);
    expect(targetStyle.minWidth ?? targetStyle.width).toBeGreaterThanOrEqual(48);
    expect(targetStyle.minHeight ?? targetStyle.height).toBeGreaterThanOrEqual(48);
    expect(trackStyle.width).toBe(52);
    expect(trackStyle.height).toBe(32);
  });

  test('uses a physical 44dp pressable switch frame', () => {
    render(<ThemeProvider><TwSwitch checked accessibilityLabel="Enable mirroring" /></ThemeProvider>);

    const control = screen.getByRole('switch', { name: 'Enable mirroring' });
    const style = StyleSheet.flatten(control.props.style);
    expect(style.minWidth ?? style.width).toBeGreaterThanOrEqual(48);
    expect(style.minHeight ?? style.height).toBeGreaterThanOrEqual(48);
  });

  test('renders a theme-owned thumb in both light and dark modes without raw color literals', () => {
    expect(renderedThumbColor('light')).toBe(twTheme({ dark: false }).colors.onPrimary);
    expect(renderedThumbColor('dark')).toBe(twTheme({ dark: true }).colors.onPrimary);
    const source = fs.readFileSync(path.join(__dirname, '..', 'TwSwitch.tsx'), 'utf8');
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });
});
