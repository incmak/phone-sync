import React, { useEffect } from 'react';
import { View, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Circle, Path } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

export default function PairSuccessScreen() {
  const theme = useTheme();

  useEffect(() => {
    (async () => {
      try {
        const relayUrl = await OnboardingState.getRelayUrl();
        if (relayUrl) {
          await TwinotifyCoreModule.startSyncService(relayUrl);
        }
        await OnboardingState.markComplete();
      } catch {
        // Non-fatal — sync service can be restarted from the home screen.
      }
    })();
  }, []);

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: theme.bg }}
    >
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 }}>
        {/* Two overlapping rings + checkmark hero */}
        <Svg width={160} height={100} viewBox="0 0 160 100">
          <Circle
            cx={60} cy={50} r={38}
            stroke={theme.ink} strokeWidth={2.4} fill="none"
          />
          <Circle
            cx={100} cy={50} r={38}
            stroke={theme.accent} strokeWidth={2.4} fill="none"
          />
          <Path
            d="M70 50 l8 8 l16 -16"
            stroke={theme.accent} strokeWidth={3}
            fill="none" strokeLinecap="round" strokeLinejoin="round"
          />
        </Svg>

        <Text style={{
          fontSize: 28,
          fontFamily: theme.fonts.uiSemi,
          color: theme.ink,
          marginTop: 24,
          letterSpacing: -0.4,
        }}>
          Twinned.
        </Text>
        <Text style={{
          fontSize: 15,
          color: theme.ink3,
          fontFamily: theme.fonts.ui,
          marginTop: 12,
          textAlign: 'center',
          lineHeight: 22,
          maxWidth: 280,
        }}>
          Your phones are paired. Notifications will start mirroring immediately.
        </Text>
      </View>

      <View style={{ padding: 20 }}>
        <TwButton
          variant="primary"
          size="lg"
          fullWidth
          onPress={() => router.replace('/home')}
        >
          Done
        </TwButton>
      </View>
    </SafeAreaView>
  );
}
