import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { useTheme } from '../Theme';
import type { NotificationDetail } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

function meaningful(value: string | null): string | null {
  return value?.trim() || null;
}

export function NotificationBody({ detail }: { detail: NotificationDetail }) {
  const theme = useTheme();
  const title = meaningful(detail.title);
  const text = meaningful(detail.text);
  const bigText = meaningful(detail.bigText);
  const subText = meaningful(detail.subText);
  const showBigText = Boolean(bigText && bigText !== text);

  return (
    <View style={styles.container}>
      {subText && subText !== title && subText !== text ? (
        <Text style={[theme.type.caption, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
          {subText}
        </Text>
      ) : null}
      {title ? (
        <Text style={[theme.type.title1, styles.title, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
          {title}
        </Text>
      ) : null}
      {text ? (
        <Text style={[theme.type.body, styles.paragraph, { color: theme.colors.onSurface, fontFamily: theme.fonts.ui }]}>
          {text}
        </Text>
      ) : null}
      {showBigText ? (
        <Text style={[theme.type.body, styles.paragraph, { color: theme.colors.onSurface, fontFamily: theme.fonts.ui }]}>
          {bigText}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 12,
  },
  title: {
    flexShrink: 1,
  },
  paragraph: {
    flexShrink: 1,
  },
});
