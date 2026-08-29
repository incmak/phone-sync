import React from 'react';
import { Text, StyleSheet } from 'react-native';

import { useTheme } from '../Theme';
import type { NotificationActionInvocationState } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const STATUS_COPY: Record<NotificationActionInvocationState, string> = {
  PENDING: 'Sending…',
  DISPATCHED: 'Sent',
  OUTCOME_UNKNOWN: 'Unconfirmed',
  FAILED: 'Could not send',
  ACTION_GONE: 'Action unavailable',
  NOTIFICATION_GONE: 'Notification unavailable',
  EXPIRED: 'Timed out',
};

export function actionStatusCopy(state: NotificationActionInvocationState | null): string | null {
  return state ? STATUS_COPY[state] : null;
}

export function NotificationActionStatus({ state }: { state: NotificationActionInvocationState | null }) {
  const theme = useTheme();
  const copy = actionStatusCopy(state);
  if (!copy) return null;

  const color = state === 'DISPATCHED'
    ? theme.sem.ok.foreground
    : state === 'PENDING'
      ? theme.colors.onSurfaceVariant
      : state === 'OUTCOME_UNKNOWN'
        ? theme.sem.warn.foreground
        : theme.sem.danger.foreground;

  return (
    <Text
      accessibilityLiveRegion="polite"
      style={[styles.text, theme.type.caption, { color, fontFamily: theme.fonts.ui }]}
    >
      {copy}
    </Text>
  );
}

const styles = StyleSheet.create({
  text: {
    marginTop: 8,
  },
});
