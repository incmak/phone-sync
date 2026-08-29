import { useCallback, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';

import TwinotifyCoreModule, {
  type RecentActivityItem,
} from '../modules/twinotify-core/src/TwinotifyCoreModule';

export type RecentActivityState =
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'error'; retry: () => void }
  | { kind: 'populated'; items: RecentActivityItem[]; refreshedAt: number };

export function useRecentActivity(limit = 5): RecentActivityState {
  const boundedLimit = Math.max(1, Math.min(limit, 5));
  const [retryGeneration, setRetryGeneration] = useState(0);
  const [state, setState] = useState<RecentActivityState>({ kind: 'loading' });
  const lastSuccess = useRef<RecentActivityItem[] | null>(null);
  const retry = useCallback(() => setRetryGeneration((value) => value + 1), []);

  useFocusEffect(
    useCallback(() => {
      let active = true;

      async function refresh() {
        try {
          const items = await TwinotifyCoreModule.getRecentActivity(boundedLimit);
          if (!active) return;
          lastSuccess.current = items;
          setState(items.length === 0 ? { kind: 'empty' } : { kind: 'populated', items, refreshedAt: Date.now() });
        } catch {
          if (active && lastSuccess.current === null) setState({ kind: 'error', retry });
        }
      }

      void refresh();
      const interval = setInterval(refresh, 5_000);
      return () => {
        active = false;
        clearInterval(interval);
      };
    }, [boundedLimit, retry, retryGeneration]),
  );

  return state;
}
