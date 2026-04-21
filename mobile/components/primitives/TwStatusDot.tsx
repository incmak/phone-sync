import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import { useTheme } from '../Theme';
import { TW_SEMANTIC } from '../tokens';

export type TwConnectionState = 'lan' | 'relay' | 'offline' | 'pairing';

interface TwStatusDotProps {
  state?: TwConnectionState;
  size?: number;
}

const STATE_MAP: Record<TwConnectionState, { c: string; pulse: boolean }> = {
  lan:     { c: TW_SEMANTIC.ok,     pulse: false },
  relay:   { c: TW_SEMANTIC.info,   pulse: false },
  offline: { c: TW_SEMANTIC.danger, pulse: false },
  pairing: { c: TW_SEMANTIC.warn,   pulse: true  },
};

export function TwStatusDot({ state = 'offline', size = 10 }: TwStatusDotProps) {
  useTheme(); // ensure we're inside provider
  const { c, pulse } = STATE_MAP[state] ?? STATE_MAP.offline;

  const scale = useSharedValue(1);
  const opacity = useSharedValue(0.6);

  useEffect(() => {
    if (pulse) {
      scale.value = withRepeat(
        withTiming(2.4, { duration: 1600, easing: Easing.out(Easing.ease) }),
        -1,
        false,
      );
      opacity.value = withRepeat(
        withTiming(0, { duration: 1600, easing: Easing.out(Easing.ease) }),
        -1,
        false,
      );
    } else {
      scale.value = 1;
      opacity.value = 0;
    }
  }, [pulse, scale, opacity]);

  const pulseStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
    opacity: opacity.value,
  }));

  return (
    <View style={[styles.container, { width: size, height: size }]}>
      <View style={[styles.dot, { width: size, height: size, borderRadius: size / 2, backgroundColor: c }]} />
      {pulse && (
        <Animated.View
          style={[
            styles.pulse,
            { width: size, height: size, borderRadius: size / 2, backgroundColor: c },
            pulseStyle,
          ]}
        />
      )}
    </View>
  );
}

export function twStatusLabel(state: TwConnectionState): string {
  const map: Record<TwConnectionState, string> = {
    lan:     'LAN · Direct',
    relay:   'Relay · Encrypted',
    offline: 'Offline',
    pairing: 'Pairing…',
  };
  return map[state] ?? 'Unknown';
}

const styles = StyleSheet.create({
  container: {
    position: 'relative',
  },
  dot: {
    position: 'absolute',
  },
  pulse: {
    position: 'absolute',
  },
});
