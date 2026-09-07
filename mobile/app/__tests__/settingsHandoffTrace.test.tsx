import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';

import SettingsScreen from '../settings';
import { HandoffDisclosureMark } from '../../components/HandoffTrace';
import { TwCard } from '../../components/primitives/TwCard';
import { OnboardingState } from '../../state/onboardingState';

async function renderSettings({ relayUrl }: { relayUrl?: string } = {}) {
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
    paired: true,
    peerDeviceId: '12345678-peer',
  });
  global.__TWINOTIFY_CORE__.getPreferLan.mockResolvedValue(false);
  if (relayUrl) await OnboardingState.setRelayUrl(relayUrl);

  const screen = render(<SettingsScreen />);
  await act(async () => {
    await Promise.resolve();
  });
  return screen;
}

describe('Settings handoff ledger', () => {
  it('keeps four ordered groups on one open ledger with authored disclosures', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    await waitFor(() => expect(screen.getByText('Relay server')).toBeTruthy());
    const labels = screen.UNSAFE_getAllByType(Text).map((node) => node.props.children).filter(Boolean);
    expect(labels.indexOf('Pairing')).toBeLessThan(labels.indexOf('Sync'));
    expect(labels.indexOf('Sync')).toBeLessThan(labels.indexOf('Privacy'));
    expect(labels.indexOf('Privacy')).toBeLessThan(labels.indexOf('About'));
    expect(screen.UNSAFE_queryAllByType(TwCard)).toHaveLength(0);
    // Pairing, relay, filters and history: every row that leads somewhere carries the mark.
    expect(screen.UNSAFE_queryAllByType(HandoffDisclosureMark)).toHaveLength(4);
    expect(screen.queryByText('›')).toBeNull();
  });

  it('rolls back the visible preference when relay persistence is rejected', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct delivery' })).toBeTruthy());
    const routePreference = screen.getByRole('switch', { name: 'Prefer direct delivery' });
    expect(screen.getByText('Relay server')).toBeTruthy();
    expect(routePreference.props.accessibilityState.checked).toBe(false);

    global.__TWINOTIFY_CORE__.setPreferLan.mockRejectedValueOnce(new Error('storage unavailable'));
    fireEvent.press(routePreference);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct delivery' }).props.accessibilityState.checked).toBe(false));
  });

  it('keeps the direct Wi-Fi only branch truthful without an impossible preference control', async () => {
    const screen = await renderSettings();

    await waitFor(() =>
      expect(screen.getByText('Direct delivery only. Add a relay to reach different networks.')).toBeTruthy());
    expect(screen.getByText('Delivery route')).toBeTruthy();
    // There is no relay to prefer against, so the preference control stays absent.
    expect(screen.queryByRole('switch', { name: 'Prefer direct delivery' })).toBeNull();
  });

  it('shows the delivery order as something to read, never something to edit', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    expect(await screen.findByText('Delivery order')).toBeTruthy();
    // This fixture has the direct preference off, so the description has to lead with the relay.
    // The row exists to make that switch's consequence visible, not to restate a fixed order.
    expect(screen.getByText('Relay, then Direct Wi-Fi. Nothing is carrying yet.')).toBeTruthy();
    // Bluetooth is absent because this pair never associated one.
    expect(screen.queryByText(/Bluetooth, then/)).toBeNull();
    // Reading, not editing: the order follows from the preference switch below it, and giving it
    // its own control would let someone pick a silently worse order with no feedback.
    expect(screen.queryByRole('button', { name: /Delivery order/ })).toBeNull();
  });

  it('sends the direct-only branch somewhere it can actually add a relay', async () => {
    // The row used to be inert, which is how a nearby-paired user reached a dead end: no way to
    // add a relay, and no hint that the paired-device screen is where it happens.
    const screen = await renderSettings();

    await waitFor(() => expect(screen.getByText('Delivery route')).toBeTruthy());
    fireEvent.press(screen.getByText('Delivery route'));

    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/peers');
  });

  it('keeps actions named with their subtitle and routes them to the original destination', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });
    global.__TWINOTIFY_CORE__.openAppSettings = jest.fn(async () => {});

    await waitFor(() => expect(screen.getByRole('button', { name: /Paired devices, 12345678 · offline/ })).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: /Paired devices, 12345678 · offline/ }));
    fireEvent.press(screen.getByRole('button', { name: 'App filter, Control which apps are mirrored' }));
    fireEvent.press(screen.getByRole('button', { name: 'Notification settings, Tap to open system notification settings' }));

    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/peers');
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/filter');
    expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1);
  });

  it('keeps a scalable ledger and separate 44dp trailing-control slot', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct delivery' })).toBeTruthy());
    for (const text of [
      screen.getByText('Settings'),
      screen.getByText('Pairing'),
      screen.getByText('Prefer direct delivery'),
      screen.getByText('Uses the relay first, with direct Wi-Fi and Bluetooth as backups.'),
    ]) {
      const style = StyleSheet.flatten(text.props.style);
      expect(text.props.allowFontScaling).not.toBe(false);
      expect(style.lineHeight).toBeUndefined();
    }
    expect(StyleSheet.flatten(screen.getByRole('switch', { name: 'Prefer direct delivery' }).props.style).minWidth).toBeGreaterThanOrEqual(48);
    expect(StyleSheet.flatten(screen.getByRole('switch', { name: 'Prefer direct delivery' }).props.style).minHeight).toBeGreaterThanOrEqual(48);
  });
});
