import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Alert } from 'react-native';

import PairDetailScreen from '../pair';
import RelaySetupScreen from '../relay';
import { OnboardingState } from '../../../state/onboardingState';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
  var __SET_SEARCH_PARAMS__: (params: Record<string, string>) => void;
}

const DEFAULT_RELAY = 'https://relay.example.test';

jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { extra: { defaultRelayUrl: 'https://relay.example.test' } } },
}));

function pressAlertButton(alertSpy: jest.SpyInstance, text: string) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2] as { text?: string; onPress?: () => void }[] | undefined;
  const button = buttons?.find((candidate) => candidate.text === text);
  expect(button).toBeTruthy();
  act(() => button?.onPress?.());
}

async function renderPair({ relayUrl }: { relayUrl?: string } = {}) {
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({
    paired: true,
    peerDeviceId: 'peer-device-1234',
    peerDisplayName: 'Pixel',
    peerEncPubkey: 'enc',
    peerSignPubkey: 'sign',
  });
  global.__TWINOTIFY_CORE__.computeFingerprint.mockResolvedValue('ab'.repeat(32));
  global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: false, enabled: false });
  if (relayUrl) await OnboardingState.setRelayUrl(relayUrl);

  const screen = render(<PairDetailScreen />);
  await screen.findByText('Bluetooth fallback');
  return screen;
}

async function renderSetup({ mode }: { mode?: string } = {}) {
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__SET_SEARCH_PARAMS__(mode ? { mode } : {});
  global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true, peerDisplayName: 'Pixel' });
  global.fetch = jest.fn(async () => ({ ok: true, status: 200 })) as unknown as typeof fetch;
  const screen = render(<RelaySetupScreen />);
  await screen.findByLabelText('Relay address');
  return screen;
}

function saveButton(screen: ReturnType<typeof render>, label: string) {
  return screen.getByRole('button', { name: label });
}

beforeEach(async () => {
  jest.clearAllMocks();
  await OnboardingState.reset();
  global.__TWINOTIFY_CORE__.attachRelay.mockResolvedValue('attached');
  global.__TWINOTIFY_CORE__.detachRelay.mockResolvedValue('detached');
});

afterEach(() => jest.restoreAllMocks());

describe('relay management on the paired device screen', () => {
  it('offers adding a relay the same way as the other two routes, and says what it is for', async () => {
    const screen = await renderPair();

    expect(await screen.findByText('Add a relay')).toBeTruthy();
    expect(
      screen.getByText(
        'Reaches your other phone when it is on a different network, such as mobile data. Contents stay encrypted end to end.',
      ),
    ).toBeTruthy();

    fireEvent.press(screen.getByLabelText('Add a relay'));

    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/relay');
  });

  it('shows the configured relay with change and remove, not the add card', async () => {
    const screen = await renderPair({ relayUrl: DEFAULT_RELAY });

    await waitFor(() => expect(screen.getByText('Relay server')).toBeTruthy());
    expect(screen.queryByText('Add a relay')).toBeNull();
    expect(screen.getByText(`${DEFAULT_RELAY}. Carries notifications when the phones are on different networks, encrypted end to end.`)).toBeTruthy();

    fireEvent.press(screen.getByText('Change relay'));
    expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith('/settings/relay?mode=change');
  });

  it('removing a relay confirms first, keeps the pair, and drops back to the add card', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const screen = await renderPair({ relayUrl: DEFAULT_RELAY });
    await waitFor(() => expect(screen.getByText('Remove relay')).toBeTruthy());

    fireEvent.press(screen.getByText('Remove relay'));

    expect(alertSpy.mock.calls.at(-1)?.[0]).toBe('Stop using this relay?');
    expect(alertSpy.mock.calls.at(-1)?.[1]).toContain('Your pair and fingerprint stay as they are.');
    expect(global.__TWINOTIFY_CORE__.detachRelay).not.toHaveBeenCalled();

    pressAlertButton(alertSpy, 'Remove');

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.detachRelay).toHaveBeenCalledTimes(1));
    await waitFor(async () => expect(await OnboardingState.getRelayUrl()).toBeNull());
    expect(await screen.findByText('Add a relay')).toBeTruthy();
    // Removing a relay is not an unpair: the peer must survive it.
    expect(global.__TWINOTIFY_CORE__.unpair).not.toHaveBeenCalled();
  });

  it('says so plainly when the relay could not be told to forget the pair', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    global.__TWINOTIFY_CORE__.detachRelay.mockResolvedValue('detached_unrevoked');
    const screen = await renderPair({ relayUrl: DEFAULT_RELAY });
    await waitFor(() => expect(screen.getByText('Remove relay')).toBeTruthy());

    fireEvent.press(screen.getByText('Remove relay'));
    pressAlertButton(alertSpy, 'Remove');

    await waitFor(() => expect(alertSpy.mock.calls.at(-1)?.[0]).toBe('Relay removed here'));
    expect(await OnboardingState.getRelayUrl()).toBeNull();
  });
});

