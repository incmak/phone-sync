import type { RouteStatus } from '../modules/twinotify-core/src/TwinotifyCoreModule';

export interface RouteOrderInputs {
  /** The durable "Prefer direct delivery" setting. */
  preferDirect: boolean;
  /** A Bluetooth association exists and the route is switched on. */
  bluetoothReady: boolean;
  /** A relay endpoint is configured for this pair. */
  hasRelay: boolean;
  active: RouteStatus['route'];
}

export interface RouteOrderDescription {
  /** The routes this pair can actually use, in the order they are tried. */
  order: string[];
  /** One sentence naming the order and what is carrying, or why nothing is. */
  summary: string;
}

const NAMES: Record<'lan' | 'bluetooth' | 'relay', string> = {
  lan: 'Direct Wi-Fi',
  bluetooth: 'Bluetooth',
  relay: 'Relay',
};

/**
 * Describes the delivery order as it actually is, for reading rather than editing.
 *
 * Only routes this pair can use are listed: naming Bluetooth to someone who never associated, or
 * a relay to a direct-only pair, would describe a system they do not have. The order itself is not
 * a preference among many, it is the consequence of one switch, so this reports rather than
 * offers.
 */
export function describeRouteOrder({
  preferDirect,
  bluetoothReady,
  hasRelay,
  active,
}: RouteOrderInputs): RouteOrderDescription {
  const direct: ('lan' | 'bluetooth')[] = bluetoothReady ? ['lan', 'bluetooth'] : ['lan'];
  const keys: ('lan' | 'bluetooth' | 'relay')[] = hasRelay
    ? (preferDirect ? [...direct, 'relay'] : ['relay', ...direct])
    : direct;

  const order = keys.map((key) => NAMES[key]);
  const sequence = order.length === 1
    ? order[0]
    : `${order.slice(0, -1).join(', then ')}, then ${order[order.length - 1]}`;

  const carrying = keys.find((key) => key === active);
  const summary = carrying
    ? `${sequence}. ${NAMES[carrying]} is carrying now.`
    : `${sequence}. Nothing is carrying yet.`;

  return { order, summary };
}
