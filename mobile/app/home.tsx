import React, { useCallback, useEffect, useState } from 'react';
import { Alert, ScrollView, StyleSheet, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import {
  ConnectionSurface,
  HomeFilterAction,
  HomeMetrics,
  HomeTopAppBar,
  RecentActivitySection,
  useTheme,
} from '../components';
import { useMetrics } from '../hooks/useMetrics';
import { useRecentActivity } from '../hooks/useRecentActivity';
import { useRouteStatus } from '../hooks/useRouteStatus';
import { useSyncStatus } from '../hooks/useSyncStatus';
import TwinotifyCoreModule, { type PairStatus, type SyncState } from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../state/onboardingState';
import { presentRoute } from '../state/routePresentation';

function serviceIsRunning(state: SyncState): boolean {
  return state === 'CONNECTED' || state === 'LEGACY_ONLINE_ONLY' || state === 'CONNECTING' || state === 'OFFLINE_QUEUED';
}

export default function HomeScreen() {
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const { state, enabled } = useSyncStatus();
  const routeStatus = useRouteStatus();
  const metrics = useMetrics();
  const recentActivity = useRecentActivity(5);
  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null>(null);
  const nativeMirrorOn = enabled ?? serviceIsRunning(state);
  const [previousNativeMirrorOn, setPreviousNativeMirrorOn] = useState(nativeMirrorOn);
  const [mirrorOn, setMirrorOn] = useState(nativeMirrorOn);

  if (previousNativeMirrorOn !== nativeMirrorOn) {
    setPreviousNativeMirrorOn(nativeMirrorOn);
    setMirrorOn(nativeMirrorOn);
  }

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus().then(setPairStatus).catch(() => {});
    OnboardingState.getRelayUrl().then(setRelayUrl).catch(() => {});
  }, []);

  const handleMirrorToggle = useCallback(async (next: boolean) => {
    setMirrorOn(next);
    try {
      if (next) {
        if (relayUrl) await TwinotifyCoreModule.startSyncService(relayUrl);
        else await TwinotifyCoreModule.startLanOnlySyncService();
      } else {
        await TwinotifyCoreModule.stopSyncService();
      }
    } catch (error: unknown) {
      Alert.alert('Error', error instanceof Error ? error.message : 'Unknown error');
      setMirrorOn(!next);
    }
  }, [relayUrl]);

  const handleRetry = useCallback(async () => {
    try {
      await TwinotifyCoreModule.retryRoute();
    } catch (error: unknown) {
      Alert.alert('Error', error instanceof Error ? error.message : 'Unknown error');
    }
  }, []);

  const route = presentRoute(routeStatus, pairStatus.paired, mirrorOn);
  const peerName = pairStatus.paired ? (pairStatus.peerDisplayName?.trim() || 'Unknown device') : 'Not paired';
  const gutter = width <= 360 ? 16 : 24;
  const connectionWidth = Math.max(232, width - gutter * 2);
  const traceWidth = Math.max(192, connectionWidth - 40);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.colors.surface }]}>
      <ScrollView contentContainerStyle={[styles.content, { paddingHorizontal: gutter }]} showsVerticalScrollIndicator={false}>
        <HomeTopAppBar onOpenSettings={() => router.push('/settings')} />
        <ConnectionSurface
          route={route}
          enabled={mirrorOn}
          onToggle={handleMirrorToggle}
          peerName={peerName}
          onOpenPeer={() => router.push('/settings/pair')}
          traceWidth={traceWidth}
          onRetry={handleRetry}
          onPair={() => router.push('/pair/nearby')}
          onPermissions={() => router.push('/onboarding/perms')}
        />
        <HomeMetrics mirroredToday={metrics.mirroredToday} blockedToday={metrics.blockedToday} latencyMs={metrics.latencyMs} />
        <RecentActivitySection state={recentActivity} peerName={peerName} />
        <HomeFilterAction onPress={() => router.push('/filter')} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { paddingBottom: 36, gap: 28 },
});
