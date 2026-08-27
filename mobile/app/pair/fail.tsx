import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useLocalSearchParams } from 'expo-router';
import Svg, { Path } from 'react-native-svg';

import { TwButton, useTheme } from '../../components';

export default function PairFailScreen() {
  const theme = useTheme();
  const params = useLocalSearchParams<{ reason?: string }>();
  const identityFailure = params.reason === 'identity_mismatch';

  return (
    <SafeAreaView edges={['top', 'bottom']} style={{ flex: 1, backgroundColor: theme.bg }}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: 32, gap: 20 }}>
        <Svg width={48} height={48} viewBox="0 0 36 36" accessibilityRole="image" accessibilityLabel="Pairing stopped">
          <Path d="M18 4 L32 28 H4 Z" stroke={theme.sem.danger.foreground} strokeWidth={2} fill="none" strokeLinejoin="round" />
          <Path d="M18 14 v8" stroke={theme.sem.danger.foreground} strokeWidth={2.4} strokeLinecap="round" />
          <Path d="M18 25.5 v0.01" stroke={theme.sem.danger.foreground} strokeWidth={2.8} strokeLinecap="round" />
        </Svg>

        <Text style={{ fontSize: 22, lineHeight: 28, fontFamily: theme.fonts.uiSemi, color: theme.ink, textAlign: 'center' }}>
          {identityFailure ? 'Codes did not match' : 'Fingerprints did not match'}
        </Text>
        <Text style={{ fontSize: 14, color: theme.ink3, fontFamily: theme.fonts.ui, textAlign: 'center', maxWidth: 320, lineHeight: 21 }}>
          Pairing stopped before either phone changed its trusted identity. Start again and compare every digit or fingerprint block.
        </Text>
      </ScrollView>

      <View style={{ padding: 20 }}>
        <TwButton variant="primary" fullWidth onPress={() => router.replace('/onboarding/connect')}>
          Choose how to pair
        </TwButton>
      </View>
    </SafeAreaView>
  );
}
