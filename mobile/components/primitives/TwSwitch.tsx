import React, { useEffect } from 'react';
import { Pressable, StyleSheet } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  useReducedMotion,
} from 'react-native-reanimated';
import { useTheme } from '../Theme';

export type TwSwitchSize = 'md' | 'lg';

interface TwSwitchProps {
  checked: boolean;
  onChange?: (next: boolean) => void;
  size?: TwSwitchSize;
  disabled?: boolean;
  touchTargetSize?: 44 | 48;
  /** Required whenever the adjacent text does not already name the control. */
  accessibilityLabel?: string;
}

export function TwSwitch({
  checked,
  onChange,
  size = 'md',
  disabled,
  touchTargetSize = 44,
  accessibilityLabel,
}: TwSwitchProps) {
  const theme = useTheme();
  const w = 52;
  const h = 32;
  const d = checked ? 24 : 16;
  const thumbOffOn = w - d - 4;

  const progress = useSharedValue(checked ? 1 : 0);
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    progress.value = withTiming(checked ? 1 : 0, { duration: reduceMotion ? 0 : 180 });
  }, [checked, progress, reduceMotion]);

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
      style={[
        styles.target,
        { minWidth: Math.max(48, touchTargetSize), minHeight: Math.max(48, touchTargetSize), opacity: disabled ? 0.38 : 1 },
      ]}
    >
      <Animated.View
        testID="tw-switch-track"
        style={[
          styles.track,
          {
            width: w,
            height: h,
            borderRadius: h / 2,
            borderColor: checked ? theme.colors.primary : theme.colors.outline,
            backgroundColor: checked ? theme.colors.primary : theme.colors.surfaceContainerHighest,
          },
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
              top: (h - d) / 2,
              left: 4,
              backgroundColor: checked ? theme.colors.onPrimary : theme.colors.outline,
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
    borderWidth: 1,
  },
  target: {
    minWidth: 48,
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumb: {
    position: 'absolute',
  },
});
