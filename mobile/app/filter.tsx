import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { useTheme, TwAppChip, TW_APPS, TwSwitch, TwBanner } from '../components';

// ── types ─────────────────────────────────────────────────────────────────────

type TabKey = 'all' | 'mirrored' | 'blocked';
type AppFilter = Record<string, boolean>; // packageName → allowed

const FILTER_STORAGE_KEY = 'twinotify_app_filter';

// Default: most apps allowed; banking/OTP pre-blocked
const DEFAULT_FILTER: AppFilter = {
  signal: true,
  whatsapp: true,
  slack: true,
  gmail: true,
  linear: true,
  github: true,
  spotify: true,
  calendar: true,
  maps: false,
  chase: false,   // pre-blocked (banking)
  authy: false,   // pre-blocked (OTP)
  uber: true,
  instagram: true,
  discord: true,
  telegram: true,
};

// ── component ─────────────────────────────────────────────────────────────────

export default function FilterScreen() {
  const theme = useTheme();
  const [tab, setTab] = useState<TabKey>('all');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<AppFilter>(DEFAULT_FILTER);

  // Load persisted filter on mount
  useEffect(() => {
    AsyncStorage.getItem(FILTER_STORAGE_KEY)
      .then((raw) => {
        if (!raw) return;
        try {
          const parsed: unknown = JSON.parse(raw);
          if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            // Merge with defaults to handle new apps added later
            setFilter({ ...DEFAULT_FILTER, ...(parsed as AppFilter) });
          }
        } catch {
          // malformed — keep defaults
        }
      })
      .catch(() => {});
  }, []);

  const handleToggle = useCallback((key: string, next: boolean) => {
    setFilter((prev) => {
      const updated = { ...prev, [key]: next };
      AsyncStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(updated)).catch(() => {});
      return updated;
    });
  }, []);

  const allKeys = Object.keys(TW_APPS);
  const mirroredCount = allKeys.filter((k) => filter[k] !== false).length;
  const blockedCount = allKeys.filter((k) => filter[k] === false).length;

  const tabs: { k: TabKey; label: string; count: number }[] = [
    { k: 'all',      label: 'All',      count: allKeys.length },
    { k: 'mirrored', label: 'Mirrored', count: mirroredCount  },
    { k: 'blocked',  label: 'Blocked',  count: blockedCount   },
  ];

  const visibleKeys = useMemo(() => {
    const lq = query.toLowerCase();
    return allKeys.filter((k) => {
      const app = TW_APPS[k];
      if (!app) return false;
      if (lq && !app.name.toLowerCase().includes(lq)) return false;
      if (tab === 'mirrored') return filter[k] !== false;
      if (tab === 'blocked')  return filter[k] === false;
      return true;
    });
  }, [allKeys, tab, query, filter]);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      {/* Header */}
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable onPress={() => router.back()} hitSlop={12} style={styles.backBtn}>
          <Text style={[styles.backLabel, { color: theme.accent, fontFamily: theme.fonts.ui }]}>
            ‹ Settings
          </Text>
        </Pressable>
        <Text style={[styles.headerTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          App filter
        </Text>
        <View style={styles.backBtn} />
      </View>

      {/* Phase 3 limitation banner */}
      <TwBanner
        tone="warn"
        title="Phase 3: cosmetic only"
        body="This list is stored but not yet enforced. Hardcoded deny-list is active. Per-app control lands in Phase 4."
        compact
        style={styles.limitBanner}
      />

      {/* Search + tabs — sticky above list */}
      <View style={styles.controls}>
        {/* Search bar */}
        <View style={[styles.searchBar, { backgroundColor: theme.fill, borderColor: theme.border }]}>
          <Text style={[styles.searchIcon, { color: theme.ink3 }]}>🔍</Text>
          <TextInput
            placeholder="Search apps"
            placeholderTextColor={theme.ink3}
            value={query}
            onChangeText={setQuery}
            style={[styles.searchInput, { color: theme.ink, fontFamily: theme.fonts.ui }]}
            returnKeyType="search"
            autoCorrect={false}
          />
        </View>

        {/* Tabs */}
        <View style={styles.tabRow}>
          {tabs.map((t) => {
            const active = t.k === tab;
            return (
              <Pressable
                key={t.k}
                onPress={() => setTab(t.k)}
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
                  {t.label}{' '}
                  <Text style={{ opacity: 0.6 }}>{t.count}</Text>
                </Text>
              </Pressable>
            );
          })}
        </View>

        {/* OTP pre-blocked banner — only on All tab */}
        {tab === 'all' && (
          <View
            style={[
              styles.otpBanner,
              { backgroundColor: theme.fill, borderColor: theme.border },
            ]}
          >
            <Text style={[styles.otpText, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
              🛡 3 banking apps pre-blocked · <Text style={{ color: theme.ink, fontFamily: theme.fonts.uiSemi }}>hash verified</Text>
            </Text>
          </View>
        )}
      </View>

      {/* App list */}
      <ScrollView
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        <View style={[styles.listCard, { backgroundColor: theme.card, borderColor: theme.border }]}>
          {visibleKeys.length === 0 ? (
            <View style={styles.emptyRow}>
              <Text style={[styles.emptyText, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
                No apps match
              </Text>
            </View>
          ) : (
            visibleKeys.map((k, i) => {
              const app = TW_APPS[k];
              if (!app) return null;
              const allowed = filter[k] !== false;
              return (
                <View key={k}>
                  {i > 0 && (
                    <View style={[styles.rowDivider, { backgroundColor: theme.border }]} />
                  )}
                  <View style={styles.appRow}>
                    <TwAppChip app={app} size="sm" />
                    <Text
                      style={[
                        styles.appName,
                        { color: theme.ink, fontFamily: theme.fonts.uiMedium },
                      ]}
                    >
                      {app.name}
                    </Text>
                    <TwSwitch
                      checked={allowed}
                      onChange={(next) => handleToggle(k, next)}
                      size="md"
                    />
                  </View>
                </View>
              );
            })
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  // Header
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backBtn: { minWidth: 80 },
  backLabel: { fontSize: 16 },
  headerTitle: { fontSize: 17 },
  // Limit banner
  limitBanner: { marginHorizontal: 16, marginTop: 12 },
  // Controls
  controls: { paddingHorizontal: 20, paddingTop: 12, gap: 12 },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderRadius: 12,
    borderWidth: 1,
    paddingVertical: 10,
    paddingHorizontal: 12,
  },
  searchIcon: { fontSize: 14 },
  searchInput: { flex: 1, fontSize: 14, padding: 0 },
  // Tabs
  tabRow: { flexDirection: 'row', gap: 8 },
  tabBtn: {
    flex: 1,
    paddingVertical: 8,
    paddingHorizontal: 10,
    borderRadius: 999,
    borderWidth: 1,
    alignItems: 'center',
  },
  tabLabel: { fontSize: 13 },
  // OTP banner
  otpBanner: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 10,
    borderWidth: 1,
  },
  otpText: { fontSize: 12 },
  // List
  list: { padding: 20, paddingBottom: 40 },
  listCard: {
    borderRadius: 14,
    borderWidth: 1,
    overflow: 'hidden',
  },
  rowDivider: { height: StyleSheet.hairlineWidth, marginHorizontal: 14 },
  appRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  appName: { flex: 1, fontSize: 14 },
  emptyRow: { padding: 32, alignItems: 'center' },
  emptyText: { fontSize: 14 },
});
