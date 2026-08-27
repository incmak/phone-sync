import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import FilterScreen from '../filter';

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

async function appSwitch(
  screen: ReturnType<typeof render>,
  appName: string,
  index: number,
) {
  await screen.findByText(appName);
  return screen.getAllByRole('switch')[index];
}

describe('app-filter persistence enforcement', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
    filterCore().getUserDenylist = jest.fn(async () => []);
    filterCore().addToDenylist = jest.fn(async () => undefined);
    filterCore().removeFromDenylist = jest.fn(async () => undefined);
  });

  test('rolls back a rejected block and announces a content-free error', async () => {
    filterCore().addToDenylist.mockRejectedValueOnce(
      new Error('failed for package signal with private native details'),
    );
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal', 0);

    fireEvent.press(signal);

    await waitFor(() => {
      expect(screen.getAllByRole('switch')[0].props.accessibilityState.checked).toBe(true);
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
      pkg === 'signal' ? signalCommit.promise : Promise.resolve()
    ));
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal', 0);
    const whatsapp = screen.getAllByRole('switch')[1];

    fireEvent.press(signal);
    fireEvent.press(signal);
    expect(screen.getAllByRole('switch')[0].props.accessibilityState.disabled).toBe(true);
    expect(screen.getAllByRole('switch')[1].props.accessibilityState.disabled).toBe(false);
    fireEvent.press(whatsapp);

    expect(filterCore().addToDenylist).toHaveBeenCalledTimes(2);
    expect(filterCore().addToDenylist).toHaveBeenNthCalledWith(1, 'signal');
    expect(filterCore().addToDenylist).toHaveBeenNthCalledWith(2, 'whatsapp');

    await act(async () => {
      signalCommit.resolve();
      await signalCommit.promise;
    });
    await waitFor(() => {
      expect(screen.getAllByRole('switch')[0].props.accessibilityState.disabled).toBe(false);
      expect(screen.getAllByRole('switch')[1].props.accessibilityState.disabled).toBe(false);
    });
  });

  test('rolls back a rejected allow to the durably blocked state', async () => {
    filterCore().getUserDenylist.mockResolvedValueOnce(['signal']);
    filterCore().removeFromDenylist.mockRejectedValueOnce(new Error('storage unavailable'));
    const screen = render(<FilterScreen />);
    const signal = await appSwitch(screen, 'Signal', 0);
    expect(signal.props.accessibilityState.checked).toBe(false);

    fireEvent.press(signal);

    await waitFor(() => {
      expect(screen.getAllByRole('switch')[0].props.accessibilityState.checked).toBe(false);
    });
    expect(filterCore().removeFromDenylist).toHaveBeenCalledWith('signal');
  });
});
