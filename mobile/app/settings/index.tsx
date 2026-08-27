import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Constants from 'expo-constants';

import {
  useTheme,
  TwRow,
  TwSwitch,
} from '../../components';
import { HandoffDisclosureMark } from '../../components/HandoffTrace';
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
  const syncStatus = useSyncStatus();
  const { state } = syncStatus;
  const { width } = useWindowDimensions();
  const horizontalGutter = width <= 360 ? 16 : 22;

  const [pairStatus, setPairStatus] = useState<PairStatus>({ paired: false });
  const [relayUrl, setRelayUrl] = useState<string | null | undefined>(undefined);
  const [preferLan, setPreferLan] = useState<boolean | null>(null);
  const [callCaptureEnabled, setCallCaptureEnabled] = useState<boolean | null>(null);
  const [callPermissionCanAskAgain, setCallPermissionCanAskAgain] = useState(true);
  const [callCaptureBusy, setCallCaptureBusy] = useState(false);
  const [callEnablePending, setCallEnablePending] = useState(false);

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus()
      .then(setPairStatus)
      .catch(() => {});
    TwinotifyCoreModule.getPreferLan()
      .then(setPreferLan)
      .catch(() => {});
    Promise.all([
      TwinotifyCoreModule.getCallCaptureEnabled(),
      TwinotifyCoreModule.getCallStatePermissionAsync(),
    ]).then(([enabled, permission]) => {
      setCallCaptureEnabled(enabled && permission.granted);
      setCallPermissionCanAskAgain(permission.canAskAgain);
    }).catch(() => setCallCaptureEnabled(false));
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

  const persistCallCapture = useCallback(async (next: boolean) => {
    const previous = callCaptureEnabled ?? false;
    setCallCaptureBusy(true);
    try {
      if (next) {
        const permission = await TwinotifyCoreModule.requestCallStatePermissionAsync();
        setCallPermissionCanAskAgain(permission.canAskAgain);
        if (!permission.granted) {
          setCallCaptureEnabled(false);
          return;
        }
        setCallEnablePending(true);
      }
      await TwinotifyCoreModule.setCallCaptureEnabled(next);
      const durable = await TwinotifyCoreModule.getCallCaptureEnabled();
      setCallCaptureEnabled(durable);
    } catch {
      setCallCaptureEnabled(previous);
    } finally {
      setCallEnablePending(false);
      setCallCaptureBusy(false);
    }
  }, [callCaptureEnabled]);

  const handleCallCaptureChange = useCallback((next: boolean) => {
    if (!next) {
      void persistCallCapture(false);
      return;
    }
    Alert.alert(
      'Mirror call state?',
      'Twinotify shares only ringing, active, and ended states. It never shares phone numbers and adds no call controls.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Continue', onPress: () => { void persistCallCapture(true); } },
      ],
    );
  }, [persistCallCapture]);

  const relayDisplay = relayUrl ?? (pairStatus.paired ? 'Direct Wi-Fi only' : 'Not configured');
  const version = Constants.expoConfig?.version ?? '1.0.0';
  const callUnsupported = syncStatus.callCaptureDisabledReason === 'call_telephony_unsupported';
  const callPreferenceEnabled = callCaptureEnabled === true && !callUnsupported;
  const callStarting = callEnablePending;
  const callUnavailable = callPreferenceEnabled && syncStatus.callCaptureEnabled !== true;
  const callSubtitle = callUnsupported
    ? 'Call state mirroring is unavailable on this device.'
    : callStarting
      ? 'Enabled. Waiting for call capture to start. No phone numbers or controls.'
      : callUnavailable
        ? 'Enabled. Call capture is not active. No phone numbers or controls.'
        : 'Shares only ringing, active, and ended states. No phone numbers or controls.';
  const callStateLabel = callPreferenceEnabled ? 'On' : 'Off';

  const sectionHeader = (label: string) => (
    <Text style={[styles.sectionHeader, { color: theme.ink2, fontFamily: theme.fonts.uiMedium }]}>
      {label}
    </Text>
  );
  const disclosure = (testID: string) => (
    <View testID={testID} style={styles.disclosureSlot}>
      <HandoffDisclosureMark color={theme.accentText} />
    </View>
  );

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <View style={[styles.header, { paddingHorizontal: horizontalGutter }]}>
        <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.display }]}>
          Settings
        </Text>
      </View>

      <ScrollView contentContainerStyle={[styles.scroll, { paddingHorizontal: horizontalGutter }]} showsVerticalScrollIndicator={false}>
        <View style={styles.group}>
          {sectionHeader('Pairing')}
          <TwRow
            title="Paired device"
            subtitle={peerStatusStr}
            onPress={pairStatus.paired ? () => router.push('/settings/pair') : undefined}
            trailing={pairStatus.paired ? disclosure('settings-pair-disclosure') : undefined}
            style={styles.ledgerRow}
          />
        </View>

        <View style={styles.group}>
          {sectionHeader('Sync')}
          {relayUrl ? (
            <>
              <TwRow title="Relay server" subtitle={relayDisplay} style={styles.ledgerRow} />
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
                  <View style={styles.controlSlot}>
                    <TwSwitch
                      checked={preferLan ?? false}
                      onChange={handlePreferLanChange}
                      size="md"
                      disabled={preferLan === null}
                      accessibilityLabel="Prefer direct Wi-Fi delivery"
                    />
                  </View>
                }
                style={styles.ledgerRow}
              />
            </>
          ) : (
            <TwRow
              title="Delivery route"
              subtitle={relayUrl === undefined ? 'Loading delivery configuration' : relayDisplay}
              style={styles.ledgerRow}
            />
          )}
        </View>

        <View style={styles.group}>
          {sectionHeader('Privacy')}
          <TwRow
            title="Mirror call state"
            subtitle={callCaptureEnabled === null ? 'Loading call state preference' : callSubtitle}
            trailing={
              <View style={styles.controlSlot}>
                <TwSwitch
                  checked={callPreferenceEnabled}
                  onChange={handleCallCaptureChange}
                  size="md"
                  disabled={callCaptureEnabled === null || callCaptureBusy || callUnsupported}
                  touchTargetSize={48}
                  accessibilityLabel={`Mirror call state, ${
                    callCaptureEnabled === null
                      ? 'Loading call state preference'
                      : `${callSubtitle} ${callStateLabel}`
                  }`}
                />
              </View>
            }
            style={styles.ledgerRow}
          />
          {!callPermissionCanAskAgain && !callPreferenceEnabled ? (
            <TwRow
              title="Allow call state permission"
              subtitle="Open Android settings"
              onPress={() => TwinotifyCoreModule.openAppSettings().catch(() => {})}
              accessibilityLabel="Open Android settings to allow call state permission"
              trailing={disclosure('settings-call-permission-disclosure')}
              style={styles.ledgerRow}
            />
          ) : null}
          <TwRow
            title="App filter"
            subtitle="Control which apps are mirrored"
            onPress={() => router.push('/filter')}
            trailing={disclosure('settings-filter-disclosure')}
            style={styles.ledgerRow}
          />
        </View>

        <View style={styles.group}>
          {sectionHeader('About')}
          <TwRow
            title="Notification settings"
            subtitle="Tap to open system notification settings"
            onPress={() => TwinotifyCoreModule.openAppSettings().catch(() => {})}
            trailing={disclosure('settings-notification-disclosure')}
            style={styles.ledgerRow}
          />
          <TwRow
            title="Version"
            subtitle={version}
            style={styles.ledgerRow}
          />
        </View>

      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    paddingTop: 18,
    paddingBottom: 10,
  },
  title: { fontSize: 34, letterSpacing: -0.4 },
  scroll: { paddingTop: 8, paddingBottom: 48, gap: 32 },
  sectionHeader: {
    fontSize: 16,
    letterSpacing: 0,
    marginBottom: 6,
  },
  group: { gap: 2 },
  ledgerRow: { paddingHorizontal: 0, paddingVertical: 12, alignItems: 'flex-start' },
  disclosureSlot: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: -2,
  },
  controlSlot: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: -2,
  },
});
