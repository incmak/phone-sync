import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  Linking,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { useTheme, TwCard, TwFingerprint, TwButton, TwSpinner, TwRow, TwSwitch } from '../../components';
import TwinotifyCoreModule, {
  BluetoothRouteSettings,
  PairStatus,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

const BLUETOOTH_EXPLANATION =
  'Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.';

export default function PairDetailScreen() {
  const theme = useTheme();

  const [pairStatus, setPairStatus] = useState<PairStatus | null>(null);
  const [fingerprint, setFingerprint] = useState<string | null>(null);
  const [unpairing, setUnpairing] = useState(false);
  const [bluetooth, setBluetooth] = useState<BluetoothRouteSettings | null>(null);
  const [bluetoothBusy, setBluetoothBusy] = useState(false);

  useEffect(() => {
    TwinotifyCoreModule.getPairStatus()
      .then(async (ps) => {
        setPairStatus(ps);
        if (ps.paired && ps.peerEncPubkey && ps.peerSignPubkey) {
          try {
            const fp = await TwinotifyCoreModule.computeFingerprint(
              ps.peerEncPubkey,
              ps.peerSignPubkey,
            );
            setFingerprint(fp);
          } catch {}
        }
      })
      .catch(() => {});
    TwinotifyCoreModule.getBluetoothRouteSettings()
      .then(setBluetooth)
      .catch(() => {});
  }, []);

  const handleUnpair = useCallback(() => {
    Alert.alert(
      'Unpair this device?',
      'Your peer keys will be cleared and yours will be rotated. You will need to pair again to resume mirroring.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Unpair',
          style: 'destructive',
          onPress: async () => {
            setUnpairing(true);
            try {
              await TwinotifyCoreModule.unpair();
              await OnboardingState.reset();
              router.replace('/onboarding/role');
            } catch (e: unknown) {
              const msg = e instanceof Error ? e.message : 'Unknown error';
              Alert.alert('Unpair failed', msg);
              setUnpairing(false);
            }
          },
        },
      ],
    );
  }, []);

  const reloadBluetooth = useCallback(async () => {
    try {
      setBluetooth(await TwinotifyCoreModule.getBluetoothRouteSettings());
    } catch {
      // Keep the last durable answer rather than guessing at association state.
    }
  }, []);

  const openBluetoothSettings = useCallback(() => {
    void (async () => {
      try {
        await Linking.sendIntent('android.settings.BLUETOOTH_SETTINGS');
      } catch {
        // Quick settings remains available; nothing else changed.
      }
    })();
  }, []);

  const handleBluetoothAssociation = useCallback(async () => {
    if (bluetoothBusy) return;
    setBluetoothBusy(true);
    try {
      const permission = await TwinotifyCoreModule.requestBluetoothRoutePermissionAsync();
      if (!permission.granted) {
        if (permission.canAskAgain) {
          Alert.alert(
            'Nearby devices permission needed',
            'Bluetooth fallback needs the Nearby devices permission. Nothing changed.',
          );
        } else {
          Alert.alert(
            'Nearby devices permission needed',
            'Allow Nearby devices in Android settings to set up Bluetooth fallback. Nothing changed.',
            [
              { text: 'Not now', style: 'cancel' },
              {
                text: 'Open settings',
                onPress: () => { void TwinotifyCoreModule.openAppSettings().catch(() => {}); },
              },
            ],
          );
        }
        return;
      }
      // The picker can be cancelled; the durable settings below stay authoritative
      // either way, so a cancelled association returns quietly.
      await TwinotifyCoreModule.startBluetoothAssociation();
      await reloadBluetooth();
    } catch (error) {
      // Bounded native failure codes arrive as the rejection message.
      if ((error as { message?: string } | null)?.message === 'bluetooth_unavailable') {
        Alert.alert(
          'Turn on Bluetooth',
          'Bluetooth is off on this phone, so the fallback cannot be set up. Nothing changed.',
          [
            { text: 'Not now', style: 'cancel' },
            { text: 'Open settings', onPress: openBluetoothSettings },
          ],
        );
      } else {
        Alert.alert('Bluetooth fallback unavailable', 'Nothing changed. Try again.');
      }
    } finally {
      setBluetoothBusy(false);
    }
  }, [bluetoothBusy, openBluetoothSettings, reloadBluetooth]);

  const handleBluetoothRouteChange = useCallback(async (next: boolean) => {
    const previous = bluetooth?.enabled ?? false;
    setBluetoothBusy(true);
    try {
      const durable = await TwinotifyCoreModule.setBluetoothRouteEnabled(next);
      setBluetooth((current) => (current ? { ...current, enabled: durable } : current));
      if (durable !== next) {
        Alert.alert(
          'Bluetooth fallback unavailable',
          'Nothing changed. Check Nearby devices permission and try again.',
        );
      }
    } catch {
      setBluetooth((current) => (current ? { ...current, enabled: previous } : current));
      Alert.alert('Bluetooth fallback unavailable', 'Nothing changed. Try again.');
    } finally {
      setBluetoothBusy(false);
    }
  }, [bluetooth?.enabled]);

  const handleRemoveBluetooth = useCallback(() => {
    Alert.alert(
      'Remove Bluetooth fallback?',
      'Twinotify will stop nearby Bluetooth sync with this paired phone. Wi-Fi and relay pairing stay unchanged.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Remove',
          style: 'destructive',
          onPress: () => {
            void (async () => {
              setBluetoothBusy(true);
              try {
                await TwinotifyCoreModule.removeBluetoothAssociation();
                await reloadBluetooth();
              } catch {
                Alert.alert('Bluetooth fallback unavailable', 'Nothing changed. Try again.');
              } finally {
                setBluetoothBusy(false);
              }
            })();
          },
        },
      ],
    );
  }, [reloadBluetooth]);

  const handleEnableNearby = useCallback(() => {
    void OnboardingState.setPairingMode('nearby');
    router.push('/pair/nearby');
  }, []);

  const peerIdFull = pairStatus?.peerDeviceId ?? '';
  const peerDisplayName = pairStatus?.peerDisplayName?.trim() || '';

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back to settings"
          onPress={() => router.back()}
          hitSlop={8}
          style={styles.backBtn}
        >
          <Text style={[styles.backLabel, { color: theme.accent, fontFamily: theme.fonts.ui }]}>‹ Settings</Text>
        </Pressable>
        <Text style={[styles.headerTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Paired device
        </Text>
        <View style={styles.backBtn} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        <View style={styles.hero}>
          <Text style={[styles.peerId, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
            {pairStatus === null ? '…' : (peerDisplayName || 'Paired device')}
          </Text>
          <Text style={[styles.peerFull, { color: theme.ink3, fontFamily: theme.fonts.mono }]}>
            {peerIdFull || 'Unavailable'}
          </Text>
        </View>

        <Text style={[styles.sectionHeader, { color: theme.ink4, fontFamily: theme.fonts.uiSemi }]}>
          PEER FINGERPRINT
        </Text>

        {fingerprint !== null ? (
          <TwFingerprint hex={fingerprint} highlightGroups={[0, 5, 10, 15]} />
        ) : (
          <TwCard tone="fill" padding={20} style={styles.fpPlaceholder}>
            {pairStatus === null ? (
              <TwSpinner />
            ) : (
              <Text style={[styles.fpEmpty, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                {pairStatus.paired
                  ? 'Fingerprint unavailable'
                  : 'No paired device'}
              </Text>
            )}
          </TwCard>
        )}

        <Text style={[styles.fpHint, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          If you ever reinstall, check that these 64 characters are unchanged.
        </Text>

        {pairStatus?.paired && (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Enable nearby sync"
            onPress={handleEnableNearby}
            style={({ pressed }) => [
              styles.nearbyAction,
              { backgroundColor: pressed ? theme.hover : theme.fill },
            ]}
          >
            <Text style={[styles.nearbyTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
              Enable nearby sync
            </Text>
            <Text style={[styles.nearbyBody, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
              Add a direct Wi-Fi path without replacing this relay pair.
            </Text>
          </Pressable>
        )}

        {pairStatus?.paired && bluetooth !== null && (
          <View style={styles.bluetoothGroup}>
            {bluetooth.associated ? (
              <>
                <TwRow
                  title="Bluetooth fallback"
                  subtitle={`Associated. ${BLUETOOTH_EXPLANATION}`}
                  accessibilityLabel="Bluetooth fallback, associated"
                  style={styles.ledgerRow}
                />
                <TwRow
                  title="Use Bluetooth fallback"
                  subtitle={bluetooth.enabled
                    ? 'On. Encrypted sync can use Bluetooth when higher-priority delivery is unavailable.'
                    : 'Off. The association is kept until you remove it.'}
                  trailing={
                    <View style={styles.controlSlot}>
                      <TwSwitch
                        checked={bluetooth.enabled}
                        onChange={(next) => { void handleBluetoothRouteChange(next); }}
                        size="md"
                        touchTargetSize={48}
                        disabled={bluetoothBusy}
                        accessibilityLabel={`Use Bluetooth fallback, ${bluetooth.enabled ? 'On' : 'Off'}`}
                      />
                    </View>
                  }
                  style={styles.ledgerRow}
                />
                <TwRow
                  title="Remove Bluetooth fallback"
                  onPress={handleRemoveBluetooth}
                  style={styles.ledgerRow}
                />
              </>
            ) : (
              // Setting Bluetooth up is the same kind of action as adding nearby Wi-Fi, so it
              // uses the same card rather than a ledger row.
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Set up Bluetooth fallback"
                onPress={() => { void handleBluetoothAssociation(); }}
                style={({ pressed }) => [
                  styles.bluetoothAction,
                  { backgroundColor: pressed ? theme.hover : theme.fill },
                ]}
              >
                <Text style={[styles.nearbyTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
                  Bluetooth fallback
                </Text>
                <Text style={[styles.nearbyBody, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
                  {BLUETOOTH_EXPLANATION}
                </Text>
              </Pressable>
            )}
          </View>
        )}

        <View style={styles.spacer} />

        <TwButton
          variant="destructive"
          size="md"
          fullWidth
          onPress={handleUnpair}
          loading={unpairing}
          disabled={!pairStatus?.paired}
        >
          Unpair
        </TwButton>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backBtn: { minWidth: 80, minHeight: 48, justifyContent: 'center' },
  backLabel: { fontSize: 16 },
  headerTitle: { fontSize: 17 },
  scroll: {
    paddingHorizontal: 20,
    paddingTop: 24,
    paddingBottom: 40,
    flexGrow: 1,
  },
  hero: { alignItems: 'center', paddingBottom: 32 },
  peerId: { fontSize: 22, fontWeight: '600', letterSpacing: -0.2, marginBottom: 6 },
  peerFull: { fontSize: 11, letterSpacing: 0.5, marginBottom: 4 },
  peerMeta: { fontSize: 13 },
  sectionHeader: {
    fontSize: 11,
    letterSpacing: 0.6,
    marginBottom: 10,
    paddingHorizontal: 4,
  },
  fpPlaceholder: { alignItems: 'center', justifyContent: 'center', minHeight: 100 },
  fpEmpty: { fontSize: 14 },
  fpHint: { fontSize: 12, marginTop: 10, paddingHorizontal: 4, lineHeight: 18 },
  nearbyAction: { minHeight: 72, borderRadius: 14, paddingHorizontal: 16, paddingVertical: 14, marginTop: 24, justifyContent: 'center' },
  nearbyTitle: { fontSize: 15, lineHeight: 21 },
  nearbyBody: { fontSize: 13, lineHeight: 19, marginTop: 3 },
  bluetoothGroup: { gap: 2, marginTop: 24 },
  bluetoothAction: {
    minHeight: 72,
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 14,
    justifyContent: 'center',
  },
  ledgerRow: { paddingHorizontal: 0, paddingVertical: 12, alignItems: 'flex-start' },
  controlSlot: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: -2,
  },
  spacer: { flex: 1, minHeight: 32 },
});
