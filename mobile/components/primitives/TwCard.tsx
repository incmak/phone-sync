import React from 'react';
import { Pressable, View, StyleSheet, ViewStyle } from 'react-native';
import { useTheme } from '../Theme';

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

  // Semantic tones always use the active mode's explicit foreground/surface pair.
  let bg = '';
  let borderColor = '';
  let borderWidth = 0;

  switch (tone) {
    case 'default':
      bg = theme.card;
      break;
    case 'raised':
      bg = theme.card;
      break;
    case 'fill':
      bg = theme.fill;
      borderColor = 'transparent';
      break;
    case 'accent':
      bg = theme.accentLo;
      borderColor = theme.accent;
      borderWidth = 1;
      break;
    case 'warn':
      bg = theme.sem.warn.surface;
      borderColor = theme.sem.warn.foreground;
      borderWidth = 1;
      break;
    case 'danger':
      bg = theme.sem.danger.surface;
      borderColor = theme.sem.danger.foreground;
      borderWidth = 1;
      break;
  }

  const cardStyle: ViewStyle = {
    backgroundColor: bg,
    borderColor,
    borderWidth,
    borderRadius: theme.radius.lg,
    padding,
    ...((interactive || onPress) ? { minWidth: 44, minHeight: 44 } : {}),
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
