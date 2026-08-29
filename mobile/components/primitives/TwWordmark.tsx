import React from 'react';
import { Text, StyleSheet, View, type ColorValue } from 'react-native';
import { useTheme } from '../Theme';
import { TwinotifyMark } from './TwinotifyMark';

interface TwWordmarkProps {
  size?: number;
  color?: ColorValue;
}

export function TwWordmark({ size = 20, color }: TwWordmarkProps) {
  const theme = useTheme();
  const fg = color ?? theme.ink;

  return (
    <View style={styles.lockup}>
      <TwinotifyMark size={size * 1.14} color={fg} />
      <Text
        allowFontScaling={false}
        style={[styles.wordmark, { fontSize: size, lineHeight: size * 1.3, color: fg, fontFamily: theme.fonts.uiSemi }]}
      >
        twinotify
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  lockup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  wordmark: {
    letterSpacing: -0.4,
  },
});
