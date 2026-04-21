import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { useTheme } from '../Theme';

interface TwEmptyProps {
  title: string;
  body?: string;
  cta?: React.ReactNode;
  art?: React.ReactNode;
}

export function TwEmpty({ title, body, cta, art }: TwEmptyProps) {
  const theme = useTheme();

  const defaultArt = (
    <Svg width="64" height="64" viewBox="0 0 64 64" fill="none">
      <Circle cx="24" cy="32" r="14" stroke={theme.ink4} strokeWidth="1.6" strokeDasharray="3 4" />
      <Circle cx="40" cy="32" r="14" stroke={theme.accent} strokeWidth="1.6" />
    </Svg>
  );

  return (
    <View style={styles.container}>
      {art ?? defaultArt}

      <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
        {title}
      </Text>

      {body !== undefined && (
        <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          {body}
        </Text>
      )}

      {cta}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: 14,
    padding: 32,
  },
  title: {
    fontSize: 17,
    textAlign: 'center',
  },
  body: {
    fontSize: 14,
    textAlign: 'center',
    maxWidth: 280,
    lineHeight: 21,
  },
});
