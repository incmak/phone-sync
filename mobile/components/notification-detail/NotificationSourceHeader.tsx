import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';

import { useTheme } from '../Theme';
import type { NotificationDetail } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

function receivedTime(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(timestamp));
}

function stateCopy(state: NotificationDetail['state']): string {
  if (state === 'CANCELLED') return 'Dismissed on one of your phones';
  if (state === 'GONE') return 'No longer active';
  return 'Active';
}

export function NotificationSourceHeader({
  detail,
  stacked,
}: {
  detail: NotificationDetail;
  stacked: boolean;
}) {
  const theme = useTheme();
  const appName = detail.sourceAppName?.trim() || detail.sourcePackage;

  return (
    <View
      testID="notification-source-header-content"
      style={[styles.container, { flexDirection: stacked ? 'column' : 'row' }]}
    >
      {detail.sourceAppIconDataUri ? (
        <Image
          testID="notification-source-icon"
          source={{ uri: detail.sourceAppIconDataUri }}
          accessibilityElementsHidden
          importantForAccessibility="no"
          resizeMode="contain"
          style={styles.icon}
        />
      ) : null}
      <View style={styles.copy}>
        <Text style={[theme.type.title2, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
          {appName}
        </Text>
        <Text style={[theme.type.body, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
          from {detail.originDeviceLabel}
        </Text>
        <View style={[styles.metadata, stacked && styles.metadataStacked]}>
          <Text style={[theme.type.caption, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
            {receivedTime(detail.receivedAt)}
          </Text>
          <Text
            accessibilityLiveRegion="polite"
            style={[
              theme.type.caption,
              {
                color: detail.state === 'ACTIVE' ? theme.colors.onSurfaceVariant : theme.sem.warn.foreground,
                fontFamily: theme.fonts.uiMedium,
              },
            ]}
          >
            {stateCopy(detail.state)}
          </Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'flex-start',
    gap: 16,
  },
  icon: {
    width: 44,
    height: 44,
  },
  copy: {
    flexShrink: 1,
    gap: 2,
  },
  metadata: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'baseline',
    gap: 12,
    marginTop: 2,
  },
  metadataStacked: {
    flexDirection: 'column',
    gap: 2,
  },
});
