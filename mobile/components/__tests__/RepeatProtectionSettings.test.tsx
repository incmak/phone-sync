import React from 'react';
import { Alert } from 'react-native';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { RepeatProtectionSettings } from '../RepeatProtectionSettings';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const core = jest.mocked(TwinotifyCoreModule);

beforeEach(() => {
  jest.clearAllMocks();
  core.getRepeatProtectionSettings.mockResolvedValue({ enabled: true, blockedCount: 0 });
});

afterEach(() => jest.restoreAllMocks());

it('loads the default-on setting and persists a toggle', async () => {
  const screen = render(<RepeatProtectionSettings />);
  const control = screen.getByRole('switch', { name: 'Repeat protection' });
  await waitFor(() => expect(control.props.accessibilityState.disabled).toBe(false));
  expect(control.props.accessibilityState.checked).toBe(true);
  core.getRepeatProtectionSettings.mockResolvedValue({ enabled: false, blockedCount: 0 });
  fireEvent.press(control);
  await waitFor(() => expect(core.setRepeatProtectionEnabled).toHaveBeenCalledWith(false));
  await waitFor(() => expect(control.props.accessibilityState.checked).toBe(false));
});

it('restores blocked notifications after their notice has been dismissed', async () => {
  core.getRepeatProtectionSettings.mockResolvedValue({ enabled: true, blockedCount: 2 });
  const screen = render(<RepeatProtectionSettings />);
  const restore = await screen.findByLabelText('Re-enable blocked notifications');
  core.getRepeatProtectionSettings.mockResolvedValue({ enabled: true, blockedCount: 0 });
  fireEvent.press(restore);
  await waitFor(() => expect(core.restoreRepeatBlockedNotifications).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(screen.queryByLabelText('Re-enable blocked notifications')).toBeNull());
});

it('keeps the saved setting visible when a write fails', async () => {
  jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  core.setRepeatProtectionEnabled.mockRejectedValueOnce(new Error('disk unavailable'));
  const screen = render(<RepeatProtectionSettings />);
  const control = screen.getByRole('switch', { name: 'Repeat protection' });
  await waitFor(() => expect(control.props.accessibilityState.disabled).toBe(false));
  fireEvent.press(control);
  await waitFor(() => expect(Alert.alert).toHaveBeenCalled());
  expect(control.props.accessibilityState.checked).toBe(true);
});
