import React, { useCallback, useEffect, useState } from 'react';
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Constants from 'expo-constants';

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
    case 'LEGACY_ONLINE_ONLY': return 'online';
    case 'CONNECTING':     return 'connecting';
    case 'OFFLINE_QUEUED': return 'offline';
    case 'DISCONNECTED':   return 'offline';
  }
}

// ── component ─────────────────────────────────────────────────────────────────

export default function SettingsScreen() {
  const theme = useTheme();
  const { state } = useSyncStatus();

  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null | undefined>(undefined);
  const [preferLan, setPreferLan] = useState<boolean | null>(null);

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus()
      .then(setPairStatus)
      .catch(() => {});
    TwinotifyCoreModule.getPreferLan()
      .then(setPreferLan)
      .catch(() => {});
    OnboardingState.getRelayUrl()
      .then(setRelayUrl)
      .catch(() => {});
  }, []);

  const peerShort = pairStatus.peerDeviceId
    ? pairStatus.peerDeviceId.slice(0, 8)
    : 'Not paired';
  const peerStatusStr = pairStatus.paired ? `${peerShort} · ${connectionLabel(state)}` : 'Not paired';
  const handlePreferLanChange = useCallback(async (next: boolean) => {
    setPreferLan(next);
    try {
      await TwinotifyCoreModule.setPreferLan(next);
    } catch {
      // Keep the durable setting authoritative rather than showing a stale toggle.
      setPreferLan(!next);
    }
  }, []);

  const relayDisplay = relayUrl ?? (pairStatus.paired ? 'Direct Wi-Fi only' : 'Not configured');
  const version = Constants.expoConfig?.version ?? '1.0.0';

  const sectionHeader = (label: string) => (
    <Text style={[styles.sectionHeader, { color: theme.ink3, fontFamily: theme.fonts.uiSemi }]}>
      {label}
    </Text>
  );

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
        </TwCard>

        {/* Sync */}
        {sectionHeader('Sync')}
        <TwCard tone="default" padding={0} style={styles.group}>
          {relayUrl ? (
            <>
              <TwRow title="Relay server" subtitle={relayDisplay} style={styles.rowPad} />
              <TwRow
                title="Prefer direct Wi-Fi"
                subtitle={
                  preferLan === null
                    ? 'Loading delivery preference'
                    : preferLan
                    ? 'Delivers straight to your other phone when it is on the same Wi-Fi'
                    : 'Uses the relay first, with direct Wi-Fi as backup'
                }
                trailing={
                  <TwSwitch
                    checked={preferLan ?? false}
                    onChange={handlePreferLanChange}
                    size="md"
                    disabled={preferLan === null}
                    accessibilityLabel="Prefer direct Wi-Fi delivery"
                  />
                }
                style={styles.rowPad}
              />
            </>
          ) : (
            <TwRow
              title="Delivery route"
              subtitle={relayUrl === undefined ? 'Loading delivery configuration' : relayDisplay}
              style={styles.rowPad}
            />
          )}
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
        </TwCard>

        {/* About */}
        {sectionHeader('About')}
        <TwCard tone="default" padding={0} style={styles.group}>
          <TwRow
            title="Notification settings"
            subtitle="Tap to open system notification settings"
            onPress={() => TwinotifyCoreModule.openAppSettings().catch(() => {})}
            trailing={<Text style={[styles.chevron, { color: theme.ink3 }]}>›</Text>}
            style={styles.rowPad}
          />
          <TwRow
            title="Version"
            subtitle={version}
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
    letterSpacing: 0,
    marginBottom: 8,
    marginTop: 20,
    paddingHorizontal: 4,
  },
  group: { overflow: 'hidden', marginBottom: 4 },
  rowPad: { paddingHorizontal: 16 },
  chevron: { fontSize: 22 },
});
