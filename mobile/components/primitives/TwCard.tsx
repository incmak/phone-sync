import React from 'react';
import { Pressable, View, StyleSheet, ViewStyle } from 'react-native';
import { useTheme } from '../Theme';
import { TW_SEMANTIC, hexWithAlpha } from '../tokens';

export type TwCardTone = 'default' | 'raised' | 'fill' | 'accent' | 'warn' | 'danger';

interface TwCardProps {
  children?: React.ReactNode;
  padding?: number;
  tone?: TwCardTone;
  interactive?: boolean;
  onPress?: () => void;
  style?: ViewStyle;
}

export function TwCard({
  children,
  padding = 20,
  tone = 'default',
  interactive,
  onPress,
  style,
}: TwCardProps) {
  const theme = useTheme();

  // Pre-compute tone colors. color-mix(in oklch, ...) is replaced with
  // static hex blends: warn/danger backgrounds use an approximate tint.
  let bg = '';
  let borderColor = '';
  let shadowStyle = {};

  switch (tone) {
    case 'default':
      bg = theme.card;
      borderColor = theme.border;
      break;
    case 'raised':
      bg = theme.card;
      borderColor = theme.border;
      shadowStyle = theme.shadowSm;
      break;
    case 'fill':
      bg = theme.fill;
      borderColor = 'transparent';
      break;
    case 'accent':
      bg = theme.accentLo;
      borderColor = hexWithAlpha(theme.accent, 0.25); // ~25% opacity
      break;
    case 'warn':
      // Approximate color-mix(warn 10%, card): blend warn at low opacity over card
      bg = hexWithAlpha(TW_SEMANTIC.warn, 0.10); // ~10% opacity layer
      borderColor = hexWithAlpha(TW_SEMANTIC.warn, 0.30); // ~30%
      break;
    case 'danger':
      bg = hexWithAlpha(TW_SEMANTIC.danger, 0.08); // ~8%
      borderColor = hexWithAlpha(TW_SEMANTIC.danger, 0.30); // ~30%
      break;
  }

  const cardStyle: ViewStyle = {
    backgroundColor: bg,
    borderColor,
    borderWidth: 1,
    borderRadius: theme.radius.lg,
    padding,
    ...shadowStyle,
  };

  if (interactive || onPress) {
    return (
      <Pressable onPress={onPress} style={[cardStyle, style]}>
        {children}
      </Pressable>
    );
  }

  return <View style={[cardStyle, style, styles.base]}>{children}</View>;
}

const styles = StyleSheet.create({
  base: {},
});
