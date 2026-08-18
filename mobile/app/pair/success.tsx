import React, { useEffect, useState } from 'react';
import { ScrollView, View, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Circle, Path } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../../state/onboardingState';

export default function PairSuccessScreen() {
  const theme = useTheme();
  const [peerName, setPeerName] = useState<string>('');
  const [verifiedComplete, setVerifiedComplete] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const offline = await TwinotifyCoreModule.getOfflinePairingStatus();
        const ps = await TwinotifyCoreModule.getPairStatus();
        const complete = offline.completed && offline.phase === 'complete';
        if (!complete && !ps.paired) return;
        await OnboardingState.markComplete();
        setVerifiedComplete(true);
        if (ps.peerDisplayName?.trim()) {
          setPeerName(ps.peerDisplayName.trim());
        }
        const relayUrl = await OnboardingState.getRelayUrl();
        if (relayUrl) {
          try {
            await TwinotifyCoreModule.startSyncService(relayUrl);
          } catch {
            // Pairing is already committed; the home screen can retry relay sync.
          }
        }
      } catch {
        setVerifiedComplete(false);
      }
    })();
  }, []);

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={{ flex: 1, backgroundColor: theme.bg }}
    >
      <ScrollView contentContainerStyle={{ flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: 32 }}>
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
          {verifiedComplete ? 'Twinned.' : 'Finishing pairing…'}
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
          {verifiedComplete && peerName
            ? `Paired with ${peerName}. Notifications will start mirroring immediately.`
            : verifiedComplete
              ? 'Your phones are paired. Notifications will start mirroring immediately.'
              : 'Waiting for the native pairing confirmation.'}
        </Text>
      </ScrollView>

      <View style={{ padding: 20 }}>
        <TwButton
          variant="primary"
          size="lg"
          fullWidth
          disabled={!verifiedComplete}
          onPress={() => router.replace('/home')}
        >
          Done
        </TwButton>
      </View>
    </SafeAreaView>
  );
}
