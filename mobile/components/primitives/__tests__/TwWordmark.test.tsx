import React from 'react';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwWordmark } from '../TwWordmark';

describe('TwWordmark brand contract', () => {
  test('composes one bare Seam mark with the product name', () => {
    render(<ThemeProvider><TwWordmark /></ThemeProvider>);

    expect(screen.getByTestId('twinotify-mark')).toBeTruthy();
    expect(screen.getByText('twinotify').props.allowFontScaling).toBe(false);
  });
});
