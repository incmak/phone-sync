import React from 'react';
import { StyleSheet } from 'react-native';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwButton } from '../TwButton';

function renderButton(element: React.ReactElement) {
  return render(<ThemeProvider>{element}</ThemeProvider>);
}

describe('TwButton accessibility contract', () => {
  test('infers a button name and keeps the small target at least 48dp', () => {
    renderButton(<TwButton size="sm">Continue</TwButton>);

    const button = screen.getByRole('button', { name: 'Continue' });
    const style = StyleSheet.flatten(button.props.style);
    expect(style.minHeight ?? style.height).toBeGreaterThanOrEqual(48);
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
