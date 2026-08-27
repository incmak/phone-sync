import React from 'react';
import { View, Text, Pressable, ScrollView, StyleSheet, useWindowDimensions } from 'react-native';
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
  const { fontScale } = useWindowDimensions();

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <ScrollView contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
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
              {
                color: theme.ink,
                fontFamily: theme.fonts.uiBold,
                fontWeight: '700',
                lineHeight: theme.type.display.lineHeight * fontScale,
              },
            ]}
          >
            Mirror selected notifications.
          </Text>
          <Text
            style={[
              theme.type.body,
              {
                color: theme.ink3,
                fontFamily: theme.fonts.ui,
                lineHeight: theme.type.body.lineHeight * fontScale,
              },
            ]}
          >
            Send selected alerts to your second phone. End-to-end encrypted,
            with no account required.
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
            accessibilityRole="button"
            accessibilityLabel="I already have a code"
          >
            <Text
              style={[
                theme.type.bodyMed,
                {
                  color: theme.accentText,
                  fontFamily: theme.fonts.uiMedium,
                  lineHeight: theme.type.bodyMed.lineHeight * fontScale,
                },
              ]}
            >
              I already have a code
            </Text>
          </Pressable>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 12, paddingBottom: 8 },
  header: { alignItems: 'center', marginBottom: 8 },
  hero: { minHeight: 360, alignItems: 'center', justifyContent: 'center' },
  copy: { marginBottom: 32, flexShrink: 0 },
  headline: { marginBottom: 12 },
  actions: { gap: 16, paddingBottom: 8, flexShrink: 0 },
  secondaryLink: { alignItems: 'center', justifyContent: 'center', minHeight: 44, paddingVertical: 8 },
});
