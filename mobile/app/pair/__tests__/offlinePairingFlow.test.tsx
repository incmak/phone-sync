import React from 'react';
import { ScrollView, StyleSheet, Text } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { ThemeProvider } from '../../../components/Theme';
import { OnboardingState } from '../../../state/onboardingState';
import ConnectScreen from '../../onboarding/connect';
import RelayScreen from '../../onboarding/relay';
import PairDetailScreen from '../../settings/pair';
import FingerprintScreen from '../fingerprint';
import NearbyScreen from '../nearby';
import PairQRScreen from '../qr';
import ScanScreen from '../scan';
import SuccessScreen from '../success';
import VerifyScreen from '../verify';

declare global {
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
  var __SET_SEARCH_PARAMS__: (params: Record<string, string>) => void;
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __EMIT_OFFLINE_STATUS__: (status: OfflineStatusFixture) => void;
  var __EMIT_STALE_OFFLINE_STATUS__: (status: OfflineStatusFixture) => void;
  var __GET_LAST_OFFLINE_REMOVE__: () => jest.Mock;
  var __PRESS_HARDWARE_BACK__: () => boolean | undefined;
  var __SET_CAMERA_PERMISSION__: (permission: { granted: boolean }) => void;
  var __SET_DARK_THEME__: (dark: boolean) => void;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
}

type OfflineStatusFixture = {
  role: 'initiator' | 'joiner' | null;
  phase: string;
  sessionId: string | null;
  errorCode: string | null;
  peerDisplayName: string | null;
  sas: string | null;
  completed: boolean;
};

const activeStatus = (overrides: Partial<OfflineStatusFixture> = {}): OfflineStatusFixture => ({
  role: 'initiator',
  phase: 'advertising',
  sessionId: '11111111-1111-4111-8111-111111111111',
  errorCode: null,
  peerDisplayName: null,
  sas: null,
  completed: false,
  ...overrides,
});

