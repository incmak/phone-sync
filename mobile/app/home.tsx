import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { useTheme, TwWordmark, TwSwitch, TwButton } from '../components';
import { HandoffDisclosureMark, HandoffTrace } from '../components/HandoffTrace';
import { useSyncStatus } from '../hooks/useSyncStatus';
import { useRouteStatus } from '../hooks/useRouteStatus';
import { presentRoute } from '../state/routePresentation';
import { useMetrics } from '../hooks/useMetrics';
import TwinotifyCoreModule, { PairStatus, SyncState } from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../state/onboardingState';

function serviceIsRunning(state: SyncState): boolean {
  return state === 'CONNECTED' || state === 'LEGACY_ONLINE_ONLY' || state === 'CONNECTING' || state === 'OFFLINE_QUEUED';
}

export default function HomeScreen() {
  const theme = useTheme();
  const { width: windowWidth } = useWindowDimensions();
  const { state } = useSyncStatus();
  const routeStatus = useRouteStatus();
  const metrics = useMetrics();
  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null>(null);
  const [mirrorOn, setMirrorOn] = useState(serviceIsRunning(state));
  const [settingsPressed, setSettingsPressed] = useState(false);

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus().then(setPairStatus).catch(() => {});
    OnboardingState.getRelayUrl().then(setRelayUrl).catch(() => {});
  }, []);

  useEffect(() => {
    setMirrorOn(serviceIsRunning(state));
  }, [state]);

  const handleMirrorToggle = useCallback(async (next: boolean) => {
    setMirrorOn(next);
    try {
      if (next) {
        if (relayUrl) await TwinotifyCoreModule.startSyncService(relayUrl);
        else await TwinotifyCoreModule.startLanOnlySyncService();
      } else {
        await TwinotifyCoreModule.stopSyncService();
      }
    } catch (e: unknown) {
      Alert.alert('Error', e instanceof Error ? e.message : 'Unknown error');
      setMirrorOn(!next);
    }
  }, [relayUrl]);

  const handleRetry = useCallback(async () => {
    try {
      await TwinotifyCoreModule.retryRoute();
    } catch (e: unknown) {
      Alert.alert('Error', e instanceof Error ? e.message : 'Unknown error');
    }
  }, []);

  const route = presentRoute(routeStatus, pairStatus.paired, mirrorOn);
  const peerLabel = pairStatus.paired
    ? (pairStatus.peerDisplayName?.trim() || pairStatus.peerDeviceId?.slice(0, 8) || 'Unknown device')
    : 'Not paired';
  const peerReachable = route.state === 'direct' || route.state === 'relay';
  const gutter = windowWidth <= 360 ? 16 : 20;
  const traceWidth = Math.max(240, windowWidth - gutter * 2);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={[styles.scroll, { paddingHorizontal: gutter }]} showsVerticalScrollIndicator={false}>
        <View style={styles.topBar}>
          <TwWordmark size={17} />
          <Pressable
            onPress={() => router.push('/settings')}
            onPressIn={() => setSettingsPressed(true)}
            onPressOut={() => setSettingsPressed(false)}
            style={[styles.minTarget, styles.settingsAction, { backgroundColor: settingsPressed ? theme.hover : 'transparent' }]}
            accessibilityRole="button"
            accessibilityLabel="Open settings"
          >
            <Text style={[styles.settingsText, { color: theme.ink, fontFamily: theme.fonts.uiMedium }]}>settings</Text>
          </Pressable>
        </View>

        <View style={styles.routeHead}>
          <View accessible accessibilityRole="text" accessibilityLiveRegion="polite" accessibilityLabel={`${route.label}. ${route.explanation}`} style={styles.routeCopy}>
            <Text style={[styles.routeState, { color: theme.ink, fontFamily: theme.fonts.display }]}>{route.label}</Text>
            <Text style={[styles.routeExplanation, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>{route.explanation}</Text>
          </View>
          <TwSwitch checked={mirrorOn} onChange={handleMirrorToggle} size="lg" disabled={!pairStatus.paired} accessibilityLabel="Mirror notifications" />
        </View>

        <View style={[styles.traceStage, { backgroundColor: theme.fill }]}>
          <HandoffTrace state={route.state} width={traceWidth} height={104} testID={`handoff-trace-${route.state}`} />
        </View>

        <View style={styles.peerLine}>
          <View style={styles.peerCopy}>
            <Text style={[styles.peerEyebrow, { color: theme.ink3, fontFamily: theme.fonts.uiMedium }]}>Paired phone</Text>
            <Text style={[styles.peerName, { color: theme.ink, fontFamily: theme.fonts.uiMedium }]} numberOfLines={2}>{peerLabel}</Text>
            {pairStatus.paired && peerReachable && <Text style={[styles.peerReachability, { color: theme.accentText, fontFamily: theme.fonts.ui }]}>Reachable</Text>}
          </View>
          <Pressable
            onPress={() => router.push('/settings/pair')}
            accessibilityRole="button"
            accessibilityLabel="Open paired device settings"
            style={({ pressed }) => [styles.disclosureTarget, { backgroundColor: pressed ? theme.hover : 'transparent' }]}
          >
            <HandoffDisclosureMark color={theme.accentText} />
          </Pressable>
        </View>

        <View accessibilityRole="summary" style={styles.metricsRow}>
          <View style={styles.metric}>
            <Text style={[styles.metricLabel, { color: theme.ink3, fontFamily: theme.fonts.uiMedium }]}>Today</Text>
            <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>{metrics.mirroredToday}</Text>
          </View>
          <View style={styles.metric}>
            <Text style={[styles.metricLabel, { color: theme.ink3, fontFamily: theme.fonts.uiMedium }]}>Latency</Text>
            <Text style={[styles.metricValue, metrics.latencyMs <= 0 && styles.metricEmptyValue, { color: theme.ink, fontFamily: metrics.latencyMs > 0 ? theme.fonts.mono : theme.fonts.uiMedium }]} accessibilityLabel={metrics.latencyMs > 0 ? `${metrics.latencyMs} milliseconds` : 'Latency not measured'}>
              {metrics.latencyMs > 0 ? `${metrics.latencyMs}ms` : 'No data'}
            </Text>
          </View>
          <View style={styles.metric}>
            <Text style={[styles.metricLabel, { color: theme.ink3, fontFamily: theme.fonts.uiMedium }]}>Blocked</Text>
            <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>{metrics.blockedToday}</Text>
          </View>
        </View>

        {route.action === 'retry' && <TwButton size="md" variant="secondary" onPress={handleRetry} style={styles.routeAction} accessibilityHint="Tries your other phone again straight away">Try again now</TwButton>}
        {route.action === 'pair' && <TwButton size="md" variant="primary" onPress={() => router.push('/pair/nearby')} style={styles.routeAction}>Link your other phone</TwButton>}

        <View style={styles.recentSection}>
          <Text style={[styles.recentTitle, { color: theme.ink2, fontFamily: theme.fonts.uiMedium }]}>Recent</Text>
          <Text style={[styles.recentEmpty, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>No mirrors yet. Your first mirrored notification will show up here.</Text>
        </View>

        <Pressable onPress={() => router.push('/filter')} accessibilityRole="button" accessibilityLabel="App filter" style={({ pressed }) => [styles.filterAction, { backgroundColor: pressed ? theme.hover : theme.fill }]}>
          <Text style={[styles.filterText, { color: theme.ink, fontFamily: theme.fonts.uiMedium }]}>App filter</Text>
          <HandoffDisclosureMark color={theme.accentText} />
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { paddingTop: 8, paddingBottom: 32 },
  topBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingBottom: 20 },
  minTarget: { minWidth: 44, minHeight: 44 },
  settingsAction: { minWidth: 72, minHeight: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 14, paddingHorizontal: 8 },
  settingsText: { fontSize: 15, lineHeight: 20, textTransform: 'capitalize' },
  routeHead: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  routeCopy: { flex: 1, minWidth: 0, paddingTop: 2 },
  routeState: { fontSize: 34, fontWeight: '700', letterSpacing: -0.6, lineHeight: 39 },
  routeExplanation: { fontSize: 14, lineHeight: 20, marginTop: 5 },
  traceStage: { alignItems: 'center', justifyContent: 'center', minHeight: 104, borderRadius: 20, marginTop: 20, overflow: 'hidden' },
  peerLine: { flexDirection: 'row', alignItems: 'center', marginTop: 24, minHeight: 56 },
  peerCopy: { flex: 1, minWidth: 0 },
  peerEyebrow: { fontSize: 12, lineHeight: 17 },
  peerName: { fontSize: 16, lineHeight: 22, marginTop: 1 },
  peerReachability: { fontSize: 13, lineHeight: 18, marginTop: 1 },
  disclosureTarget: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 14, marginLeft: 12 },
  metricsRow: { flexDirection: 'row', gap: 12, marginTop: 20 },
  metric: { flex: 1, minWidth: 0 },
  metricLabel: { fontSize: 12, lineHeight: 17, marginBottom: 3 },
  metricValue: { fontSize: 21, fontWeight: '600', lineHeight: 27 },
  metricEmptyValue: { fontSize: 14, lineHeight: 20 },
  routeAction: { alignSelf: 'stretch', marginTop: 22 },
  recentSection: { marginTop: 30 },
  recentTitle: { fontSize: 15, lineHeight: 21 },
  recentEmpty: { fontSize: 14, lineHeight: 20, marginTop: 5, maxWidth: 330 },
  filterAction: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 48, borderRadius: 14, marginTop: 24, paddingHorizontal: 16 },
  filterText: { fontSize: 15, lineHeight: 20 },
});
