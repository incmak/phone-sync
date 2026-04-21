import React from 'react';
import Svg, { Circle, Path, Rect } from 'react-native-svg';
import { useTheme } from '../Theme';

export type TwLogoVariant = 'pair' | 'dots' | 'monogram';

interface TwLogoProps {
  size?: number;
  color?: string;
  accent?: string;
  variant?: TwLogoVariant;
}

export function TwLogo({ size = 32, color, accent, variant = 'pair' }: TwLogoProps) {
  const theme = useTheme();
  const fg = color ?? theme.ink;
  const ac = accent ?? theme.accent;

  if (variant === 'pair') {
    // Two interlocked rings — "twin" glyph
    return (
      <Svg width={size} height={size} viewBox="0 0 32 32" fill="none">
        <Circle cx="12" cy="16" r="9" stroke={fg} strokeWidth="2.2" />
        <Circle cx="20" cy="16" r="9" stroke={ac} strokeWidth="2.2" />
      </Svg>
    );
  }

  if (variant === 'dots') {
    // Doubled-i motif — two dots over a bar
    return (
      <Svg width={size} height={size} viewBox="0 0 32 32" fill="none">
        <Rect x="6" y="18" width="20" height="3" rx="1.5" fill={fg} />
        <Circle cx="12" cy="11" r="3" fill={fg} />
        <Circle cx="20" cy="11" r="3" fill={ac} />
      </Svg>
    );
  }

  // monogram — T mirrored
  return (
    <Svg width={size} height={size} viewBox="0 0 32 32" fill="none">
      <Path d="M4 8 H28 M10 8 V24" stroke={fg} strokeWidth="2.6" strokeLinecap="round" />
      <Path d="M22 8 V24" stroke={ac} strokeWidth="2.6" strokeLinecap="round" />
    </Svg>
  );
}
