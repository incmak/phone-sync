import { useEffect, useState } from 'react';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import type { SyncStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

export function useSyncStatus(): SyncStatus {
  const [status, setStatus] = useState<SyncStatus>({ state: 'DISCONNECTED', queuedCount: 0 });

  useEffect(() => {
    // Seed with current snapshot
    TwinotifyCoreModule.getSyncStatus()
      .then((s: SyncStatus) => setStatus(s))
      .catch(() => { /* keep default on error */ });

    const sub = TwinotifyCoreModule.addListener('onSyncStatus', (evt: SyncStatus) => {
      setStatus(evt);
    });
    return () => sub.remove();
  }, []);

  return status;
}
