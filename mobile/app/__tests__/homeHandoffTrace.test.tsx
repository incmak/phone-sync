import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Dimensions, StyleSheet } from 'react-native';
import HomeScreen from '../home';
import { OnboardingState } from '../../state/onboardingState';

jest.mock('react-native-reanimated', () => {
  const actual = jest.requireActual('react-native-reanimated');
  return {
    __esModule: true,
    ...actual,
    default: actual.default,
    useReducedMotion: jest.fn(() => true),
  };
});

type ScreenCase = {
  state: 'direct' | 'relay' | 'reconnecting' | 'queued' | 'paused' | 'unpaired';
  paired: boolean;
  syncState: 'CONNECTED' | 'DISCONNECTED';
  route: 'lan' | 'relay' | 'none';
  phase: 'authenticated' | 'idle';
  queued: number;
  label: string;
  explanation: string;
};

const cases: readonly ScreenCase[] = [
  {
    state: 'direct', paired: true, syncState: 'CONNECTED', route: 'lan', phase: 'authenticated', queued: 0,
    label: 'Direct on Wi-Fi', explanation: 'Your phones are talking to each other directly over Wi-Fi.',
  },
  {
    state: 'relay', paired: true, syncState: 'CONNECTED', route: 'relay', phase: 'authenticated', queued: 0,
    label: 'Via relay', explanation: 'Going through the relay, still encrypted end to end.',
  },
  {
    state: 'reconnecting', paired: true, syncState: 'CONNECTED', route: 'none', phase: 'idle', queued: 0,
    label: 'Reconnecting', explanation: 'Looking for your other phone. This retries on its own.',
  },
  {
    state: 'queued', paired: true, syncState: 'CONNECTED', route: 'none', phase: 'idle', queued: 2,
    label: 'Queued for delivery', explanation: '2 notifications are waiting for your other phone.',
  },
  {
    state: 'paused', paired: true, syncState: 'DISCONNECTED', route: 'lan', phase: 'authenticated', queued: 0,
    label: 'Paused', explanation: 'Turn on mirroring when you want delivery to resume.',
  },
  {
    state: 'unpaired', paired: false, syncState: 'DISCONNECTED', route: 'none', phase: 'idle', queued: 0,
    label: 'Not paired', explanation: 'Link your other phone to start mirroring notifications.',
  },
];

function arrange(routeCase: ScreenCase) {
  global.__RESET_OFFLINE_TEST_STATE__();
  jest.spyOn(OnboardingState, 'getRelayUrl').mockResolvedValue(null);
  global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue(
    routeCase.paired ? { paired: true, peerDisplayName: 'Pixel' } : { paired: false },
  );
  global.__TWINOTIFY_CORE__.getSyncStatus.mockResolvedValue({ state: routeCase.syncState, queuedCount: routeCase.queued });
  global.__TWINOTIFY_CORE__.getRouteStatus.mockResolvedValue({
    route: routeCase.route,
    phase: routeCase.phase,
    queued_count: routeCase.queued,
    route_generation: 0,
  });
}

function traceFor(screen: ReturnType<typeof render>, state: ScreenCase['state']) {
  return screen.UNSAFE_getByProps({ testID: `handoff-trace-${state}` });
}

function closestFlexDirection(node: ReturnType<typeof render>['root'] | null): unknown {
  let current = node;
  while (current) {
    const direction = StyleSheet.flatten(current.props.style)?.flexDirection;
    if (direction) return direction;
    current = current.parent;
  }
  return undefined;
}

