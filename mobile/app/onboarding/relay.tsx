import React, { useState } from 'react';
import {
  View, Text, TextInput, StyleSheet, ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { useTheme, TwButton } from '../../components';
import { OnboardingState } from '../../state/onboardingState';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const DEFAULT_RELAY = 'wss://relay.twinotify.app';
const TIMEOUT_MS = 10_000;

type TestState = 'idle' | 'testing' | 'ok' | 'error';

export default function RelayScreen() {
  const theme = useTheme();
  const [url, setUrl] = useState(DEFAULT_RELAY);
  const [testState, setTestState] = useState<TestState>('idle');
  const [latency, setLatency] = useState<number | null>(null);
  const [errorMsg, setErrorMsg] = useState<string>('');

  async function handleTest() {
    const trimmed = url.trim();
    if (!trimmed) return;
    setTestState('testing');
    setErrorMsg('');
    setLatency(null);

    const start = Date.now();
    try {
      const timeoutPromise = new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('Timed out after 10s')), TIMEOUT_MS),
      );
      await Promise.race([
        TwinotifyCoreModule.ping(trimmed, false),
        timeoutPromise,
      ]);
      setLatency(Date.now() - start);
      setTestState('ok');
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
      setTestState('error');
    }
  }

  async function handleContinue() {
    await OnboardingState.setRelayUrl(url.trim());
    router.push('/onboarding/perms');
  }

  const canContinue = testState === 'ok';

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.header}>
          <Text
            style={[
              theme.type.display,
              { color: theme.ink, fontFamily: theme.fonts.uiBold },
            ]}
          >
            Relay server
          </Text>
          <Text
            style={[
              theme.type.body,
              styles.subtitle,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            Notifications are routed through a relay. Use the default or enter your own.
          </Text>
        </View>

        <View style={styles.inputSection}>
          <Text
            style={[
              theme.type.caption,
              styles.label,
              { color: theme.ink4, fontFamily: theme.fonts.uiMedium },
            ]}
          >
            RELAY URL
          </Text>
          <TextInput
            style={[
              styles.input,
              {
                borderColor: testState === 'error' ? theme.sem.danger : theme.border,
                borderRadius: theme.radius.md,
                backgroundColor: theme.fill,
                color: theme.ink,
                fontFamily: theme.fonts.mono,
                fontSize: 14,
              },
            ]}
            value={url}
            onChangeText={(t) => {
              setUrl(t);
              setTestState('idle');
              setLatency(null);
              setErrorMsg('');
            }}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            placeholder={DEFAULT_RELAY}
            placeholderTextColor={theme.ink4}
          />

          {testState === 'ok' && latency !== null && (
            <Text
              style={[
                styles.feedbackOk,
                { color: theme.sem.ok, fontFamily: theme.fonts.uiMedium },
              ]}
            >
              Reached in {latency}ms
            </Text>
          )}
          {testState === 'error' && (
            <Text
              style={[
                styles.feedbackErr,
                { color: theme.sem.danger, fontFamily: theme.fonts.ui },
              ]}
            >
              {errorMsg}
            </Text>
          )}
        </View>

        <View style={styles.testRow}>
          <TwButton
            variant="secondary"
            size="md"
            disabled={testState === 'testing'}
            onPress={handleTest}
          >
            {testState === 'testing' ? (
              <ActivityIndicator size="small" color={theme.ink} />
            ) : (
              'Test connection'
            )}
          </TwButton>
        </View>

        <View style={styles.footer}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            disabled={!canContinue}
            onPress={handleContinue}
          >
            Continue
          </TwButton>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 8 },
  header: { marginBottom: 32 },
  subtitle: { marginTop: 10 },
  inputSection: { marginBottom: 16 },
  label: { marginBottom: 6, letterSpacing: 0.5 },
  input: { borderWidth: 1, paddingHorizontal: 14, paddingVertical: 12 },
  feedbackOk: { marginTop: 8, fontSize: 13 },
  feedbackErr: { marginTop: 8, fontSize: 13 },
  testRow: { marginBottom: 24 },
  footer: { flex: 1, justifyContent: 'flex-end', paddingBottom: 8 },
});
