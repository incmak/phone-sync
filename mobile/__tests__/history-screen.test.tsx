import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import HistoryScreen from '../app/history';

describe('HistoryScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    global.__TWINOTIFY_CORE__.getHistory.mockResolvedValue([
      {
        appName: 'Messages', artworkDataUri: null, appGroupId: 'opaque-messages',
        direction: 'RECEIVED', kind: 'NOTIFICATION', status: 'APPLIED', route: 'LAN',
        occurredAt: Date.now(), title: 'Alice', preview: 'Dinner at seven?',
      },
      {
        appName: 'Mail', artworkDataUri: null, appGroupId: 'opaque-mail',
        direction: 'SENT', kind: 'NOTIFICATION', status: 'DELIVERED', route: 'RELAY',
        occurredAt: Date.now() - 1_000, title: null, preview: null,
      },
    ]);
    global.__TWINOTIFY_CORE__.getHistorySettings.mockResolvedValue({
      contentEnabled: true, retentionDays: 30, maxRows: 500, maxContentBytes: 2_097_152,
    });
    global.__TWINOTIFY_CORE__.getPairStatus.mockResolvedValue({ paired: true, peerDisplayName: 'POCO F1' });
  });

  it('shows useful retained content and supports app grouping', async () => {
    const screen = render(<HistoryScreen />);

    expect(await screen.findByText('Alice')).toBeTruthy();
    expect(screen.getByText('Dinner at seven?')).toBeTruthy();
    expect(screen.getByText(/Mirrored from POCO F1.*Direct/)).toBeTruthy();

    fireEvent.press(screen.getByRole('tab', { name: 'By app' }));

    expect(screen.getAllByText('Messages').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Clear Messages history')).toBeTruthy();
  });

  it('confirms destructive content deletion and keeps the native toggle authoritative', async () => {
    const alert = jest.spyOn(Alert, 'alert');
    const screen = render(<HistoryScreen />);
    await screen.findByText('Privacy and retention');

    fireEvent.press(screen.getByLabelText('Save notification titles and previews'));
    const buttons = alert.mock.calls[0][2];
    const destructive = buttons?.find((button) => button.style === 'destructive');
    await act(async () => {
      destructive?.onPress?.();
    });

    await waitFor(() => expect(global.__TWINOTIFY_CORE__.setHistoryContentEnabled).toHaveBeenCalledWith(false));
  });
});
