import { act, renderHook, waitFor } from '@testing-library/react-native';

import { useRecentActivity } from '../useRecentActivity';

const safeItem = {
  appName: 'Messages',
  artworkDataUri: null,
  direction: 'RECEIVED' as const,
  kind: 'NOTIFICATION' as const,
  status: 'APPLIED' as const,
  route: 'LAN' as const,
  occurredAt: 2_000,
};

describe('useRecentActivity', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__TWINOTIFY_CORE__.getRecentActivity.mockResolvedValue([safeItem]);
  });

  afterEach(() => jest.useRealTimers());

  it('loads immediately and refreshes every five seconds while focused', async () => {
    const { result } = renderHook(() => useRecentActivity(5));

    await waitFor(() => expect(result.current.kind).toBe('populated'));
    expect(global.__TWINOTIFY_CORE__.getRecentActivity).toHaveBeenCalledWith(5);

    await act(async () => jest.advanceTimersByTime(5_000));
    expect(global.__TWINOTIFY_CORE__.getRecentActivity).toHaveBeenCalledTimes(2);
  });

  it('keeps the previous success when a refresh fails', async () => {
    const { result } = renderHook(() => useRecentActivity(5));
    await waitFor(() => expect(result.current.kind).toBe('populated'));

    global.__TWINOTIFY_CORE__.getRecentActivity.mockRejectedValueOnce(new Error('offline'));
    await act(async () => jest.advanceTimersByTime(5_000));

    expect(result.current.kind).toBe('populated');
  });
});
