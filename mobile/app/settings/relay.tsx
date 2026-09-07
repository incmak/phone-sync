import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useLocalSearchParams } from 'expo-router';
import Constants from 'expo-constants';

import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule, {
  type RelayAttachOutcome,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

const TIMEOUT_MS = 10_000;

function defaultRelayUrl(): string {
  const configured = Constants.expoConfig?.extra?.defaultRelayUrl;
  return typeof configured === 'string' ? configured : '';
}

/**
 * Bounded native codes become copy that says what happened and what still works. Every failure
 * here leaves the pair and its direct routes untouched, so each message can promise that plainly
 * rather than leaving the user wondering what it broke.
 */
function attachFailureCopy(code: Exclude<RelayAttachOutcome, 'attached'>): [string, string] {
  switch (code) {
    case 'relay_url_invalid':
      return ['Check the address', 'Use a secure https:// or wss:// relay address.'];
    case 'not_paired':
      return ['Pair the phones first', 'A relay is added to an existing pair. Nothing changed.'];
    case 'no_direct_route':
      return [
        'Bring the phones together',
        'Both phones need to be connected to each other to set this up. Nothing changed.',
      ];
    case 'relay_unreachable':
      return ['Could not reach the relay', 'Check the address and try again. Nothing changed.'];
    case 'peer_timeout':
      return [
        'The other phone did not answer',
        'Open Twinotify on your other phone and try again. Nothing changed.',
      ];
    case 'peer_identity_mismatch':
      return [
        'That was not your phone',
        'The device answering was not the phone you paired with. Nothing changed.',
      ];
    default:
      return ['Could not add the relay', 'Nothing changed. Try again.'];
  }
}

type TestState = 'idle' | 'testing' | 'ok' | 'error';

export default function RelaySetupScreen() {
  const theme = useTheme();
  const params = useLocalSearchParams<{ mode?: string; peerLinkId?: string }>();
  const changing = params.mode === 'change';

  const [url, setUrl] = useState(defaultRelayUrl);
  const [testState, setTestState] = useState<TestState>('idle');
  const [latency, setLatency] = useState<number | null>(null);
  const [errorMsg, setErrorMsg] = useState('');
  const [saving, setSaving] = useState(false);
  const [peerName, setPeerName] = useState('your other phone');

  useEffect(() => {
    (params.peerLinkId ? TwinotifyCoreModule.getPeerStatus(params.peerLinkId) : TwinotifyCoreModule.getPairStatus())
      .then((status) => {
        const name = status.peerDisplayName?.trim();
        if (name) setPeerName(name);
      })
      .catch(() => {});
  }, [params.peerLinkId]);

  const handleTest = useCallback(async () => {
    const trimmed = url.trim();
    if (!trimmed) return;
    setTestState('testing');
    setErrorMsg('');
    setLatency(null);

    let healthUrl: string;
    try {
      const parsed = new URL(trimmed);
      if (parsed.protocol !== 'https:' && parsed.protocol !== 'wss:') {
        throw new Error('secure relay required');
      }
      healthUrl = `https://${parsed.host}/health`;
    } catch {
      setErrorMsg('Use a secure https:// or wss:// address');
      setTestState('error');
      return;
    }

    const start = Date.now();
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), TIMEOUT_MS);
      const response = await fetch(healthUrl, { signal: controller.signal });
      clearTimeout(timeoutId);
      if (!response.ok) throw new Error(`relay returned HTTP ${response.status}`);
      setLatency(Date.now() - start);
      setTestState('ok');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      setErrorMsg(message.includes('Aborted') ? 'Timed out after 10s' : message);
      setTestState('error');
    }
  }, [url]);

  const handleSave = useCallback(async () => {
    const trimmed = url.trim();
    setSaving(true);
    try {
      const outcome = await (params.peerLinkId ? TwinotifyCoreModule.attachRelayToPeer(trimmed, '', params.peerLinkId) : TwinotifyCoreModule.attachRelay(trimmed, ''));
      if (outcome === 'attached') {
        // Home reads this to choose the relay-capable service over the direct-only one, so the
        // native endpoint alone is not enough to keep the relay after the next mirror toggle.
        if (!params.peerLinkId) await OnboardingState.setRelayUrl(trimmed);
        router.back();
        return;
      }
      const [title, body] = attachFailureCopy(outcome);
      Alert.alert(title, body);
    } catch (err: unknown) {
      Alert.alert('Could not add the relay', err instanceof Error ? err.message : 'Nothing changed.');
    } finally {
      setSaving(false);
    }
  }, [url, params.peerLinkId]);

  const canSave = testState === 'ok' && !saving;

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back to paired device"
          onPress={() => router.back()}
          hitSlop={8}
          style={styles.backBtn}
        >
          <Text style={[styles.backLabel, { color: theme.accent, fontFamily: theme.fonts.ui }]}>‹ Back</Text>
        </Pressable>
        <Text style={[styles.headerTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          {changing ? 'Change relay' : 'Add a relay'}
        </Text>
        <View style={styles.backBtn} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
        <Text style={[styles.lead, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
          A relay carries notifications when this phone and {peerName} are on different networks.
          It stores them encrypted and cannot read them.
        </Text>

        <Text style={[styles.label, { color: theme.ink3, fontFamily: theme.fonts.uiMedium }]}>
          RELAY ADDRESS
        </Text>
        <TextInput
          accessibilityLabel="Relay address"
          style={[
            styles.input,
            {
              borderColor: testState === 'error' ? theme.sem.danger.foreground : theme.border,
              borderRadius: theme.radius.md,
              backgroundColor: theme.fill,
              color: theme.ink,
              fontFamily: theme.fonts.mono,
            },
          ]}
          value={url}
          onChangeText={(next) => {
            setUrl(next);
            setTestState('idle');
            setLatency(null);
            setErrorMsg('');
          }}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          placeholder="https://relay.example.com"
          placeholderTextColor={theme.ink3}
        />

        {testState === 'ok' && latency !== null && (
          <Text style={[styles.feedback, { color: theme.sem.ok.foreground, fontFamily: theme.fonts.uiMedium }]}>
            Reached in {latency}ms
          </Text>
        )}
        {testState === 'error' && (
          <Text style={[styles.feedback, { color: theme.sem.danger.foreground, fontFamily: theme.fonts.ui }]}>
            {errorMsg}
          </Text>
        )}

        <View style={styles.testRow}>
          <TwButton
            variant="secondary"
            size="md"
            loading={testState === 'testing'}
            accessibilityLabel="Test connection"
            onPress={handleTest}
          >
            Test connection
          </TwButton>
        </View>

        <Text style={[styles.note, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          Both phones join the relay over the connection they already have, so keep {peerName}
          {' '}nearby and unlocked. Your paired fingerprint does not change.
        </Text>

        <View style={styles.footer}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            disabled={!canSave}
            loading={saving}
            onPress={handleSave}
          >
            {changing ? 'Change relay' : 'Add relay'}
          </TwButton>
        </View>
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
  scroll: { paddingHorizontal: 20, paddingTop: 24, paddingBottom: 40, flexGrow: 1 },
  lead: { fontSize: 14, lineHeight: 21, marginBottom: 28 },
  label: { fontSize: 11, letterSpacing: 0.6, marginBottom: 8 },
  input: { minHeight: 48, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 12, fontSize: 14 },
  feedback: { fontSize: 13, marginTop: 8 },
  testRow: { marginTop: 16 },
  note: { fontSize: 13, lineHeight: 19, marginTop: 24 },
  footer: { flex: 1, justifyContent: 'flex-end', paddingTop: 32 },
});
