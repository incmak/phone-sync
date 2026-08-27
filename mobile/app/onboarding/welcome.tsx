import React from 'react';
import { View, Text, Pressable, ScrollView, StyleSheet, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { useTheme, TwWordmark, TwButton } from '../../components';
import { HandoffTrace } from '../../components/HandoffTrace';

export default function WelcomeScreen() {
  const theme = useTheme();
  const { width, fontScale } = useWindowDimensions();
  const gutter = width <= 360 ? 16 : 24;
  const traceWidth = Math.min(Math.max(64, width - gutter * 2), 560);
  const traceStageHeight = Math.min(272, Math.max(184, width * 0.58));

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <ScrollView
        contentContainerStyle={[styles.container, { paddingHorizontal: gutter }]}
        showsVerticalScrollIndicator={false}
      >
        <View testID="welcome-header" style={styles.header}>
          <TwWordmark size={22} />
        </View>

        <View style={[styles.traceStage, { width: traceWidth, minHeight: traceStageHeight }]}>
          <HandoffTrace state="unpaired" width={traceWidth} height={132} testID="welcome-handoff-trace" />
        </View>

        <View testID="welcome-copy" style={styles.copy}>
          <Text
            style={[
              theme.type.display,
              styles.headline,
              {
                color: theme.ink,
                fontFamily: theme.fonts.display,
                lineHeight: theme.type.display.lineHeight * fontScale,
              },
            ]}
          >
            Mirror selected notifications.
          </Text>
          <Text
            style={[
              theme.type.body,
              styles.body,
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

        <View testID="welcome-actions" style={styles.actions}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            onPress={() => router.push('/onboarding/how')}
          >
            Get started
          </TwButton>

          <Pressable
            testID="welcome-secondary-action"
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
  container: { flexGrow: 1, paddingTop: 16, paddingBottom: 16 },
  header: { alignItems: 'flex-start', marginBottom: 4 },
  traceStage: { alignItems: 'flex-start', justifyContent: 'center', alignSelf: 'flex-start' },
  copy: { alignItems: 'flex-start', marginBottom: 32, flexShrink: 0 },
  headline: { marginBottom: 12, fontWeight: '600' },
  body: { maxWidth: 520 },
  actions: { gap: 12, paddingBottom: 8, flexShrink: 0 },
  secondaryLink: { alignItems: 'flex-start', justifyContent: 'center', minHeight: 44, paddingVertical: 8 },
});