describe('Home handoff trace', () => {
  it.each(cases)('$state binds presentRoute words and matching trace geometry', async (routeCase) => {
    arrange(routeCase);
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(traceFor(screen, routeCase.state)).toBeTruthy());
    expect(screen.getByTestId('connection-surface')).toBeTruthy();
    expect(screen.getAllByText(routeCase.label).length).toBeGreaterThan(0);
    expect(screen.getByText(routeCase.explanation)).toBeTruthy();
    expect(screen.getByLabelText(`${routeCase.label}. ${routeCase.explanation}`).props.accessibilityLiveRegion).toBe('polite');
  });

  it('keeps one pair action for an unpaired phone and no recovery action', async () => {
    arrange(cases[5]);
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Link your other phone' })).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Try again now' })).toBeNull();
  });

  it('keeps native mirror and route actions wired to their original operations', async () => {
    arrange(cases[3]);
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Try again now' })).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: 'Try again now' }));
    expect(global.__TWINOTIFY_CORE__.retryRoute).toHaveBeenCalledTimes(1);

    fireEvent.press(screen.getByRole('button', { name: 'Open settings' }));
    fireEvent.press(screen.getByRole('button', { name: 'Open paired phone' }));
    fireEvent.press(screen.getByRole('button', { name: 'Choose mirrored apps' }));
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings');
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/pair');
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/filter');
  });

  it('starts through relay when configured, starts LAN-only without it, and stops when turned off', async () => {
    arrange(cases[4]);
    jest.spyOn(OnboardingState, 'getRelayUrl').mockResolvedValue('https://relay.example.test');
    const relayScreen = render(<HomeScreen />);
    await waitFor(() => {
      const mirror = relayScreen.getByRole('switch', { name: 'Mirror notifications' });
      expect(mirror.props.accessibilityState.checked).toBe(false);
      expect(mirror.props.accessibilityState.disabled).toBe(false);
    });
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    fireEvent.press(relayScreen.getByRole('switch', { name: 'Mirror notifications' }));
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startSyncService).toHaveBeenCalledWith('https://relay.example.test'));
    expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).not.toHaveBeenCalled();
    relayScreen.unmount();

    arrange(cases[4]);
    const lanScreen = render(<HomeScreen />);
    await waitFor(() => {
      const mirror = lanScreen.getByRole('switch', { name: 'Mirror notifications' });
      expect(mirror.props.accessibilityState.checked).toBe(false);
      expect(mirror.props.accessibilityState.disabled).toBe(false);
    });
    fireEvent.press(lanScreen.getByRole('switch', { name: 'Mirror notifications' }));
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startLanOnlySyncService).toHaveBeenCalledTimes(1));
    lanScreen.unmount();

    arrange(cases[0]);
    const runningScreen = render(<HomeScreen />);
    await waitFor(() => {
      const mirror = runningScreen.getByRole('switch', { name: 'Mirror notifications' });
      expect(mirror.props.accessibilityState.checked).toBe(true);
      expect(mirror.props.accessibilityState.disabled).toBe(false);
    });
    fireEvent.press(runningScreen.getByRole('switch', { name: 'Mirror notifications' }));
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.stopSyncService).toHaveBeenCalledTimes(1));
  });

  it('rolls the mirror switch back after a failed native start', async () => {
    arrange(cases[4]);
    global.__TWINOTIFY_CORE__.startLanOnlySyncService.mockRejectedValueOnce(new Error('LAN unavailable'));
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.checked).toBe(false));
    fireEvent.press(screen.getByRole('switch', { name: 'Mirror notifications' }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.checked).toBe(false));
  });

  it('reflects mirror intent immediately while the native start is still pending', async () => {
    arrange(cases[4]);
    let finishStart: (() => void) | undefined;
    global.__TWINOTIFY_CORE__.startLanOnlySyncService.mockImplementationOnce(
      () => new Promise<void>((resolve) => { finishStart = resolve; }),
    );
    const screen = render(<HomeScreen />);

    const mirror = await screen.findByRole('switch', { name: 'Mirror notifications' });
    expect(mirror.props.accessibilityState.checked).toBe(false);

    fireEvent.press(mirror);

    expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.checked).toBe(true);
    await act(async () => { finishStart?.(); });
  });

  it('does not retain the generic card and Unicode icon treatment', async () => {
    arrange(cases[0]);
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(traceFor(screen, 'direct')).toBeTruthy());
    expect(screen.queryByText('⚙')).toBeNull();
    expect(screen.queryByText('›')).toBeNull();
    expect(screen.queryByText(/No mirrors yet/)).toBeNull();
    expect(screen.getByText('No activity yet')).toBeTruthy();
  });

  it('renders a conventional settings icon action without a text-tab treatment', async () => {
    arrange(cases[0]);
    const screen = render(<HomeScreen />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Open settings' })).toBeTruthy());
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Mirror notifications' }).props.accessibilityState.disabled).toBe(false));
    expect(screen.queryByText('settings')).toBeNull();
  });

  it('keeps narrow route status readable and physical touch targets safely above 44dp', async () => {
    const originalWindow = Dimensions.get('window');
    const originalScreen = Dimensions.get('screen');
    Dimensions.set({ window: { ...originalWindow, width: 320 }, screen: { ...originalScreen, width: 320 } });

    let screen: ReturnType<typeof render> | undefined;
    try {
      arrange(cases[2]);
      screen = render(<HomeScreen />);

      const liveStatus = await screen.findByLabelText(
        'Reconnecting. Looking for your other phone. This retries on its own.',
      );
      expect(closestFlexDirection(liveStatus)).toBe('column');
      expect(
        StyleSheet.flatten(
          screen.getByRole('button', { name: 'Open paired phone' }).props.style,
        ).minHeight,
      ).toBeGreaterThanOrEqual(48);
    } finally {
      screen?.unmount();
      Dimensions.set({ window: originalWindow, screen: originalScreen });
    }
  });

  it('renders real privacy-safe recent activity instead of the permanent placeholder', async () => {
    arrange(cases[0]);
    global.__TWINOTIFY_CORE__.getRecentActivity.mockResolvedValueOnce([{
      appName: 'Messages', artworkDataUri: null, direction: 'SENT', kind: 'NOTIFICATION',
      status: 'DELIVERED', route: 'LAN', occurredAt: 2_000,
    }]);
    const screen = render(<HomeScreen />);

    expect(await screen.findByText('Mirrored to Pixel')).toBeTruthy();
    expect(screen.queryByText(/No mirrors yet/)).toBeNull();
  });

  it('does not emit React lifecycle warnings while asserting reconnecting route truth', async () => {
    arrange(cases[2]);
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const screen = render(<HomeScreen />);

    try {
      await screen.findByLabelText(
        'Reconnecting. Looking for your other phone. This retries on its own.',
      );
      await new Promise((resolve) => setTimeout(resolve, 20));

      const actWarnings = consoleError.mock.calls.filter(([message]) =>
        String(message).includes('not wrapped in act'),
      );
      expect(actWarnings).toEqual([]);
    } finally {
      screen.unmount();
      consoleError.mockRestore();
    }
  });
});
