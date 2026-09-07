import { useEffect } from 'react';
import { router } from 'expo-router';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';

/**
 * Global listener that handles peer-initiated unpair events.
 * Return to the peer list so the remaining links and any pending cleanup stay visible.
 */
export function usePeerUnpairListener(): void {
  useEffect(() => {
    const sub = TwinotifyCoreModule.addListener('onPeerUnpair', async () => {
      router.replace('/settings/peers');
    });
    return () => sub.remove();
  }, []);
}
