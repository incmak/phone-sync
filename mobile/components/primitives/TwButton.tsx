import React, { useCallback } from 'react';
import { Pressable, Text, StyleSheet, ActivityIndicator, ViewStyle } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { useTheme } from '../Theme';
import { TW_SEMANTIC, hexWithAlpha } from '../tokens';

export type TwButtonVariant = 'primary' | 'accent' | 'secondary' | 'ghost' | 'destructive';
export type TwButtonSize = 'sm' | 'md' | 'lg';

interface TwButtonProps {
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
  sm: { height: 36, paddingHorizontal: 14, fontSize: 13 },
  md: { height: 48, paddingHorizontal: 18, fontSize: 15 },
  lg: { height: 56, paddingHorizontal: 22, fontSize: 16 },
} as const;

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
}: TwButtonProps) {
  const theme = useTheme();
  const scale = useSharedValue(1);
  const s = SIZE_CONFIG[size];

  const animStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
  }));

  const handlePressIn = useCallback(() => {
    if (!disabled) scale.value = withTiming(0.97, { duration: 80 });
  }, [disabled, scale]);

  const handlePressOut = useCallback(() => {
    scale.value = withTiming(1, { duration: 120 });
  }, [scale]);

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
        onPress={onPress}
        onPressIn={handlePressIn}
        onPressOut={handlePressOut}
        disabled={disabled || loading}
        style={[
          styles.base,
          {
            height: s.height,
            paddingHorizontal: s.paddingHorizontal,
            backgroundColor: bg,
            borderColor: borderColor,
            borderWidth: borderColor ? 1 : 0,
            borderRadius: theme.radius.md,
            opacity: disabled ? 0.4 : 1,
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
