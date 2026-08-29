import React from 'react';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';

import { useTheme } from '../Theme';

interface HomeMetricsProps { mirroredToday: number; blockedToday: number; latencyMs: number }

export function HomeMetrics({ mirroredToday, blockedToday, latencyMs }: HomeMetricsProps) {
  const theme = useTheme();
  const { width, fontScale } = useWindowDimensions();
  const wrapValues = width <= 360 || fontScale >= 1.6;
  const metrics = [
    { label: 'Mirrored', value: String(mirroredToday), accessibilityLabel: `${mirroredToday} mirrored today` },
    { label: 'Latency', value: latencyMs > 0 ? `${latencyMs} ms` : 'No data', accessibilityLabel: latencyMs > 0 ? `${latencyMs} milliseconds` : 'Latency not measured' },
    { label: 'Blocked', value: String(blockedToday), accessibilityLabel: `${blockedToday} blocked today` },
  ];
  return (
    <View testID="home-metrics" accessibilityRole="summary" style={styles.row}>
      {metrics.map((metric) => (
        <View key={metric.label} style={[styles.metric, wrapValues && styles.metricWrapped]}>
          <Text style={[styles.label, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>{metric.label}</Text>
          <Text accessibilityLabel={metric.accessibilityLabel} style={[styles.value, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>{metric.value}</Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', columnGap: 16, rowGap: 18 },
  metric: { flex: 1, minWidth: 84 },
  metricWrapped: { flexBasis: '44%' },
  label: { minHeight: 20, fontSize: 13, lineHeight: 20 },
  value: { marginTop: 3, minHeight: 32, fontSize: 22, lineHeight: 30, letterSpacing: -0.3 },
});
