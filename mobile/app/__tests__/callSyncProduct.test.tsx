import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Alert, StyleSheet } from 'react-native';

import SettingsScreen from '../settings';
import type { SyncStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const baseStatus: SyncStatus = {
  state: 'DISCONNECTED',
  queuedCount: 0,
  callCaptureEnabled: false,
  callCaptureDisabledReason: 'call_capture_disabled',
  callNotificationMode: null,
  lastCallEventAt: null,
};

async function renderSettings() {
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue(baseStatus);
  const screen = render(<SettingsScreen />);
  await act(async () => { await Promise.resolve(); });
  return screen;
}

function acceptRationale(alertSpy: jest.SpyInstance) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2];
  const continueButton = buttons?.find((button: { text?: string }) => button.text === 'Continue');
  act(() => continueButton?.onPress?.());
}

function allowCallControls(alertSpy: jest.SpyInstance) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2];
  const allowButton = buttons?.find((button: { text?: string }) => button.text === 'Allow');
  act(() => allowButton?.onPress?.());
}

function emitSyncStatus(status: SyncStatus) {
  const registration = global.__TWINOTIFY_CORE__.addListener.mock.calls.find(
    ([event]: [string]) => event === 'onSyncStatus',
  );
  const listener = registration?.[1] as ((next: SyncStatus) => void) | undefined;
  act(() => listener?.(status));
}

