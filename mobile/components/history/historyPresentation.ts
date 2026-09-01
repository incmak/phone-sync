import type { HistoryItem } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

export type HistoryGrouping = 'TIME' | 'APP';

export interface HistoryGroup {
  key: string;
  title: string;
  clearGroupId: string | null;
  items: HistoryItem[];
}
function startOfLocalDay(timestamp: number): number {
  const value = new Date(timestamp);
  value.setHours(0, 0, 0, 0);
  return value.getTime();
}

function timeGroup(timestamp: number, now: number): { key: string; title: string } {
  const elapsedDays = Math.floor((startOfLocalDay(now) - startOfLocalDay(timestamp)) / 86_400_000);
  if (elapsedDays <= 0) return { key: 'today', title: 'Today' };
  if (elapsedDays === 1) return { key: 'yesterday', title: 'Yesterday' };
  return { key: 'earlier', title: 'Earlier' };
}

export function groupHistory(
  items: HistoryItem[],
  grouping: HistoryGrouping,
  now = Date.now(),
): HistoryGroup[] {
  const groups = new Map<string, HistoryGroup>();
  for (const item of items) {
    const descriptor = grouping === 'TIME'
      ? { ...timeGroup(item.occurredAt, now), clearGroupId: null }
      : {
          key: item.appGroupId ?? `unknown:${item.appName?.trim() || 'app'}`,
          title: item.appName?.trim() || 'Unknown app',
          clearGroupId: item.appGroupId,
        };
    const existing = groups.get(descriptor.key);
    if (existing) existing.items.push(item);
    else groups.set(descriptor.key, { ...descriptor, items: [item] });
  }
  return [...groups.values()];
}

export function relativeHistoryTime(timestamp: number, now = Date.now()): string {
  const elapsed = Math.max(0, now - timestamp);
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

export function historyStatus(item: HistoryItem, peerName: string, now = Date.now()): string {
  const peer = peerName.trim() || 'your other phone';
  const action = (() => {
    if (item.kind === 'CALL') return item.direction === 'SENT' ? `Call state sent to ${peer}` : `Call state from ${peer}`;
    if (item.kind === 'DISMISSAL' || item.status === 'DISMISSED') return item.direction === 'SENT' ? `Dismissed on ${peer}` : `Dismissed from ${peer}`;
    if (item.status === 'QUEUED') return `Queued for ${peer}`;
    if (item.status === 'EXPIRED') return 'Expired before delivery';
    if (item.status === 'FAILED') return `Couldn’t mirror to ${peer}`;
    return item.direction === 'SENT' ? `Mirrored to ${peer}` : `Mirrored from ${peer}`;
  })();
  const route = item.route === 'LAN' ? 'Direct' : item.route === 'RELAY' ? 'Relay' : null;
  return [action, relativeHistoryTime(item.occurredAt, now), route].filter(Boolean).join(' · ');
}
