import React, { useEffect } from 'react';
import { Pressable, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  interpolateColor,
} from 'react-native-reanimated';
import { useTheme } from '../Theme';

export type TwSwitchSize = 'md' | 'lg';

interface TwSwitchProps {
  checked: boolean;
  onChange?: (next: boolean) => void;
  size?: TwSwitchSize;
  disabled?: boolean;
  /** Required whenever the adjacent text does not already name the control. */
  accessibilityLabel?: string;
}

export function TwSwitch({
  checked,
  onChange,
  size = 'md',
  disabled,
  accessibilityLabel,
}: TwSwitchProps) {
  const theme = useTheme();
  const w = size === 'lg' ? 52 : 44;
  const h = size === 'lg' ? 30 : 26;
  const d = h - 6; // thumb diameter
  const thumbOffOn = w - d - 3;

  const progress = useSharedValue(checked ? 1 : 0);

  useEffect(() => {
    progress.value = withTiming(checked ? 1 : 0, { duration: 180 });
  }, [checked, progress]);

  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      progress.value,
      [0, 1],
      [theme.borderHi, theme.accent],
    ),
  }));

  const thumbStyle = useAnimatedStyle(() => ({
    transform: [
      {
        translateX: progress.value * (thumbOffOn - 3),
      },
    ],
  }));

  return (
    <Pressable
      onPress={() => !disabled && onChange?.(!checked)}
      disabled={disabled}
      accessibilityRole="switch"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ checked, disabled: !!disabled }}
      // A 44pt target regardless of the visual size of the track.
      hitSlop={10}
      style={{ opacity: disabled ? 0.5 : 1 }}
    >
      <Animated.View
        style={[
          styles.track,
          trackStyle,
          { width: w, height: h, borderRadius: h / 2 },
        ]}
      >
        <Animated.View
          style={[
            styles.thumb,
            thumbStyle,
            {
              width: d,
              height: d,
              borderRadius: d / 2,
              top: 3,
              left: 3,
            },
          ]}
        />
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  track: {
    position: 'relative',
  },
  thumb: {
    position: 'absolute',
    backgroundColor: '#ffffff',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.2,
    shadowRadius: 3,
    elevation: 2,
  },
});
