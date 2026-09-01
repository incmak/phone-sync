import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import HomeScreen from '../home';
import WelcomeScreen from '../onboarding/welcome';
import SettingsScreen from '../settings';
import { OnboardingState } from '../../state/onboardingState';

describe('route product truth', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true, peerDisplayName: 'Pixel' });
    global.__TWINOTIFY_CORE__.getPreferLan.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({ state: 'DISCONNECTED', queuedCount: 0 });
    global.__TWINOTIFY_CORE__.getRouteStatus.mockResolvedValue({
      route: 'none', phase: 'idle', queued_count: 0, pending_local_count: 0,
      awaiting_peer_count: 0, held_by_relay_count: 0, peer_evidence: 'unknown',
      delivery_reason: 'none', user_content_kind: 'notifications', route_generation: 0,
    });
  });

  it('loads the durable route preference and explains relay-first fallback truthfully', async () => {
    await OnboardingState.setRelayUrl('https://relay.example.test');
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getPreferLan).toHaveBeenCalled());
    expect(screen.getByText('Uses the relay first, with direct Wi-Fi as backup')).toBeTruthy();
    expect(screen.getByRole('switch', { name: 'Prefer direct Wi-Fi delivery' }).props.accessibilityState.checked).toBe(false);
    expect(screen.queryByText(/phase 3/i)).toBeNull();
  });

  it('shows direct Wi-Fi only without an impossible route-order switch', async () => {
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByText('Direct Wi-Fi only')).toBeTruthy());

    expect(screen.getByText('Delivery route')).toBeTruthy();
    expect(screen.queryByRole('switch', { name: 'Prefer direct Wi-Fi delivery' })).toBeNull();
    expect(screen.queryByText(/relay first/i)).toBeNull();
  });

  it('does not render a lock-screen control without native privacy semantics', async () => {
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getPairStatus).toHaveBeenCalled());

    expect(screen.queryByText('Lock-screen preview')).toBeNull();
    expect(screen.queryByRole('switch', { name: /lock screen/i })).toBeNull();
  });

  it('describes mirroring as paused when the service is off', async () => {
    const screen = render(<HomeScreen />);
    await waitFor(() => expect(screen.getByText('Paused')).toBeTruthy());

    expect(screen.getByText('Turn on mirroring when you want delivery to resume.')).toBeTruthy();
    expect(screen.queryByText(/retries on its own/i)).toBeNull();
  });

  it('keeps durable mirroring intent on and routes a permission-blocked recovery through explanation', async () => {
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      state: 'DISCONNECTED', queuedCount: 0, enabled: true,
    });
    global.__TWINOTIFY_CORE__.getRouteStatus.mockResolvedValue({
      route: 'none', phase: 'idle', queued_count: 0, pending_local_count: 0,
      awaiting_peer_count: 0, held_by_relay_count: 0, peer_evidence: 'unknown',
      delivery_reason: 'none', user_content_kind: 'notifications', route_generation: 0,
      recovery_issue: 'notification_access_required',
      presentation: {
        state: 'stopped',
        label: 'Notification access needed',
        explanation: 'Allow notification access to resume mirroring.',
        action: 'permissions',
        queued_count: 0,
        peer_line: null,
      },
    });

    const screen = render(<HomeScreen />);

    expect(await screen.findByText('Notification access needed')).toBeTruthy();
    expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.checked).toBe(true);
    fireEvent.press(screen.getByRole('button', { name: 'Review permissions' }));
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/onboarding/perms');
  });

  it('starts a LAN-only peer without inventing a relay URL', async () => {
    const screen = render(<HomeScreen />);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.disabled).toBe(false));
    fireEvent.press(screen.getByRole('switch', { name: 'Mirror notifications' }));
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).toHaveBeenCalled());
    expect(global.__TWINOTIFY_CORE__.startSyncService).not.toHaveBeenCalled();
  });

  it('names route controls and keeps touch targets at least 44 points', async () => {
    const screen = render(<HomeScreen />);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.disabled).toBe(false));
    expect(screen.getByRole('button', { name: 'Open settings' }).props.style).toEqual(expect.arrayContaining([expect.objectContaining({ minWidth: 48, minHeight: 48 })]));
    expect(screen.getByRole('button', { name: 'Open paired phone' })).toBeTruthy();
    expect(screen.getByRole('switch', { name: 'Mirror notifications' })).toBeTruthy();
    expect(screen.getByText('No data')).toBeTruthy();
    expect(screen.getByLabelText('Latency not measured')).toBeTruthy();
    expect(screen.queryByText('0ms')).toBeNull();
    const liveStatus = screen.getByLabelText(/Paused\. Turn on mirroring/);
    expect(liveStatus.props.accessible).toBe(true);
    expect(liveStatus.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByRole('button', { name: 'Settings' })).toBeNull();
  });

  it('names the system notification settings action truthfully', async () => {
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByText('Notification settings')).toBeTruthy());

    expect(screen.queryByText('Reliability audit')).toBeNull();
  });

  it('describes encryption without denying the optional relay', () => {
    const screen = render(<WelcomeScreen />);
    expect(screen.getByText(/Mirror selected notifications/)).toBeTruthy();
    expect(screen.getByText(/Send selected alerts to your second phone/)).toBeTruthy();
    expect(screen.getByText(/End-to-end encrypted, with no account required/)).toBeTruthy();
    expect(screen.queryByText(/no cloud/i)).toBeNull();
    expect(screen.queryByText(/mirror every/i)).toBeNull();
    expect(screen.queryByText(/delivered silently/i)).toBeNull();
    const alternate = screen.getByRole('button', { name: 'I already have a code' });
    expect(StyleSheet.flatten(alternate.props.style).minHeight).toBeGreaterThanOrEqual(44);
    for (const text of [
      screen.getByText(/Mirror selected notifications/),
      screen.getByText(/Send selected alerts to your second phone/),
      screen.getByText('I already have a code'),
    ]) {
      const style = StyleSheet.flatten(text.props.style);
      expect(text.props.allowFontScaling).not.toBe(false);
      expect(style.lineHeight).toBeGreaterThan(style.fontSize);
    }
  });

  it.each([false, true])('keeps Welcome body copy at full-opacity accessible ink when dark=%s', (dark) => {
    global.__SET_DARK_THEME__(dark);
    const screen = render(<WelcomeScreen />);
    const body = screen.getByText(/Send selected alerts to your second phone/);

    expect(StyleSheet.flatten(body.props.style).opacity ?? 1).toBe(1);
  });
});
