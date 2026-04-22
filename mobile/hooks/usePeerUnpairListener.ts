import { useEffect } from 'react';
import { router } from 'expo-router';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState } from '../state/onboardingState';

/**
 * Global listener that handles peer-initiated unpair events.
 * Mount once at the app root so any screen can be interrupted and routed back to onboarding.
 */
export function usePeerUnpairListener(): void {
  useEffect(() => {
    const sub = TwinotifyCoreModule.addListener('onPeerUnpair', async () => {
      await OnboardingState.reset().catch(() => { /* ignore — wipe is best-effort */ });
      router.replace('/onboarding/role');
    });
    return () => sub.remove();
  }, []);
}
