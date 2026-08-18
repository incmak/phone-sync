import React from 'react';
import { StyleSheet } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { ThemeProvider } from '../../../components/Theme';
import { OnboardingState } from '../../../state/onboardingState';
import ConnectScreen from '../../onboarding/connect';
import PairDetailScreen from '../../settings/pair';
import NearbyScreen from '../nearby';
import ScanScreen from '../scan';
import SuccessScreen from '../success';
import VerifyScreen from '../verify';

declare global {
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
  var __SET_SEARCH_PARAMS__: (params: Record<string, string>) => void;
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __EMIT_OFFLINE_STATUS__: (status: OfflineStatusFixture) => void;
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

beforeEach(async () => {
  global.__RESET_OFFLINE_TEST_STATE__();
  await AsyncStorage.clear();
});

describe('offline pairing UI behavior', () => {
  test('offers nearby pairing and relay pairing as explicit connection choices', async () => {
    renderScreen(<ConnectScreen />);

    expect(await screen.findByRole('button', { name: 'Pair nearby without internet' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Use a relay' })).toBeTruthy();
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

  test('marks onboarding complete only after native reports COMPLETE', async () => {
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

  test('keeps a native-complete pairing finished when relay sync cannot restart', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue(activeStatus({
      phase: 'complete',
      completed: true,
    }));
    global.__TWINOTIFY_CORE__.startSyncService.mockRejectedValue(new Error('relay unavailable'));
    await OnboardingState.setRelayUrl('wss://relay.invalid/ws');

    renderScreen(<SuccessScreen />);

    expect(await screen.findByText('Twinned.')).toBeTruthy();
    expect(screen.getByText('Done')).toBeTruthy();
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
});