describe('relay setup screen', () => {
  it('pre-fills the built-in relay but still gates saving on a reachability test', async () => {
    const screen = await renderSetup();

    expect(screen.getByLabelText('Relay address').props.value).toBe(DEFAULT_RELAY);
    expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(true);

    fireEvent.press(screen.getByLabelText('Test connection'));

    await waitFor(() => expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(false));
    expect(global.fetch).toHaveBeenCalledWith('https://relay.example.test/health', expect.anything());
  });

  it('editing the address withdraws a passed test, so a untested relay cannot be saved', async () => {
    const screen = await renderSetup();
    fireEvent.press(screen.getByLabelText('Test connection'));
    await waitFor(() => expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(false));

    fireEvent.changeText(screen.getByLabelText('Relay address'), 'https://other.example.test');

    expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(true);
  });

  it('refuses a cleartext address without calling the network', async () => {
    const screen = await renderSetup();

    fireEvent.changeText(screen.getByLabelText('Relay address'), 'http://relay.example.test');
    fireEvent.press(screen.getByLabelText('Test connection'));

    expect(await screen.findByText('Use a secure https:// or wss:// address')).toBeTruthy();
    expect(global.fetch).not.toHaveBeenCalled();
    expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(true);
  });

  it('a successful attach records the relay for the JS service choice and returns', async () => {
    const screen = await renderSetup();
    fireEvent.press(screen.getByLabelText('Test connection'));
    await waitFor(() => expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(false));

    fireEvent.press(saveButton(screen, 'Add relay'));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.attachRelay).toHaveBeenCalledWith(DEFAULT_RELAY, ''));
    // home.tsx reads this to choose the relay-capable service, so the native endpoint alone
    // would be dropped on the next mirror toggle.
    await waitFor(async () => expect(await OnboardingState.getRelayUrl()).toBe(DEFAULT_RELAY));
    expect(global.__TEST_ROUTER__.back).toHaveBeenCalled();
  });

  it('maps each bounded failure to copy that promises nothing changed', async () => {
    const cases: [string, string][] = [
      ['peer_identity_mismatch', 'That was not your phone'],
      ['peer_timeout', 'The other phone did not answer'],
      ['no_direct_route', 'Bring the phones together'],
      ['relay_unreachable', 'Could not reach the relay'],
      ['not_paired', 'Pair the phones first'],
    ];

    for (const [code, title] of cases) {
      const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
      global.__TWINOTIFY_CORE__.attachRelay.mockResolvedValue(code);
      const screen = await renderSetup();
      fireEvent.press(screen.getByLabelText('Test connection'));
      await waitFor(() => expect(saveButton(screen, 'Add relay').props.accessibilityState.disabled).toBe(false));

      fireEvent.press(saveButton(screen, 'Add relay'));

      await waitFor(() => expect(alertSpy.mock.calls.at(-1)?.[0]).toBe(title));
      expect(alertSpy.mock.calls.at(-1)?.[1]).toContain('Nothing changed.');
      expect(await OnboardingState.getRelayUrl()).toBeNull();
      screen.unmount();
      alertSpy.mockRestore();
    }
  });

  it('keeps every label on the screen above the small-text contrast floor', async () => {
    // ink4 measures 3.22:1 on bg in light mode, under the 4.5:1 needed for small text, so the
    // field label and placeholder must not use it. Measured, not assumed.
    const { twTheme } = jest.requireActual('../../../components/tokens');
    const luminance = (hex: string) => {
      const n = hex.replace('#', '');
      const [r, g, b] = [0, 2, 4]
        .map((i) => parseInt(n.slice(i, i + 2), 16) / 255)
        .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const contrast = (a: string, b: string) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
      return (hi + 0.05) / (lo + 0.05);
    };

    for (const dark of [false, true]) {
      const theme = twTheme({ dark }) as Record<string, string>;
      expect(contrast(theme.ink3, theme.bg)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(theme.ink3, theme.fill)).toBeGreaterThanOrEqual(4.5);
    }

    const source = jest.requireActual('fs').readFileSync(
      `${__dirname}/../relay.tsx`,
      'utf8',
    ) as string;
    expect(source).not.toContain('theme.ink4');
  });

  it('titles and labels itself as a change when opened that way', async () => {
    const screen = await renderSetup({ mode: 'change' });

    // The header orients and the button acts, so the phrase legitimately appears twice.
    expect(screen.getAllByText('Change relay')).toHaveLength(2);
    expect(saveButton(screen, 'Change relay').props.accessibilityState.disabled).toBe(true);
  });
});
