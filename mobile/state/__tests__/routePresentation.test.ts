import { presentRoute } from '../routePresentation';
import type { RouteStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const status = (over: Partial<RouteStatus> = {}): RouteStatus => ({
  route: 'none', phase: 'idle', queued_count: 0, pending_local_count: 0,
  awaiting_peer_count: 0, held_by_relay_count: 0, peer_evidence: 'unknown',
  delivery_reason: 'none', user_content_kind: 'notifications', route_generation: 0,
  ...over,
});

describe('presentRoute delivery truth table', () => {
  it('uses the native presentation for an enabled paired session', () => {
    const presentation = presentRoute(status({
      route: 'relay',
      phase: 'authenticated',
      presentation: {
        state: 'relay',
        label: 'Native relay truth',
        explanation: 'Native custody explanation.',
        action: null,
        queued_count: 4,
        peer_line: 'Checked in recently',
      },
    }), true, true);

    expect(presentation).toEqual({
      state: 'relay',
      label: 'Native relay truth',
      explanation: 'Native custody explanation.',
      action: undefined,
      queuedCount: 4,
      peerLine: 'Checked in recently',
    });
  });

  it.each([
    {
      name: 'direct',
      value: status({ route: 'lan', phase: 'authenticated', peer_evidence: 'direct' }),
      expected: ['Direct on Wi-Fi', 'Your phones are talking directly over Wi-Fi.', 'Reachable now', undefined],
    },
    {
      name: 'fresh relay',
      value: status({ route: 'relay', phase: 'authenticated', peer_evidence: 'recent' }),
      expected: ['Via relay', 'Your other phone checked in recently. Delivery is encrypted end to end.', 'Checked in recently', undefined],
    },
    {
      name: 'stale relay empty',
      value: status({ route: 'relay', phase: 'authenticated', peer_evidence: 'stale' }),
      expected: ['Via relay', 'Connected to the relay. Waiting for your other phone to check in.', 'Not confirmed online', undefined],
    },
    {
      name: 'relay-held',
      value: status({
        route: 'relay', phase: 'authenticated', awaiting_peer_count: 2, held_by_relay_count: 2,
        peer_evidence: 'stale', delivery_reason: 'relay_holding', user_content_kind: 'sync_updates',
      }),
      expected: ['Via relay', '2 sync updates are stored securely and waiting for your other phone.', 'Not confirmed online', undefined],
    },
    {
      name: 'bootstrap waiting',
      value: status({ route: 'relay', phase: 'authenticated', peer_evidence: 'recent', delivery_reason: 'lan_bootstrap_waiting' }),
      expected: ['Via relay', 'Setting up direct Wi-Fi in the background. Delivery is encrypted end to end.', 'Checked in recently', undefined],
    },
    {
      name: 'incompatible peer',
      value: status({ route: 'relay', phase: 'authenticated', delivery_reason: 'peer_version_incompatible' }),
      expected: ['Via relay', 'Update Twinotify on your other phone to enable direct Wi-Fi.', 'Not confirmed online', undefined],
    },
    {
      name: 'binding conflict',
      value: status({ route: 'relay', phase: 'authenticated', peer_evidence: 'recent', delivery_reason: 'lan_binding_conflict' }),
      expected: ['Via relay', 'Direct Wi-Fi needs attention. Relay delivery remains encrypted end to end.', 'Checked in recently', undefined],
    },
    {
      name: 'no route pending local',
      value: status({ phase: 'reconnecting', queued_count: 2, pending_local_count: 2, delivery_reason: 'no_route' }),
      expected: ['Queued on this phone', '2 notifications will send when a connection is available.', 'Not confirmed online', 'retry'],
    },
    {
      name: 'no route relay-held',
      value: status({
        phase: 'reconnecting', awaiting_peer_count: 3, held_by_relay_count: 3,
        delivery_reason: 'relay_holding', user_content_kind: 'sync_updates',
      }),
      expected: ['Reconnecting', '3 sync updates are stored securely while this phone reconnects.', 'Not confirmed online', 'retry'],
    },
    {
      name: 'reconnecting empty',
      value: status({ phase: 'reconnecting' }),
      expected: ['Reconnecting', 'Looking for your other phone. This retries on its own.', 'Not confirmed online', undefined],
    },
  ])('$name', ({ value, expected }) => {
    const presentation = presentRoute(value, true);
    expect([presentation.label, presentation.explanation, presentation.peerLine, presentation.action])
      .toEqual(expected);
  });

  it('reports paused without making a reachability claim', () => {
    expect(presentRoute(status({ route: 'lan', phase: 'authenticated' }), true, false)).toMatchObject({
      state: 'paused', label: 'Paused',
      explanation: 'Turn on mirroring when you want delivery to resume.', peerLine: null,
    });
  });

  it('uses notification grammar only for notification-only work', () => {
    expect(presentRoute(status({ pending_local_count: 1, queued_count: 1 }), true).explanation)
      .toBe('1 notification will send when a connection is available.');
    expect(presentRoute(status({
      pending_local_count: 1, queued_count: 1, user_content_kind: 'sync_updates',
    }), true).explanation).toBe('1 sync update will send when a connection is available.');
    expect(presentRoute(status({
      route: 'relay', phase: 'authenticated', held_by_relay_count: 2, awaiting_peer_count: 2,
      delivery_reason: 'relay_holding', user_content_kind: 'notifications',
    }), true).explanation).toBe('2 notifications are stored securely and waiting for your other phone.');
  });

  it('never calls relay-held work queued on this phone', () => {
    const presentation = presentRoute(status({
      route: 'relay', phase: 'authenticated', held_by_relay_count: 1, awaiting_peer_count: 1,
      delivery_reason: 'relay_holding',
    }), true);
    expect(presentation.label).toBe('Via relay');
    expect(presentation.label).not.toMatch(/queued/i);
  });

  it('reports not paired before anything is linked', () => {
    expect(presentRoute(status({ route: 'lan', phase: 'authenticated' }), false)).toMatchObject({
      state: 'unpaired', label: 'Not paired', action: 'pair', peerLine: null,
    });
  });

  it('exposes no network detail in any user-facing string', () => {
    const all = [
      presentRoute(status({ route: 'lan', phase: 'authenticated' }), true),
      presentRoute(status({ route: 'relay', phase: 'authenticated' }), true),
      presentRoute(status({ phase: 'reconnecting', pending_local_count: 2 }), true),
    ];
    for (const presentation of all) {
      expect(`${presentation.label} ${presentation.explanation} ${presentation.peerLine ?? ''}`)
        .not.toMatch(/\d+\.\d+\.\d+\.\d+|wss?:|ssid|port|:\d{2,5}\b/i);
    }
  });
});
