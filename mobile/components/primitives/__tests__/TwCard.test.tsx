import React from 'react';
import { StyleSheet } from 'react-native';
import { render } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwCard } from '../TwCard';

describe('TwCard material', () => {
  test('default and raised planes use tone, not default borders or shadows', () => {
    const defaultCard = render(<ThemeProvider><TwCard /></ThemeProvider>).toJSON() as { props: { style: unknown } };
    const raisedCard = render(<ThemeProvider><TwCard tone="raised" /></ThemeProvider>).toJSON() as { props: { style: unknown } };

    const defaultStyle = StyleSheet.flatten(defaultCard.props.style) as Record<string, unknown>;
    const raisedStyle = StyleSheet.flatten(raisedCard.props.style) as Record<string, unknown>;
    expect(Number(defaultStyle.borderWidth ?? 0)).toBe(0);
    expect(Number(raisedStyle.borderWidth ?? 0)).toBe(0);
    expect(defaultStyle.shadowOpacity).toBeUndefined();
    expect(raisedStyle.shadowOpacity).toBeUndefined();
    expect(defaultStyle.elevation).toBeUndefined();
    expect(raisedStyle.elevation).toBeUndefined();
  });

  test('interactive cards reserve a physical 44dp target', () => {
    const node = render(<ThemeProvider><TwCard interactive onPress={() => {}} /></ThemeProvider>).toJSON() as { props: { style: unknown } };
    const style = StyleSheet.flatten(node.props.style) as Record<string, unknown>;
    expect(Number(style.minWidth ?? style.width)).toBeGreaterThanOrEqual(44);
    expect(Number(style.minHeight ?? style.height)).toBeGreaterThanOrEqual(44);
  });
});
