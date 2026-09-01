import React from 'react';
import {
  ActivityIndicator,
  Image,
  StyleSheet,
  Text,
  View,
  Pressable,
} from 'react-native';

import type { RecentActivityState } from '../../hooks/useRecentActivity';
import { useTheme } from '../Theme';
import { TwinotifyMark } from '../primitives/TwinotifyMark';
import { TwButton } from '../primitives/TwButton';
import { presentRecentActivity } from './recentActivityPresentation';

interface RecentActivitySectionProps {
  state: RecentActivityState;
  peerName: string;
  now?: number;
  onSeeAll?: () => void;
}

export function RecentActivitySection({ state, peerName, now, onSeeAll }: RecentActivitySectionProps) {
  const theme = useTheme();
  const relativeNow = now ?? (state.kind === 'populated' ? state.refreshedAt : 0);

  return (
    <View accessibilityLabel="Recent activity" style={styles.section}>
      <View style={styles.headingRow}>
        <Text style={[styles.heading, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>Recent</Text>
        {onSeeAll && state.kind === 'populated' && (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Open notification history"
            hitSlop={8}
            onPress={onSeeAll}
            style={styles.seeAllTarget}
          >
            <Text style={[styles.seeAll, { color: theme.colors.primary, fontFamily: theme.fonts.uiSemi }]}>See all</Text>
          </Pressable>
        )}
      </View>

      {state.kind === 'loading' && (
        <View style={styles.messageRow} accessibilityLiveRegion="polite">
          <ActivityIndicator color={theme.colors.primary} />
          <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Loading activity</Text>
        </View>
      )}

      {state.kind === 'empty' && (
        <View style={styles.empty}>
          <Text style={[styles.emptyTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>No activity yet</Text>
          <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Your first mirrored notification will appear here.</Text>
        </View>
      )}

      {state.kind === 'error' && (
        <View style={styles.empty} accessibilityLiveRegion="polite">
          <Text style={[styles.emptyTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>Activity unavailable</Text>
          <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Twinotify couldn’t refresh this local history.</Text>
          <TwButton variant="secondary" size="sm" onPress={state.retry}>Try again</TwButton>
        </View>
      )}

      {state.kind === 'populated' && (
        <View style={styles.list}>
          {state.items.map((item, index) => {
            const copy = presentRecentActivity(item, peerName, relativeNow);
            return (
              <View key={`${item.occurredAt}-${index}`} style={styles.row}>
                {item.artworkDataUri ? (
                  <Image source={{ uri: item.artworkDataUri }} style={styles.artwork} resizeMode="contain" accessible={false} />
                ) : (
                  <View style={styles.mark} accessible={false}>
                    <TwinotifyMark size={28} color={theme.colors.onSurfaceVariant} />
                  </View>
                )}
                <View style={styles.copy}>
                  <Text style={[styles.rowTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>{copy.title}</Text>
                  <Text style={[styles.rowDetail, { color: theme.colors.onSurfaceVariant }]}>{copy.detail}</Text>
                </View>
              </View>
            );
          })}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: 16 },
  headingRow: { minHeight: 32, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  heading: { fontSize: 22, lineHeight: 28, letterSpacing: -0.3 },
  seeAllTarget: { minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  seeAll: { fontSize: 14, lineHeight: 20 },
  messageRow: { minHeight: 64, flexDirection: 'row', alignItems: 'center', gap: 12 },
  empty: { minHeight: 92, justifyContent: 'center', alignItems: 'flex-start', gap: 6 },
  emptyTitle: { fontSize: 17, lineHeight: 24 },
  body: { fontSize: 15, lineHeight: 22 },
  list: { gap: 18 },
  row: { minHeight: 56, flexDirection: 'row', alignItems: 'center', gap: 16 },
  artwork: { width: 40, height: 40 },
  mark: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  copy: { flex: 1, gap: 2 },
  rowTitle: { fontSize: 16, lineHeight: 22 },
  rowDetail: { fontSize: 14, lineHeight: 20 },
});
