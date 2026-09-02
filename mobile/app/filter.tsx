import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import MaterialIcons from '@expo/vector-icons/MaterialIcons';

import TwinotifyCoreModule, {
  type FilterableApp,
} from '../modules/twinotify-core/src/TwinotifyCoreModule';
import { useTheme, TwAppChip, TwSwitch, TwBanner } from '../components';

type TabKey = 'all' | 'mirrored' | 'blocked';
type AppFilter = Record<string, boolean>;

export function filterTabAccessibilityLabel(label: string, count: number): string {
  return `${label}, ${count} ${count === 1 ? 'app' : 'apps'}`;
}

export default function FilterScreen() {
  const theme = useTheme();
  const [tab, setTab] = useState<TabKey>('all');
  const [query, setQuery] = useState('');
  const [apps, setApps] = useState<FilterableApp[]>([]);
  const [filter, setFilter] = useState<AppFilter>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [pendingKeys, setPendingKeys] = useState<Set<string>>(() => new Set());
  const [saveError, setSaveError] = useState(false);
  const pendingKeysRef = useRef(new Set<string>());

  useEffect(() => {
    let active = true;

    async function loadCatalog() {
      try {
        const catalog = await TwinotifyCoreModule.getFilterableApps();
        // Discovery happens first so native refreshes against the current app categories.
        const denied = await TwinotifyCoreModule.getUserDenylist();
        if (!active) return;
        const deniedSet = new Set(denied);
        const initial: AppFilter = {};
        for (const app of catalog) {
          initial[app.packageName] = (
            !app.alwaysFiltered && !deniedSet.has(app.packageName)
          );
        }
        setApps(catalog);
        setFilter(initial);
        setLoadError(false);
      } catch {
        if (active) setLoadError(true);
      } finally {
        if (active) setLoading(false);
      }
    }

    void loadCatalog();
    return () => { active = false; };
  }, []);

  const mirroredCount = apps.filter((app) => filter[app.packageName] !== false).length;
  const blockedCount = apps.filter((app) => filter[app.packageName] === false).length;
  const tabs: { k: TabKey; label: string; count: number }[] = [
    { k: 'all', label: 'All', count: apps.length },
    { k: 'mirrored', label: 'Mirrored', count: mirroredCount },
    { k: 'blocked', label: 'Blocked', count: blockedCount },
  ];

  const visibleApps = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    return apps.filter((app) => {
      if (
        normalizedQuery
        && !app.displayName.toLocaleLowerCase().includes(normalizedQuery)
        && !app.packageName.toLocaleLowerCase().includes(normalizedQuery)
      ) return false;
      if (tab === 'mirrored') return filter[app.packageName] !== false;
      if (tab === 'blocked') return filter[app.packageName] === false;
      return true;
    });
  }, [apps, filter, query, tab]);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable
          onPress={() => router.back()}
          accessibilityRole="button"
          accessibilityLabel="Back"
          style={styles.backBtn}
        >
          <MaterialIcons name="arrow-back" size={24} color={theme.colors.primary as string} />
        </Pressable>
        <Text style={[styles.headerTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          App filter
        </Text>
        <View style={styles.backBtn} />
      </View>

      <TwBanner
        tone="info"
        title="Privacy defaults"
        body="Banking, authenticator, and password-manager apps stay blocked. Music and audio apps start blocked, but you can turn them on below."
        compact
        style={styles.infoBanner}
      />

      <View style={styles.controls}>
        <View style={[styles.searchBar, { backgroundColor: theme.fill, borderColor: theme.border }]}>
          <MaterialIcons name="search" size={20} color={theme.ink3} />
          <TextInput
            placeholder="Search apps"
            placeholderTextColor={theme.ink3}
            value={query}
            onChangeText={setQuery}
            style={[styles.searchInput, { color: theme.ink, fontFamily: theme.fonts.ui }]}
            returnKeyType="search"
            autoCapitalize="none"
            autoCorrect={false}
          />
        </View>

        <View style={styles.tabRow}>
          {tabs.map((item) => {
            const active = item.k === tab;
            return (
              <Pressable
                key={item.k}
                onPress={() => setTab(item.k)}
                accessibilityRole="tab"
                accessibilityLabel={filterTabAccessibilityLabel(item.label, item.count)}
                accessibilityState={{ selected: active }}
                style={[
                  styles.tabBtn,
                  {
                    backgroundColor: active ? theme.ink : theme.card,
                    borderColor: active ? theme.ink : theme.border,
                  },
                ]}
              >
                <Text
                  style={[
                    styles.tabLabel,
                    {
                      color: active ? theme.bg : theme.ink,
                      fontFamily: theme.fonts.uiSemi,
                    },
                  ]}
                >
                  {item.label}{' '}
                  <Text style={{ opacity: 0.6 }}>{item.count}</Text>
                </Text>
              </Pressable>
            );
          })}
        </View>

        {saveError && (
          <Text
            accessibilityRole="alert"
            accessibilityLiveRegion="assertive"
            style={[
              styles.saveError,
              { color: theme.sem.danger.foreground, fontFamily: theme.fonts.uiMedium },
            ]}
          >
            Couldn&apos;t save this change. Try again.
          </Text>
        )}
      </View>

      <FlatList
        data={loading ? [] : visibleApps}
        keyExtractor={(app) => app.packageName}
        initialNumToRender={24}
        style={[styles.listCard, { backgroundColor: theme.card, borderColor: theme.border }]}
        contentContainerStyle={(loading || visibleApps.length === 0) ? styles.emptyList : undefined}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={(
          <View style={styles.emptyRow}>
            {loading ? (
              <Text style={[styles.emptyText, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                Loading…
              </Text>
            ) : loadError ? (
              <Text
                accessibilityRole="alert"
                style={[
                  styles.emptyText,
                  { color: theme.sem.danger.foreground, fontFamily: theme.fonts.ui },
                ]}
              >
                Couldn&apos;t load installed apps. Reopen this screen to try again.
              </Text>
            ) : (
              <Text style={[styles.emptyText, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                No apps match
              </Text>
            )}
          </View>
        )}
        renderItem={({ item: app, index }) => {
          const packageName = app.packageName;
          const allowed = filter[packageName] !== false;
          return (
            <View
              style={[
                styles.appRow,
                index > 0 && {
                  borderTopColor: theme.border,
                  borderTopWidth: StyleSheet.hairlineWidth,
                },
              ]}
            >
              <TwAppChip
                app={{ name: app.displayName, artworkDataUri: app.artworkDataUri }}
                size="sm"
              />
              <View style={styles.appCopy}>
                <Text
                  numberOfLines={1}
                  style={[
                    styles.appName,
                    { color: theme.ink, fontFamily: theme.fonts.uiMedium },
                  ]}
                >
                  {app.displayName}
                </Text>
                {app.alwaysFiltered ? (
                  <Text style={[styles.appMeta, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
                    Always blocked for privacy
                  </Text>
                ) : app.defaultFiltered ? (
                  <Text style={[styles.appMeta, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
                    Music or audio · blocked by default
                  </Text>
                ) : null}
              </View>
              <TwSwitch
                checked={allowed}
                onChange={async (next) => {
                  if (pendingKeysRef.current.has(packageName)) return;
                  pendingKeysRef.current.add(packageName);
                  setPendingKeys(new Set(pendingKeysRef.current));
                  setSaveError(false);
                  setFilter((previous) => ({ ...previous, [packageName]: next }));
                  try {
                    if (next) {
                      await TwinotifyCoreModule.removeFromDenylist(packageName);
                    } else {
                      await TwinotifyCoreModule.addToDenylist(packageName);
                    }
                  } catch {
                    setFilter((previous) => ({ ...previous, [packageName]: !next }));
                    setSaveError(true);
                  } finally {
                    pendingKeysRef.current.delete(packageName);
                    setPendingKeys(new Set(pendingKeysRef.current));
                  }
                }}
                size="md"
                disabled={app.alwaysFiltered || pendingKeys.has(packageName)}
                accessibilityLabel={`${app.displayName} mirroring`}
              />
            </View>
          );
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backBtn: { width: 80, minHeight: 48, justifyContent: 'center' },
  headerTitle: { fontSize: 17 },
  infoBanner: { marginHorizontal: 16, marginTop: 12 },
  controls: { paddingHorizontal: 20, paddingTop: 12, gap: 12 },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderRadius: 12,
    borderWidth: 1,
    minHeight: 48,
    paddingVertical: 10,
    paddingHorizontal: 12,
  },
  searchInput: { flex: 1, fontSize: 14, padding: 0 },
  tabRow: { flexDirection: 'row', gap: 8 },
  tabBtn: {
    flex: 1,
    minHeight: 44,
    paddingVertical: 8,
    paddingHorizontal: 10,
    borderRadius: 999,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabLabel: { fontSize: 13 },
  saveError: { fontSize: 13, lineHeight: 18 },
  listCard: {
    flex: 1,
    marginHorizontal: 20,
    marginTop: 16,
    marginBottom: 20,
    borderRadius: 14,
    borderWidth: 1,
    overflow: 'hidden',
  },
  emptyList: { flexGrow: 1 },
  appRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 10,
    paddingHorizontal: 14,
    minHeight: 64,
  },
  appCopy: { flex: 1, minWidth: 0 },
  appName: { fontSize: 14 },
  appMeta: { fontSize: 12, lineHeight: 17, marginTop: 1 },
  emptyRow: { flex: 1, padding: 32, alignItems: 'center', justifyContent: 'center' },
  emptyText: { fontSize: 14, lineHeight: 20, textAlign: 'center' },
});
