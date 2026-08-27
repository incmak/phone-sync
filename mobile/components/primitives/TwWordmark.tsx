import React from 'react';
import { Text, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';

interface TwWordmarkProps {
  size?: number;
  color?: string;
}

export function TwWordmark({ size = 20, color }: TwWordmarkProps) {
  const theme = useTheme();
  const fg = color ?? theme.ink;

  return (
    <Text
      allowFontScaling={false}
      style={[styles.wordmark, { fontSize: size, lineHeight: size * 1.3, color: fg, fontFamily: theme.fonts.uiSemi }]}
    >
      twinotify
    </Text>
  );
}

const styles = StyleSheet.create({
  wordmark: {
    letterSpacing: -0.4,
  },
});
