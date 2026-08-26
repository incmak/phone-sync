import React from 'react';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwWordmark } from '../TwWordmark';

describe('TwWordmark font scaling', () => {
  test('keeps the brand mark on one complete line at enlarged system fonts', () => {
    render(<ThemeProvider><TwWordmark /></ThemeProvider>);

    expect(screen.getByText(/twin/).props.allowFontScaling).toBe(false);
  });
});
