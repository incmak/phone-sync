import type { RouteStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

export type DeliveryState = 'direct' | 'relay' | 'reconnecting' | 'queued' | 'paused' | 'unpaired';

export type DeliveryAction = 'retry' | 'pair';

export interface DeliveryPresentation {
  state: DeliveryState;
  /** The single route label. Approved copy; do not vary per screen. */
  label: string;
  /** One sentence. It explains the state and promises nothing the app cannot do. */
  explanation: string;
  /** Present only when recovery genuinely needs the user. */
  action?: DeliveryAction;
  queuedCount: number;
}

/**
 * The one place a route becomes user-facing words.
 *
 * It reads the native route status and nothing else. In particular it never
 * infers "offline" from relay state: a healthy direct route reports Direct on
 * Wi-Fi even with no relay connection at all.
 */
export function presentRoute(status: RouteStatus, paired: boolean, enabled: boolean = true): DeliveryPresentation {
  const queuedCount = Math.max(0, status.queued_count ?? 0);

  if (!paired) {
    return {
      state: 'unpaired',
      label: 'Not paired',
      explanation: 'Link your other phone to start mirroring notifications.',
      action: 'pair',
      queuedCount: 0,
    };
  }

  if (!enabled) {
    return {
      state: 'paused',
      label: 'Paused',
      explanation: 'Turn on mirroring when you want delivery to resume.',
      queuedCount,
    };
  }

  if (status.phase === 'authenticated' && status.route === 'lan') {
    return {
      state: 'direct',
      label: 'Direct on Wi-Fi',
      explanation: 'Your phones are talking to each other directly over Wi-Fi.',
      queuedCount,
    };
  }

  if (status.phase === 'authenticated' && status.route === 'relay') {
    return {
      state: 'relay',
      label: 'Via relay',
      explanation: 'Going through the relay, still encrypted end to end.',
      queuedCount,
    };
  }

  // Durable work with no usable route is the state worth acting on, so it
  // outranks a bare reconnecting message and carries the one retry control.
  if (queuedCount > 0) {
    return {
      state: 'queued',
      label: 'Queued for delivery',
      explanation:
        queuedCount === 1
          ? '1 notification is waiting for your other phone.'
          : `${queuedCount} notifications are waiting for your other phone.`,
      action: 'retry',
      queuedCount,
    };
  }

  return {
    state: 'reconnecting',
    label: 'Reconnecting',
    explanation: 'Looking for your other phone. This retries on its own.',
    queuedCount,
  };
}
