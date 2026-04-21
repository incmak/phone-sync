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

import { useTheme, TwCard, TwFingerprint, TwButton, TwSpinner } from '../../components';
import TwinotifyCoreModule, { PairStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

// ── component ─────────────────────────────────────────────────────────────────

export default function PairDetailScreen() {
  const theme = useTheme();

  const [pairStatus, setPairStatus] = useState<PairStatus | null>(null);
  const [fingerprint, setFingerprint] = useState<string | null>(null);
  const [unpairing, setUnpairing] = useState(false);

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
          } catch {
            // fingerprint unavailable — show blank grid
          }
        }
      })
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

  const peerIdFull = pairStatus?.peerDeviceId ?? '';
  const peerIdShort = peerIdFull.slice(0, 8) || '—';

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      {/* Header */}
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable onPress={() => router.back()} hitSlop={12} style={styles.backBtn}>
          <Text style={[styles.backLabel, { color: theme.accent, fontFamily: theme.fonts.ui }]}>‹ Settings</Text>
        </Pressable>
        <Text style={[styles.headerTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Paired device
        </Text>
        <View style={styles.backBtn} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        {/* Device hero */}
        <View style={styles.hero}>
          <View style={[styles.deviceIcon, { backgroundColor: theme.accentLo }]}>
            {/* Phone silhouette */}
            <Text style={[styles.phoneMoji, { color: theme.accent }]}>📱</Text>
          </View>
          <Text style={[styles.peerId, { color: theme.ink, fontFamily: theme.fonts.mono }]}>
            {pairStatus === null ? '…' : peerIdShort}
          </Text>
          <Text style={[styles.peerFull, { color: theme.ink3, fontFamily: theme.fonts.mono }]}>
            {peerIdFull || '—'}
          </Text>
          <Text style={[styles.peerMeta, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
            Phase 3 · UUID identifier
          </Text>
        </View>

        {/* Fingerprint section */}
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

        {/* Spacer */}
        <View style={styles.spacer} />

        {/* Unpair button */}
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
  backBtn: { minWidth: 80 },
  backLabel: { fontSize: 16 },
  headerTitle: { fontSize: 17 },
  scroll: {
    paddingHorizontal: 20,
    paddingTop: 24,
    paddingBottom: 40,
    flexGrow: 1,
  },
  // Hero
  hero: { alignItems: 'center', paddingBottom: 32 },
  deviceIcon: {
    width: 88,
    height: 88,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 16,
  },
  phoneMoji: { fontSize: 40 },
  peerId: { fontSize: 22, fontWeight: '600', letterSpacing: -0.2, marginBottom: 6 },
  peerFull: { fontSize: 11, letterSpacing: 0.5, marginBottom: 4 },
  peerMeta: { fontSize: 13 },
  // Section
  sectionHeader: {
    fontSize: 11,
    letterSpacing: 0.6,
    marginBottom: 10,
    paddingHorizontal: 4,
  },
  fpPlaceholder: { alignItems: 'center', justifyContent: 'center', minHeight: 100 },
  fpEmpty: { fontSize: 14 },
  fpHint: { fontSize: 12, marginTop: 10, paddingHorizontal: 4, lineHeight: 18 },
  spacer: { flex: 1, minHeight: 32 },
});
