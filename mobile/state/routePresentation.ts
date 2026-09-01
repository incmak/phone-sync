import type { RouteStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

export type DeliveryState = 'direct' | 'relay' | 'reconnecting' | 'queued' | 'paused' | 'stopped' | 'unpaired';

export type DeliveryAction = 'retry' | 'pair' | 'permissions';

export interface DeliveryPresentation {
  state: DeliveryState;
  /** The single route label. Approved copy; do not vary per screen. */
  label: string;
  /** One sentence. It explains the state and promises nothing the app cannot do. */
  explanation: string;
  /** Present only when recovery genuinely needs the user. */
  action?: DeliveryAction;
  queuedCount: number;
  peerLine: 'Reachable now' | 'Checked in recently' | 'Not confirmed online' | null;
}

function count(value: number | undefined): number {
  return Math.max(0, value ?? 0);
}

function itemName(total: number, kind: RouteStatus['user_content_kind']): string {
  const noun = kind === 'notifications' ? 'notification' : 'sync update';
  return `${total} ${noun}${total === 1 ? '' : 's'}`;
}

function evidenceLine(status: RouteStatus): DeliveryPresentation['peerLine'] {
  if (status.route === 'lan' && status.phase === 'authenticated') return 'Reachable now';
  if (status.peer_evidence === 'recent') return 'Checked in recently';
  return 'Not confirmed online';
}

/**
 * The one place a route becomes user-facing words.
 *
 * It reads the native route status and nothing else. In particular it never
 * infers "offline" from relay state: a healthy direct route reports Direct on
 * Wi-Fi even with no relay connection at all.
 */
export function presentRoute(status: RouteStatus, paired: boolean, enabled: boolean = true): DeliveryPresentation {
  const queuedCount = count(status.pending_local_count ?? status.queued_count);
  const awaitingPeer = count(status.awaiting_peer_count);
  const heldByRelay = count(status.held_by_relay_count);
  const contentKind = status.user_content_kind ?? 'notifications';

  if (!paired) {
    return {
      state: 'unpaired',
      label: 'Not paired',
      explanation: 'Link your other phone to start mirroring notifications.',
      action: 'pair',
      queuedCount: 0,
      peerLine: null,
    };
  }

  if (!enabled) {
    return {
      state: 'paused',
      label: 'Paused',
      explanation: 'Turn on mirroring when you want delivery to resume.',
      queuedCount,
      peerLine: null,
    };
  }

  if (status.presentation) {
    return {
      state: status.presentation.state,
      label: status.presentation.label,
      explanation: status.presentation.explanation,
      action: status.presentation.action ?? undefined,
      queuedCount: count(status.presentation.queued_count),
      peerLine: status.presentation.peer_line,
    };
  }

  if (status.phase === 'authenticated' && status.route === 'lan') {
    return {
      state: 'direct',
      label: 'Direct on Wi-Fi',
      explanation: 'Your phones are talking directly over Wi-Fi.',
      queuedCount,
      peerLine: 'Reachable now',
    };
  }

  if (status.phase === 'authenticated' && status.route === 'relay') {
    const peerLine = evidenceLine(status);
    let explanation: string;
    if (status.delivery_reason === 'lan_binding_conflict') {
      explanation = 'Direct Wi-Fi needs attention. Relay delivery remains encrypted end to end.';
    } else if (status.delivery_reason === 'peer_version_incompatible') {
      explanation = 'Update Twinotify on your other phone to enable direct Wi-Fi.';
    } else if (heldByRelay > 0) {
      explanation = `${itemName(heldByRelay, contentKind)} ${heldByRelay === 1 ? 'is' : 'are'} stored securely and waiting for your other phone.`;
    } else if (awaitingPeer > 0) {
      explanation = `${itemName(awaitingPeer, contentKind)} ${awaitingPeer === 1 ? 'is' : 'are'} waiting for confirmation from your other phone.`;
    } else if (status.delivery_reason === 'lan_bootstrap_waiting') {
      explanation = 'Setting up direct Wi-Fi in the background. Delivery is encrypted end to end.';
    } else if (status.peer_evidence === 'recent') {
      explanation = 'Your other phone checked in recently. Delivery is encrypted end to end.';
    } else {
      explanation = 'Connected to the relay. Waiting for your other phone to check in.';
    }
    return {
      state: 'relay',
      label: 'Via relay',
      explanation,
      queuedCount,
      peerLine,
    };
  }

  // Durable work with no usable route is the state worth acting on, so it
  // outranks a bare reconnecting message and carries the one retry control.
  if (queuedCount > 0) {
    return {
      state: 'queued',
      label: 'Queued on this phone',
      explanation: `${itemName(queuedCount, contentKind)} will send when a connection is available.`,
      action: 'retry',
      queuedCount,
      peerLine: 'Not confirmed online',
    };
  }

  if (heldByRelay > 0) {
    return {
      state: 'reconnecting',
      label: 'Reconnecting',
      explanation: `${itemName(heldByRelay, contentKind)} ${heldByRelay === 1 ? 'is' : 'are'} stored securely while this phone reconnects.`,
      action: 'retry',
      queuedCount,
      peerLine: 'Not confirmed online',
    };
  }

  return {
    state: 'reconnecting',
    label: 'Reconnecting',
    explanation: 'Looking for your other phone. This retries on its own.',
    queuedCount,
    peerLine: 'Not confirmed online',
  };
}
