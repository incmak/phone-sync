import React, { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { useTheme } from '../../components';
import { OnboardingState } from '../../state/onboardingState';

export default function ConnectScreen() {
  const theme = useTheme();

  const chooseNearby = useCallback(async () => {
    await OnboardingState.setPairingMode('nearby');
    router.push('/onboarding/perms');
  }, []);

  const chooseRelay = useCallback(async () => {
    await OnboardingState.setPairingMode('relay');
    router.push('/onboarding/relay');
  }, []);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={styles.content}>
        <View>
          <Text style={[theme.type.display, { color: theme.ink, fontFamily: theme.fonts.uiBold }]}>
            Connect your phones
          </Text>
          <Text style={[theme.type.body, styles.intro, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
            Choose how the phones meet. Both options keep notification contents end-to-end encrypted.
          </Text>
        </View>

        <View style={styles.options} accessibilityRole="radiogroup">
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Pair nearby without internet"
            onPress={() => { void chooseNearby(); }}
            style={({ pressed }) => [
              styles.option,
              { backgroundColor: pressed ? theme.hover : theme.fill },
            ]}
          >
            <Text style={[styles.optionTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
              Pair nearby without internet
            </Text>
            <Text style={[styles.optionBody, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
              Works on the same Wi-Fi, even when that Wi-Fi has no internet connection.
            </Text>
          </Pressable>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Use a relay"
            onPress={() => { void chooseRelay(); }}
            style={({ pressed }) => [
              styles.option,
              { backgroundColor: pressed ? theme.hover : theme.fill },
            ]}
          >
            <Text style={[styles.optionTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
              Use a relay
            </Text>
            <Text style={[styles.optionBody, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
              Connect across different networks through your relay server.
            </Text>
          </Pressable>
        </View>

        <Text style={[styles.note, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          You can add the other connection method later from the paired-device settings.
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 24 },
  intro: { marginTop: 12, maxWidth: 420 },
  options: { gap: 12, marginTop: 36 },
  option: { minHeight: 96, borderRadius: 14, paddingHorizontal: 18, paddingVertical: 16, justifyContent: 'center' },
  optionTitle: { fontSize: 16, lineHeight: 22 },
  optionBody: { fontSize: 14, lineHeight: 20, marginTop: 4 },
  note: { fontSize: 13, lineHeight: 19, marginTop: 'auto', paddingTop: 28 },
});
