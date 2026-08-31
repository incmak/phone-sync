import { useEffect, useState } from 'react';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import type { RouteStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

const NOT_CONNECTED: RouteStatus = {
  route: 'none',
  phase: 'idle',
  queued_count: 0,
  pending_local_count: 0,
  awaiting_peer_count: 0,
  held_by_relay_count: 0,
  peer_evidence: 'unknown',
  delivery_reason: 'none',
  user_content_kind: 'notifications',
  route_generation: 0,
};

function normalized(status: Partial<RouteStatus> & Pick<RouteStatus, 'route' | 'phase'>): RouteStatus {
  const queued = status.queued_count ?? 0;
  return {
    ...NOT_CONNECTED,
    ...status,
    queued_count: queued,
    pending_local_count: status.pending_local_count ?? queued,
  };
}

/**
 * The live delivery route, straight from the native status. Nothing here infers a
 * route from relay state: an unknown route reports `none`, never a guess.
 */
export function useRouteStatus(): RouteStatus {
  const [status, setStatus] = useState<RouteStatus>(NOT_CONNECTED);

  useEffect(() => {
    let active = true;
    TwinotifyCoreModule.getRouteStatus()
      .then((s: RouteStatus) => {
        if (active) setStatus(normalized(s));
      })
      .catch(() => {
        /* keep the not-connected default rather than claiming a route */
      });

    const sub = TwinotifyCoreModule.addListener('onRouteStatus', (evt: RouteStatus) => {
      setStatus(normalized(evt));
    });
    return () => {
      active = false;
      sub.remove();
    };
  }, []);

  return status;
}
