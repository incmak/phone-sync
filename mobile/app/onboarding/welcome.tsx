import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Circle } from 'react-native-svg';
import { useTheme, TwWordmark, TwButton } from '../../components';

function HeroRings({ accent, border }: { accent: string; border: string }) {
  return (
    <Svg width={200} height={200} viewBox="0 0 200 200">
      <Circle cx={100} cy={100} r={90} stroke={border} strokeWidth={1.5} fill="none" />
      <Circle cx={100} cy={100} r={60} stroke={accent} strokeWidth={2.5} fill="none" opacity={0.7} />
      <Circle cx={100} cy={100} r={30} fill={accent} opacity={0.15} />
      <Circle cx={100} cy={100} r={12} fill={accent} opacity={0.8} />
    </Svg>
  );
}

export default function WelcomeScreen() {
  const theme = useTheme();

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.header}>
          <TwWordmark size={22} />
        </View>

        <View style={styles.hero}>
          <HeroRings accent={theme.accent} border={theme.border} />
        </View>

        <View style={styles.copy}>
          <Text
            style={[
              theme.type.display,
              styles.headline,
              { color: theme.ink, fontFamily: theme.fonts.uiBold },
            ]}
          >
            Mirror every{'\n'}notification.
          </Text>
          <Text
            style={[
              theme.type.body,
              styles.body,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            Your phone&#39;s alerts, delivered silently to your second device — end-to-end encrypted,
            no accounts, no cloud.
          </Text>
        </View>

        <View style={styles.actions}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            onPress={() => router.push('/onboarding/how')}
          >
            Get started
          </TwButton>

          <Pressable
            style={styles.secondaryLink}
            onPress={() => router.replace('/onboarding/role')}
          >
            <Text
              style={[
                theme.type.bodyMed,
                { color: theme.accent, fontFamily: theme.fonts.uiMedium },
              ]}
            >
              I already have a code
            </Text>
          </Pressable>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 12, paddingBottom: 8 },
  header: { alignItems: 'center', marginBottom: 8 },
  hero: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  copy: { marginBottom: 32 },
  headline: { marginBottom: 12 },
  body: { opacity: 0.9 },
  actions: { gap: 16, paddingBottom: 8 },
  secondaryLink: { alignItems: 'center', paddingVertical: 8 },
});