describe('call sync Settings product', () => {
  afterEach(() => jest.restoreAllMocks());

  it('loads durable native truth and exposes privacy-bounded accessible copy', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
      callNotificationMode: 'call_style_deferred_no_controls',
    });

    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByRole('switch', {
      name: 'Mirror call state, Shares only ringing, active, and ended states. No phone numbers or controls. On',
    }).props.accessibilityState.checked).toBe(true));

    expect(global.__TWINOTIFY_CORE__.getCallCaptureEnabled).toHaveBeenCalledTimes(1);
    expect(screen.getByText(
      'Shares only ringing, active, and ended states. No phone numbers or controls.',
    )).toBeTruthy();
  });

  it('announces loading truthfully and gives the call switch a 48dp target', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    let resolveEnabled!: (enabled: boolean) => void;
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockReturnValue(
      new Promise<boolean>((resolve) => { resolveEnabled = resolve; }),
    );
    const screen = render(<SettingsScreen />);

    const toggle = screen.getByRole('switch', { name: /Mirror call state.*Loading/ });
    expect(toggle.props.accessibilityState.disabled).toBe(true);
    const style = StyleSheet.flatten(toggle.props.style);
    expect(style.minWidth).toBeGreaterThanOrEqual(48);
    expect(style.minHeight).toBeGreaterThanOrEqual(48);
    await act(async () => { resolveEnabled(false); });
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.disabled,
    ).toBe(false));
  });

  it('requests permission only after rationale confirmation, then persists and reads back enablement', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    const screen = await renderSettings();
    const toggle = await screen.findByRole('switch', { name: /Mirror call state/ });

    expect(global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync).not.toHaveBeenCalled();
    fireEvent.press(toggle);
    expect(alertSpy).toHaveBeenCalledWith(
      'Mirror call state?',
      expect.stringMatching(/ringing, active, and ended states.*phone numbers.*controls/i),
      expect.any(Array),
    );
    expect(global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync).not.toHaveBeenCalled();

    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValueOnce(true);
    acceptRationale(alertSpy);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setCallCaptureEnabled).toHaveBeenCalledWith(true));
    expect(global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(true));
  });

  it('waits only during bounded native admission then renders Off on failure', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    let resolveAdmission!: () => void;
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.setCallCaptureEnabled.mockReturnValueOnce(
      new Promise<void>((resolve) => { resolveAdmission = resolve; }),
    );
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.disabled,
    ).toBe(false));

    fireEvent.press(screen.getByRole('switch', { name: /Mirror call state/ }));
    acceptRationale(alertSpy);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*Waiting for call capture to start/ })
        .props.accessibilityState.disabled,
    ).toBe(true));

    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValueOnce(false);
    await act(async () => { resolveAdmission(); });
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*Off/ }).props.accessibilityState.checked,
    ).toBe(false));
    expect(screen.queryByText(/Waiting for call capture to start/)).toBeNull();
  });

  it('keeps preference off on denial and offers one accessible permanent-denial recovery action', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync.mockResolvedValue({
      status: 'denied', granted: false, canAskAgain: false, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.disabled).toBe(false));
    const toggle = screen.getByRole('switch', { name: /Mirror call state/ });

    fireEvent.press(toggle);
    acceptRationale(alertSpy);
    await waitFor(() => expect(screen.getByRole('button', {
      name: 'Open Android settings to allow call state permission',
    })).toBeTruthy());
    expect(global.__TWINOTIFY_CORE__.setCallCaptureEnabled).not.toHaveBeenCalledWith(true);
    expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(false);

    fireEvent.press(screen.getByRole('button', { name: 'Open Android settings to allow call state permission' }));
    expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1);
  });

  it('keeps retryable denial off without misdirecting the user to app settings', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync.mockResolvedValue({
      status: 'denied', granted: false, canAskAgain: true, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.disabled).toBe(false));

    fireEvent.press(screen.getByRole('switch', { name: /Mirror call state/ }));
    acceptRationale(alertSpy);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.requestCallStatePermissionAsync).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(false);
    expect(screen.queryByRole('button', { name: /Open Android settings to allow call state permission/ })).toBeNull();
  });

  it('rolls back a rejected enable and restores persisted truth after remount', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    const screen = await renderSettings();
    await waitFor(() => expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.disabled).toBe(false));
    global.__TWINOTIFY_CORE__.setCallCaptureEnabled.mockRejectedValueOnce(new Error('native enable failed'));

    fireEvent.press(screen.getByRole('switch', { name: /Mirror call state/ }));
    acceptRationale(alertSpy);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setCallCaptureEnabled).toHaveBeenCalledWith(true));
    expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(false);

    screen.unmount();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    const restarted = render(<SettingsScreen />);
    await waitFor(() => expect(restarted.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(true));
  });

  it('disables by the graceful native API and rolls back when native truth rejects a change', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(true));
    const toggle = screen.getByRole('switch', { name: /Mirror call state/ });

    global.__TWINOTIFY_CORE__.setCallCaptureEnabled.mockRejectedValueOnce(new Error('shutdown failed'));
    fireEvent.press(toggle);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setCallCaptureEnabled).toHaveBeenCalledWith(false));
    expect(screen.getByRole('switch', { name: /Mirror call state/ }).props.accessibilityState.checked).toBe(true);
  });

  it('truthfully disables capture on unsupported telephony hardware', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureDisabledReason: 'call_telephony_unsupported',
    });
    const screen = render(<SettingsScreen />);

    const toggle = await screen.findByRole('switch', { name: /Mirror call state/ });
    expect(toggle.props.accessibilityState.disabled).toBe(true);
    expect(toggle.props.accessibilityState.checked).toBe(false);
    expect(screen.getByText('Call state mirroring is unavailable on this device.')).toBeTruthy();
  });

  it('shows durable opt-in separately from unavailable capture health', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureDisabledReason: 'call_capture_starting',
    });
    const screen = render(<SettingsScreen />);

    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*Call capture is not active.*On/ })
        .props.accessibilityState.checked,
    ).toBe(true));
  });

  it('renders live permission revocation as Off with a truthful recovery message', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValueOnce({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    }).mockResolvedValueOnce({
      status: 'denied', granted: false, canAskAgain: false, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*On/ }).props.accessibilityState.checked,
    ).toBe(true));

    emitSyncStatus({
      ...baseStatus,
      callCaptureEnabled: false,
      callCaptureDisabledReason: 'call_permission_denied',
    });

    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*Call state permission is required.*Off/ })
        .props.accessibilityState.checked,
    ).toBe(false));
    expect(await screen.findByRole('button', {
      name: 'Open Android settings to allow call state permission',
    })).toBeTruthy();
  });

  it('renders an active persistence health failure without exposing its internal code', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*On/ }).props.accessibilityState.checked,
    ).toBe(true));

    emitSyncStatus({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
      callCaptureHealthCode: 'call_event_persist_failed',
    });

    await waitFor(() => expect(screen.getByText(
      'Enabled, but call state updates could not be saved. Try turning call mirroring off and on again.',
    )).toBeTruthy());
    expect(screen.getByRole('switch', { name: /Mirror call state.*On/ }).props.accessibilityState.checked).toBe(true);
    expect(screen.queryByText(/call_event_persist_failed/)).toBeNull();

    emitSyncStatus({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
      callCaptureHealthCode: 'unexpected_native_detail',
    });
    await waitFor(() => expect(screen.getByText(
      'Enabled, but call capture needs attention. Try turning call mirroring off and on again.',
    )).toBeTruthy());
    expect(screen.queryByText(/unexpected_native_detail/)).toBeNull();
  });

  it('keeps a durable opt-in On when a live stopped-service status defers capture', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({
      ...baseStatus,
      callCaptureEnabled: true,
      callCaptureDisabledReason: null,
    });
    const screen = render(<SettingsScreen />);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*On/ }).props.accessibilityState.checked,
    ).toBe(true));

    emitSyncStatus({
      ...baseStatus,
      state: 'DISCONNECTED',
      callCaptureEnabled: false,
      callCaptureDisabledReason: 'call_capture_disabled',
    });

    await waitFor(() => expect(
      screen.getByRole('switch', { name: /Mirror call state.*Call capture is not active.*On/ })
        .props.accessibilityState.checked,
    ).toBe(true));
  });

  it('renders separate 48dp call-control consent disabled while call state is off', async () => {
    const screen = await renderSettings();

    const controls = screen.getByRole('switch', {
      name: 'Let paired phone control calls, Off',
    });
    expect(controls.props.accessibilityState.checked).toBe(false);
    expect(controls.props.accessibilityState.disabled).toBe(true);
    const style = StyleSheet.flatten(controls.props.style);
    expect(style.minWidth).toBeGreaterThanOrEqual(48);
    expect(style.minHeight).toBeGreaterThanOrEqual(48);
    expect(screen.getByText('Turn on Mirror call state first.')).toBeTruthy();
  });

  it('uses the paired-device name in exact consent without promising caller identity or audio transfer', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
      paired: true,
      peerDeviceId: 'peer-device',
      peerDisplayName: 'Pixel 9',
    });
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallControlsEnabled.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    const controls = await screen.findByRole('switch', { name: 'Let paired phone control calls, Off' });

    fireEvent.press(controls);

    expect(alertSpy).toHaveBeenCalledWith(
      'Let Pixel 9 control calls?',
      "Anyone holding Pixel 9 can answer, decline, or end an incoming cellular call on this phone, including while Pixel 9 is locked. Call audio stays on this phone. Twinotify never sends the caller's name or number.",
      expect.any(Array),
    );
    expect(global.__TWINOTIFY_CORE__.setCallControlsEnabled).not.toHaveBeenCalled();
    allowCallControls(alertSpy);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setCallControlsEnabled).toHaveBeenCalledWith(true));
    await waitFor(() => expect(
      screen.getByRole('switch', { name: 'Let paired phone control calls, On' })
        .props.accessibilityState.checked,
    ).toBe(true));
    expect(screen.getByText(
      'The paired phone can answer, decline, or end compatible incoming calls on this phone. Audio stays here.',
    )).toBeTruthy();
    expect(screen.getByText(/No phone numbers\.$/)).toBeTruthy();
    expect(screen.queryByText(/or controls\./)).toBeNull();
    expect(screen.queryByText(/caller name|caller number|audio moves|talk on/i)).toBeNull();
  });

  it('rolls call controls back and reports both rejected durable truth and native failure', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallControlsEnabled.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    await screen.findByRole('switch', { name: 'Let paired phone control calls, Off' });

    global.__TWINOTIFY_CORE__.setCallControlsEnabled.mockResolvedValueOnce(false);
    fireEvent.press(screen.getByRole('switch', { name: 'Let paired phone control calls, Off' }));
    allowCallControls(alertSpy);
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Call controls unavailable',
      'This phone could not save the call-control setting.',
    ));
    expect(screen.getByRole('switch', { name: 'Let paired phone control calls, Off' })
      .props.accessibilityState.checked).toBe(false);

    global.__TWINOTIFY_CORE__.setCallControlsEnabled.mockRejectedValueOnce(new Error('native failure'));
    fireEvent.press(screen.getByRole('switch', { name: 'Let paired phone control calls, Off' }));
    allowCallControls(alertSpy);
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Call controls unavailable',
      'Nothing changed. Try again.',
    ));
    expect(screen.getByRole('switch', { name: 'Let paired phone control calls, Off' })
      .props.accessibilityState.checked).toBe(false);
  });

  it('disables the call-control switch while the durable native update is pending', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert');
    let resolveControls!: (enabled: boolean) => void;
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.setCallControlsEnabled.mockReturnValueOnce(
      new Promise<boolean>((resolve) => { resolveControls = resolve; }),
    );
    const screen = render(<SettingsScreen />);
    await screen.findByRole('switch', { name: 'Let paired phone control calls, Off' });

    fireEvent.press(screen.getByRole('switch', { name: 'Let paired phone control calls, Off' }));
    allowCallControls(alertSpy);
    await waitFor(() => expect(
      screen.getByRole('switch', { name: 'Let paired phone control calls, Off' })
        .props.accessibilityState.disabled,
    ).toBe(true));

    await act(async () => { resolveControls(true); });
    await waitFor(() => expect(
      screen.getByRole('switch', { name: 'Let paired phone control calls, On' })
        .props.accessibilityState.disabled,
    ).toBe(false));
  });

  it('disabling call state also renders controls off from durable native truth', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallControlsEnabled.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.getCallStatePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    const screen = render(<SettingsScreen />);
    await screen.findByRole('switch', { name: 'Let paired phone control calls, On' });

    global.__TWINOTIFY_CORE__.getCallCaptureEnabled.mockResolvedValueOnce(false);
    global.__TWINOTIFY_CORE__.getCallControlsEnabled.mockResolvedValueOnce(false);
    fireEvent.press(screen.getByRole('switch', { name: /Mirror call state/ }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setCallCaptureEnabled).toHaveBeenCalledWith(false));
    await waitFor(() => expect(
      screen.getByRole('switch', { name: 'Let paired phone control calls, Off' })
        .props.accessibilityState.disabled,
    ).toBe(true));
  });
});
