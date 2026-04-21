import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import Svg, { Rect, Line } from 'react-native-svg';
import { useTheme } from '../Theme';
import { TwStatusDot, TwConnectionState } from './TwStatusDot';

interface TwDeviceChipProps {
  name?: string;
  role?: string;
  status?: TwConnectionState;
  compact?: boolean;
}

export function TwDeviceChip({ name = 'Pixel 9 Pro', role, status, compact }: TwDeviceChipProps) {
  const theme = useTheme();

  return (
    <View
      style={[
        styles.chip,
        {
          paddingVertical: compact ? 4 : 6,
          paddingHorizontal: compact ? 10 : 12,
          backgroundColor: theme.fill,
          borderColor: theme.border,
        },
      ]}
    >
      {/* Phone icon */}
      <Svg width="14" height="16" viewBox="0 0 14 16" fill="none" stroke={theme.ink} strokeWidth="1.6">
        <Rect x="1" y="1" width="12" height="14" rx="2" />
        <Line x1="6" y1="12.2" x2="8" y2="12.2" strokeLinecap="round" />
      </Svg>

      <Text style={[styles.name, { color: theme.ink, fontFamily: theme.fonts.uiMedium }]}>
        {name}
      </Text>

      {role !== undefined && (
        <Text style={[styles.role, { color: theme.ink4, fontFamily: theme.fonts.uiMedium }]}>
          {role}
        </Text>
      )}

      {status !== undefined && <TwStatusDot state={status} size={7} />}
    </View>
  );
}

const styles = StyleSheet.create({
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderWidth: 1,
    borderRadius: 999,
    alignSelf: 'flex-start',
  },
  name: {
    fontSize: 13,
  },
  role: {
    fontSize: 11,
    letterSpacing: 0.3,
  },
});
