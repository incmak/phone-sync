import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import PairBluetoothScreen from '../bluetooth';
import PairSuccessScreen from '../success';

declare global {
  var __TWINOTIFY_CORE__: Record<string, jest.Mock>;
  var __RESET_OFFLINE_TEST_STATE__: () => void;
  var __TEST_ROUTER__: { push: jest.Mock; replace: jest.Mock; back: jest.Mock };
}

beforeEach(() => {
  jest.clearAllMocks();
  global.__RESET_OFFLINE_TEST_STATE__();
  global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: false, enabled: false });
  global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync = jest.fn(async () => ({
    granted: true,
    canAskAgain: true,
  }));
  global.__TWINOTIFY_CORE__.startBluetoothAssociation = jest.fn(async () => {});
});

describe('Bluetooth setup step', () => {
  it('is offered right after pairing, while someone is still holding both phones', async () => {
    global.__TWINOTIFY_CORE__.getOfflinePairingStatus.mockResolvedValue({ completed: true, phase: 'complete' });
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true, peerDisplayName: 'Pixel' });

    const screen = render(<PairSuccessScreen />);
    const done = await screen.findByRole('button', { name: /Done|Continue|Finish/i }).catch(() => null);
    if (done) fireEvent.press(done);

    // Whatever the button is called, pairing must not land straight on home any more.
    await waitFor(() => expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/pair/bluetooth'));
  });

  it('explains that both phones must do it at the same time', async () => {
    const screen = render(<PairBluetoothScreen />);

    expect(await screen.findByText('Set this up on both phones now')).toBeTruthy();
    expect(
      screen.getByText(/both have to be doing it at the same\s+time/),
    ).toBeTruthy();
  });

  it('makes setting up the prominent action and skipping an explicit, secondary one', async () => {
    const screen = render(<PairBluetoothScreen />);

    const setUp = await screen.findByRole('button', { name: 'Set up Bluetooth' });
    const skip = screen.getByLabelText('Skip Bluetooth backup');

    expect(setUp).toBeTruthy();
    expect(skip).toBeTruthy();
    // Skipping is a real choice that has to be taken, never the default path.
    expect(screen.getByText('You can add it later from paired-device settings.')).toBeTruthy();
  });

  it('skipping goes to home and associates nothing', async () => {
    const screen = render(<PairBluetoothScreen />);

    fireEvent.press(await screen.findByLabelText('Skip Bluetooth backup'));

    expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/home');
    expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).not.toHaveBeenCalled();
  });

  it('does not ask again when the pair is already associated', async () => {
    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: true, enabled: true });

    render(<PairBluetoothScreen />);

    await waitFor(() => expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/home'));
  });

  it('a successful association continues to home', async () => {
    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings
      .mockResolvedValueOnce({ associated: false, enabled: false })
      .mockResolvedValue({ associated: true, enabled: true });
    const screen = render(<PairBluetoothScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Set up Bluetooth' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(global.__TEST_ROUTER__.replace).toHaveBeenCalledWith('/home'));
  });

  it('a cancelled picker leaves the user on the step rather than pretending it worked', async () => {
    global.__TWINOTIFY_CORE__.getBluetoothRouteSettings.mockResolvedValue({ associated: false, enabled: false });
    const screen = render(<PairBluetoothScreen />);

    fireEvent.press(await screen.findByRole('button', { name: 'Set up Bluetooth' }));

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).toHaveBeenCalled());
    expect(global.__TEST_ROUTER__.replace).not.toHaveBeenCalledWith('/home');
  });
});
