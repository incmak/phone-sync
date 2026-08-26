import React from 'react';
import { Pressable, View, Text, StyleSheet, ViewStyle } from 'react-native';
import { useTheme } from '../Theme';

interface TwRowProps {
  leading?: React.ReactNode;
  title: string;
  subtitle?: string;
  trailing?: React.ReactNode;
  onPress?: () => void;
  style?: ViewStyle;
  accessibilityLabel?: string;
  accessibilityHint?: string;
}

export function TwRow({ leading, title, subtitle, trailing, onPress, style, accessibilityLabel, accessibilityHint }: TwRowProps) {
  const theme = useTheme();

  const inner = (
    <>
      {leading}
      <View style={styles.content}>
        <Text
          style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiMedium }]}
        >
          {title}
        </Text>
        {subtitle !== undefined && (
          <Text
            style={[styles.subtitle, { color: theme.ink3, fontFamily: theme.fonts.ui }]}
          >
            {subtitle}
          </Text>
        )}
      </View>
      {trailing}
    </>
  );

  if (onPress) {
    return (
      <Pressable
        onPress={onPress}
        style={[styles.row, style]}
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel ?? (subtitle ? `${title}, ${subtitle}` : title)}
        accessibilityHint={accessibilityHint}
      >
        {inner}
      </Pressable>
    );
  }

  return <View style={[styles.row, style]}>{inner}</View>;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    paddingVertical: 14,
    paddingHorizontal: 4,
    minHeight: 48,
  },
  content: {
    flex: 1,
    minWidth: 0,
  },
  title: {
    fontSize: 15,
    lineHeight: 20,
  },
  subtitle: {
    fontSize: 13,
    marginTop: 2,
    lineHeight: 18,
  },
});
