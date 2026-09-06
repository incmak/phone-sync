import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { OnboardingState } from '../../../state/onboardingState';
import appJson from '../../../app.json';
import RelayScreen from '../relay';

// The screen reads its pre-filled relay from build config, so the tests drive that value
// directly rather than depending on how jest-expo happens to surface app.json.
let mockDefaultRelayUrl: string | undefined;
jest.mock('expo-constants', () => ({
  __esModule: true,
  default: {
    get expoConfig() {
      return {
        extra: mockDefaultRelayUrl === undefined ? {} : { defaultRelayUrl: mockDefaultRelayUrl },
      };
    },
  },
}));

const DEFAULT = 'https://relay.example.test';

beforeEach(async () => {
  jest.clearAllMocks();
  await AsyncStorage.clear();
  mockDefaultRelayUrl = DEFAULT;
  global.fetch = jest.fn(async () => ({ ok: true, status: 200 })) as unknown as typeof fetch;
});

function relayInput() {
  return screen.getByLabelText('Relay URL');
}

function continueDisabled() {
  return screen.getByRole('button', { name: 'Continue' }).props.accessibilityState.disabled;
}

async function passHealthTest() {
  fireEvent.press(screen.getByLabelText('Test connection'));
  await waitFor(() => expect(continueDisabled()).toBe(false));
}

describe('relay onboarding default', () => {
  test('pre-fills the relay URL from build config', () => {
    render(<RelayScreen />);

    expect(relayInput().props.value).toBe(DEFAULT);
  });

  test('a pre-filled relay is still gated on a successful health test', async () => {
    render(<RelayScreen />);

    expect(continueDisabled()).toBe(true);

    await passHealthTest();

    expect(global.fetch).toHaveBeenCalledWith(
      'https://relay.example.test/health',
      expect.anything(),
    );
  });

  test('a typed custom relay replaces the default', async () => {
    render(<RelayScreen />);

    fireEvent.changeText(relayInput(), 'https://relay.custom.test');
    await passHealthTest();
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(async () =>
      expect(await OnboardingState.getRelayUrl()).toBe('https://relay.custom.test'));
  });

  test('falls back to an empty field when build config carries no default', () => {
    mockDefaultRelayUrl = undefined;

    render(<RelayScreen />);

    expect(relayInput().props.value).toBe('');
    expect(continueDisabled()).toBe(true);
  });

  test('app.json ships a secure default relay', () => {
    const shipped = appJson.expo.extra.defaultRelayUrl;

    expect(shipped).toMatch(/^https:\/\//);
  });
});
