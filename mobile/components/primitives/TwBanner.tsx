import React from 'react';
import { View, Text, StyleSheet, ViewStyle } from 'react-native';
import { useTheme } from '../Theme';
import { TW_SEMANTIC, hexWithAlpha } from '../tokens';
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

// bgOpacity: 0x1a/255≈0.10, 0x1f/255≈0.12, 0x14/255≈0.08; borderOpacity: 0x4d/255≈0.30
const TONE_COLORS: Record<TwBannerTone, { c: string; bgOpacity: number; borderOpacity: number }> = {
  info:   { c: TW_SEMANTIC.info,   bgOpacity: 0.10, borderOpacity: 0.30 },
  warn:   { c: TW_SEMANTIC.warn,   bgOpacity: 0.12, borderOpacity: 0.30 },
  danger: { c: TW_SEMANTIC.danger, bgOpacity: 0.08, borderOpacity: 0.30 },
  ok:     { c: TW_SEMANTIC.ok,     bgOpacity: 0.10, borderOpacity: 0.30 },
};

export function TwBanner({ tone = 'info', title, body, action, compact, icon, style }: TwBannerProps) {
  const theme = useTheme();
  const { c, bgOpacity, borderOpacity } = TONE_COLORS[tone];

  return (
    <View
      style={[
        styles.container,
        {
          paddingVertical: compact ? 10 : 14,
          paddingHorizontal: compact ? 14 : 16,
          backgroundColor: hexWithAlpha(c, bgOpacity),
          borderColor: hexWithAlpha(c, borderOpacity),
          borderRadius: theme.radius.md,
        },
        style,
      ]}
    >
      <View style={[styles.iconWrap, { marginTop: 1 }]}>
        {icon ?? <TwIcon name="alert" color={c} />}
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
