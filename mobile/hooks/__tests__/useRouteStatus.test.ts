import { act, renderHook, waitFor } from '@testing-library/react-native';

const mockGetRouteStatus = jest.fn();
const mockAddListener = jest.fn();
const mockRemove = jest.fn();

jest.mock('../../modules/twinotify-core/src/TwinotifyCoreModule', () => ({
  __esModule: true,
  default: {
    getRouteStatus: (...args: unknown[]) => mockGetRouteStatus(...args),
    addListener: (...args: unknown[]) => mockAddListener(...args),
  },
}));

import { useRouteStatus } from '../useRouteStatus';

describe('useRouteStatus', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAddListener.mockReturnValue({ remove: mockRemove });
    mockGetRouteStatus.mockResolvedValue({ route: 'none', phase: 'idle', queued_count: 0 });
  });

  it('starts not connected rather than guessing a route', () => {
    mockGetRouteStatus.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useRouteStatus());

    expect(result.current).toEqual({ route: 'none', phase: 'idle', queued_count: 0 });
  });

  it('reports the direct route when the native status says so', async () => {
    mockGetRouteStatus.mockResolvedValue({ route: 'lan', phase: 'authenticated', queued_count: 0 });

    const { result } = renderHook(() => useRouteStatus());

    await waitFor(() => expect(result.current.route).toBe('lan'));
    expect(result.current.phase).toBe('authenticated');
  });

  it('follows live route changes including queued reconnects', async () => {
    const { result } = renderHook(() => useRouteStatus());
    await waitFor(() => expect(mockAddListener).toHaveBeenCalledWith('onRouteStatus', expect.any(Function)));
    const emit = mockAddListener.mock.calls[0][1] as (s: unknown) => void;

    act(() => emit({ route: 'relay', phase: 'authenticated', queued_count: 2 }));
    expect(result.current).toEqual({ route: 'relay', phase: 'authenticated', queued_count: 2 });

    act(() => emit({ route: 'none', phase: 'reconnecting', queued_count: 5 }));
    expect(result.current).toEqual({ route: 'none', phase: 'reconnecting', queued_count: 5 });
  });

  it('keeps the not-connected default when the native call fails', async () => {
    mockGetRouteStatus.mockRejectedValue(new Error('no service'));

    const { result } = renderHook(() => useRouteStatus());

    await waitFor(() => expect(mockGetRouteStatus).toHaveBeenCalled());
    expect(result.current.route).toBe('none');
  });

  it('removes its listener on unmount', async () => {
    const { unmount } = renderHook(() => useRouteStatus());
    await waitFor(() => expect(mockAddListener).toHaveBeenCalled());

    unmount();

    expect(mockRemove).toHaveBeenCalled();
  });
});
