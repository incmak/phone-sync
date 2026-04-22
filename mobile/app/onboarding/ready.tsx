import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Circle, Path } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import { OnboardingState, Role } from '../../state/onboardingState';

function CheckCircle({ color, size = 96 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 96 96">
      <Circle cx={48} cy={48} r={46} stroke={color} strokeWidth={2} fill={color + '1A'} />
      <Path
        d="M28,50 L41,63 L68,36"
        stroke={color}
        strokeWidth={4}
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

export default function ReadyScreen() {
  const theme = useTheme();
  const [role, setRole] = useState<Role | null>(null);

  useEffect(() => {
    OnboardingState.getRole().then((r) => setRole(r));
    // Note: onboarding is marked complete only after a successful pair (see pair/success.tsx).
    // Marking it here would route the user to /home on next launch even if pair fails,
    // leaving no way back to the pair flow without clearing app data.
  }, []);

  function handleAction() {
    if (role === 'B') {
      router.replace('/pair/scan');
    } else {
      router.replace('/pair/qr');
    }
  }

  const buttonLabel = role === 'B' ? 'Scan code' : 'Show my code';

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.hero}>
          <CheckCircle color={theme.accent} size={96} />
        </View>

        <View style={styles.copy}>
          <Text
            style={[
              theme.type.display,
              styles.headline,
              { color: theme.ink, fontFamily: theme.fonts.uiBold },
            ]}
          >
            You&#39;re ready.
          </Text>
          <Text
            style={[
              theme.type.body,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            {role === 'B'
              ? 'Scan the QR code on your first phone to complete pairing.'
              : 'Show this phone\'s QR code to your second device to start pairing.'}
          </Text>
        </View>

        <View style={styles.footer}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            onPress={handleAction}
          >
            {buttonLabel}
          </TwButton>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 8 },
  hero: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  copy: { marginBottom: 32 },
  headline: { marginBottom: 10 },
  footer: { paddingBottom: 8 },
});
