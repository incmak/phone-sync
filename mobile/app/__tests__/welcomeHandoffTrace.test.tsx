import React from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { fireEvent, render } from '@testing-library/react-native';

import WelcomeScreen from '../onboarding/welcome';

type RenderNode = {
  props?: Record<string, unknown>;
  children?: (RenderNode | string)[];
};

function nodesWithTestId(node: RenderNode | RenderNode[] | string | null, testID: string): RenderNode[] {
  if (!node || typeof node === 'string') return [];
  if (Array.isArray(node)) return node.flatMap((child) => nodesWithTestId(child, testID));
  return [
    ...(node.props?.testID === testID ? [node] : []),
    ...(node.children ?? []).flatMap((child) => nodesWithTestId(child, testID)),
  ];
}

describe('Welcome Handoff Trace composition', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
  });

  it('presents one decorative trace in a left-aligned upper stage', () => {
    const { toJSON, ...screen } = render(<WelcomeScreen />);

    const traces = nodesWithTestId(toJSON() as RenderNode, 'welcome-handoff-trace');
    const trace = traces[0]!;
    const traceProps = trace.props!;
    expect(traceProps.width).toBeGreaterThan(0);
    expect(traceProps.accessible).toBe(false);
    expect(traceProps.importantForAccessibility).toBe('no-hide-descendants');
    expect(traces).toHaveLength(1);
    expect(JSON.stringify(toJSON())).not.toMatch(/Circle|RadialGradient|LinearGradient/i);

    expect(StyleSheet.flatten(screen.getByTestId('welcome-header').props.style).alignItems).toBe('flex-start');
    expect(StyleSheet.flatten(screen.getByTestId('welcome-copy').props.style).alignItems).toBe('flex-start');
  });

  it('keeps approved copy and both onboarding destinations', () => {
    const screen = render(<WelcomeScreen />);

    expect(screen.getByText('Mirror selected notifications.')).toBeTruthy();
    expect(screen.getByText('Send selected alerts to your second phone. End-to-end encrypted, with no account required.')).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: 'Get started' }));
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/onboarding/how');

    fireEvent.press(screen.getByRole('button', { name: 'I already have a code' }));
    expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/onboarding/role');
  });

  it('uses a scalable scroll layout with reachable stationary actions', () => {
    const screen = render(<WelcomeScreen />);
    const scroll = screen.UNSAFE_getByType(ScrollView);
    const headline = screen.getByText('Mirror selected notifications.');
    const body = screen.getByText('Send selected alerts to your second phone. End-to-end encrypted, with no account required.');
    const alternate = screen.getByRole('button', { name: 'I already have a code' });

    expect(scroll.props.contentContainerStyle).toEqual(expect.anything());
    for (const text of [headline, body, screen.getByText('I already have a code')]) {
      const style = StyleSheet.flatten(text.props.style);
      expect(text.props.allowFontScaling).not.toBe(false);
      expect(style.lineHeight).toBeGreaterThan(style.fontSize);
    }
    // 48dp stays at or above the verifier's 44dp physical target after Android
    // rounds layout coordinates at high display densities.
    expect(StyleSheet.flatten(alternate.props.style).minHeight).toBeGreaterThanOrEqual(48);
    expect(StyleSheet.flatten(screen.getByRole('button', { name: 'Get started' }).props.style).minHeight).toBeGreaterThanOrEqual(44);
    expect(StyleSheet.flatten(screen.getByTestId('welcome-actions').props.style).position).toBeUndefined();
  });
});
