import React from 'react';
import { Text } from 'react-native';
import { render } from '@testing-library/react-native';
import { ThemeProvider } from '../../Theme';
import { TwFingerprint } from '../TwFingerprint';

test('preserves all 64 fingerprint digits when the native value includes separators', () => {
  const raw = '0123456789abcdef'.repeat(4);
  const native = raw.match(/.{4}/g)!.join('-');
  const screen = render(<ThemeProvider><TwFingerprint hex={native} /></ThemeProvider>);
  const shown = screen.UNSAFE_getAllByType(Text).map(node => node.props.children).join('');
  expect(shown).toBe(raw.toUpperCase());
});

test('does not invent or truncate a fingerprint from malformed data', () => {
  for (const value of [undefined, 'abc', 'z'.repeat(64), 'a'.repeat(65)]) {
    const screen = render(<ThemeProvider><TwFingerprint hex={value} /></ThemeProvider>);
    expect(screen.getByText('Fingerprint unavailable')).toBeTruthy();
    screen.unmount();
  }
});
