import React, { useEffect, useState } from 'react';
import { View, Text, ScrollView } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useLocalSearchParams } from 'expo-router';
import {
  useTheme,
  TwButton,
  TwFingerprint,
  TwSpinner,
} from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

type ParamKeys =
  | 'role'
  | 'pairToken'
  | 'peerEncB64'
  | 'peerSignB64'
  | 'peerDeviceId'
  | 'peerDisplayName'
  | 'relayUrl';

export default function FingerprintScreen() {
  const theme = useTheme();
  const params = useLocalSearchParams<Record<ParamKeys, string>>();

  const role = params.role as 'A' | 'B';
  const pairToken = params.pairToken ?? '';
  const peerEncB64 = params.peerEncB64 ?? '';
  const peerSignB64 = params.peerSignB64 ?? '';
  const peerDeviceId = params.peerDeviceId ?? 'peer';
  const peerDisplayName = params.peerDisplayName ?? '';
  const relayUrl = params.relayUrl ?? '';

  const [ownFp, setOwnFp] = useState('');
  const [peerFp, setPeerFp] = useState('');
  const [working, setWorking] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const own = await TwinotifyCoreModule.getPublicKeys();
        const [of_, pf] = await Promise.all([
          TwinotifyCoreModule.computeFingerprint(own.encPubkey, own.signPubkey),
          TwinotifyCoreModule.computeFingerprint(peerEncB64, peerSignB64),
        ]);
        setOwnFp(of_);
        setPeerFp(pf);
      } catch (err: unknown) {
        setErrorMsg(err instanceof Error ? err.message : 'Failed to compute fingerprints.');
      }
    })();
  }, [peerEncB64, peerSignB64]);

  async function handleAConfirmMatch() {
    try {
      setWorking(true);
      setErrorMsg(null);
      const sigB64 = await TwinotifyCoreModule.deviceASignConfirmation(
        pairToken, peerEncB64, peerSignB64,
      );
      await TwinotifyCoreModule.sendConfirmationSig(relayUrl, pairToken, sigB64);
      await TwinotifyCoreModule.storePeerPubkeys(peerEncB64, peerSignB64, peerDeviceId, peerDisplayName);
      router.replace('/pair/success');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Signing or relay push failed.');
      setWorking(false);
    }
  }

  async function handleBConfirmMatch() {
    try {
      setWorking(true);
      setErrorMsg(null);
      const sigB64 = await TwinotifyCoreModule.awaitPairSig(relayUrl, pairToken);
      await TwinotifyCoreModule.deviceBCompletePairing(relayUrl, pairToken, sigB64);
      await TwinotifyCoreModule.storePeerPubkeys(peerEncB64, peerSignB64, peerDeviceId, peerDisplayName);
      router.replace('/pair/success');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Pair completion failed.');
      router.replace('/pair/fail');
    }
  }

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: theme.bg }}
    >
      <ScrollView
        contentContainerStyle={{ padding: 20, flexGrow: 1 }}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={{ fontSize: 22, fontFamily: theme.fonts.uiSemi, color: theme.ink, marginBottom: 8 }}>
          Verify the match
        </Text>
        <Text style={{ fontSize: 15, color: theme.ink3, fontFamily: theme.fonts.ui, lineHeight: 22, marginBottom: 4 }}>
          {peerDisplayName?.trim()
            ? `Compare these 16 blocks with what ${peerDisplayName} is showing. They must match exactly.`
            : 'Compare these 16 blocks with what your other phone is showing. They must match exactly.'}
        </Text>
        {peerDisplayName ? (
          <Text style={{ fontSize: 13, color: theme.ink3, fontFamily: theme.fonts.ui, marginBottom: 20 }}>
            Pairing with: <Text style={{ color: theme.ink, fontFamily: theme.fonts.uiSemi }}>{peerDisplayName}</Text>
          </Text>
        ) : (
          <View style={{ marginBottom: 20 }} />
        )}

        <Text style={{
          fontSize: 11, fontFamily: theme.fonts.uiSemi, color: theme.ink3,
          marginBottom: 6, letterSpacing: 0.4, textTransform: 'uppercase',
        }}>
          Peer fingerprint
        </Text>
        {peerFp
          ? <TwFingerprint hex={peerFp} columns={4} />
          : <Text style={{ color: theme.ink4, fontFamily: theme.fonts.ui }}>Loading…</Text>}

        <Text style={{
          fontSize: 11, fontFamily: theme.fonts.uiSemi, color: theme.ink3,
          marginTop: 16, marginBottom: 6, letterSpacing: 0.4, textTransform: 'uppercase',
        }}>
          Your fingerprint
        </Text>
        {ownFp
          ? <TwFingerprint hex={ownFp} columns={4} />
          : <Text style={{ color: theme.ink4, fontFamily: theme.fonts.ui }}>Loading…</Text>}

        {role === 'B' && working && (
          <Text style={{
            fontSize: 13, color: theme.ink3, fontFamily: theme.fonts.ui,
            marginTop: 16, lineHeight: 20,
          }}>
            Waiting for Phone A to confirm…
          </Text>
        )}

        {errorMsg !== null && (
          <Text style={{
            color: theme.sem.danger.foreground,
            fontFamily: theme.fonts.ui,
            fontSize: 14,
            marginTop: 12,
            lineHeight: 20,
          }}>
            {errorMsg}
          </Text>
        )}

        <View style={{ flex: 1, minHeight: 24 }} />

        {working ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, padding: 12 }}>
            <TwSpinner size={16} />
            <Text style={{ color: theme.ink3, fontFamily: theme.fonts.ui }}>Working…</Text>
          </View>
        ) : (
          <View style={{ gap: 8, marginTop: 24 }}>
            {role === 'A' && (
              <TwButton variant="primary" fullWidth onPress={handleAConfirmMatch}>
                They match
              </TwButton>
            )}
            {role === 'B' && (
              <TwButton variant="primary" fullWidth onPress={handleBConfirmMatch}>
                They match
              </TwButton>
            )}
            <TwButton
              variant="ghost"
              fullWidth
              onPress={() => router.replace('/pair/fail')}
            >
              {"Don't match"}
            </TwButton>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
