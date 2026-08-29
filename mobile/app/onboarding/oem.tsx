import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Rect } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

function BatteryIcon({ color, size = 48 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 48 48">
      <Rect x={6} y={12} width={36} height={24} rx={4} stroke={color} strokeWidth={2} fill="none" />
      <Rect x={42} y={18} width={4} height={12} rx={2} fill={color} opacity={0.5} />
      <Rect x={10} y={16} width={16} height={16} rx={2} fill={color} opacity={0.3} />
    </Svg>
  );
}

interface StepCardProps {
  number: string;
  title: string;
  body: string;
  ink: string;
  ink3: string;
  accent: string;
  fill: string;
  border: string;
  uiSemi: string;
  ui: string;
  radius: number;
}

function StepCard({ number, title, body, ink, ink3, accent, fill, border, uiSemi, ui, radius }: StepCardProps) {
  return (
    <View style={[stepStyles.card, { backgroundColor: fill, borderColor: border, borderRadius: radius }]}>
      <View style={[stepStyles.badge, { backgroundColor: accent + '22' }]}>
        <Text style={{ color: accent, fontFamily: uiSemi, fontSize: 13 }}>{number}</Text>
      </View>
      <View style={stepStyles.text}>
        <Text style={{ fontFamily: uiSemi, color: ink, fontSize: 15, marginBottom: 2 }}>{title}</Text>
        <Text style={{ fontFamily: ui, color: ink3, fontSize: 13, lineHeight: 18 }}>{body}</Text>
      </View>
    </View>
  );
}

const stepStyles = StyleSheet.create({
  card: { flexDirection: 'row', alignItems: 'flex-start', padding: 14, gap: 12, borderWidth: 1 },
  badge: { width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  text: { flex: 1 },
});

async function openBatterySettings() {
  try {
    await TwinotifyCoreModule.openAppSettings();
  } catch {
    // ignore
  }
}

export default function OemScreen() {
  const theme = useTheme();

  const cardProps = {
    ink: theme.ink,
    ink3: theme.ink3,
    accent: theme.accent,
    fill: theme.fill,
    border: theme.border,
    uiSemi: theme.fonts.uiSemi,
    ui: theme.fonts.ui,
    radius: theme.radius.lg,
  };

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.header}>
          <BatteryIcon color={theme.accent} size={48} />
          <Text
            style={[
              theme.type.title1,
              styles.headline,
              { color: theme.ink, fontFamily: theme.fonts.uiBold, fontWeight: '700' },
            ]}
          >
            One last thing
          </Text>
          <Text
            style={[
              theme.type.body,
              styles.subtitle,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            Some Android devices aggressively close background apps to save battery.
            Allow Twinotify to run in the background so notifications aren&#39;t missed.
          </Text>
        </View>

        <View style={styles.steps}>
          <StepCard
            {...cardProps}
            number="1"
            title="Disable battery optimization"
            body={"Go to Settings \u2192 Battery \u2192 Battery Optimization, find Twinotify, and choose \u201cDon\u2019t optimize\u201d."}
          />
          <StepCard
            {...cardProps}
            number="2"
            title="Enable auto-start (if available)"
            body="On some devices, an Auto-start or Startup manager setting also needs to be enabled."
          />
        </View>

        <View style={styles.actions}>
          <TwButton
            variant="secondary"
            size="lg"
            fullWidth
            onPress={openBatterySettings}
          >
            Open battery settings
          </TwButton>
          <TwButton
            variant="ghost"
            size="lg"
            fullWidth
            onPress={() => router.push('/onboarding/ready')}
          >
            Skip for now
          </TwButton>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 8 },
  header: { marginBottom: 28 },
  headline: { marginTop: 16, marginBottom: 8 },
  subtitle: { opacity: 0.9 },
  steps: { gap: 10, flex: 1 },
  actions: { gap: 8, paddingBottom: 8 },
});
