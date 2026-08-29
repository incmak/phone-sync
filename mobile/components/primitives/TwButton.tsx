import React, { useState } from 'react';
import {
  Pressable,
  Text,
  StyleSheet,
  ActivityIndicator,
  View,
  type PressableProps,
  type ViewStyle,
  type ColorValue,
  useWindowDimensions,
} from 'react-native';
import { useTheme } from '../Theme';
import type { Theme } from '../tokens';

export type TwButtonVariant = 'primary' | 'accent' | 'secondary' | 'ghost' | 'destructive';
export type TwButtonSize = 'sm' | 'md' | 'lg';

export interface TwButtonColors {
  backgroundColor: ColorValue;
  pressedBackgroundColor: ColorValue;
  textColor: ColorValue;
  borderColor?: ColorValue;
}

function pressedButtonStyle(backgroundColor: ColorValue, pressedBackgroundColor: ColorValue, pressed: boolean, disabled: boolean) {
  return { backgroundColor: pressed && !disabled ? pressedBackgroundColor : backgroundColor };
}

export function resolveButtonColors(
  theme: Theme,
  variant: TwButtonVariant,
): TwButtonColors {
  switch (variant) {
    case 'primary':
      return { backgroundColor: theme.colors.primary, pressedBackgroundColor: theme.colors.primary, textColor: theme.colors.onPrimary };
    case 'accent':
      return { backgroundColor: theme.colors.tertiary, pressedBackgroundColor: theme.colors.tertiary, textColor: theme.colors.onTertiary };
    case 'secondary':
      return { backgroundColor: theme.colors.secondaryContainer, pressedBackgroundColor: theme.colors.surfaceContainerHighest, textColor: theme.colors.onSecondaryContainer };
    case 'ghost':
      return { backgroundColor: 'transparent', pressedBackgroundColor: theme.colors.surfaceContainerHighest, textColor: theme.colors.onSurface };
    case 'destructive':
      return {
        backgroundColor: 'transparent',
        pressedBackgroundColor: theme.colors.errorContainer,
        textColor: theme.colors.error,
        borderColor: theme.colors.error,
      };
  }
}

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
  const [pressed, setPressed] = useState(false);
  const { fontScale } = useWindowDimensions();
  const s = SIZE_CONFIG[size];
  const isDisabled = Boolean(disabled || loading);
  const inferredLabel = typeof children === 'string' || typeof children === 'number'
    ? String(children)
    : undefined;

  const { backgroundColor, pressedBackgroundColor, textColor, borderColor } = resolveButtonColors(theme, variant);

  return (
    <Pressable
      accessible
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? inferredLabel}
      accessibilityHint={accessibilityHint}
      accessibilityState={{ disabled: isDisabled, busy: Boolean(loading) }}
      testID={testID}
      onPress={onPress}
      onPressIn={() => setPressed(true)}
      onPressOut={() => setPressed(false)}
      disabled={isDisabled}
      style={[
        styles.base,
        {
          minHeight: s.minHeight,
          paddingHorizontal: s.paddingHorizontal,
          paddingVertical: s.paddingVertical,
          ...pressedButtonStyle(backgroundColor, pressedBackgroundColor, pressed, isDisabled),
          borderColor: borderColor,
          borderWidth: borderColor ? 1 : 0,
          borderRadius: theme.radius.md,
          opacity: isDisabled ? 0.4 : 1,
        },
        fullWidth && styles.fullWidth,
        style,
      ]}
    >
      <View
        pointerEvents="none"
        testID="tw-button-state-layer"
        style={[
          StyleSheet.absoluteFill,
          {
            borderRadius: theme.radius.md,
            backgroundColor: textColor,
            opacity: pressed && !isDisabled ? 0.08 : 0,
          },
        ]}
      />
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
