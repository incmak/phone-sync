import { useEffect } from 'react';
import { View } from 'react-native';
import { router } from 'expo-router';
import AsyncStorage from '@react-native-async-storage/async-storage';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';

export default function Index() {
  useEffect(() => {
    (async () => {
      // Route to /home only if BOTH flags are true:
      //   1. Onboarding complete (set by pair/success.tsx).
      //   2. PeerStore has a paired peer.
      // A missing peer means either unpair wasn't propagated or app data was cleared
      // mid-onboarding; in either case the right destination is /onboarding/welcome.
      const done = await AsyncStorage.getItem('twinotify_onboarding_complete');
      if (done !== 'true') {
        router.replace('/onboarding/welcome');
        return;
      }
      try {
        const pair = await TwinotifyCoreModule.getPairStatus();
        router.replace(pair?.paired ? '/home' : '/onboarding/welcome');
      } catch {
        router.replace('/onboarding/welcome');
      }
    })();
  }, []);

  // Invisible splash while AsyncStorage resolves
  return <View />;
}
