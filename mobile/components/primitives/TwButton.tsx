import React, { useCallback } from 'react';
import {
  Pressable,
  Text,
  StyleSheet,
  ActivityIndicator,
  type PressableProps,
  type ViewStyle,
  useWindowDimensions,
} from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
  useReducedMotion,
} from 'react-native-reanimated';
import { useTheme } from '../Theme';
import { TW_SEMANTIC, hexWithAlpha } from '../tokens';

export type TwButtonVariant = 'primary' | 'accent' | 'secondary' | 'ghost' | 'destructive';
export type TwButtonSize = 'sm' | 'md' | 'lg';

interface TwButtonProps extends Pick<PressableProps, 'accessibilityHint' | 'accessibilityLabel' | 'testID'> {
  variant?: TwButtonVariant;
  size?: TwButtonSize;
  icon?: React.ReactNode;
  children?: React.ReactNode;
  onPress?: () => void;
  disabled?: boolean;
  loading?: boolean;
  fullWidth?: boolean;
  style?: ViewStyle;
}

const SIZE_CONFIG = {
  sm: { minHeight: 48, paddingHorizontal: 14, paddingVertical: 12, fontSize: 13 },
  md: { minHeight: 48, paddingHorizontal: 18, paddingVertical: 12, fontSize: 15 },
  lg: { minHeight: 56, paddingHorizontal: 22, paddingVertical: 15, fontSize: 16 },
} as const;

export function pressedButtonScale(reduceMotion: boolean, pressed: boolean): number {
  return pressed && !reduceMotion ? 0.97 : 1;
}

export function TwButton({
  variant = 'primary',
  size = 'md',
  icon,
  children,
  onPress,
  disabled,
  loading,
  fullWidth,
  style,
  accessibilityHint,
  accessibilityLabel,
  testID,
}: TwButtonProps) {
  const theme = useTheme();
  const { fontScale } = useWindowDimensions();
  const scale = useSharedValue(1);
  const reduceMotion = useReducedMotion();
  const s = SIZE_CONFIG[size];
  const isDisabled = Boolean(disabled || loading);
  const inferredLabel = typeof children === 'string' || typeof children === 'number'
    ? String(children)
    : undefined;

  const animStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
  }));

  const handlePressIn = useCallback(() => {
    if (!disabled) {
      const next = pressedButtonScale(reduceMotion, true);
      scale.value = reduceMotion ? next : withTiming(next, { duration: 80 });
    }
  }, [disabled, reduceMotion, scale]);

  const handlePressOut = useCallback(() => {
    scale.value = reduceMotion ? 1 : withTiming(1, { duration: 120 });
  }, [reduceMotion, scale]);

  // Resolve background + text colors per variant
  let bg = '';
  let textColor = '';
  let borderColor: string | undefined;

  switch (variant) {
    case 'primary':
      bg = theme.ink;
      textColor = theme.bg;
      break;
    case 'accent':
      bg = theme.accent;
      textColor = '#ffffff';
      break;
    case 'secondary':
      bg = theme.fill;
      textColor = theme.ink;
      borderColor = theme.border;
      break;
    case 'ghost':
      bg = 'transparent';
      textColor = theme.ink;
      break;
    case 'destructive':
      bg = 'transparent';
      textColor = TW_SEMANTIC.danger;
      // blend danger with border: approximate the color-mix by using a slightly
      // tinted border (pre-computed: 40% danger over existing border hue)
      borderColor = hexWithAlpha(TW_SEMANTIC.danger, 0.40); // ~40% opacity
      break;
  }

  return (
    <Animated.View style={[animStyle, fullWidth && styles.fullWidth, style]}>
      <Pressable
        accessible
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel ?? inferredLabel}
        accessibilityHint={accessibilityHint}
        accessibilityState={{ disabled: isDisabled, busy: Boolean(loading) }}
        testID={testID}
        onPress={onPress}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        disabled={isDisabled}
        style={({ pressed }) => [
          styles.base,
          {
            minHeight: s.minHeight,
            paddingHorizontal: s.paddingHorizontal,
            paddingVertical: s.paddingVertical,
            backgroundColor: bg,
            borderColor: borderColor,
            borderWidth: borderColor ? 1 : 0,
            borderRadius: theme.radius.md,
            opacity: isDisabled ? 0.4 : pressed && reduceMotion ? 0.82 : 1,
          },
          fullWidth && styles.fullWidth,
        ]}
      >
        {loading ? (
          <ActivityIndicator size="small" color={textColor} />
        ) : (
          <>
            {icon}
            {children !== undefined && (
              <Text
                style={[
                  styles.label,
                  {
                    fontSize: s.fontSize,
                    lineHeight: Math.ceil(s.fontSize * 1.3 * Math.max(1, fontScale)),
                    color: textColor,
                    fontFamily: theme.fonts.uiSemi,
                  },
                ]}
              >
                {children}
              </Text>
            )}
          </>
        )}
      </Pressable>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  fullWidth: {
    width: '100%',
  },
  label: {
    letterSpacing: -0.1,
  },
});
