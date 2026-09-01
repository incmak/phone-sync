import type { HistoryItem } from '../../../modules/twinotify-core/src/TwinotifyCoreModule';
import { groupHistory } from '../historyPresentation';

const item = (overrides: Partial<HistoryItem>): HistoryItem => ({
  appName: 'Messages',
  artworkDataUri: null,
  appGroupId: 'group-messages',
  direction: 'RECEIVED',
  kind: 'NOTIFICATION',
  status: 'APPLIED',
  route: 'LAN',
  occurredAt: Date.UTC(2026, 8, 1, 8),
  title: 'A title',
  preview: 'A preview',
  ...overrides,
});
describe('groupHistory', () => {
  it('groups chronologically without exposing internal identifiers', () => {
    const groups = groupHistory([
      item({ occurredAt: Date.UTC(2026, 8, 1, 8) }),
      item({ occurredAt: Date.UTC(2026, 7, 30, 8) }),
    ], 'TIME', Date.UTC(2026, 8, 1, 12));

    expect(groups.map((group) => group.title)).toEqual(['Today', 'Earlier']);
    expect(groups.every((group) => group.clearGroupId === null)).toBe(true);
  });

  it('groups by app with only the opaque native clear token', () => {
    const groups = groupHistory([
      item({ appName: 'Messages', appGroupId: 'opaque-a' }),
      item({ appName: 'Mail', appGroupId: 'opaque-b' }),
      item({ appName: 'Messages', appGroupId: 'opaque-a' }),
    ], 'APP', Date.UTC(2026, 8, 1, 12));

    expect(groups.map((group) => [group.title, group.items.length, group.clearGroupId])).toEqual([
      ['Messages', 2, 'opaque-a'],
      ['Mail', 1, 'opaque-b'],
    ]);
  });
});
