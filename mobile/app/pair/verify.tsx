import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { BackHandler, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { useTheme } from '../../components';
import TwinotifyCoreModule, {
  type OfflinePairingStatus,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { repairForOfflineError } from './nearby';

function errorCodeFrom(error: unknown): string {
  if (error && typeof error === 'object' && 'code' in error && typeof error.code === 'string') {
    return error.code;
  }
  return 'pair_runtime_unavailable';
}

function VerifyAction({ label, onPress, quiet, disabled }: {
  label: string;
  onPress: () => void;
  quiet?: boolean;
  disabled?: boolean;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: Boolean(disabled) }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.action,
        {
          backgroundColor: quiet ? 'transparent' : theme.ink,
          opacity: disabled ? 0.45 : pressed ? 0.78 : 1,
        },
      ]}
    >
      <Text style={[styles.actionLabel, { color: quiet ? theme.ink2 : theme.bg, fontFamily: theme.fonts.uiSemi }]}>
        {label}
      </Text>
    </Pressable>
  );
}

export default function VerifyNearbyScreen() {
  const theme = useTheme();
  const [status, setStatus] = useState<OfflinePairingStatus | null>(null);
  const [working, setWorking] = useState(false);
  const [confirmationError, setConfirmationError] = useState<string | null>(null);

  const applyStatus = useCallback((next: OfflinePairingStatus) => {
    setStatus(next);
    if (next.completed && next.phase === 'complete') router.replace('/pair/success');
    if (next.errorCode) router.replace('/pair/nearby');
  }, []);

  useEffect(() => {
    let mounted = true;
    const subscription = TwinotifyCoreModule.addListener('onOfflinePairingStatus', (next) => {
      if (mounted) applyStatus(next);
    });
    void TwinotifyCoreModule.getOfflinePairingStatus().then((next) => {
      if (mounted) applyStatus(next);
    });
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, [applyStatus]);

  const cancel = useCallback(async (destination: 'back' | 'fail' | 'repair') => {
    if (status?.sessionId) {
      try {
        await TwinotifyCoreModule.cancelOfflinePairing(status.sessionId);
      } catch {
        // Native still owns cleanup; navigation must remain available.
      }
    }
    if (destination === 'back') router.back();
    else if (destination === 'fail') {
      router.replace({ pathname: '/pair/fail', params: { reason: 'identity_mismatch' } });
    } else {
      router.replace('/pair/nearby');
    }
  }, [status]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      void cancel('back');
      return true;
    });
    return () => subscription.remove();
  }, [cancel]);

  const confirm = useCallback(async () => {
    if (!status?.sessionId) return;
    setWorking(true);
    setConfirmationError(null);
    try {
      await TwinotifyCoreModule.confirmOfflinePairing(status.sessionId);
    } catch (error: unknown) {
      setConfirmationError(errorCodeFrom(error));
    } finally {
      setWorking(false);
    }
  }, [status]);

  const displayCode = useMemo(() => {
    const sas = status?.sas ?? '';
    return sas.length === 6 ? `${sas.slice(0, 3)} ${sas.slice(3)}` : 'Waiting…';
  }, [status?.sas]);
  const confirmationRepair = confirmationError
    ? repairForOfflineError(confirmationError)
    : null;

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Compare both phones
        </Text>
        <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          This six-digit code must be identical on both screens. Confirm only after checking every digit.
        </Text>

        <View style={[styles.codeSurface, { backgroundColor: theme.fill }]} accessible accessibilityLabel={`Verification code ${status?.sas ?? 'not ready'}`}>
          <Text style={[styles.code, { color: theme.ink, fontFamily: theme.fonts.monoMedium }]}>
            {displayCode}
          </Text>
        </View>

        <Text accessibilityLiveRegion="polite" style={[styles.peer, { color: theme.ink2, fontFamily: theme.fonts.uiMedium }]}>
          {status?.peerDisplayName ? `Comparing with ${status.peerDisplayName}` : 'Waiting for the other phone'}
        </Text>

        {confirmationRepair ? (
          <View style={styles.repair} accessibilityRole="alert">
            <Text style={[styles.repairTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
              {confirmationRepair.title}
            </Text>
            <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
              {confirmationRepair.body}
            </Text>
            <View style={styles.actions}>
              <VerifyAction label="Return to nearby pairing" onPress={() => { void cancel('repair'); }} />
            </View>
          </View>
        ) : (
          <View style={styles.actions}>
            <VerifyAction
              label="Codes match"
              disabled={!status?.sessionId || !status.sas || working}
              onPress={() => { void confirm(); }}
            />
            <VerifyAction label="Codes do not match" quiet onPress={() => { void cancel('fail'); }} />
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 24 },
  title: { fontSize: 24, lineHeight: 30 },
  body: { fontSize: 15, lineHeight: 22, marginTop: 10, maxWidth: 440 },
  codeSurface: { minHeight: 112, borderRadius: 14, marginTop: 36, paddingHorizontal: 16, paddingVertical: 20, justifyContent: 'center' },
  code: { fontSize: 38, lineHeight: 48, letterSpacing: 5, textAlign: 'center' },
  peer: { fontSize: 14, lineHeight: 20, marginTop: 18, textAlign: 'center' },
  repair: { marginTop: 24 },
  repairTitle: { fontSize: 18, lineHeight: 24, marginBottom: 4 },
  actions: { gap: 8, marginTop: 'auto', paddingTop: 32 },
  action: { minHeight: 48, borderRadius: 14, paddingHorizontal: 18, paddingVertical: 12, justifyContent: 'center', alignItems: 'center' },
  actionLabel: { fontSize: 15, lineHeight: 21, textAlign: 'center' },
});
