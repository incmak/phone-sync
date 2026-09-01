import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { StyleSheet } from 'react-native';

import { ThemeProvider } from '../../../components/Theme';
import { OnboardingState } from '../../../state/onboardingState';
import HowScreen from '../how';
import OemScreen from '../oem';
import PermsScreen from '../perms';

jest.mock('expo-notifications', () => ({
  requestPermissionsAsync: jest.fn(async () => ({ granted: true })),
}));

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
}

function renderScreen(element: React.ReactElement) {
  return render(<ThemeProvider>{element}</ThemeProvider>);
}

const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' };
const denied = { status: 'undetermined', granted: false, canAskAgain: true, expires: 'never' };

beforeEach(async () => {
  jest.clearAllMocks();
  global.__RESET_OFFLINE_TEST_STATE__();
  await AsyncStorage.clear();
  global.__TWINOTIFY_CORE__.isPostNotificationsGranted.mockResolvedValue(true);
  global.__TWINOTIFY_CORE__.isNotificationListenerGranted.mockResolvedValue(true);
});

describe('non-technical onboarding journey', () => {
  test('nearby mode explains and grants nearby access before Continue is enabled', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getNearbyWifiPermissionAsync.mockResolvedValue(denied);
    global.__TWINOTIFY_CORE__.requestNearbyWifiPermissionAsync.mockResolvedValue(granted);

    renderScreen(<PermsScreen />);

    expect(await screen.findByText('Twinotify needs three permissions for nearby pairing.')).toBeTruthy();
    expect(screen.getByText('Nearby devices')).toBeTruthy();
    const action = screen.getByRole('button', { name: 'Allow Nearby devices' });
    const actionStyle = StyleSheet.flatten(action.props.style);
    expect(actionStyle.width).toBeGreaterThanOrEqual(48);
    expect(actionStyle.height).toBeGreaterThanOrEqual(48);
    expect(screen.getByRole('button', { name: 'Continue' }).props.accessibilityState.disabled).toBe(true);

    fireEvent.press(action);

    await waitFor(() => expect(
      global.__TWINOTIFY_CORE__.requestNearbyWifiPermissionAsync,
    ).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(
      screen.getByRole('button', { name: 'Continue' }).props.accessibilityState.disabled,
    ).toBe(false));
  });

  test('relay mode does not ask for nearby-device access', async () => {
    await OnboardingState.setPairingMode('relay');

    renderScreen(<PermsScreen />);

    expect(await screen.findByText('Twinotify needs two permissions to mirror notifications.')).toBeTruthy();
    expect(screen.queryByText('Nearby devices')).toBeNull();
    expect(global.__TWINOTIFY_CORE__.getNearbyWifiPermissionAsync).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Continue' }).props.accessibilityState.disabled).toBe(false);
  });

  test('permanent nearby denial opens the explained Android settings recovery', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.getNearbyWifiPermissionAsync.mockResolvedValue(denied);
    global.__TWINOTIFY_CORE__.requestNearbyWifiPermissionAsync.mockResolvedValue({
      status: 'denied', granted: false, canAskAgain: false, expires: 'never',
    });

    renderScreen(<PermsScreen />);
    fireEvent.press(await screen.findByRole('button', { name: 'Allow Nearby devices' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1));
  });

  test('every ungranted permission action is named and at least 48dp', async () => {
    await OnboardingState.setPairingMode('nearby');
    global.__TWINOTIFY_CORE__.isPostNotificationsGranted.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.isNotificationListenerGranted.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.getNearbyWifiPermissionAsync.mockResolvedValue(denied);

    renderScreen(<PermsScreen />);

    for (const name of ['Allow Post notifications', 'Open Notification access settings', 'Allow Nearby devices']) {
      const action = await screen.findByRole('button', { name });
      const style = StyleSheet.flatten(action.props.style);
      expect(style.width).toBeGreaterThanOrEqual(48);
      expect(style.height).toBeGreaterThanOrEqual(48);
    }
  });

  test('the explainer promises selection and says only paired phones can read content', () => {
    renderScreen(<HowScreen />);

    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Mirror selected notifications')).toBeTruthy();
    expect(screen.getByText(/alerts you choose/i)).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText(/only your paired phones can read the content/i)).toBeTruthy();
  });

  test('background guidance matches the app-info destination', async () => {
    renderScreen(<OemScreen />);

    expect(screen.getByText(/tap App battery usage/i)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open app settings' }));
    await act(async () => { await Promise.resolve(); });
    expect(global.__TWINOTIFY_CORE__.openAppSettings).toHaveBeenCalledTimes(1);
  });
});
