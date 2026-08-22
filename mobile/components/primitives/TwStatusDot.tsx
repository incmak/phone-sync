import React from 'react';
import { View, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';
import { TW_SEMANTIC } from '../tokens';

/** The delivery states the product reports. Kept in step with `state/routePresentation`. */
export type TwConnectionState = 'direct' | 'relay' | 'reconnecting' | 'queued' | 'unpaired';

interface TwStatusDotProps {
  state?: TwConnectionState;
  size?: number;
}

/**
 * A quiet presence marker for a device row.
 *
 * It does not pulse and carries no glow. A dot that breathes reads as decoration
 * standing in for information, and the route itself is always stated in words
 * next to it, so the colour only has to separate one row from another.
 */
export function TwStatusDot({ state = 'unpaired', size = 10 }: TwStatusDotProps) {
  const theme = useTheme();
  const stateColor: Record<TwConnectionState, string> = {
    direct: TW_SEMANTIC.ok,
    relay: TW_SEMANTIC.info,
    reconnecting: TW_SEMANTIC.warn,
    queued: TW_SEMANTIC.warn,
    // An unlinked device is quiet ink, not a colour that competes for attention.
    unpaired: theme.ink4,
  };
  const color = stateColor[state] ?? stateColor.unpaired;

  return (
    <View
      accessible={false}
      importantForAccessibility="no"
      style={[
        styles.dot,
        { width: size, height: size, borderRadius: size / 2, backgroundColor: color },
      ]}
    />
  );
}

const styles = StyleSheet.create({
  dot: {
    flexShrink: 0,
  },
});
