import { describeRouteOrder } from '../routeOrder';

const base = { preferDirect: true, bluetoothReady: true, hasRelay: true, active: 'lan' } as const;

describe('delivery order description', () => {
  it('reports the default order, direct before the relay', () => {
    const { order, summary } = describeRouteOrder({ ...base });

    expect(order).toEqual(['Direct Wi-Fi', 'Bluetooth', 'Relay']);
    expect(summary).toBe('Direct Wi-Fi, then Bluetooth, then Relay. Direct Wi-Fi is carrying now.');
  });

  it('puts the relay first when the direct preference is off', () => {
    const { order } = describeRouteOrder({ ...base, preferDirect: false });

    expect(order).toEqual(['Relay', 'Direct Wi-Fi', 'Bluetooth']);
  });

  it('omits Bluetooth until it is actually set up', () => {
    // Naming a route the pair cannot use would describe a system they do not have.
    const { order, summary } = describeRouteOrder({ ...base, bluetoothReady: false });

    expect(order).toEqual(['Direct Wi-Fi', 'Relay']);
    expect(summary).not.toContain('Bluetooth');
  });

  it('omits the relay for a direct-only pair', () => {
    const { order, summary } = describeRouteOrder({ ...base, hasRelay: false, active: 'bluetooth' });

    expect(order).toEqual(['Direct Wi-Fi', 'Bluetooth']);
    expect(summary).toBe('Direct Wi-Fi, then Bluetooth. Bluetooth is carrying now.');
  });

  it('reads correctly with a single usable route', () => {
    const { summary } = describeRouteOrder({
      preferDirect: true, bluetoothReady: false, hasRelay: false, active: 'lan',
    });

    expect(summary).toBe('Direct Wi-Fi. Direct Wi-Fi is carrying now.');
  });

  it('says nothing is carrying rather than implying a route is live', () => {
    const { summary } = describeRouteOrder({ ...base, active: 'none' });

    expect(summary).toContain('Nothing is carrying yet.');
  });

  it('never claims a route is carrying when it is not in the usable list', () => {
    // Guards against a stale status naming a route the pair has since removed.
    const { summary } = describeRouteOrder({ ...base, hasRelay: false, active: 'relay' });

    expect(summary).toContain('Nothing is carrying yet.');
    expect(summary).not.toContain('Relay is carrying');
  });
});
