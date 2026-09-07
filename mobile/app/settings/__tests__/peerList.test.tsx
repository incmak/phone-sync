import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import PeerListScreen from '../peers';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
}

beforeEach(() => { jest.clearAllMocks(); global.__RESET_OFFLINE_TEST_STATE__(); });

it('opens the chosen device and displays each independent delivery state', async () => {
  global.__TWINOTIFY_CORE__.getPeerLinks.mockResolvedValue([
    { peerLinkId: 'phone', peerDisplayName: 'Pixel', lifecycle: 'ACTIVE', routeStatus: { presentation: { label: 'Direct' } } },
    { peerLinkId: 'mac', peerDisplayName: 'Mac', lifecycle: 'ACTIVE', routeStatus: { presentation: { label: 'Waiting for peer' } } },
  ]);
  const screen = render(<PeerListScreen />);
  expect(await screen.findByText('Direct')).toBeTruthy();
  expect(screen.getByText('Waiting for peer')).toBeTruthy();
  fireEvent.press(screen.getByText('Mac'));
  expect(global.__TEST_ROUTER__.push).toHaveBeenCalledWith({ pathname: '/settings/pair', params: { peerLinkId: 'mac' } });
  expect(screen.queryByText('Pair another device')).toBeNull();
});

it('keeps pending removal visible and disables its controls', async () => {
  global.__TWINOTIFY_CORE__.getPeerLinks.mockResolvedValue([
    { peerLinkId: 'mac', peerDisplayName: 'Mac', lifecycle: 'REMOVING', routeStatus: {} },
  ]);
  const screen = render(<PeerListScreen />);
  await waitFor(() => expect(screen.getByText(/Removing · cleanup/)).toBeTruthy());
  fireEvent.press(screen.getByText('Mac'));
  expect(global.__TEST_ROUTER__.push).not.toHaveBeenCalled();
});
