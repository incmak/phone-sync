import { useEffect, useState } from 'react';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import type { MetricsSnapshot } from '../modules/twinotify-core/src/TwinotifyCoreModule';

const INITIAL: MetricsSnapshot = { mirroredToday: 0, blockedToday: 0, latencyMs: null };

/**
 * Polls MetricsStore every [intervalMs] (default 5 s).
 * Counters don't need instant updates, so polling is simpler than events here.
 */
export function useMetrics(intervalMs = 5000): MetricsSnapshot {
  const [metrics, setMetrics] = useState<MetricsSnapshot>(INITIAL);

  useEffect(() => {
    let cancelled = false;

    async function refresh() {
      try {
        const m = await TwinotifyCoreModule.getMetrics();
        if (!cancelled) setMetrics(m);
      } catch {
        // swallow — keep stale values on transient failure
      }
    }

    refresh();
    const id = setInterval(refresh, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [intervalMs]);

  return metrics;
}
