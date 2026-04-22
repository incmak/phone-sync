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
  TwConnectionState,
  twStatusLabel,
  TwCard,
  TwSwitch,
  TwBanner,
  TwButton,
  TwEmpty,
} from '../components';
import { useSyncStatus } from '../hooks/useSyncStatus';
import TwinotifyCoreModule, { PairStatus, SyncState } from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../state/onboardingState';

// ── helpers ──────────────────────────────────────────────────────────────────

function syncStateToConnection(state: SyncState): TwConnectionState {
  switch (state) {
    case 'CONNECTED':      return 'relay';   // Phase 3: all connections are relay
    case 'CONNECTING':     return 'pairing';
    case 'OFFLINE_QUEUED': return 'offline';
    case 'DISCONNECTED':   return 'offline';
  }
}

const STATUS_COPY: Record<TwConnectionState, { title: string; body: string }> = {
  lan:     { title: 'Direct on LAN',       body: 'Encrypted peer-to-peer over Wi-Fi. Fastest path.' },
  relay:   { title: 'Over relay',          body: 'Relay-tunneled. Still end-to-end encrypted.' },
  offline: { title: 'Offline',             body: "We can't reach your other phone right now." },
  pairing: { title: 'Reconnecting…',       body: 'Renegotiating keys with your peer.' },
};

function serviceIsRunning(state: SyncState): boolean {
  return state === 'CONNECTED' || state === 'CONNECTING' || state === 'OFFLINE_QUEUED';
}

// ── component ─────────────────────────────────────────────────────────────────

export default function HomeScreen() {
  const theme = useTheme();
  const { state, queuedCount: _queuedCount } = useSyncStatus();
  const connection = syncStateToConnection(state);
  const copy = STATUS_COPY[connection];

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
      const url = relayUrl ?? 'wss://relay.twinotify.app';
      await TwinotifyCoreModule.startSyncService(url);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      Alert.alert('Error', msg);
    }
  }, [relayUrl]);

  // Display values
  const peerLabel = pairStatus.paired
    ? (pairStatus.peerDisplayName?.trim() || pairStatus.peerDeviceId?.slice(0, 8) || '—')
    : 'Not paired';
  const peerOnline = connection !== 'offline';

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
          tone={connection === 'offline' ? 'danger' : 'default'}
          style={styles.heroCard}
          padding={20}
        >
          {/* Top row: status label + mirror switch */}
          <View style={styles.heroTop}>
            <View style={styles.heroLeft}>
              <View style={styles.statusRow}>
                <TwStatusDot state={connection} size={9} />
                <Text style={[styles.statusLabel, { color: theme.ink3, fontFamily: theme.fonts.uiSemi }]}>
                  {twStatusLabel(connection).toUpperCase()}
                </Text>
              </View>
              <Text style={[styles.heroTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
                {copy.title}
              </Text>
              <Text style={[styles.heroBody, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                {copy.body}
              </Text>
            </View>
            <TwSwitch
              checked={mirrorOn}
              onChange={handleMirrorToggle}
              size="lg"
              disabled={connection === 'offline'}
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
              {pairStatus.paired ? ` · ${peerOnline ? 'online' : 'offline'}` : ''}
            </Text>
            <Pressable onPress={() => router.push('/settings/pair')} hitSlop={8}>
              <Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>
            </Pressable>
          </View>

          {/* Metrics row — Phase 3 stubs */}
          <View style={styles.metricsRow}>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>TODAY</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>—</Text>
            </View>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>LATENCY</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>—</Text>
            </View>
            <View style={styles.metric}>
              <Text style={[styles.metricLabel, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>BLOCKED</Text>
              <Text style={[styles.metricValue, { color: theme.ink, fontFamily: theme.fonts.mono }]}>—</Text>
            </View>
          </View>
        </TwCard>

        {/* Inline state-specific banners */}
        {connection === 'offline' && (
          <TwBanner
            tone="danger"
            title="Your other phone hasn't responded"
            body="Check that it's online and Twinotify is running. We'll keep retrying."
            style={styles.banner}
            action={
              <TwButton size="sm" variant="secondary" onPress={handleRetry}>
                Retry now
              </TwButton>
            }
          />
        )}
        {connection === 'pairing' && (
          <TwBanner
            tone="warn"
            title="Temporarily out of sync"
            body="Reconnecting with fresh keys. Should be a few seconds."
            style={styles.banner}
          />
        )}
        {connection === 'relay' && (
          <TwBanner
            tone="info"
            title="Not on the same Wi-Fi"
            body="We'll keep you on the relay until your phones can find each other again."
            style={styles.banner}
            compact
          />
        )}

        {/* Recent activity — Phase 3 empty state */}
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
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 6 },
  statusLabel: { fontSize: 11, letterSpacing: 0.5 },
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
  banner: { marginTop: 12 },
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
