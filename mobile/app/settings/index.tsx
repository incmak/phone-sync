import React, { useEffect, useState } from 'react';
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Constants from 'expo-constants';
import AsyncStorage from '@react-native-async-storage/async-storage';

import {
  useTheme,
  TwRow,
  TwSwitch,
  TwCard,
} from '../../components';
import { useSyncStatus } from '../../hooks/useSyncStatus';
import TwinotifyCoreModule, { PairStatus, SyncState } from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

// ── helpers ──────────────────────────────────────────────────────────────────

function connectionLabel(state: SyncState): string {
  switch (state) {
    case 'CONNECTED':      return 'online';
    case 'CONNECTING':     return 'connecting';
    case 'OFFLINE_QUEUED': return 'offline';
    case 'DISCONNECTED':   return 'offline';
  }
}

const LOCK_SCREEN_KEY = 'twinotify_lock_screen_preview';

// ── component ─────────────────────────────────────────────────────────────────

export default function SettingsScreen() {
  const theme = useTheme();
  const { state } = useSyncStatus();

  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null>(null);
  const [lockScreenPreview, setLockScreenPreview] = useState(false);

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus()
      .then(setPairStatus)
      .catch(() => {});
    OnboardingState.getRelayUrl()
      .then(setRelayUrl)
      .catch(() => {});
    AsyncStorage.getItem(LOCK_SCREEN_KEY)
      .then((v) => setLockScreenPreview(v === 'true'))
      .catch(() => {});
  }, []);

  const handleLockScreenToggle = async (next: boolean) => {
    setLockScreenPreview(next);
    await AsyncStorage.setItem(LOCK_SCREEN_KEY, String(next)).catch(() => {});
  };

  const peerShort = pairStatus.peerDeviceId
    ? pairStatus.peerDeviceId.slice(0, 8)
    : 'Not paired';
  const peerStatusStr = pairStatus.paired ? `${peerShort} · ${connectionLabel(state)}` : 'Not paired';
  const relayDisplay = relayUrl ?? 'wss://relay.twinotify.app';
  const version = Constants.expoConfig?.version ?? '1.0.0-phase3';

  const sectionHeader = (label: string) => (
    <Text style={[styles.sectionHeader, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>
      {label.toUpperCase()}
    </Text>
  );

  const divider = <View style={[styles.divider, { backgroundColor: theme.border }]} />;

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      {/* Header */}
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Settings
        </Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        {/* Pairing */}
        {sectionHeader('Pairing')}
        <TwCard tone="default" padding={0} style={styles.group}>
          <TwRow
            title="Paired device"
            subtitle={peerStatusStr}
            onPress={pairStatus.paired ? () => router.push('/settings/pair') : undefined}
            trailing={
              pairStatus.paired ? (
                <Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>
              ) : undefined
            }
            style={styles.rowPad}
          />
          {divider}
          <TwRow
            title="Add another device"
            subtitle="Coming in v2"
            style={styles.rowPadDisabled}
          />
        </TwCard>

        {/* Sync */}
        {sectionHeader('Sync')}
        <TwCard tone="default" padding={0} style={styles.group}>
          <TwRow
            title="Relay server"
            subtitle={relayDisplay}
            style={styles.rowPad}
          />
          {divider}
          <TwRow
            title="Always-connected"
            subtitle="Phase 3: Always-connected mode (always ON)"
            trailing={<TwSwitch checked disabled size="md" />}
            style={styles.rowPad}
          />
          {divider}
          <TwRow
            title="Prefer LAN"
            subtitle="Coming in Phase 4"
            trailing={<TwSwitch checked={false} disabled size="md" />}
            style={styles.rowPadDisabled}
          />
        </TwCard>

        {/* Privacy */}
        {sectionHeader('Privacy')}
        <TwCard tone="default" padding={0} style={styles.group}>
          <TwRow
            title="App filter"
            subtitle="Control which apps are mirrored"
            onPress={() => router.push('/filter')}
            trailing={<Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>}
            style={styles.rowPad}
          />
          {divider}
          <TwRow
            title="Lock-screen preview"
            subtitle={lockScreenPreview ? 'Visible on lock screen' : 'Hidden until unlocked'}
            trailing={
              <TwSwitch
                checked={lockScreenPreview}
                onChange={handleLockScreenToggle}
                size="md"
              />
            }
            style={styles.rowPad}
          />
        </TwCard>

        {/* About */}
        {sectionHeader('About')}
        <TwCard tone="default" padding={0} style={styles.group}>
          <TwRow
            title="Reliability audit"
            subtitle="Tap to open system notification settings"
            onPress={() => TwinotifyCoreModule.openAppSettings().catch(() => {})}
            trailing={<Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>}
            style={styles.rowPad}
          />
          {divider}
          <TwRow
            title="Version"
            subtitle={`${version} · Phase 3`}
            style={styles.rowPad}
          />
        </TwCard>

      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    paddingHorizontal: 20,
    paddingVertical: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: { fontSize: 22, letterSpacing: -0.3 },
  scroll: { paddingHorizontal: 20, paddingTop: 16, paddingBottom: 40, gap: 0 },
  sectionHeader: {
    fontSize: 11,
    letterSpacing: 0.6,
    marginBottom: 8,
    marginTop: 20,
    paddingHorizontal: 4,
  },
  group: { overflow: 'hidden', marginBottom: 4 },
  rowPad: { paddingHorizontal: 16 },
  disabledRow: { opacity: 0.5 },
  rowPadDisabled: { paddingHorizontal: 16, opacity: 0.5 },
  divider: { height: StyleSheet.hairlineWidth, marginHorizontal: 16 },
  chevron: { fontSize: 22 },
});
