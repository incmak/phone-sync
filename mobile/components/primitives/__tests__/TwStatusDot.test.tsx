import React from 'react';
import { StyleSheet, ViewStyle } from 'react-native';
import { render } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwStatusDot, TwConnectionState } from '../TwStatusDot';

function renderDot(state: TwConnectionState) {
  return render(
    <ThemeProvider>
      <TwStatusDot state={state} size={10} />
    </ThemeProvider>,
  );
}

describe('TwStatusDot', () => {
  test('renders one plain marker with no pulse layer behind it', () => {
    const { toJSON } = renderDot('direct');

    const tree = JSON.stringify(toJSON());
    // A second stacked circle would be the expanding glow ring; there must be none.
    expect((tree.match(/borderRadius/g) ?? []).length).toBe(1);
  });

  test('stays out of the accessibility tree because the route is stated in words', () => {
    const { toJSON } = renderDot('relay');
    const node = toJSON() as unknown as { props: Record<string, unknown> };

    expect(node.props.accessible).toBe(false);
    expect(node.props.importantForAccessibility).toBe('no');
  });

  test('gives each delivery state its own colour', () => {
    const colorFor = (state: TwConnectionState) => {
      const node = renderDot(state).toJSON() as unknown as { props: { style: ViewStyle } };
      return StyleSheet.flatten(node.props.style).backgroundColor;
    };

    const direct = colorFor('direct');
    const relay = colorFor('relay');
    const unpaired = colorFor('unpaired');

    expect(direct).not.toBe(relay);
    expect(relay).not.toBe(unpaired);
    expect(direct).not.toBe(unpaired);
  });

  test('falls back to the unpaired marker for an unknown state', () => {
    const unknown = renderDot('nonsense' as TwConnectionState).toJSON() as unknown as { props: { style: ViewStyle } };
    const unpaired = renderDot('unpaired').toJSON() as unknown as { props: { style: ViewStyle } };

    expect(StyleSheet.flatten(unknown.props.style).backgroundColor).toBe(
      StyleSheet.flatten(unpaired.props.style).backgroundColor,
    );
  });
});
