import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import FingerprintScreen from '../fingerprint';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
  var __SET_SEARCH_PARAMS__: (params: Record<string, string>) => void;
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
}

const params = { pairToken: 'token', peerEncB64: 'enc', peerSignB64: 'sign',
  peerDeviceId: 'peer', peerDisplayName: 'Pixel', relayUrl: 'https://relay.example.com' };

beforeEach(() => {
  jest.clearAllMocks();
  global.__RESET_OFFLINE_TEST_STATE__();
  const core = global.__TWINOTIFY_CORE__;
  core.getPublicKeys.mockResolvedValue({ encPubkey: 'own-enc', signPubkey: 'own-sign' });
  core.computeFingerprint.mockResolvedValue('abcd'.repeat(16));
  core.deviceASignConfirmation.mockResolvedValue('signature');
  core.sendConfirmationSig.mockResolvedValue(undefined);
  core.storePeerPubkeys.mockResolvedValue('link');
});

it('does not save or finish the initiating phone before responder completion', async () => {
  global.__SET_SEARCH_PARAMS__({ ...params, role: 'A' });
  let complete!: (pairId: string) => void;
  global.__TWINOTIFY_CORE__.awaitPairComplete.mockReturnValue(new Promise<string>((resolve) => { complete = resolve; }));
  const screen = render(<FingerprintScreen />);
  fireEvent.press(await screen.findByRole('button', { name: 'They match' }));
  await waitFor(() => expect(global.__TWINOTIFY_CORE__.awaitPairComplete).toHaveBeenCalled());
  expect(global.__TWINOTIFY_CORE__.storePeerPubkeys).not.toHaveBeenCalled();
  expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalled();
  await act(async () => complete('pair-id'));
  await waitFor(() => expect(global.__TWINOTIFY_CORE__.storePeerPubkeys).toHaveBeenCalledWith(
    'enc', 'sign', 'peer', 'Pixel', params.relayUrl, 'pair-id'));
  expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith({ pathname: '/pair/success', params: { peerLinkId: 'link' } });
});

it('retains the completed responder pair ID when saving the peer', async () => {
  global.__SET_SEARCH_PARAMS__({ ...params, role: 'B' });
  global.__TWINOTIFY_CORE__.awaitPairSig.mockResolvedValue('signature');
  global.__TWINOTIFY_CORE__.deviceBCompletePairing.mockResolvedValue('responder-pair');
  const screen = render(<FingerprintScreen />);
  fireEvent.press(await screen.findByRole('button', { name: 'They match' }));
  await waitFor(() => expect(global.__TWINOTIFY_CORE__.storePeerPubkeys).toHaveBeenCalledWith(
    'enc', 'sign', 'peer', 'Pixel', params.relayUrl, 'responder-pair'));
});
