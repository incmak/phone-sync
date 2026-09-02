import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import FilterScreen, { filterTabAccessibilityLabel } from '../filter';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function filterCore() {
  return global.__TWINOTIFY_CORE__;
}

const FILTERABLE_APPS = [
  {
    packageName: 'org.thoughtcrime.securesms',
    displayName: 'Signal',
    artworkDataUri: null,
    defaultFiltered: false,
    alwaysFiltered: false,
  },
  {
    packageName: 'com.whatsapp',
    displayName: 'WhatsApp',
    artworkDataUri: null,
    defaultFiltered: false,
    alwaysFiltered: false,
  },
  {
    packageName: 'com.spotify.music',
    displayName: 'Spotify',
    artworkDataUri: null,
    defaultFiltered: true,
    alwaysFiltered: false,
  },
  {
    packageName: 'com.authy.authy',
    displayName: 'Authenticator',
    artworkDataUri: null,
    defaultFiltered: false,
    alwaysFiltered: true,
  },
  ...Array.from({ length: 14 }, (_, index) => ({
    packageName: `com.example.app${String(index + 4).padStart(2, '0')}`,
    displayName: `App ${String(index + 4).padStart(2, '0')}`,
    artworkDataUri: null,
    defaultFiltered: false,
    alwaysFiltered: false,
  })),
];

async function appSwitch(
  screen: ReturnType<typeof render>,
  appName: string,
) {
  return await screen.findByRole('switch', { name: `${appName} mirroring` });
}

describe('app-filter persistence enforcement', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
    filterCore().getFilterableApps = jest.fn(async () => FILTERABLE_APPS);
    filterCore().getUserDenylist = jest.fn(async () => ['com.spotify.music']);
    filterCore().addToDenylist = jest.fn(async () => undefined);
    filterCore().removeFromDenylist = jest.fn(async () => undefined);
  });

  test('uses grammatical accessible app counts', () => {
    expect(filterTabAccessibilityLabel('Blocked', 1)).toBe('Blocked, 1 app');
    expect(filterTabAccessibilityLabel('Blocked', 2)).toBe('Blocked, 2 apps');
  });

  test('loads the complete native catalog and uses a route-neutral accessible back action', async () => {
    const screen = render(<FilterScreen />);

    expect(await screen.findByRole('button', { name: 'Back' })).toBeTruthy();
    await screen.findByText('Signal');
    expect(filterCore().getFilterableApps).toHaveBeenCalledTimes(1);

    fireEvent.changeText(screen.getByPlaceholderText('Search apps'), 'App 17');
    expect(await screen.findByText('App 17')).toBeTruthy();
    expect(screen.queryByText(/Settings/)).toBeNull();
    expect(screen.queryByText(/3 banking apps pre-blocked/i)).toBeNull();
  });

  test('rolls back a rejected block and announces a content-free error', async () => {
    filterCore().addToDenylist.mockRejectedValueOnce(
      new Error('failed for package org.thoughtcrime.securesms with private native details'),
    );
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal');

    fireEvent.press(signal);

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: 'Signal mirroring' }).props.accessibilityState.checked).toBe(true);
    });
    const alert = screen.getByRole('alert');
    expect(alert.props.accessibilityLiveRegion).toBe('assertive');
    expect(alert.props.children).toBe("Couldn't save this change. Try again.");
    expect(screen.queryByText(/private native details/i)).toBeNull();
    expect(screen.queryByText(/failed for package/i)).toBeNull();
  });

  test('prevents overlapping writes for one package while other rows remain interactive', async () => {
    const signalCommit = deferred<void>();
    filterCore().addToDenylist.mockImplementation((pkg: string) => (
      pkg === 'org.thoughtcrime.securesms' ? signalCommit.promise : Promise.resolve()
    ));
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal');
    const whatsapp = await appSwitch(screen, 'WhatsApp');

    fireEvent.press(signal);
    fireEvent.press(signal);
    expect(screen.getByRole('switch', { name: 'Signal mirroring' }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByRole('switch', { name: 'WhatsApp mirroring' }).props.accessibilityState.disabled).toBe(false);
    fireEvent.press(whatsapp);

    expect(filterCore().addToDenylist).toHaveBeenCalledTimes(2);
    expect(filterCore().addToDenylist).toHaveBeenNthCalledWith(1, 'org.thoughtcrime.securesms');
    expect(filterCore().addToDenylist).toHaveBeenNthCalledWith(2, 'com.whatsapp');

    await act(async () => {
      signalCommit.resolve();
      await signalCommit.promise;
    });
    await waitFor(() => {
      expect(screen.getByRole('switch', { name: 'Signal mirroring' }).props.accessibilityState.disabled).toBe(false);
      expect(screen.getByRole('switch', { name: 'WhatsApp mirroring' }).props.accessibilityState.disabled).toBe(false);
    });
  });

  test('rolls back a rejected allow to the durably blocked state', async () => {
    filterCore().getUserDenylist.mockResolvedValueOnce([
      'org.thoughtcrime.securesms',
      'com.spotify.music',
    ]);
    filterCore().removeFromDenylist.mockRejectedValueOnce(new Error('storage unavailable'));
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal');
    expect(signal.props.accessibilityState.checked).toBe(false);

    fireEvent.press(signal);

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: 'Signal mirroring' }).props.accessibilityState.checked).toBe(false);
    });
    expect(filterCore().removeFromDenylist).toHaveBeenCalledWith('org.thoughtcrime.securesms');
  });

  test('shows music apps as blocked by default and lets the user enable them', async () => {
    const screen = render(<FilterScreen />);
    const spotify = await appSwitch(screen, 'Spotify');

    expect(spotify.props.accessibilityState.checked).toBe(false);
    fireEvent.press(spotify);

    await waitFor(() => {
      expect(filterCore().removeFromDenylist).toHaveBeenCalledWith('com.spotify.music');
      expect(screen.getByRole('switch', { name: 'Spotify mirroring' }).props.accessibilityState.checked).toBe(true);
    });
  });

  test('shows protected apps as permanently blocked', async () => {
    const screen = render(<FilterScreen />);
    const authenticator = await appSwitch(screen, 'Authenticator');

    expect(authenticator.props.accessibilityState.checked).toBe(false);
    expect(authenticator.props.accessibilityState.disabled).toBe(true);
    fireEvent.press(authenticator);

    expect(filterCore().removeFromDenylist).not.toHaveBeenCalledWith('com.authy.authy');
    expect(filterCore().addToDenylist).not.toHaveBeenCalledWith('com.authy.authy');
  });

  test('searches real package names as well as display names', async () => {
    const screen = render(<FilterScreen />);
    await screen.findByText('Signal');

    fireEvent.changeText(screen.getByPlaceholderText('Search apps'), 'com.spotify');

    expect(await screen.findByText('Spotify')).toBeTruthy();
    expect(screen.queryByText('Signal')).toBeNull();
  });
});
