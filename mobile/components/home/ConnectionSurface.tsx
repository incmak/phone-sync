import React from 'react';
import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';

import type { DeliveryPresentation } from '../../state/routePresentation';
import { HandoffDisclosureMark, HandoffTrace } from '../HandoffTrace';
import { useTheme } from '../Theme';
import { TwButton } from '../primitives/TwButton';
import { TwSwitch } from '../primitives/TwSwitch';

interface ConnectionSurfaceProps {
  route: DeliveryPresentation;
  enabled: boolean;
  onToggle: (next: boolean) => void;
  peerName: string;
  peerReachable: boolean;
  onOpenPeer: () => void;
  traceWidth: number;
  onRetry?: () => void;
  onPair?: () => void;
}

export function ConnectionSurface({ route, enabled, onToggle, peerName, peerReachable, onOpenPeer, traceWidth, onRetry, onPair }: ConnectionSurfaceProps) {
  const theme = useTheme();
  const { width, fontScale } = useWindowDimensions();
  const narrow = width <= 360 || fontScale >= 1.6;
  return (
    <View testID="connection-surface" style={[styles.surface, { backgroundColor: theme.colors.surfaceContainerLow }]}>
      <View style={[styles.header, narrow && styles.headerNarrow]}>
        <View
          accessible
          accessibilityRole="text"
          accessibilityLiveRegion="polite"
          accessibilityLabel={`${route.label}. ${route.explanation}`}
          style={styles.routeCopy}
        >
          <Text style={[styles.routeLabel, { color: theme.colors.onSurface, fontFamily: theme.fonts.display }]}>{route.label}</Text>
          <Text style={[styles.routeExplanation, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>{route.explanation}</Text>
        </View>
        <View style={narrow ? styles.switchNarrow : undefined}>
          <TwSwitch checked={enabled} onChange={onToggle} disabled={route.state === 'unpaired'} touchTargetSize={48} accessibilityLabel="Mirror notifications" />
        </View>
      </View>

      <View style={styles.trace}>
        <HandoffTrace state={route.state} width={traceWidth} height={104} testID={`handoff-trace-${route.state}`} />
      </View>

      <Pressable
        onPress={onOpenPeer}
        accessibilityRole="button"
        accessibilityLabel="Open paired phone"
        style={({ pressed }) => [styles.peer, { backgroundColor: pressed ? theme.colors.surfaceContainerHigh : 'transparent' }]}
      >
        <View style={styles.peerCopy}>
          <Text style={[styles.peerRole, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>Paired phone</Text>
          <Text style={[styles.peerName, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>{peerName}</Text>
          {peerReachable && <Text style={[styles.reachable, { color: theme.colors.primary, fontFamily: theme.fonts.ui }]}>Reachable now</Text>}
        </View>
        <HandoffDisclosureMark color={theme.colors.primary} />
      </Pressable>

      {route.action === 'retry' && onRetry && (
        <TwButton variant="secondary" onPress={onRetry} fullWidth accessibilityHint="Tries your other phone again straight away">Try again now</TwButton>
      )}
      {route.action === 'pair' && onPair && <TwButton variant="primary" onPress={onPair} fullWidth>Link your other phone</TwButton>}
    </View>
  );
}

const styles = StyleSheet.create({
  surface: { borderRadius: 28, padding: 20, gap: 18 },
  header: { flexDirection: 'row', alignItems: 'flex-start', gap: 16 },
  headerNarrow: { flexDirection: 'column', gap: 8 },
  routeCopy: { flex: 1, minWidth: 0 },
  routeLabel: { fontSize: 32, lineHeight: 38, letterSpacing: -0.7 },
  routeExplanation: { marginTop: 6, fontSize: 15, lineHeight: 22 },
  switchNarrow: { alignSelf: 'flex-end' },
  trace: { minHeight: 104, alignItems: 'center', justifyContent: 'center' },
  peer: { minHeight: 64, marginHorizontal: -8, paddingHorizontal: 8, borderRadius: 16, flexDirection: 'row', alignItems: 'center', gap: 16 },
  peerCopy: { flex: 1, minWidth: 0 },
  peerRole: { fontSize: 13, lineHeight: 18 },
  peerName: { marginTop: 1, fontSize: 17, lineHeight: 24 },
  reachable: { marginTop: 1, fontSize: 13, lineHeight: 18 },
});
