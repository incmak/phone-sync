import React from 'react';
import { View, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Path } from 'react-native-svg';
import { useTheme, TwButton, hexWithAlpha } from '../../components';

export default function PairFailScreen() {
  const theme = useTheme();

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: theme.bg }}
    >
      <View style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        padding: 32,
        gap: 20,
      }}>
        {/* Alert icon */}
        <View style={{
          width: 88, height: 88,
          borderRadius: 24,
          backgroundColor: hexWithAlpha(theme.sem.danger, 0.12),
          alignItems: 'center',
          justifyContent: 'center',
        }}>
          <Svg width={36} height={36} viewBox="0 0 36 36">
            {/* Triangle */}
            <Path
              d="M18 4 L32 28 H4 Z"
              stroke={theme.sem.danger} strokeWidth={2}
              fill="none" strokeLinejoin="round"
            />
            {/* Exclamation stem */}
            <Path
              d="M18 14 v8"
              stroke={theme.sem.danger} strokeWidth={2.4}
              strokeLinecap="round"
            />
            {/* Exclamation dot */}
            <Path
              d="M18 25.5 v0.01"
              stroke={theme.sem.danger} strokeWidth={2.8}
              strokeLinecap="round"
            />
          </Svg>
        </View>

        <Text style={{
          fontSize: 22,
          fontFamily: theme.fonts.uiSemi,
          color: theme.ink,
          textAlign: 'center',
        }}>
          Fingerprints didn't match
        </Text>

        <Text style={{
          fontSize: 14,
          color: theme.ink3,
          fontFamily: theme.fonts.ui,
          textAlign: 'center',
          maxWidth: 300,
          lineHeight: 21,
        }}>
          Pairing was aborted for security. Try again — if this keeps happening, your network may be interfering.
        </Text>
      </View>

      <View style={{ padding: 20 }}>
        <TwButton
          variant="primary"
          fullWidth
          onPress={() => router.replace('/onboarding/role')}
        >
          Start over
        </TwButton>
      </View>
    </SafeAreaView>
  );
}
