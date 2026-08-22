import { presentRoute } from '../routePresentation';
import type { RouteStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

const status = (over: Partial<RouteStatus> = {}): RouteStatus => ({
  route: 'none',
  phase: 'idle',
  queued_count: 0,
  ...over,
});

describe('presentRoute', () => {
  it('reports Direct on Wi-Fi for an authenticated LAN route', () => {
    const p = presentRoute(status({ route: 'lan', phase: 'authenticated' }), true);

    expect(p.state).toBe('direct');
    expect(p.label).toBe('Direct on Wi-Fi');
    expect(p.action).toBeUndefined();
  });

  it('reports Via relay for an authenticated relay route', () => {
    const p = presentRoute(status({ route: 'relay', phase: 'authenticated' }), true);

    expect(p.state).toBe('relay');
    expect(p.label).toBe('Via relay');
    expect(p.action).toBeUndefined();
  });

  it('never shows offline merely because the relay is down while LAN is healthy', () => {
    const p = presentRoute(status({ route: 'lan', phase: 'authenticated' }), true);

    expect(p.label).not.toMatch(/offline/i);
    expect(p.state).toBe('direct');
  });

  it('reports Reconnecting while recovering with no queued work', () => {
    const p = presentRoute(status({ route: 'none', phase: 'reconnecting' }), true);

    expect(p.state).toBe('reconnecting');
    expect(p.label).toBe('Reconnecting');
    expect(p.action).toBeUndefined();
  });

  it('reports Queued for delivery with the count and a single retry control', () => {
    const p = presentRoute(status({ route: 'none', phase: 'reconnecting', queued_count: 4 }), true);

    expect(p.state).toBe('queued');
    expect(p.label).toBe('Queued for delivery');
    expect(p.explanation).toContain('4');
    expect(p.action).toBe('retry');
  });

  it('says one notification in the singular', () => {
    const p = presentRoute(status({ queued_count: 1 }), true);

    expect(p.explanation).toBe('1 notification is waiting for your other phone.');
  });

  it('reports Not paired before anything is linked', () => {
    const p = presentRoute(status({ route: 'lan', phase: 'authenticated' }), false);

    expect(p.state).toBe('unpaired');
    expect(p.label).toBe('Not paired');
    expect(p.action).toBe('pair');
  });

  it('offers no action in the two healthy states', () => {
    expect(presentRoute(status({ route: 'lan', phase: 'authenticated' }), true).action).toBeUndefined();
    expect(presentRoute(status({ route: 'relay', phase: 'authenticated' }), true).action).toBeUndefined();
  });

  it('treats a missing or negative queue count as empty', () => {
    expect(presentRoute(status({ queued_count: -3 }), true).queuedCount).toBe(0);
    expect(presentRoute({ route: 'none', phase: 'idle' } as RouteStatus, true).queuedCount).toBe(0);
  });

  it('exposes no network detail in any user-facing string', () => {
    const all = [
      presentRoute(status({ route: 'lan', phase: 'authenticated' }), true),
      presentRoute(status({ route: 'relay', phase: 'authenticated' }), true),
      presentRoute(status({ phase: 'reconnecting' }), true),
      presentRoute(status({ queued_count: 2 }), true),
      presentRoute(status(), false),
    ];

    for (const p of all) {
      expect(`${p.label} ${p.explanation}`).not.toMatch(/\d+\.\d+\.\d+\.\d+|wss?:|ssid|port|:\d{2,5}\b/i);
    }
  });
});
