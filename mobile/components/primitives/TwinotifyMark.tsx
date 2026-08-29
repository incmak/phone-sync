import React from 'react';
import { type ColorValue } from 'react-native';
import Svg, { Path } from 'react-native-svg';

import { useTheme } from '../Theme';

interface TwinotifyMarkProps {
  size?: number;
  color?: ColorValue;
}

export function TwinotifyMark({ size = 24, color }: TwinotifyMarkProps) {
  const theme = useTheme();
  const stroke = color ?? theme.colors.onSurface;

  return (
    <Svg
      testID="twinotify-mark"
      width={size}
      height={size * 0.88}
      viewBox="0 0 48 43"
      accessible={false}
      importantForAccessibility="no"
    >
      <Path
        d="M4 10 H22 L27 15 H44 V33 H26 L21 28 H4 Z"
        fill="none"
        stroke={stroke}
        strokeWidth={4}
        strokeLinejoin="round"
      />
      <Path
        d="M15 5 V38"
        fill="none"
        stroke={stroke}
        strokeWidth={4}
        strokeLinecap="round"
      />
    </Svg>
  );
}
