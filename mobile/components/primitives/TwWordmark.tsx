import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';
import { TwLogo, TwLogoVariant } from './TwLogo';

interface TwWordmarkProps {
  size?: number;
  color?: string;
  accent?: string;
  variant?: TwLogoVariant;
}

export function TwWordmark({ size = 20, color, accent, variant = 'pair' }: TwWordmarkProps) {
  const theme = useTheme();
  const fg = color ?? theme.ink;
  const ac = accent ?? theme.accent;

  return (
    <View style={styles.row}>
      <TwLogo size={size * 1.4} color={fg} accent={ac} variant={variant} />
      <View style={{ marginLeft: size * 0.4 }}>
        <Text
          allowFontScaling={false}
          style={[styles.wordmark, { fontSize: size, lineHeight: size * 1.3, color: fg, fontFamily: theme.fonts.uiSemi }]}
        >
          twin<Text style={{ color: ac }}>otify</Text>
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  wordmark: {
    letterSpacing: -0.4,
  },
});
