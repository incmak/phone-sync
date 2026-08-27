import React from 'react';
import { View, Text, StyleSheet, ViewStyle } from 'react-native';
import { useTheme } from '../Theme';
import { TwIcon } from './TwIcon';

export type TwBannerTone = 'info' | 'warn' | 'danger' | 'ok';

interface TwBannerProps {
  tone?: TwBannerTone;
  title: string;
  body?: string;
  action?: React.ReactNode;
  compact?: boolean;
  icon?: React.ReactNode;
  style?: ViewStyle;
}

export function TwBanner({ tone = 'info', title, body, action, compact, icon, style }: TwBannerProps) {
  const theme = useTheme();
  const semantic = theme.sem[tone];

  return (
    <View
      style={[
        styles.container,
        {
          paddingVertical: compact ? 10 : 14,
          paddingHorizontal: compact ? 14 : 16,
          backgroundColor: semantic.surface,
          borderColor: semantic.foreground,
          borderRadius: theme.radius.md,
        },
        style,
      ]}
    >
      <View style={[styles.iconWrap, { marginTop: 1 }]}>
        {icon ?? <TwIcon name="alert" color={semantic.foreground} />}
      </View>

      <View style={styles.body}>
        <Text
          style={[
            styles.title,
            { color: theme.ink, fontFamily: theme.fonts.uiSemi },
            body !== undefined && styles.titleWithBody,
          ]}
        >
          {title}
        </Text>

        {body !== undefined && (
          <Text style={[styles.bodyText, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
            {body}
          </Text>
        )}

        {action !== undefined && <View style={styles.action}>{action}</View>}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    gap: 12,
    borderWidth: 1,
  },
  iconWrap: {
    flexShrink: 0,
  },
  body: {
    flex: 1,
    minWidth: 0,
  },
  title: {
    fontSize: 14,
  },
  titleWithBody: {
    marginBottom: 2,
  },
  bodyText: {
    fontSize: 13,
    lineHeight: 19,
  },
  action: {
    marginTop: 8,
  },
});
