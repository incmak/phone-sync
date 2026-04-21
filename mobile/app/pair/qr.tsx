import React, { useEffect, useState } from 'react';
import { View, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import {
  useTheme,
  TwButton,
  TwQR,
  TwWordmark,
  TwStatusDot,
} from '../../components';
import TwinotifyCoreModule, { PairStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

type ScreenStatus = 'starting' | 'waiting' | 'connected' | 'error';

export default function PairQRScreen() {
  const theme = useTheme();
  const [payload, setPayload] = useState<string | null>(null);
  const [status, setStatus] = useState<ScreenStatus>('starting');
  const [seconds, setSeconds] = useState(300);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const relayUrl = await OnboardingState.getRelayUrl();
        if (!relayUrl) {
          setErrorMsg('No relay URL configured. Restart onboarding.');
          setStatus('error');
          return;
        }

        const payloadStr = await TwinotifyCoreModule.startPairInitiator(relayUrl);
        if (cancelled) return;
        setPayload(payloadStr);
        setStatus('waiting');

        let parsed: { pairToken: string };
        try {
          parsed = JSON.parse(payloadStr) as { pairToken: string };
        } catch {
          setErrorMsg('Malformed pairing payload from native module.');
          setStatus('error');
          return;
        }

        // awaitPairSig blocks until relay pushes pair.sig (Device B complete signal).
        try {
          await TwinotifyCoreModule.awaitPairSig(relayUrl, parsed.pairToken);
          if (cancelled) return;
          setStatus('connected');

          const pairStatus: PairStatus = await TwinotifyCoreModule.getPairStatus();
          if (cancelled) return;

          router.push({
            pathname: '/pair/fingerprint',
            params: {
              role: 'A',
              pairToken: parsed.pairToken,
              relayUrl,
              peerEncB64: pairStatus.peerEncPubkey ?? '',
              peerSignB64: pairStatus.peerSignPubkey ?? '',
              peerDeviceId: pairStatus.peerDeviceId ?? 'peer',
            },
          });
        } catch (sigErr: unknown) {
          if (cancelled) return;
          setErrorMsg(sigErr instanceof Error ? sigErr.message : 'Pair wait failed.');
          setStatus('error');
        }
      } catch (err: unknown) {
        if (cancelled) return;
        setErrorMsg(err instanceof Error ? err.message : 'Pair init failed.');
        setStatus('error');
      }
    })();

    return () => { cancelled = true; };
  }, []);

  // Countdown timer — only ticks while waiting
  useEffect(() => {
    if (status !== 'waiting' || seconds <= 0) return;
    const id = setTimeout(() => setSeconds((s) => s - 1), 1000);
    return () => clearTimeout(id);
  }, [status, seconds]);

  const mm = String(Math.floor(seconds / 60));
  const ss = String(seconds % 60).padStart(2, '0');

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: theme.bg }}
    >
      <View style={{ flex: 1, padding: 20 }}>
        {/* Header row */}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <TwWordmark size={16} />
          <View style={{
            paddingHorizontal: 10, paddingVertical: 6,
            backgroundColor: theme.fill,
            borderRadius: 999,
          }}>
            <Text style={{ fontFamily: theme.fonts.mono, fontSize: 13, color: theme.ink3 }}>
              {mm}:{ss}
            </Text>
          </View>
        </View>

        {/* Title */}
        <Text style={{
          fontSize: 20,
          fontFamily: theme.fonts.uiSemi,
          color: theme.ink,
          textAlign: 'center',
          marginBottom: 24,
        }}>
          Scan this from your other phone
        </Text>

        {/* QR code */}
        <View style={{ alignItems: 'center', marginBottom: 28 }}>
          {payload ? (
            <TwQR size={240} value={payload} />
          ) : (
            <View style={{
              width: 259, height: 259,
              backgroundColor: theme.fill,
              borderRadius: 14,
              borderWidth: 1, borderColor: theme.border,
              alignItems: 'center', justifyContent: 'center',
            }}>
              <Text style={{ color: theme.ink4, fontFamily: theme.fonts.ui, fontSize: 14 }}>
                Generating QR…
              </Text>
            </View>
          )}
        </View>

        {/* Status row */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, justifyContent: 'center' }}>
          {status === 'waiting' && <TwStatusDot state="pairing" size={8} />}
          <Text style={{ fontSize: 14, color: theme.ink3, fontFamily: theme.fonts.ui }}>
            {status === 'starting' && 'Starting pairing…'}
            {status === 'waiting' && 'Waiting for Phone B…'}
            {status === 'connected' && 'Connected — navigating…'}
            {status === 'error' && (errorMsg ?? 'Pairing failed.')}
          </Text>
        </View>

        <View style={{ flex: 1 }} />

        <TwButton variant="secondary" fullWidth onPress={() => router.back()}>
          Cancel
        </TwButton>
      </View>
    </SafeAreaView>
  );
}
