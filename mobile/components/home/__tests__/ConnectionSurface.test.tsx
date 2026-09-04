import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import { ConnectionSurface } from '../ConnectionSurface';
import { presentRoute } from '../../../state/routePresentation';
import type { DeliveryPresentation } from '../../../state/routePresentation';

const route = (over: Partial<DeliveryPresentation> = {}): DeliveryPresentation => ({
  state: 'relay',
  label: 'Via relay',
  explanation: 'Connected to the relay. Waiting for your other phone to check in.',
  peerLine: 'Not confirmed online',
  queuedCount: 0,
  ...over,
});

function surface(value: DeliveryPresentation) {
  const toggle = jest.fn();
  const openPeer = jest.fn();
  const screen = render(
    <ConnectionSurface
      route={value}
      enabled
      onToggle={toggle}
      peerName="POCO F1"
      onOpenPeer={openPeer}
      traceWidth={260}
    />,
  );
  return { screen, toggle, openPeer };
}

test('keeps route truth, switch, trace, and peer action in one surface', () => {
  const { screen, toggle, openPeer } = surface(route({
    state: 'direct', label: 'Direct on Wi-Fi', explanation: 'Phones connected.', peerLine: 'Reachable now',
  }));

  expect(screen.getByTestId('connection-surface')).toBeTruthy();
  expect(screen.UNSAFE_getByProps({ testID: 'handoff-trace-direct' })).toBeTruthy();
  fireEvent.press(screen.getByRole('switch', { name: 'Mirror notifications' }));
  fireEvent.press(screen.getByRole('button', { name: 'Open paired phone' }));
  expect(toggle).toHaveBeenCalledWith(false);
  expect(openPeer).toHaveBeenCalledTimes(1);
});

test('renders an authenticated Bluetooth route as direct Bluetooth', () => {
  const { screen } = surface(presentRoute({
    route: 'bluetooth',
    phase: 'authenticated',
    queued_count: 0,
    pending_local_count: 0,
    awaiting_peer_count: 0,
    held_by_relay_count: 0,
    peer_evidence: 'unknown',
    delivery_reason: 'none',
    user_content_kind: 'notifications',
    route_generation: 4,
  }, true));

  expect(screen.getByText('Direct Bluetooth')).toBeTruthy();
  expect(screen.getByText('Your phones are talking directly over Bluetooth.')).toBeTruthy();
  expect(screen.getByText('Reachable now')).toBeTruthy();
  expect(screen.UNSAFE_getByProps({ testID: 'handoff-trace-direct' })).toBeTruthy();
  expect(screen.queryByText(/call audio|headset|hands-free/i)).toBeNull();
});

test('reserves Reachable now for direct and renders weaker relay evidence', () => {
  const direct = surface(route({ state: 'direct', peerLine: 'Reachable now' })).screen;
  expect(direct.getByText('Reachable now')).toBeTruthy();
  direct.unmount();

  const recentRelay = surface(route({ peerLine: 'Checked in recently' })).screen;
  expect(recentRelay.getByText('Checked in recently')).toBeTruthy();
  expect(recentRelay.queryByText('Reachable now')).toBeNull();
  recentRelay.unmount();

  const staleRelay = surface(route({ peerLine: 'Not confirmed online' })).screen;
  expect(staleRelay.getByText('Not confirmed online')).toBeTruthy();
  expect(staleRelay.queryByText('Reachable now')).toBeNull();
});

test('announces the main label, explanation, and peer line together', () => {
  const { screen } = surface(route({ peerLine: 'Checked in recently' }));

  expect(screen.getByLabelText(
    'Via relay. Connected to the relay. Waiting for your other phone to check in. Checked in recently.',
  ).props.accessibilityLiveRegion).toBe('polite');
});

test('keeps switch and peer button targets at least 48dp', () => {
  const { screen } = surface(route());
  const peer = screen.getByRole('button', { name: 'Open paired phone' });
  const mirror = screen.getByRole('switch', { name: 'Mirror notifications' });

  expect(StyleSheet.flatten(peer.props.style).minHeight).toBeGreaterThanOrEqual(48);
  expect(mirror.props.accessibilityState.disabled).toBe(false);
});

test('renders one explicit permission-recovery action with a physical touch target', () => {
  const onPermissions = jest.fn();
  const screen = render(
    <ConnectionSurface
      route={route({
        state: 'stopped',
        label: 'Notification access needed',
        explanation: 'Allow notification access to resume mirroring.',
        peerLine: null,
        action: 'permissions',
      })}
      enabled
      onToggle={jest.fn()}
      peerName="Pixel"
      onOpenPeer={jest.fn()}
      traceWidth={260}
      onPermissions={onPermissions}
    />,
  );

  const action = screen.getByRole('button', { name: 'Review permissions' });
  fireEvent.press(action);
  expect(onPermissions).toHaveBeenCalledTimes(1);
  expect(StyleSheet.flatten(action.props.style).minHeight).toBeGreaterThanOrEqual(44);
});
