import React from 'react';
import { StyleSheet } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { twTheme } from '../../tokens';
import { resolveButtonColors, TwButton, type TwButtonVariant } from '../TwButton';

function renderButton(element: React.ReactElement) {
  return render(<ThemeProvider>{element}</ThemeProvider>);
}

function luminance(hex: string): number {
  const values = hex.slice(1).match(/.{2}/g)?.map((part) => parseInt(part, 16) / 255) ?? [];
  const linear = values.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (lighter + 0.05) / (darker + 0.05);
}

describe('TwButton accessibility contract', () => {
  test('never moves on press', () => {
    renderButton(<TwButton>Continue</TwButton>);
    const button = screen.getByRole('button', { name: 'Continue' });
    const restingBefore = StyleSheet.flatten(button.props.style);
    fireEvent(button, 'pressIn');
    const pressed = StyleSheet.flatten(screen.getByRole('button', { name: 'Continue' }).props.style);
    fireEvent(button, 'pressOut');
    const resting = StyleSheet.flatten(screen.getByRole('button', { name: 'Continue' }).props.style);
    expect(restingBefore.backgroundColor).toBe(twTheme({ dark: false }).ink);
    expect(pressed.backgroundColor).toBe(twTheme({ dark: false }).borderHi);
    expect(resting.backgroundColor).toBe(twTheme({ dark: false }).ink);
    expect(JSON.stringify(pressed)).not.toMatch(/transform|scale|translate/i);
    expect(JSON.stringify(resting)).not.toMatch(/transform|scale|translate/i);
  });

  test.each([false, true])('keeps every pressed variant label readable when dark=%s', (dark) => {
    const theme = twTheme({ dark });
    const variants: TwButtonVariant[] = ['primary', 'accent', 'secondary', 'ghost', 'destructive'];

    for (const variant of variants) {
      const colors = resolveButtonColors(theme, variant);
      expect(colors.pressedBackgroundColor).not.toBe(colors.backgroundColor);
      expect(contrast(colors.textColor, colors.pressedBackgroundColor)).toBeGreaterThanOrEqual(4.5);
    }
  });

  test('infers a button name and keeps the small target at least 48dp', () => {
    renderButton(<TwButton size="sm">Continue</TwButton>);

    const button = screen.getByRole('button', { name: 'Continue' });
    const style = StyleSheet.flatten(button.props.style);
    expect(style.minHeight ?? style.height).toBeGreaterThanOrEqual(48);
    const label = screen.getByText('Continue');
    const labelStyle = StyleSheet.flatten(label.props.style);
    expect(label.props.allowFontScaling).not.toBe(false);
    expect(labelStyle.lineHeight).toBeGreaterThan(labelStyle.fontSize);
  });

  test('keeps an explicit accessible name and exposes loading as disabled and busy', () => {
    renderButton(
      <TwButton loading accessibilityLabel="Saving pairing settings">
        Save
      </TwButton>,
    );

    const button = screen.getByRole('button', { name: 'Saving pairing settings' });
    expect(button.props.accessibilityState).toEqual({ disabled: true, busy: true });
  });

  test('reports disabled without reporting busy when no work is running', () => {
    renderButton(<TwButton disabled>Continue</TwButton>);

    const button = screen.getByRole('button', { name: 'Continue' });
    expect(button.props.accessibilityState).toEqual({ disabled: true, busy: false });
  });
});
