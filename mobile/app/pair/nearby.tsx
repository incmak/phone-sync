import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  BackHandler,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { TwQR, useTheme } from '../../components';
import TwinotifyCoreModule, {
  type OfflinePairingStatus,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState, type Role } from '../../state/onboardingState';

type Repair = { title: string; body: string };

const DEFAULT_REPAIR: Repair = {
  title: 'Nearby pairing stopped',
  body: 'Keep both phones nearby, then create a new code and try again.',
};

export function repairForOfflineError(errorCode: string | null): Repair {
  switch (errorCode) {
    case 'expired':
      return {
        title: 'Pairing timed out',
        body: 'Create a new code and keep both phones on this screen until they connect.',
      };
    case 'wifi_unavailable':
      return {
        title: 'Wi-Fi may be isolating the phones',
        body: 'Put both phones on the same Wi-Fi. If it still fails, turn off guest or client isolation in the router settings.',
      };
    case 'wifi_permission_denied':
      return {
        title: 'Nearby devices permission is off',
        body: 'Allow Nearby devices for Twinotify in Android settings, then try again.',
      };
    case 'tls_pin_mismatch':
      return {
        title: 'TLS security check failed',
        body: 'The secure connection or TLS pin could not be verified. Rescan a newly created code from the other phone.',
      };
    case 'invalid_frame':
      return {
        title: 'Pairing data failed the security check',
        body: 'Create a new code on the other phone and scan it again. Do not reuse the previous code.',
      };
    case 'identity_mismatch':
      return {
        title: 'Phone identity did not match',
        body: 'Do not continue with this code. Your existing relay pair is unchanged.',
      };
    case 'peer_rejected':
      return {
        title: 'The other phone rejected pairing',
        body: 'Start again and confirm the same six-digit code on both phones.',
      };
    case 'pair_invalid_qr':
      return {
        title: 'This code is not valid',
        body: 'Create a new nearby-pairing code on the other phone and scan it again.',
      };
    case 'pair_runtime_unavailable':
      return {
        title: 'Nearby pairing is unavailable',
        body: 'Restart Twinotify on both phones. If it continues, check that Android allows Nearby devices.',
      };
    case 'pair_session_active':
    case 'pair_session_not_found':
    case 'pair_session_mismatch':
      return {
        title: 'Pairing session changed',
        body: 'Return to nearby pairing and create or scan a new code before confirming again.',
      };
    default:
      return DEFAULT_REPAIR;
  }
}

function errorCodeFrom(error: unknown): string {
  if (error && typeof error === 'object' && 'code' in error && typeof error.code === 'string') {
    return error.code;
  }
  return error instanceof Error ? error.message : 'pair_runtime_unavailable';
}

