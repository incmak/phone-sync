import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import {
  useTheme,
  TwWordmark,
  TwStatusDot,
  TwCard,
  TwSwitch,
  TwButton,
  TwEmpty,
} from '../components';
import { useSyncStatus } from '../hooks/useSyncStatus';
import { useRouteStatus } from '../hooks/useRouteStatus';
import { presentRoute } from '../state/routePresentation';
import { useMetrics } from '../hooks/useMetrics';
import TwinotifyCoreModule, { PairStatus, SyncState } from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../state/onboardingState';

// ── helpers ──────────────────────────────────────────────────────────────────

function serviceIsRunning(state: SyncState): boolean {
  return state === 'CONNECTED' || state === 'CONNECTING' || state === 'OFFLINE_QUEUED';
}

// ── component ─────────────────────────────────────────────────────────────────

export default function HomeScreen() {
  const theme = useTheme();
  const { state } = useSyncStatus();
  const routeStatus = useRouteStatus();
  const metrics = useMetrics();

  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null>(null);
  const [mirrorOn, setMirrorOn] = useState(serviceIsRunning(state));

  // Seed from native on mount
  useEffect(() => {
    TwinotifyCoreModule.getPairStatus()
      .then(setPairStatus)
      .catch(() => {});
    OnboardingState.getRelayUrl()
      .then(setRelayUrl)
      .catch(() => {});
  }, []);

  // Keep mirrorOn aligned with actual sync state
  useEffect(() => {
    setMirrorOn(serviceIsRunning(state));
  }, [state]);

  const handleMirrorToggle = useCallback(async (next: boolean) => {
    setMirrorOn(next);
    try {
      if (next) {
        const url = relayUrl ?? 'wss://relay.twinotify.app';
        await TwinotifyCoreModule.startSyncService(url);
      } else {
        await TwinotifyCoreModule.stopSyncService();
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      Alert.alert('Error', msg);
      setMirrorOn(!next); // revert
    }
  }, [relayUrl]);

  const handleRetry = useCallback(async () => {
    try {
      await TwinotifyCoreModule.retryRoute();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      Alert.alert('Error', msg);
    }
  }, []);

  // Display values
  const route = presentRoute(routeStatus, pairStatus.paired);
  const peerLabel = pairStatus.paired
    ? (pairStatus.peerDisplayName?.trim() || pairStatus.peerDeviceId?.slice(0, 8) || 'Unknown device')
    : 'Not paired';
  const peerReachable = route.state === 'direct' || route.state === 'relay';

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
      >
        {/* Top bar */}
        <View style={styles.topBar}>
          <TwWordmark size={17} />
          <Pressable
            onPress={() => router.push('/settings')}
            style={[styles.iconBtn, { backgroundColor: theme.fill }]}
            hitSlop={8}
          >
            <Text style={[styles.iconText, { color: theme.ink }]}>⚙</Text>
          </Pressable>
        </View>

        {/* Hero status card */}
        <TwCard
          tone="default"
          style={styles.heroCard}
          padding={20}
        >
          {/* Top row: status label + mirror switch */}
          <View style={styles.heroTop}>
            <View style={styles.heroLeft}>
              <View
                accessibilityRole="text"
                accessibilityLiveRegion="polite"
                accessibilityLabel={`${route.label}. ${route.explanation}`}
              >
                <Text style={[styles.heroTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
                  {route.label}
                </Text>
                <Text style={[styles.heroBody, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                  {route.explanation}
                </Text>
              </View>
            </View>
            <TwSwitch
              checked={mirrorOn}
              onChange={handleMirrorToggle}
              size="lg"
              disabled={!pairStatus.paired}
            />
          </View>

          {/* Pair row */}
          <View style={[styles.pairRow, { backgroundColor: theme.fill, borderColor: theme.border }]}>
            <View style={styles.pairBadges}>
              <View style={[styles.roleBadge, { backgroundColor: theme.ink }]}>
                <Text style={[styles.roleBadgeText, { color: theme.bg, fontFamily: theme.fonts.mono }]}>
                  A
                </Text>
              </View>
              <Text style={[styles.arrow, { color: theme.ink3 }]}>→</Text>
              <View style={[styles.roleBadge, { backgroundColor: theme.accent }]}>
                <Text style={[styles.roleBadgeText, { color: '#fff', fontFamily: theme.fonts.mono }]}>
                  B
                </Text>
              </View>
            </View>
            <Text style={[styles.peerLabel, { color: theme.ink2, fontFamily: theme.fonts.ui }]} numberOfLines={1}>
              <Text style={{ color: theme.ink, fontFamily: theme.fonts.uiSemi }}>{peerLabel}</Text>
              {pairStatus.paired && peerReachable ? ' · reachable' : ''}
            </Text>
            <Pressable onPress={() => router.push('/settings/pair')} hitSlop={8}>
              <Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>
            </Pressable>
          </View>

          {/* Metrics row */}
          <View style={styles.metricsRow}>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>TODAY</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>
                {metrics.mirroredToday}
              </Text>
            </View>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>LATENCY</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>
                {metrics.latencyMs > 0 ? `${metrics.latencyMs}ms` : '0ms'}
              </Text>
            </View>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>BLOCKED</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>
                {metrics.blockedToday}
              </Text>
            </View>
          </View>
        </TwCard>

        {route.action === 'retry' && (
          <TwButton
            size="md"
            variant="secondary"
            onPress={handleRetry}
            style={styles.routeAction}
            accessibilityHint="Tries your other phone again straight away"
          >
            Try again now
          </TwButton>
        )}
        {route.action === 'pair' && (
          <TwButton
            size="md"
            variant="primary"
            onPress={() => router.push('/pair/nearby')}
            style={styles.routeAction}
          >
            Link your other phone
          </TwButton>
        )}

        {/* Recent activity */}
        <View style={styles.recentHeader}>
          <Text style={[styles.recentTitle, { color: theme.ink2, fontFamily: theme.fonts.uiSemi }]}>
            Recent
          </Text>
        </View>
        <TwCard tone="default" style={styles.recentCard} padding={0}>
          <TwEmpty
            title="No mirrors yet"
            body="Your first mirrored notification will show up here."
          />
        </TwCard>

        {/* CTA buttons */}
        <View style={styles.ctaRow}>
          <TwButton
            variant="secondary"
            size="sm"
            fullWidth
            onPress={() => router.push('/filter')}
          >
            App filter
          </TwButton>
          <TwButton
            variant="secondary"
            size="sm"
            fullWidth
            onPress={() => router.push('/settings')}
          >
            Settings
          </TwButton>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { paddingHorizontal: 20, paddingTop: 8, paddingBottom: 32, gap: 0 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingBottom: 12,
  },
  iconBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconText: { fontSize: 18 },
  // Hero card
  heroCard: { marginTop: 4 },
  heroTop: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  heroLeft: { flex: 1, marginRight: 12 },
  heroTitle: { fontSize: 22, letterSpacing: -0.3, lineHeight: 28 },
  heroBody: { fontSize: 13, marginTop: 3, lineHeight: 18 },
  // Pair row
  pairRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 12,
    borderWidth: 1,
    marginBottom: 16,
  },
  pairBadges: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  roleBadge: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  roleBadgeText: { fontSize: 11, fontWeight: '700' },
  arrow: { fontSize: 14 },
  peerLabel: { flex: 1, fontSize: 13 },
  chevron: { fontSize: 22 },
  // Metrics
  metricsRow: { flexDirection: 'row', gap: 12 },
  metric: { flex: 1 },
  metricLabel: { fontSize: 11, letterSpacing: 0.4, marginBottom: 2 },
  metricValue: { fontSize: 20, fontWeight: '600' },
  // Banners
  routeAction: { marginTop: 12, alignSelf: 'flex-start' },
  // Recent
  recentHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 24,
    marginBottom: 8,
  },
  recentTitle: { fontSize: 14, letterSpacing: -0.1 },
  recentCard: { overflow: 'hidden' },
  // CTAs
  ctaRow: { flexDirection: 'row', gap: 8, marginTop: 16 },
});
