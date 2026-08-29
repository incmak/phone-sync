import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import * as ReactNative from 'react-native';

import NotificationDetailRoute from '../app/notification/[detailId]';
import { NotificationDetailScreen } from '../components/notification-detail/NotificationDetailScreen';
import { TwIcon } from '../components/primitives/TwIcon';
import type { NotificationDetail } from '../modules/twinotify-core/src/TwinotifyCoreModule';

const DETAIL_ID = '11111111-1111-4111-8111-111111111111';

const ACTIVE_DETAIL: NotificationDetail = {
  detailId: DETAIL_ID,
  sourceAppName: 'WhatsApp',
  sourcePackage: 'com.whatsapp',
  sourceAppIconDataUri: 'data:image/png;base64,aWNvbg==',
  originDeviceLabel: 'MI 11X',
  title: 'New message from Sam',
  text: 'Are we still meeting at 9?',
  subText: 'Sam',
  bigText: 'Are we still meeting at 9? I can bring the notes.',
  smallIconDataUri: null,
  largeIconDataUri: null,
  receivedAt: 1_788_008_520_000,
  updatedAt: 1_788_008_520_000,
  state: 'ACTIVE',
  isAutoCancel: true,
  actions: [
    {
      actionId: '22222222-2222-4222-8222-222222222222',
      title: 'Reply',
      semantic: 1,
      reply: true,
      replyLabel: 'Message',
      invocationId: null,
      invocationState: null,
    },
    {
      actionId: '33333333-3333-4333-8333-333333333333',
      title: 'Archive',
      semantic: 5,
      reply: false,
      replyLabel: null,
      invocationId: null,
      invocationState: null,
    },
  ],
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe('notification detail screen', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
    global.__SET_SEARCH_PARAMS__({ detailId: DETAIL_ID });
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValue(ACTIVE_DETAIL);
    global.__TWINOTIFY_CORE__.invokeMirrorAction.mockResolvedValue({ status: 'queued', invocationId: 'invoke-1' });
    global.__TWINOTIFY_CORE__.canLaunchSourceApp.mockResolvedValue(false);
    global.__TWINOTIFY_CORE__.openNotificationSourceApp.mockResolvedValue(true);
  });

  it('loads the opaque route and shows a truthful loading state', async () => {
    const request = deferred<NotificationDetail | null>();
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockReturnValue(request.promise);

    const screen = render(<NotificationDetailRoute />);

    expect(screen.getByText('Loading notification')).toBeTruthy();
    expect(global.__TWINOTIFY_CORE__.getNotificationDetail).toHaveBeenCalledWith(DETAIL_ID);
    await act(async () => {
      request.resolve(ACTIVE_DETAIL);
      await request.promise;
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByText('New message from Sam')).toBeTruthy());
  });

  it('distinguishes expired content from a recoverable load failure', async () => {
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValueOnce(null);
    const missing = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
    await waitFor(() => expect(missing.getByText('This notification is no longer available')).toBeTruthy());
    expect(missing.queryByText('Try again')).toBeNull();
    missing.unmount();

    global.__TWINOTIFY_CORE__.getNotificationDetail
      .mockRejectedValueOnce(new Error('database unavailable'))
      .mockResolvedValueOnce(ACTIVE_DETAIL);
    const failed = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
    await waitFor(() => expect(failed.getByText('Couldn’t load this notification')).toBeTruthy());
    fireEvent.press(failed.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(failed.getByText('New message from Sam')).toBeTruthy());
  });

  it('renders full active content on one readable surface', async () => {
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);

    await waitFor(() => expect(screen.getByText('New message from Sam')).toBeTruthy());
    expect(screen.getByText('WhatsApp')).toBeTruthy();
    expect(screen.getByText('from MI 11X')).toBeTruthy();
    expect(screen.getByText('Active')).toBeTruthy();
    expect(screen.getByText('Are we still meeting at 9?')).toBeTruthy();
    expect(screen.getByText('Are we still meeting at 9? I can bring the notes.')).toBeTruthy();
    expect(screen.getByTestId('notification-source-icon', { includeHiddenElements: true }).props.accessibilityElementsHidden).toBe(true);
    expect(screen.queryByTestId('notification-content-card')).toBeNull();
  });

  it('invokes non-reply actions and keeps pending controls disabled', async () => {
    const pending = {
      ...ACTIVE_DETAIL,
      actions: ACTIVE_DETAIL.actions.map((action) => action.title === 'Archive'
        ? { ...action, invocationId: 'invoke-1', invocationState: 'PENDING' as const }
        : action),
    };
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValue(pending);
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);

    const archive = await screen.findByRole('button', { name: 'Archive' });
    expect(archive.props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('Sending…')).toBeTruthy();
    fireEvent.press(archive);
    expect(global.__TWINOTIFY_CORE__.invokeMirrorAction).not.toHaveBeenCalled();
  });

  it('bounds focused polling for a pending action restored after process death', async () => {
    jest.useFakeTimers();
    const pending = {
      ...ACTIVE_DETAIL,
      actions: ACTIVE_DETAIL.actions.map((action) => action.title === 'Archive'
        ? { ...action, invocationId: 'invoke-1', invocationState: 'PENDING' as const }
        : action),
    };
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValue(pending);

    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
      await act(async () => { await Promise.resolve(); });
      await act(async () => { await Promise.resolve(); });

      act(() => { jest.advanceTimersByTime(121_000); });
      await act(async () => { await Promise.resolve(); });
      const callsAtTimeout = global.__TWINOTIFY_CORE__.getNotificationDetail.mock.calls.length;

      act(() => { jest.advanceTimersByTime(10_000); });
      await act(async () => { await Promise.resolve(); });
      expect(global.__TWINOTIFY_CORE__.getNotificationDetail).toHaveBeenCalledTimes(callsAtTimeout);
    } finally {
      screen?.unmount();
      jest.useRealTimers();
    }
  });

  it('validates and submits an inline reply through the shared native invoker', async () => {
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
    await screen.findByText('New message from Sam');

    fireEvent.press(screen.getByRole('button', { name: 'Reply' }));
    const input = screen.getByLabelText('Reply');
    expect(input.props.placeholder).toBe('Write a reply');
    fireEvent(input, 'focus');
    expect(ReactNative.StyleSheet.flatten(input.props.style).borderColor).toBe('#1F685A');
    fireEvent.press(screen.getByRole('button', { name: 'Send reply' }));
    expect(screen.getByText('Write a reply first')).toBeTruthy();

    fireEvent.changeText(input, 'On my way');
    fireEvent.press(screen.getByRole('button', { name: 'Send reply' }));
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.invokeMirrorAction).toHaveBeenCalledWith(
      DETAIL_ID,
      ACTIVE_DETAIL.actions[0].actionId,
      'On my way',
    ));
    expect(global.__TWINOTIFY_CORE__.getNotificationDetail).toHaveBeenCalledTimes(2);
  });

  it('presents a locked non-reply action instead of failing silently', async () => {
    global.__TWINOTIFY_CORE__.invokeMirrorAction.mockResolvedValue({ status: 'locked', invocationId: null });
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
    const archive = await screen.findByRole('button', { name: 'Archive' });

    fireEvent.press(archive);

    expect(await screen.findByText('Unlock this phone to continue')).toBeTruthy();
  });

  it('preserves native dynamic color objects for SVG controls', () => {
    const dynamicColor: ReactNative.ColorValue = ReactNative.PlatformColor('@android:color/system_neutral1_900');
    const icon = <TwIcon name="chevronLeft" color={dynamicColor} />;

    expect(icon.props.color).toBe(dynamicColor);
  });

  it.each([
    ['DISPATCHED', 'Sent'],
    ['OUTCOME_UNKNOWN', 'Unconfirmed'],
    ['FAILED', 'Could not send'],
    ['ACTION_GONE', 'Action unavailable'],
    ['NOTIFICATION_GONE', 'Notification unavailable'],
    ['EXPIRED', 'Timed out'],
  ] as const)('presents %s as %s without raw native detail', async (invocationState, copy) => {
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValue({
      ...ACTIVE_DETAIL,
      actions: [{ ...ACTIVE_DETAIL.actions[1], invocationId: 'invoke-1', invocationState }],
    });
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);

    expect(await screen.findByText(copy)).toBeTruthy();
    expect(screen.queryByText(invocationState)).toBeNull();
  });

  it('keeps cancelled content readable but removes invokable controls', async () => {
    global.__TWINOTIFY_CORE__.getNotificationDetail.mockResolvedValue({ ...ACTIVE_DETAIL, state: 'CANCELLED' });
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);

    expect(await screen.findByText('Dismissed on one of your phones')).toBeTruthy();
    expect(screen.getByText('Are we still meeting at 9?')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reply' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Archive' })).toBeNull();
  });

  it('shows a working source-app action only while launchable', async () => {
    global.__TWINOTIFY_CORE__.canLaunchSourceApp.mockResolvedValue(true);
    global.__TWINOTIFY_CORE__.openNotificationSourceApp.mockResolvedValue(false);
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);

    const open = await screen.findByRole('button', { name: 'Open WhatsApp' });
    fireEvent.press(open);
    await waitFor(() => expect(global.__TWINOTIFY_CORE__.openNotificationSourceApp).toHaveBeenCalledWith(DETAIL_ID));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Open WhatsApp' })).toBeNull());
  });

  it('supports both app-bar and hardware back with 48-point targets', async () => {
    const screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
    await screen.findByText('New message from Sam');
    const back = screen.getByRole('button', { name: 'Back' });
    const backStyle = ReactNative.StyleSheet.flatten(back.props.style);
    expect(backStyle.minWidth).toBeGreaterThanOrEqual(48);
    expect(backStyle.minHeight).toBeGreaterThanOrEqual(48);

    fireEvent.press(back);
    expect(global.__TEST_ROUTER__.back).toHaveBeenCalledTimes(1);
    global.__PRESS_HARDWARE_BACK__();
    expect(global.__TEST_ROUTER__.back).toHaveBeenCalledTimes(2);
  });

  it('reflows the source header at 200 percent font scale without fixed text heights', async () => {
    const originalWindow = ReactNative.Dimensions.get('window');
    const originalScreen = ReactNative.Dimensions.get('screen');
    ReactNative.Dimensions.set({
      window: { ...originalWindow, width: 320, height: 640, fontScale: 2 },
      screen: { ...originalScreen, width: 320, height: 640, fontScale: 2 },
    });

    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<NotificationDetailScreen detailId={DETAIL_ID} />);
      const title = await screen.findByText('New message from Sam');
      const header = screen.getByTestId('notification-source-header-content');
      expect(ReactNative.StyleSheet.flatten(header.props.style).flexDirection).toBe('column');
      expect(title.props.allowFontScaling).not.toBe(false);
      expect(ReactNative.StyleSheet.flatten(title.props.style).height).toBeUndefined();
      expect(ReactNative.StyleSheet.flatten(screen.getByTestId('notification-detail-content').props.contentContainerStyle).paddingHorizontal).toBe(16);
    } finally {
      screen?.unmount();
      ReactNative.Dimensions.set({ window: originalWindow, screen: originalScreen });
    }
  });
});