function PairAction({
  label,
  onPress,
  quiet = false,
  disabled = false,
}: {
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
      accessibilityState={{ disabled }}
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

export default function NearbyPairingScreen() {
  const theme = useTheme();
  const [role, setRole] = useState<Role | null>(null);
  const [status, setStatus] = useState<OfflinePairingStatus | null>(null);
  const [qrValue, setQrValue] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [starting, setStarting] = useState(true);
  const activeSessionIdRef = useRef<string | null>(null);

  const applyStatus = useCallback((next: OfflinePairingStatus) => {
    if (
      next.sessionId
      && activeSessionIdRef.current
      && next.sessionId !== activeSessionIdRef.current
    ) return;
    if (next.sessionId) activeSessionIdRef.current = next.sessionId;
    setStatus(next);
    setErrorCode(next.errorCode === null ? null : String(next.errorCode));
    if (next.completed && next.phase === 'complete') {
      router.replace('/pair/success');
    } else if (next.phase === 'verify_code' && next.sas && next.sessionId) {
      router.replace('/pair/verify');
    }
  }, []);

  const startForRole = useCallback(async (requestedRole: Role) => {
    setStarting(true);
    setErrorCode(null);
    setQrValue(null);
    setRole(requestedRole);
    await OnboardingState.setRole(requestedRole);
    await OnboardingState.setPairingMode('nearby');
    if (requestedRole === 'B') {
      router.replace({ pathname: '/pair/scan', params: { mode: 'nearby' } });
      return;
    }
    try {
      const displayName = await TwinotifyCoreModule.getDeviceDisplayName();
      const nativeQr = await TwinotifyCoreModule.startOfflinePairing(displayName);
      setQrValue(nativeQr);
      applyStatus(await TwinotifyCoreModule.getOfflinePairingStatus());
    } catch (error: unknown) {
      setErrorCode(errorCodeFrom(error));
    } finally {
      setStarting(false);
    }
  }, [applyStatus]);

  const restartForRole = useCallback(async (requestedRole: Role) => {
    const activeSessionId = activeSessionIdRef.current;
    if (activeSessionId) {
      try {
        await TwinotifyCoreModule.cancelOfflinePairing(activeSessionId);
      } catch {
        // Native start will return a bounded error if cleanup did not finish.
      }
    }
    activeSessionIdRef.current = null;
    await startForRole(requestedRole);
  }, [startForRole]);

  useEffect(() => {
    let mounted = true;
    const subscription = TwinotifyCoreModule.addListener('onOfflinePairingStatus', (next) => {
      if (mounted) applyStatus(next);
    });
    void (async () => {
      try {
        const current = await TwinotifyCoreModule.getOfflinePairingStatus();
        if (!mounted) return;
        if (current.sessionId || current.errorCode || current.completed) {
          applyStatus(current);
          setRole(current.role === 'initiator' ? 'A' : current.role === 'joiner' ? 'B' : null);
          setStarting(false);
          return;
        }
        const savedRole = await OnboardingState.getRole();
        if (!mounted) return;
        if (savedRole) await startForRole(savedRole);
        else setStarting(false);
      } catch (error: unknown) {
        if (mounted) {
          setErrorCode(errorCodeFrom(error));
          setStarting(false);
        }
      }
    })();
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, [applyStatus, startForRole]);

  const cancelAndLeave = useCallback(async () => {
    const sessionId = status?.sessionId;
    if (sessionId) {
      try {
        await TwinotifyCoreModule.cancelOfflinePairing(sessionId);
      } catch {
        // Native still owns cleanup; leaving must remain available.
      }
    }
    router.back();
  }, [status?.sessionId]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      void cancelAndLeave();
      return true;
    });
    return () => subscription.remove();
  }, [cancelAndLeave]);

  const repair = errorCode ? repairForOfflineError(errorCode) : null;
  const waitingCopy = status?.role === 'joiner'
    ? 'Finding the phone that created the code…'
    : 'Waiting for the other phone to scan…';

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Pair nearby
        </Text>

        {repair ? (
          <View style={styles.section} accessibilityRole="alert">
            <Text style={[styles.repairTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
              {repair.title}
            </Text>
            <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
              {repair.body}
            </Text>
            <View style={styles.actions}>
              <PairAction
                label="Try nearby pairing again"
                onPress={() => { void restartForRole(role ?? 'A'); }}
              />
              <PairAction label="Cancel nearby pairing" quiet onPress={() => { void cancelAndLeave(); }} />
            </View>
          </View>
        ) : role === null && !starting ? (
          <View style={styles.section}>
            <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
              Choose which phone will show the code. The other phone scans it.
            </Text>
            <View style={styles.actions}>
              <PairAction label="Show a code on this phone" onPress={() => { void startForRole('A'); }} />
              <PairAction label="Scan a code from the other phone" quiet onPress={() => { void startForRole('B'); }} />
            </View>
          </View>
        ) : (
          <View style={styles.section}>
            <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
              {qrValue
                ? 'Scan this code with Twinotify on your other phone. It stays on your local Wi-Fi.'
                : starting ? 'Preparing a local pairing session…' : waitingCopy}
            </Text>

            {qrValue !== null && (
              <View
                accessible
                accessibilityRole="image"
                accessibilityLabel="Nearby pairing QR code"
                style={styles.qr}
              >
                <TwQR value={qrValue} size={220} />
              </View>
            )}

            <Text accessibilityLiveRegion="polite" style={[styles.status, { color: theme.ink2, fontFamily: theme.fonts.uiMedium }]}>
              {qrValue ? waitingCopy : 'Keep both phones awake and on the same Wi-Fi.'}
            </Text>

            <View style={styles.actions}>
              <PairAction label="Cancel nearby pairing" quiet onPress={() => { void cancelAndLeave(); }} />
            </View>
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
  section: { flex: 1, paddingTop: 18 },
  body: { fontSize: 15, lineHeight: 22, maxWidth: 440 },
  qr: { alignSelf: 'center', marginVertical: 28 },
  status: { fontSize: 14, lineHeight: 20, textAlign: 'center', marginTop: 20 },
  repairTitle: { fontSize: 20, lineHeight: 26, marginTop: 8, marginBottom: 8 },
  actions: { gap: 8, marginTop: 'auto', paddingTop: 28 },
  action: { minHeight: 48, borderRadius: 14, paddingHorizontal: 18, paddingVertical: 12, justifyContent: 'center', alignItems: 'center' },
  actionLabel: { fontSize: 15, lineHeight: 21, textAlign: 'center' },
});
