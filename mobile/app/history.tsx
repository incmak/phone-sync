import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import { router, useFocusEffect } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { groupHistory, historyStatus, type HistoryGrouping } from '../components/history/historyPresentation';
import { TwinotifyMark, TwButton, TwRow, TwSwitch, useTheme } from '../components';
import TwinotifyCoreModule, {
  type HistoryItem,
  type HistorySettings,
  type PairStatus,
} from '../modules/twinotify-core/src/TwinotifyCoreModule';

type LoadState =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; items: HistoryItem[]; settings: HistorySettings; peerName: string };

export default function HistoryScreen() {
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const gutter = width <= 360 ? 16 : 22;
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const [grouping, setGrouping] = useState<HistoryGrouping>('TIME');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [items, settings, pair] = await Promise.all([
        TwinotifyCoreModule.getHistory(500),
        TwinotifyCoreModule.getHistorySettings(),
        TwinotifyCoreModule.getPairStatus(),
      ]);
      const peer = (pair as PairStatus).peerDisplayName?.trim() || 'your other phone';
      setState({ kind: 'ready', items, settings, peerName: peer });
    } catch {
      setState({ kind: 'error' });
    }
  }, []);

  useFocusEffect(useCallback(() => {
    void refresh();
  }, [refresh]));

  const groups = useMemo(
    () => state.kind === 'ready' ? groupHistory(state.items, grouping) : [],
    [grouping, state],
  );
  const canClearAll = state.kind === 'ready' && state.items.length > 0 && !busy;

  const runMutation = useCallback(async (operation: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await operation();
      await refresh();
    } catch {
      Alert.alert('History unavailable', 'Twinotify couldn’t update local history. Try again.');
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const confirmClearAll = useCallback(() => {
    Alert.alert(
      'Clear notification history?',
      'This deletes saved history on this phone. Pairing and active mirrored notifications stay unchanged.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Clear history', style: 'destructive', onPress: () => { void runMutation(() => TwinotifyCoreModule.clearHistory()); } },
      ],
    );
  }, [runMutation]);

  const changeContentRetention = useCallback((enabled: boolean) => {
    if (enabled) {
      void runMutation(() => TwinotifyCoreModule.setHistoryContentEnabled(true));
      return;
    }
    Alert.alert(
      'Delete saved titles and previews?',
      'Activity times and delivery status will remain. Saved notification content will be deleted now.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete content', style: 'destructive', onPress: () => { void runMutation(() => TwinotifyCoreModule.setHistoryContentEnabled(false)); } },
      ],
    );
  }, [runMutation]);

  const clearApp = useCallback((title: string, groupId: string) => {
    Alert.alert(
      `Clear ${title} history?`,
      'This removes this app’s saved history from this phone only.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Clear', style: 'destructive', onPress: () => { void runMutation(() => TwinotifyCoreModule.clearHistoryApp(groupId)); } },
      ],
    );
  }, [runMutation]);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.colors.surface }]}>
      <View style={[styles.topBar, { paddingHorizontal: gutter }]}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Go back"
          onPress={() => router.back()}
          style={({ pressed }) => [styles.iconTarget, { backgroundColor: pressed ? theme.colors.surfaceContainerHighest : 'transparent' }]}
        >
          <MaterialIcons name="arrow-back" size={24} color={theme.colors.onSurface as string} />
        </Pressable>
        <Text style={[styles.screenTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.display }]}>History</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Clear all notification history"
          disabled={!canClearAll}
          onPress={confirmClearAll}
          style={styles.clearAllTarget}
        >
          <Text style={[styles.clearAll, { color: canClearAll ? theme.colors.error : theme.colors.onSurfaceVariant, fontFamily: theme.fonts.uiSemi }]}>Clear all</Text>
        </Pressable>
      </View>

      <ScrollView contentContainerStyle={[styles.content, { paddingHorizontal: gutter }]} showsVerticalScrollIndicator={false}>
        <Text style={[styles.intro, { color: theme.colors.onSurfaceVariant }]}>Saved only on this phone. Delivery never depends on history.</Text>

        <View accessibilityRole="tablist" style={[styles.segment, { backgroundColor: theme.colors.surfaceContainerHigh }]}>
          {(['TIME', 'APP'] as const).map((value) => {
            const selected = grouping === value;
            return (
              <Pressable
                key={value}
                accessibilityRole="tab"
                accessibilityState={{ selected }}
                onPress={() => setGrouping(value)}
                style={[styles.segmentItem, selected && { backgroundColor: theme.colors.secondaryContainer }]}
              >
                <Text style={[styles.segmentLabel, { color: selected ? theme.colors.onSecondaryContainer : theme.colors.onSurfaceVariant, fontFamily: theme.fonts.uiSemi }]}>
                  {value === 'TIME' ? 'By time' : 'By app'}
                </Text>
              </Pressable>
            );
          })}
        </View>

        {state.kind === 'loading' && (
          <View style={styles.centerMessage} accessibilityLiveRegion="polite">
            <ActivityIndicator color={theme.colors.primary} />
            <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Loading history</Text>
          </View>
        )}

        {state.kind === 'error' && (
          <View style={styles.centerMessage} accessibilityLiveRegion="polite">
            <Text style={[styles.emptyTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>History unavailable</Text>
            <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Twinotify couldn’t read local history.</Text>
            <TwButton variant="secondary" size="sm" onPress={() => { setState({ kind: 'loading' }); void refresh(); }}>Try again</TwButton>
          </View>
        )}

        {state.kind === 'ready' && state.items.length === 0 && (
          <View style={styles.centerMessage}>
            <Text style={[styles.emptyTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>No history yet</Text>
            <Text style={[styles.body, { color: theme.colors.onSurfaceVariant }]}>Mirrored notifications and dismissals will appear here.</Text>
          </View>
        )}

        {state.kind === 'ready' && groups.map((group) => (
          <View key={group.key} style={styles.group}>
            <View style={styles.groupHeading}>
              <Text style={[styles.groupTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>{group.title}</Text>
              {group.clearGroupId && (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Clear ${group.title} history`}
                  disabled={busy}
                  hitSlop={8}
                  onPress={() => clearApp(group.title, group.clearGroupId!)}
                  style={styles.groupClearTarget}
                >
                  <Text style={[styles.groupClear, { color: theme.colors.error, fontFamily: theme.fonts.uiSemi }]}>Clear</Text>
                </Pressable>
              )}
            </View>
            <View>
              {group.items.map((item, index) => (
                <View
                  key={`${item.appGroupId ?? 'unknown'}:${item.occurredAt}:${index}`}
                  style={[styles.historyRow, index > 0 && { borderTopColor: theme.colors.outlineVariant, borderTopWidth: StyleSheet.hairlineWidth }]}
                >
                  {item.artworkDataUri ? (
                    <Image source={{ uri: item.artworkDataUri }} style={styles.artwork} resizeMode="contain" accessible={false} />
                  ) : (
                    <View style={styles.mark} accessible={false}>
                      <TwinotifyMark size={28} color={theme.colors.onSurfaceVariant} />
                    </View>
                  )}
                  <View style={styles.rowCopy}>
                    <Text style={[styles.appName, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.uiMedium }]}>{item.appName?.trim() || 'Source app'}</Text>
                    {item.title && <Text style={[styles.itemTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>{item.title}</Text>}
                    {item.preview && <Text numberOfLines={2} style={[styles.preview, { color: theme.colors.onSurfaceVariant }]}>{item.preview}</Text>}
                    <Text style={[styles.meta, { color: theme.colors.onSurfaceVariant }]}>{historyStatus(item, state.peerName)}</Text>
                  </View>
                </View>
              ))}
            </View>
          </View>
        ))}

        {state.kind === 'ready' && (
          <View style={styles.privacySection}>
            <Text style={[styles.sectionLabel, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>Privacy and retention</Text>
            <TwRow
              title="Save titles and previews"
              subtitle={state.settings.contentEnabled ? 'Encrypted with Android Keystore' : 'Only app, time, and delivery status are saved'}
              trailing={<TwSwitch checked={state.settings.contentEnabled} disabled={busy} onChange={changeContentRetention} accessibilityLabel="Save notification titles and previews" />}
            />
            <View style={styles.retentionRow}>
              <View style={styles.retentionCopy}>
                <Text style={[styles.retentionTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiMedium }]}>Keep history for</Text>
                <Text style={[styles.retentionSubtitle, { color: theme.colors.onSurfaceVariant }]}>Up to {state.settings.maxRows} events and 2 MiB of encrypted content</Text>
              </View>
              <View style={styles.daysControl}>
                {([7, 30] as const).map((days) => {
                  const selected = state.settings.retentionDays === days;
                  return (
                    <Pressable
                      key={days}
                      accessibilityRole="button"
                      accessibilityLabel={`Keep history for ${days} days`}
                      accessibilityState={{ selected, disabled: busy }}
                      disabled={busy}
                      onPress={() => { void runMutation(() => TwinotifyCoreModule.setHistoryRetentionDays(days)); }}
                      style={[styles.dayTarget, selected && { backgroundColor: theme.colors.secondaryContainer }]}
                    >
                      <Text style={[styles.dayLabel, { color: selected ? theme.colors.onSecondaryContainer : theme.colors.onSurfaceVariant, fontFamily: theme.fonts.uiSemi }]}>{days}d</Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  topBar: { minHeight: 64, flexDirection: 'row', alignItems: 'center', gap: 8 },
  iconTarget: { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center' },
  screenTitle: { flex: 1, fontSize: 28, lineHeight: 34, letterSpacing: -0.4 },
  clearAllTarget: { minWidth: 64, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  clearAll: { fontSize: 14, lineHeight: 20 },
  content: { paddingTop: 4, paddingBottom: 44, gap: 24 },
  intro: { fontSize: 14, lineHeight: 20 },
  segment: { minHeight: 48, flexDirection: 'row', borderRadius: 14, padding: 4 },
  segmentItem: { flex: 1, minHeight: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  segmentLabel: { fontSize: 14, lineHeight: 20 },
  centerMessage: { minHeight: 180, alignItems: 'flex-start', justifyContent: 'center', gap: 8 },
  emptyTitle: { fontSize: 18, lineHeight: 24 },
  body: { fontSize: 15, lineHeight: 22 },
  group: { gap: 4 },
  groupHeading: { minHeight: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  groupTitle: { fontSize: 19, lineHeight: 25 },
  groupClearTarget: { minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  groupClear: { fontSize: 13, lineHeight: 18 },
  historyRow: { minHeight: 84, flexDirection: 'row', alignItems: 'flex-start', gap: 14, paddingVertical: 14 },
  artwork: { width: 40, height: 40, marginTop: 2 },
  mark: { width: 40, height: 40, marginTop: 2, alignItems: 'center', justifyContent: 'center' },
  rowCopy: { flex: 1, minWidth: 0, gap: 2 },
  appName: { fontSize: 12, lineHeight: 17 },
  itemTitle: { fontSize: 16, lineHeight: 22 },
  preview: { fontSize: 14, lineHeight: 20 },
  meta: { fontSize: 12, lineHeight: 18, marginTop: 3 },
  privacySection: { gap: 4, paddingTop: 12 },
  sectionLabel: { fontSize: 19, lineHeight: 25, marginBottom: 4 },
  retentionRow: { minHeight: 72, flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 4, paddingVertical: 10 },
  retentionCopy: { flex: 1, minWidth: 0 },
  retentionTitle: { fontSize: 15, lineHeight: 21 },
  retentionSubtitle: { fontSize: 13, lineHeight: 18, marginTop: 2 },
  daysControl: { flexDirection: 'row', gap: 4 },
  dayTarget: { minWidth: 48, minHeight: 48, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  dayLabel: { fontSize: 13, lineHeight: 18 },
});
