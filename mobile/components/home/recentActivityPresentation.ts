import type { RecentActivityItem } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

export interface PresentedRecentActivity {
  title: string;
  detail: string;
}

function relativeTime(timestamp: number, now: number): string {
  const elapsed = Math.max(0, now - timestamp);
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

export function presentRecentActivity(
  item: RecentActivityItem,
  peerName: string,
  now = Date.now(),
): PresentedRecentActivity {
  const peer = peerName.trim() || 'your other phone';
  const app = item.appName?.trim() || 'Source app';
  const title = (() => {
    if (item.kind === 'CALL') {
      return item.direction === 'SENT' ? `Call state sent to ${peer}` : `Call state from ${peer}`;
    }
    if (item.kind === 'DISMISSAL' || item.status === 'DISMISSED') {
      return item.direction === 'SENT' ? `Dismissed on ${peer}` : `Dismissed from ${peer}`;
    }
    if (item.status === 'QUEUED') return `Queued for ${peer}`;
    if (item.status === 'EXPIRED') return 'Expired before delivery';
    if (item.status === 'FAILED') return `Couldn’t mirror to ${peer}`;
    return item.direction === 'SENT' ? `Mirrored to ${peer}` : `Mirrored from ${peer}`;
  })();
  const route = item.route === 'LAN' ? 'Direct' : item.route === 'RELAY' ? 'Relay' : null;
  return {
    title,
    detail: [app, relativeTime(item.occurredAt, now), route].filter(Boolean).join(' · '),
  };
}