function renderScreen(element: React.ReactElement) {
  return render(<ThemeProvider>{element}</ThemeProvider>);
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(async () => {
  jest.clearAllMocks();
  global.__RESET_OFFLINE_TEST_STATE__();
  await AsyncStorage.clear();
  jest.mocked(AsyncStorage.setItem).mockClear();
});

describe('offline pairing UI behavior', () => {
  test('persists and routes both explicit connection choices', async () => {
    renderScreen(<ConnectScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Pair nearby without internet' }));
    await waitFor(() => {
      expect(AsyncStorage.setItem).toHaveBeenCalledWith('twinotify_onboarding_pairing_mode', 'nearby');
      expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/onboarding/perms');
    });

    global.__TEST_ROUTER__.push.mockClear();
    renderScreen(<ConnectScreen />);
    fireEvent.press(screen.getAllByRole('button', { name: 'Use a relay' }).at(-1)!);
    await waitFor(() => {
      expect(AsyncStorage.setItem).toHaveBeenCalledWith('twinotify_onboarding_pairing_mode', 'relay');
      expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/onboarding/relay');
    });
  });

  test('shows the native initiator QR without writing its contents to logs or storage', async () => {
    const opaqueQr = JSON.stringify({ fixture: 'opaque-pairing-material' });
    const log = jest.spyOn(console, 'log').mockImplementation(() => {});
    global.__TWINOTIFY_CORE__.startOfflinePairing.mockResolvedValue(opaqueQr);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus
      .mockResolvedValueOnce(activeStatus({ role: null, phase: 'idle', sessionId: null }))
      .mockResolvedValue(activeStatus());
    await OnboardingState.setRole('A');

    renderScreen(<NearbyScreen />);

    expect(await screen.findByLabelText('Nearby pairing QR code')).toBeTruthy();
    expect(global.__TWINOTIFY_CORE__.startOfflinePairing).toHaveBeenCalledWith('Android phone');
    expect(log).not.toHaveBeenCalledWith(expect.stringContaining('opaque-pairing-material'));
    expect(AsyncStorage.setItem).not.toHaveBeenCalledWith(
      expect.any(String),
      expect.stringContaining('opaque-pairing-material'),
    );
    act(() => {
      global.__EMIT_OFFLINE_STATUS__(activeStatus({ phase: 'verify_code', sas: '204681' }));
    });
    await waitFor(() => expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/pair/verify'));
    log.mockRestore();
  });

  test('passes only the scanned text to native nearby validation', async () => {
    const scannedText = JSON.stringify({ fixture: 'native-validates-this' });
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockResolvedValue(undefined);
    await OnboardingState.setPairingMode('nearby');

    renderScreen(<ScanScreen />);
    const camera = await screen.findByTestId('camera-view');
    fireEvent(camera, 'barcodeScanned', { data: scannedText });

    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.joinOfflinePairing)
        .toHaveBeenCalledWith(scannedText, 'Android phone');
    });
    expect(global.__TWINOTIFY_CORE__.sendPeerHello).not.toHaveBeenCalled();
  });

  test('preserves the joined session when successful navigation unmounts the scanner', async () => {
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockResolvedValue(undefined);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({ role: 'joiner' }));
    const view = renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    await waitFor(() => expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/pair/nearby'));
    await act(async () => {
      view.unmount();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing).not.toHaveBeenCalled();
  });

  test.each(['initiator', 'joiner'] as const)(
    'shows the same six-digit native code to the %s and waits for explicit confirmation',
    async (role) => {
      global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
        role,
        phase: 'verify_code',
        sas: '204681',
      }));

      renderScreen(<VerifyScreen />);

      expect(await screen.findByText('204 681')).toBeTruthy();
      expect(global.__TWINOTIFY_CORE__.confirmOfflinePairing).not.toHaveBeenCalled();
      fireEvent.press(screen.getByRole('button', { name: 'Codes match' }));
      await waitFor(() => {
        expect(global.__TWINOTIFY_CORE__.confirmOfflinePairing)
          .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111');
      });
    },
  );

  test('cancels the exact native session before leaving', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus());
    renderScreen(<NearbyScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Cancel nearby pairing' }));

    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
        .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111');
    });
    expect(global.__TEST_ROUTER__.back).toHaveBeenCalled();
  });

  test('cancels a scanner-created session and suppresses late navigation after visible back', async () => {
    const joining = deferred<void>();
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockReturnValue(joining.promise);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      role: 'joiner',
      phase: 'discovering',
    }));
    renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.joinOfflinePairing).toHaveBeenCalled());
    fireEvent.press(screen.getByRole('button', { name: 'Go back' }));
    joining.resolve();

    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
        .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111');
    });
    expect(global.__TEST_ROUTER__.back).toHaveBeenCalled();
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/pair/nearby');
  });

  test('hardware back cancels an in-flight scanner join', async () => {
    const joining = deferred<void>();
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockReturnValue(joining.promise);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({ role: 'joiner' }));
    renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.joinOfflinePairing).toHaveBeenCalled());
    act(() => { global.__PRESS_HARDWARE_BACK__(); });
    joining.resolve();

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
      .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111'));
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/pair/nearby');
  });

  test('scanner unmount cancels a created session and suppresses its continuation', async () => {
    const joining = deferred<void>();
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockReturnValue(joining.promise);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({ role: 'joiner' }));
    const view = renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.joinOfflinePairing).toHaveBeenCalled());
    view.unmount();
    joining.resolve();

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
      .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111'));
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/pair/nearby');
  });

  test('does not start a native join after leaving during display-name lookup', async () => {
    const displayName = deferred<string>();
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.getDeviceDisplayName.mockReturnValue(displayName.promise);
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockResolvedValue(undefined);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      role: null,
      phase: 'idle',
      sessionId: null,
    }));
    renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    fireEvent.press(screen.getByRole('button', { name: 'Go back' }));
    displayName.resolve('Android phone');

    await waitFor(() => expect(global.__TEST_ROUTER__.back).toHaveBeenCalled());
    expect(global.__TWINOTIFY_CORE__.joinOfflinePairing).not.toHaveBeenCalled();
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/pair/nearby');
  });

  test('retries exact scanner cleanup after a transient cancellation rejection', async () => {
    const joining = deferred<void>();
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    global.__TWINOTIFY_CORE__.joinOfflinePairing.mockReturnValue(joining.promise);
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({ role: 'joiner' }));
    global.__TWINOTIFY_CORE__.cancelOfflinePairing
      .mockRejectedValueOnce(new Error('cleanup busy'))
      .mockResolvedValue(undefined);
    renderScreen(<ScanScreen />);

    fireEvent(await screen.findByTestId('camera-view'), 'barcodeScanned', { data: 'opaque-scan-text' });
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.joinOfflinePairing).toHaveBeenCalled());
    fireEvent.press(screen.getByRole('button', { name: 'Go back' }));
    await waitFor(() => expect(global.__TEST_ROUTER__.back).toHaveBeenCalled());
    joining.resolve();

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing).toHaveBeenCalledTimes(2));
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/pair/nearby');
  });

  test.each([
    ['expired', 'Pairing timed out'],
    ['wifi_unavailable', 'Wi-Fi may be isolating the phones'],
    ['wifi_permission_denied', 'Nearby devices permission is off'],
    ['tls_pin_mismatch', 'TLS security check failed'],
    ['invalid_frame', 'Pairing data failed the security check'],
    ['identity_mismatch', 'Phone identity did not match'],
    ['peer_rejected', 'The other phone rejected pairing'],
  ])('shows bounded repair guidance for %s', async (errorCode, expectedTitle) => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'idle',
      errorCode,
    }));

    renderScreen(<NearbyScreen />);

    expect(await screen.findByText(expectedTitle)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try nearby pairing again' })).toBeTruthy();
  });

  test('retry starts a fresh native initiator transition', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus
      .mockResolvedValueOnce(activeStatus({ phase: 'idle', errorCode: 'expired' }))
      .mockResolvedValue(activeStatus());
    global.__TWINOTIFY_CORE__.startOfflinePairing.mockResolvedValue('opaque-native-output');
    renderScreen(<NearbyScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Try nearby pairing again' }));

    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
        .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111');
      expect(global.__TWINOTIFY_CORE__.startOfflinePairing).toHaveBeenCalledWith('Android phone');
    });
    expect(await screen.findByLabelText('Nearby pairing QR code')).toBeTruthy();
  });

  test('ignores stale-session status events and removes the native listener', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus());
    const view = renderScreen(<NearbyScreen />);
    await screen.findByRole('button', { name: 'Cancel nearby pairing' });
    global.__TEST_ROUTER__.replace.mockClear();

    act(() => {
      global.__EMIT_OFFLINE_STATUS__(activeStatus({
        sessionId: '22222222-2222-4222-8222-222222222222',
        phase: 'verify_code',
        sas: '204681',
      }));
    });
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalled();

    act(() => {
      global.__EMIT_OFFLINE_STATUS__(activeStatus({ phase: 'verify_code', sas: '204681' }));
    });
    expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/pair/verify');

    const remove = global.__GET_LAST_OFFLINE_REMOVE__();
    view.unmount();
    expect(remove).toHaveBeenCalled();
    global.__TEST_ROUTER__.replace.mockClear();
    act(() => {
      global.__EMIT_STALE_OFFLINE_STATUS__(activeStatus({ phase: 'complete', completed: true }));
    });
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalled();
  });

  test('marks onboarding complete only after native reports COMPLETE', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'committed',
    }));
    renderScreen(<SuccessScreen />);

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getOfflinePairingStatus).toHaveBeenCalled());
    expect(AsyncStorage.setItem).not.toHaveBeenCalledWith('twinotify_onboarding_complete', 'true');

    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));
    renderScreen(<SuccessScreen />);

    await waitFor(() => {
      expect(AsyncStorage.setItem)
        .toHaveBeenCalledWith('twinotify_onboarding_complete', 'true');
    });
  });

  test('does not complete nearby mode from an existing relay pair', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'committed',
      completed: false,
    }));
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
      paired: true,
      peerDeviceId: 'existing-peer',
      peerDisplayName: 'Relay phone',
    });

    const incomplete = renderScreen(<SuccessScreen />);

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getPairStatus).toHaveBeenCalled());
    expect(screen.getByText('Finishing pairing…')).toBeTruthy();
    expect(AsyncStorage.setItem).not.toHaveBeenCalledWith('twinotify_onboarding_complete', 'true');

    incomplete.unmount();
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));
    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    expect(AsyncStorage.setItem).toHaveBeenCalledWith('twinotify_onboarding_complete', 'true');
  });

  test('allows relay mode to complete from native relay pair status', async () => {
    await OnboardingState.setPairingMode('relay');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'idle',
      sessionId: null,
      completed: false,
    }));
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true });

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
  });

  test('shows bounded repair when native confirmation rejects', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'verify_code',
      sas: '204681',
    }));
    global.__TWINOTIFY_CORE__.confirmOfflinePairing.mockRejectedValue({
      code: 'pair_session_mismatch',
    });
    renderScreen(<VerifyScreen />);

    await screen.findByText('204 681');
    fireEvent.press(screen.getByRole('button', { name: 'Codes match' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.confirmOfflinePairing)
      .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111'));
    expect(await screen.findByText('Pairing session changed')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Return to nearby pairing' })).toBeTruthy();
  });

  test('cancels the exact verification session when codes do not match', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'verify_code',
      sas: '204681',
    }));
    renderScreen(<VerifyScreen />);

    await screen.findByText('204 681');
    fireEvent.press(screen.getByRole('button', { name: 'Codes do not match' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.cancelOfflinePairing)
      .toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111'));
    expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith({
      pathname: '/pair/fail',
      params: { reason: 'identity_mismatch' },
    });
  });

  test('keeps a native-complete pairing finished when relay sync cannot restart', async () => {
    await OnboardingState.setPairingMode('relay');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));
    global.__TWINOTIFY_CORE__.startSyncService.mockRejectedValue(new Error('relay unavailable'));
    await OnboardingState.setRelayUrl('wss://relay.invalid/ws');
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true });

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.startSyncService)
        .toHaveBeenCalledWith('wss://relay.invalid/ws');
    });
    expect(screen.getByText('Done')).toBeTruthy();
  });

  test('starts LAN-only sync after offline pairing', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).toHaveBeenCalledTimes(1);
    });
    expect(global.__TWINOTIFY_CORE__.startSyncService).not.toHaveBeenCalled();
  });

  test('keeps offline pairing complete when LAN-only startup fails', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));
    global.__TWINOTIFY_CORE__.startLanOnlySyncService.mockRejectedValue(new Error('LAN unavailable'));

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).toHaveBeenCalledTimes(1);
    });
    expect(global.__TWINOTIFY_CORE__.startSyncService).not.toHaveBeenCalled();
    expect(AsyncStorage.setItem).toHaveBeenCalledWith('twinotify_onboarding_complete', 'true');
  });

  test('ignores a stale relay URL for nearby pairing', async () => {
    await OnboardingState.setPairingMode('nearby');
    await OnboardingState.setRelayUrl('wss://stale-relay.invalid/ws');
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    await waitFor(() => {
      expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).toHaveBeenCalledTimes(1);
    });
    expect(global.__TWINOTIFY_CORE__.startSyncService).not.toHaveBeenCalled();
  });

  test('keeps relay pairing complete without starting a service when relay URL is absent', async () => {
    await OnboardingState.setPairingMode('relay');
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true });

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.getPairStatus).toHaveBeenCalled());
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(global.__TWINOTIFY_CORE__.startSyncService).not.toHaveBeenCalled();
    expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).not.toHaveBeenCalled();
  });

  test('offers a nearby upgrade without replacing an existing relay identity on mismatch', async () => {
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
      paired: true,
      peerDeviceId: 'existing-peer',
      peerDisplayName: 'Relay phone',
    });
    global.__TWINOTIFY_CORE__.computeFingerprint.mockResolvedValue('ab'.repeat(32));
    renderScreen(<PairDetailScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Enable nearby sync' }));
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/pair/nearby');

    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'idle',
      errorCode: 'identity_mismatch',
    }));
    renderScreen(<NearbyScreen />);
    expect(await screen.findByText(/Your existing relay pair is unchanged/)).toBeTruthy();
    expect(global.__TWINOTIFY_CORE__.unpair).not.toHaveBeenCalled();
    expect(global.__TWINOTIFY_CORE__.storePeerPubkeys).not.toHaveBeenCalled();
  });

  test('keeps primary controls visible, labeled, and at least 48dp tall', async () => {
    renderScreen(<ConnectScreen />);
    const nearby = await screen.findByRole('button', { name: 'Pair nearby without internet' });
    const styles = StyleSheet.flatten(nearby.props.style);

    expect(styles.minHeight ?? styles.height).toBeGreaterThanOrEqual(48);
    expect(styles.opacity ?? 1).toBeGreaterThan(0);
    expect(styles.overflow).not.toBe('hidden');
    expect(screen.getByText(/works on the same Wi-Fi/i)).toBeTruthy();
  });

  test('renders representative shared controls with button semantics', async () => {
    global.__SET_CAMERA_PERMISSION__({ granted: false });
    global.__SET_SEARCH_PARAMS__({ mode: 'nearby' });
    renderScreen(<ScanScreen />);

    expect(await screen.findByRole('button', { name: 'Allow camera' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Go back' })).toBeTruthy();
  });

  test('renders dark theme with structural constraints that avoid fixed text clipping', async () => {
    global.__SET_DARK_THEME__(true);
    const view = renderScreen(<ConnectScreen />);

    const nearby = await screen.findByRole('button', { name: 'Pair nearby without internet' });
    const style = StyleSheet.flatten(nearby.props.style);
    expect(style.minHeight).toBeGreaterThanOrEqual(48);
    expect(style.height).toBeUndefined();
    expect(style.overflow).not.toBe('hidden');
    expect(screen.getByText(/Both options keep notification contents/)).toBeTruthy();
    const scrollStyle = StyleSheet.flatten(view.UNSAFE_getByType(ScrollView).props.contentContainerStyle);
    expect(scrollStyle.flexGrow).toBe(1);
    for (const text of view.UNSAFE_getAllByType(Text)) {
      const textStyle = StyleSheet.flatten(text.props.style);
      expect(textStyle.height).toBeUndefined();
      expect(textStyle.overflow).not.toBe('hidden');
    }
  });

  test('keeps the relay connection test named, disabled, and busy while fetching', async () => {
    const response = deferred<Response>();
    const fetch = jest.spyOn(global, 'fetch').mockReturnValue(response.promise);
    renderScreen(<RelayScreen />);

    fireEvent.changeText(screen.getByLabelText('Relay URL'), 'wss://relay.invalid/ws');
    fireEvent.press(screen.getByRole('button', { name: 'Test connection' }));

    const testing = await screen.findByRole('button', { name: 'Test connection' });
    expect(testing.props.accessibilityState).toEqual({ disabled: true, busy: true });

    response.resolve(new Response(null, { status: 200 }));
    await screen.findByText(/Reached in/);
    fetch.mockRestore();
  });

  test('uses a plain relay countdown and vertically ranked fingerprint actions', async () => {
    await OnboardingState.setPairingMode('relay');
    await OnboardingState.setRelayUrl('wss://relay.invalid/ws');
    global.__TWINOTIFY_CORE__.startPairInitiator.mockResolvedValue(JSON.stringify({ pair_token: 'opaque' }));
    global.__TWINOTIFY_CORE__.awaitPeerHello.mockReturnValue(new Promise(() => {}));
    const qrView = renderScreen(<PairQRScreen />);

    const countdown = await screen.findByLabelText('Pairing time remaining 5 minutes 00 seconds');
    const countdownStyle = StyleSheet.flatten(countdown.props.style);
    expect(countdownStyle.backgroundColor).toBeUndefined();
    expect(countdownStyle.borderRadius).toBeUndefined();
    qrView.unmount();

    global.__SET_SEARCH_PARAMS__({
      role: 'A',
      relayUrl: 'wss://relay.invalid/ws',
      pairToken: 'opaque',
      peerDeviceId: 'peer',
      peerEncB64: 'enc',
      peerSignB64: 'sign',
      peerDisplayName: 'Other phone',
    });
    global.__TWINOTIFY_CORE__.computeFingerprint.mockResolvedValue('ab'.repeat(32));
    global.__TWINOTIFY_CORE__.getPublicKeys.mockResolvedValue({ encPubkey: 'enc', signPubkey: 'sign' });
    renderScreen(<FingerprintScreen />);

    await screen.findByRole('button', { name: 'They match' });
    const actions = screen.getAllByRole('button').map((button) => button.props.accessibilityLabel);
    expect(actions.indexOf('They match')).toBeLessThan(actions.indexOf("Don't match"));
  });
});
