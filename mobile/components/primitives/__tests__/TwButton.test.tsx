import React from 'react';
import { StyleSheet } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { twTheme } from '../../tokens';
import { TwButton } from '../TwButton';

function renderButton(element: React.ReactElement) {
  return render(<ThemeProvider>{element}</ThemeProvider>);
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
    expect(pressed.backgroundColor).toBe(twTheme({ dark: false }).hover);
    expect(resting.backgroundColor).toBe(twTheme({ dark: false }).ink);
    expect(JSON.stringify(pressed)).not.toMatch(/transform|scale|translate/i);
    expect(JSON.stringify(resting)).not.toMatch(/transform|scale|translate/i);
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
