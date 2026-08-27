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
    expect(screen.UNSAFE_queryAllByType(HandoffDisclosureMark)).toHaveLength(3);
    expect(screen.queryByText('›')).toBeNull();
  });

  it('rolls back the visible preference when relay persistence is rejected', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' })).toBeTruthy());
    const routePreference = screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' });
    expect(screen.getByText('Relay server')).toBeTruthy();
    expect(routePreference.props.accessibilityState.checked).toBe(false);

    global.__TWINOTIFY_CORE__.setPreferLan.mockRejectedValueOnce(new Error('storage unavailable'));
    fireEvent.press(routePreference);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' }).props.accessibilityState.checked).toBe(false));
  });

  it('keeps the direct Wi-Fi only branch truthful without an impossible preference control', async () => {
    const screen = await renderSettings();

    await waitFor(() => expect(screen.getByText('Direct Wi-Fi only')).toBeTruthy());
    expect(screen.getByText('Delivery route')).toBeTruthy();
    expect(screen.queryByRole('switch', { name: 'Prefer direct Wi-Fi delivery' })).toBeNull();
  });

  it('keeps actions named with their subtitle and routes them to the original destination', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });
    global.__TWINOTIFY_CORE__.openAppSettings = jest.fn(async () => {});

    await waitFor(() => expect(screen.getByRole('button', { name: /Paired device, 12345678 · offline/ })).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: /Paired device, 12345678 · offline/ }));
    fireEvent.press(screen.getByRole('button', { name: 'App filter, Control which apps are mirrored' }));
    fireEvent.press(screen.getByRole('button', { name: 'Notification settings, Tap to open system notification settings' }));

    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/pair');
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/filter');
    expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1);
  });

  it('keeps a scalable ledger and separate 44dp trailing-control slot', async () => {
    const screen = await renderSettings({ relayUrl: 'https://relay.example.test' });

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' })).toBeTruthy());
    for (const text of [
      screen.getByText('Prefer direct Wi-Fi'),
      screen.getByText('Uses the relay first, with direct Wi-Fi as backup'),
    ]) {
      const style = StyleSheet.flatten(text.props.style);
      expect(text.props.allowFontScaling).not.toBe(false);
      expect(style.lineHeight).toBeGreaterThan(style.fontSize);
    }
    expect(StyleSheet.flatten(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' }).props.style).minWidth).toBeGreaterThanOrEqual(44);
    expect(StyleSheet.flatten(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' }).props.style).minHeight).toBeGreaterThanOrEqual(44);
  });
});
