import { useEffect, useState } from 'react';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import type { RouteStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

const NOT_CONNECTED: RouteStatus = { route: 'none', phase: 'idle', queued_count: 0 };

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
        if (active) setStatus(s);
      })
      .catch(() => {
        /* keep the not-connected default rather than claiming a route */
      });

    const sub = TwinotifyCoreModule.addListener('onRouteStatus', (evt: RouteStatus) => {
      setStatus(evt);
    });
    return () => {
      active = false;
      sub.remove();
    };
  }, []);

  return status;
}
