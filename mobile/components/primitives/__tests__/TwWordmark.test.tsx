import React from 'react';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwWordmark } from '../TwWordmark';

describe('TwWordmark brand contract', () => {
  test('uses one text node in one system color without an icon', () => {
    const { toJSON } = render(<ThemeProvider><TwWordmark /></ThemeProvider>);
    const tree = JSON.stringify(toJSON());

    expect(screen.getByText(/twin/).props.allowFontScaling).toBe(false);
    expect((tree.match(/Text/g) ?? []).length).toBe(1);
    expect(tree).not.toMatch(/TwLogo|Svg|accent/i);
  });
});
