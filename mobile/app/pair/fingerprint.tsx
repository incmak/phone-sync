/**
 * Phase 3 fingerprint screen.
 *
 * Pairing protocol note (intentional Phase 3 interim UX):
 *   Device A signs the confirmation using deviceASignConfirmation, then
 *   displays the full base-64 sig as selectable text. Device B manually
 *   pastes that sig into the text input below and submits. This manual
 *   copy-paste step replaces the automated relay push that is scoped to
 *   Phase 4. The crypto is real; only the transport is manual.
 */

import React, { useEffect, useState } from 'react';
import { View, Text, TextInput, ScrollView } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useLocalSearchParams } from 'expo-router';
import {
  useTheme,
  TwButton,
  TwFingerprint,
  TwCard,
  TwSpinner,
} from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

type ParamKeys =
  | 'role'
  | 'pairToken'
  | 'peerEncB64'
  | 'peerSignB64'
  | 'peerDeviceId'
  | 'relayUrl';

export default function FingerprintScreen() {
  const theme = useTheme();
  const params = useLocalSearchParams<Record<ParamKeys, string>>();

  const role = params.role as 'A' | 'B';
  const pairToken = params.pairToken ?? '';
  const peerEncB64 = params.peerEncB64 ?? '';
  const peerSignB64 = params.peerSignB64 ?? '';
  const peerDeviceId = params.peerDeviceId ?? 'peer';
  const relayUrl = params.relayUrl ?? '';

  const [ownFp, setOwnFp] = useState('');
  const [peerFp, setPeerFp] = useState('');
  const [sigB64, setSigB64] = useState<string | null>(null); // Role A only
  const [inputSig, setInputSig] = useState('');              // Role B only
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

  // ---- Role A: sign confirmation and display sig for manual handoff ----
  async function handleAConfirmMatch() {
    try {
      setWorking(true);
      setErrorMsg(null);
      const sig = await TwinotifyCoreModule.deviceASignConfirmation(
        pairToken, peerEncB64, peerSignB64,
      );
      setSigB64(sig);
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Signing failed.');
    } finally {
      setWorking(false);
    }
  }

  // ---- Role A: after B has verified, store peer pubkeys and finish ----
  async function handleAFinish() {
    try {
      setWorking(true);
      await TwinotifyCoreModule.storePeerPubkeys(peerEncB64, peerSignB64, peerDeviceId);
      router.replace('/pair/success');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to store peer keys.');
    } finally {
      setWorking(false);
    }
  }

  // ---- Role B: submit A's sig to relay and finalise pair ----
  async function handleBConfirmMatch() {
    if (!inputSig.trim()) {
      setErrorMsg('Paste the confirmation code from Phone A.');
      return;
    }
    try {
      setWorking(true);
      setErrorMsg(null);
      await TwinotifyCoreModule.deviceBCompletePairing(relayUrl, pairToken, inputSig.trim());
      await TwinotifyCoreModule.storePeerPubkeys(peerEncB64, peerSignB64, peerDeviceId);
      router.replace('/pair/success');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Pair completion failed.');
      router.replace('/pair/fail');
    } finally {
      setWorking(false);
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
        <Text style={{ fontSize: 15, color: theme.ink3, fontFamily: theme.fonts.ui, lineHeight: 22, marginBottom: 24 }}>
          Compare these 16 blocks with what your other phone is showing. They must match exactly.
        </Text>

        {/* Peer fingerprint */}
        <Text style={{
          fontSize: 11, fontFamily: theme.fonts.uiSemi, color: theme.ink3,
          marginBottom: 6, letterSpacing: 0.4, textTransform: 'uppercase',
        }}>
          Peer fingerprint
        </Text>
        {peerFp
          ? <TwFingerprint hex={peerFp} columns={4} />
          : <Text style={{ color: theme.ink4, fontFamily: theme.fonts.ui }}>Loading…</Text>}

        {/* Own fingerprint */}
        <Text style={{
          fontSize: 11, fontFamily: theme.fonts.uiSemi, color: theme.ink3,
          marginTop: 16, marginBottom: 6, letterSpacing: 0.4, textTransform: 'uppercase',
        }}>
          Your fingerprint
        </Text>
        {ownFp
          ? <TwFingerprint hex={ownFp} columns={4} />
          : <Text style={{ color: theme.ink4, fontFamily: theme.fonts.ui }}>Loading…</Text>}

        {/* Role A — sig display (after confirming match) */}
        {role === 'A' && sigB64 !== null && (
          <TwCard tone="accent" style={{ marginTop: 20 }}>
            <Text style={{ fontSize: 13, fontFamily: theme.fonts.uiSemi, color: theme.ink, marginBottom: 8 }}>
              Type this code into Phone B
            </Text>
            {/* Phase 3 note: manual sig transport — Phase 4 automates via relay push */}
            <Text style={{
              fontSize: 11, color: theme.ink3, fontFamily: theme.fonts.ui,
              marginBottom: 10, lineHeight: 16,
            }}>
              Phase 3 interim: copy-paste this code to Phone B. Phase 4 will automate this step.
            </Text>
            <Text
              selectable
              style={{
                fontFamily: theme.fonts.mono, fontSize: 11,
                color: theme.ink, lineHeight: 17,
              }}
            >
              {sigB64}
            </Text>
          </TwCard>
        )}

        {/* Role B — sig input */}
        {role === 'B' && (
          <View style={{ marginTop: 20 }}>
            <Text style={{
              fontSize: 13, fontFamily: theme.fonts.uiSemi, color: theme.ink3, marginBottom: 8,
            }}>
              Paste the confirmation code from Phone A
            </Text>
            <Text style={{
              fontSize: 11, color: theme.ink4, fontFamily: theme.fonts.ui,
              marginBottom: 10, lineHeight: 16,
            }}>
              Phase 3 interim: Phone A displays this code after confirming the match.
            </Text>
            <TextInput
              value={inputSig}
              onChangeText={setInputSig}
              multiline
              placeholder="Paste confirmation code here"
              placeholderTextColor={theme.ink4}
              style={{
                backgroundColor: theme.fill,
                borderWidth: 1,
                borderColor: theme.border,
                borderRadius: 10,
                padding: 12,
                fontFamily: theme.fonts.mono,
                fontSize: 12,
                color: theme.ink,
                minHeight: 88,
                textAlignVertical: 'top',
              }}
            />
          </View>
        )}

        {/* Error message */}
        {errorMsg !== null && (
          <Text style={{
            color: theme.sem.danger,
            fontFamily: theme.fonts.ui,
            fontSize: 14,
            marginTop: 12,
            lineHeight: 20,
          }}>
            {errorMsg}
          </Text>
        )}

        <View style={{ flex: 1, minHeight: 24 }} />

        {/* Action buttons */}
        {working ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, padding: 12 }}>
            <TwSpinner size={16} />
            <Text style={{ color: theme.ink3, fontFamily: theme.fonts.ui }}>Working…</Text>
          </View>
        ) : (
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 24 }}>
            {/* Abort */}
            <TwButton
              variant="destructive"
              style={{ flex: 1 }}
              onPress={() => router.replace('/pair/fail')}
            >
              Don't match
            </TwButton>

            {/* Role A: pre-sig → sign; post-sig → finish */}
            {role === 'A' && sigB64 === null && (
              <TwButton variant="primary" style={{ flex: 1 }} onPress={handleAConfirmMatch}>
                They match
              </TwButton>
            )}
            {role === 'A' && sigB64 !== null && (
              <TwButton variant="primary" style={{ flex: 1 }} onPress={handleAFinish}>
                Finish
              </TwButton>
            )}

            {/* Role B: submit pasted sig */}
            {role === 'B' && (
              <TwButton variant="primary" style={{ flex: 1 }} onPress={handleBConfirmMatch}>
                They match
              </TwButton>
            )}
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
