import React, { useEffect } from 'react';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import Svg, { Circle } from 'react-native-svg';
import { useTheme } from '../Theme';

interface TwSpinnerProps {
  size?: number;
  color?: string;
}

export function TwSpinner({ size = 18, color }: TwSpinnerProps) {
  const theme = useTheme();
  const c = color ?? theme.accent;
  const rotation = useSharedValue(0);

  useEffect(() => {
    rotation.value = withRepeat(
      withTiming(360, { duration: 1000, easing: Easing.linear }),
      -1,
      false,
    );
  }, [rotation]);

  const animStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }));

  return (
    <Animated.View style={animStyle}>
      <Svg width={size} height={size} viewBox="0 0 18 18">
        <Circle
          cx="9"
          cy="9"
          r="7"
          fill="none"
          stroke={c}
          strokeWidth="2"
          strokeLinecap="round"
          strokeDasharray="20 60"
        />
      </Svg>
    </Animated.View>
  );
}
