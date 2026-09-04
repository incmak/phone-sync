import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Alert, StyleSheet } from 'react-native';

import PairDetailScreen from '../../settings/pair';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
}

const ASSOCIATE_SUBTITLE =
  'Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.';
const ASSOCIATED_SUBTITLE =
  'Associated. Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.';

type BluetoothRouteSettings = { associated: boolean; enabled: boolean };

async function renderPair(settings: BluetoothRouteSettings = { associated: false, enabled: false }) {
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
    paired: true,
    peerDeviceId: 'peer-device-1234',
    peerDisplayName: 'Pixel',
    peerEncPubkey: 'enc',
    peerSignPubkey: 'sign',
  });
  global.__TWINOTIFY_CORE__.computeFingerprint.mockResolvedValue('ab'.repeat(32));
  global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue(settings);

  const screen = render(<PairDetailScreen />);
  await screen.findByText('Bluetooth fallback');
  return screen;
}

function pressAlertButton(alertSpy: jest.SpyInstance, text: string) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2] as { text?: string; onPress?: () => void }[] | undefined;
  const button = buttons?.find((candidate) => candidate.text === text);
  expect(button).toBeTruthy();
  act(() => button?.onPress?.());
}

describe('Bluetooth fallback association', () => {
  afterEach(() => jest.restoreAllMocks());

  it('explains that Bluetooth is data fallback and never promises call audio', async () => {
    const screen = await renderPair();

    expect(screen.getByText('Bluetooth fallback')).toBeTruthy();
    expect(screen.getByText(ASSOCIATE_SUBTITLE)).toBeTruthy();
    expect(screen.queryByText(/talk on this phone/i)).toBeNull();
    expect(screen.queryAllByText(/hands-free|headset|watch|speaker|microphone/i)).toHaveLength(0);
  });

  it('hides every Bluetooth control until the pair is confirmed', async () => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: false });
    const screen = render(<PairDetailScreen />);

    await waitFor(() => expect(screen.getByText('No paired device')).toBeTruthy());
    expect(screen.queryByText('Bluetooth fallback')).toBeNull();
    expect(screen.queryByRole('switch', { name: /Use Bluetooth fallback/ })).toBeNull();
  });

  it('requests nearby permission before opening the system association picker', async () => {
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync.mockResolvedValue({
      status: 'granted', granted: true, canAskAgain: true, expires: 'never',
    });
    global.__TWINOTIFY_CORE__.startBluetoothAssociation.mockResolvedValue({ associated: true });
    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: true, enabled: true });

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).toHaveBeenCalledTimes(1));
    expect(global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync).toHaveBeenCalledTimes(1);
    expect(global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync.mock.invocationCallOrder[0])
      .toBeLessThan(global.__TWINOTIFY_CORE__.startBluetoothAssociation.mock.invocationCallOrder[0]);
    expect(await screen.findByText(ASSOCIATED_SUBTITLE)).toBeTruthy();
    expect(screen.queryByTestId('pair-bluetooth-disclosure')).toBeNull();
  });

  it('keeps a retryable denial off the picker without misdirecting to Android settings', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync.mockResolvedValue({
      status: 'denied', granted: false, canAskAgain: true, expires: 'never',
    });

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledTimes(1));
    expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).not.toHaveBeenCalled();
    expect(alertSpy.mock.calls[0][2]).toBeUndefined();
    expect(screen.getByRole('button', { name: 'Set up Bluetooth fallback' })).toBeTruthy();
    expect(screen.queryByRole('switch', { name: /Use Bluetooth fallback/ })).toBeNull();
  });

  it('offers one Android settings recovery when the permission can no longer be asked', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync.mockResolvedValue({
      status: 'denied', granted: false, canAskAgain: false, expires: 'never',
    });

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledTimes(1));
    expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).not.toHaveBeenCalled();
    pressAlertButton(alertSpy, 'Open settings');
    expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1);
  });

  it('returns quietly to the unassociated state when the system picker is cancelled', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.startBluetoothAssociation.mockResolvedValue({ associated: false });

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Set up Bluetooth fallback' })).toBeTruthy());
    expect(alertSpy).not.toHaveBeenCalled();
    expect(screen.getByText(ASSOCIATE_SUBTITLE)).toBeTruthy();
    expect(screen.queryByRole('switch', { name: /Use Bluetooth fallback/ })).toBeNull();
  });

  it('trusts durable settings when a reported association did not persist', async () => {
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.startBluetoothAssociation.mockResolvedValue({ associated: true });
    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: false, enabled: false });

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getBluetoothRouteSettings).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('button', { name: 'Set up Bluetooth fallback' })).toBeTruthy();
    expect(screen.queryByText(ASSOCIATED_SUBTITLE)).toBeNull();
    expect(screen.queryByRole('switch', { name: /Use Bluetooth fallback/ })).toBeNull();
  });

  it('reports a failed association once without leaking native error text', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair();
    global.__TWINOTIFY_CORE__.startBluetoothAssociation.mockRejectedValue(
      new Error('bluetooth_association_failed: 3c:5a:b4:00:11:22'),
    );

    fireEvent.press(screen.getByRole('button', { name: 'Set up Bluetooth fallback' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledTimes(1));
    expect(alertSpy).toHaveBeenCalledWith('Bluetooth fallback unavailable', 'Nothing changed. Try again.');
    expect(screen.getByRole('button', { name: 'Set up Bluetooth fallback' })).toBeTruthy();
  });

  it('shows one 48dp route switch only after association', async () => {
    const screen = await renderPair({ associated: true, enabled: true });

    expect(screen.getByText(ASSOCIATED_SUBTITLE)).toBeTruthy();
    expect(screen.queryByTestId('pair-bluetooth-disclosure')).toBeNull();
    const control = screen.getByRole('switch', { name: 'Use Bluetooth fallback, On' });
    expect(control.props.accessibilityState.checked).toBe(true);
    expect(StyleSheet.flatten(control.props.style).minWidth).toBeGreaterThanOrEqual(48);
    expect(StyleSheet.flatten(control.props.style).minHeight).toBeGreaterThanOrEqual(48);
    expect(screen.getByText(
      'On. Encrypted sync can use Bluetooth when higher-priority delivery is unavailable.',
    )).toBeTruthy();
  });

  it('keeps the durable answer when disabling the route succeeds', async () => {
    const screen = await renderPair({ associated: true, enabled: true });
    global.__TWINOTIFY_CORE__.setBluetoothRouteEnabled.mockResolvedValue(false);

    fireEvent.press(screen.getByRole('switch', { name: 'Use Bluetooth fallback, On' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setBluetoothRouteEnabled).toHaveBeenCalledWith(false));
    expect(await screen.findByRole('switch', { name: 'Use Bluetooth fallback, Off' })).toBeTruthy();
    expect(screen.getByText('Off. The association is kept until you remove it.')).toBeTruthy();
  });

  it('rolls back to the previous value when persistence rejects', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair({ associated: true, enabled: false });
    global.__TWINOTIFY_CORE__.setBluetoothRouteEnabled.mockRejectedValue(new Error('storage unavailable'));

    fireEvent.press(screen.getByRole('switch', { name: 'Use Bluetooth fallback, Off' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Bluetooth fallback unavailable',
      'Nothing changed. Try again.',
    ));
    expect(screen.getByRole('switch', { name: 'Use Bluetooth fallback, Off' }).props.accessibilityState.checked)
      .toBe(false);
  });

  it('adopts durable truth and explains it when native refuses the change', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair({ associated: true, enabled: false });
    global.__TWINOTIFY_CORE__.setBluetoothRouteEnabled.mockResolvedValue(false);

    fireEvent.press(screen.getByRole('switch', { name: 'Use Bluetooth fallback, Off' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Bluetooth fallback unavailable',
      'Nothing changed. Check Nearby devices permission and try again.',
    ));
    expect(screen.getByRole('switch', { name: 'Use Bluetooth fallback, Off' }).props.accessibilityState.checked)
      .toBe(false);
  });

  it('confirms removal with the exact destructive wording and re-reads durable truth', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair({ associated: true, enabled: true });

    const remove = screen.getByRole('button', { name: 'Remove Bluetooth fallback' });
    expect(StyleSheet.flatten(remove.props.style).minHeight).toBeGreaterThanOrEqual(48);
    fireEvent.press(remove);

    expect(alertSpy).toHaveBeenCalledWith(
      'Remove Bluetooth fallback?',
      'Twinotify will stop nearby Bluetooth sync with this paired phone. Wi-Fi and relay pairing stay unchanged.',
      [
        { text: 'Cancel', style: 'cancel' },
        expect.objectContaining({ text: 'Remove', style: 'destructive' }),
      ],
    );
    expect(global.__TWINOTIFY_CORE__.removeBluetoothAssociation).not.toHaveBeenCalled();

    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: false, enabled: false });
    pressAlertButton(alertSpy, 'Remove');

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.removeBluetoothAssociation).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('button', { name: 'Set up Bluetooth fallback' })).toBeTruthy();
    expect(screen.queryByRole('switch', { name: /Use Bluetooth fallback/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove Bluetooth fallback' })).toBeNull();
  });

  it('keeps the association when removal fails', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair({ associated: true, enabled: true });
    global.__TWINOTIFY_CORE__.removeBluetoothAssociation.mockRejectedValue(new Error('companion unavailable'));

    fireEvent.press(screen.getByRole('button', { name: 'Remove Bluetooth fallback' }));
    pressAlertButton(alertSpy, 'Remove');

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Bluetooth fallback unavailable',
      'Nothing changed. Try again.',
    ));
    expect(screen.getByRole('switch', { name: 'Use Bluetooth fallback, On' })).toBeTruthy();
  });
});
