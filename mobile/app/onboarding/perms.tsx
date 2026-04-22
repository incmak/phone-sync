import React, { useState, useCallback } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useFocusEffect } from 'expo-router';
import Svg, { Path, Circle, Rect } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import * as Notifications from 'expo-notifications';

// --- Icons ---

function BellIcon({ color, size = 24 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      <Path
        d="M12 2C10.3 2 9 3.3 9 5v.3C6.6 6.1 5 8.4 5 11v5l-2 2v1h18v-1l-2-2v-5c0-2.6-1.6-4.9-4-5.7V5c0-1.7-1.3-3-3-3z"
        stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round" strokeLinejoin="round"
      />
      <Path d="M10 20c0 1.1.9 2 2 2s2-.9 2-2" stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round" />
    </Svg>
  );
}

function NotifListIcon({ color, size = 24 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      <Rect x={3} y={4} width={18} height={16} rx={3} stroke={color} strokeWidth={1.5} fill="none" />
      <Path d="M7 9h10M7 13h7" stroke={color} strokeWidth={1.5} strokeLinecap="round" />
      <Circle cx={17} cy={13} r={2} fill={color} opacity={0.7} />
    </Svg>
  );
}

function CheckIcon({ color, size = 18 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18">
      <Path d="M3,9 L7,13 L15,5" stroke={color} strokeWidth={2} fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

function ChevronIcon({ color, size = 18 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18">
      <Path d="M6,4 L12,9 L6,14" stroke={color} strokeWidth={2} fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

// --- Permission card ---

interface PermCardProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  granted: boolean;
  onGrant: () => void;
  accent: string;
  border: string;
  fill: string;
  ink: string;
  ink3: string;
  sem: { ok: string };
  uiSemi: string;
  ui: string;
  radius: number;
}

function PermCard({
  icon, title, description, granted, onGrant,
  accent, border, fill, ink, ink3, sem, uiSemi, ui, radius,
}: PermCardProps) {
  return (
    <View
      style={[
        permStyles.card,
        {
          backgroundColor: fill,
          borderColor: granted ? sem.ok : border,
          borderWidth: granted ? 2 : 1,
          borderRadius: radius,
        },
      ]}
    >
      <View style={[permStyles.iconWrap, { backgroundColor: border + '60' }]}>
        {icon}
      </View>
      <View style={permStyles.text}>
        <Text style={{ fontSize: 15, fontFamily: uiSemi, color: ink, marginBottom: 2 }}>
          {title}
        </Text>
        <Text style={{ fontSize: 13, fontFamily: ui, color: ink3, lineHeight: 18 }}>
          {description}
        </Text>
      </View>
      {granted ? (
        <View style={[permStyles.statusBadge, { backgroundColor: sem.ok + '22' }]}>
          <CheckIcon color={sem.ok} size={18} />
        </View>
      ) : (
        <Pressable
          onPress={onGrant}
          style={[permStyles.grantBtn, { backgroundColor: accent, borderRadius: radius / 2 }]}
        >
          <ChevronIcon color="#fff" size={16} />
        </Pressable>
      )}
    </View>
  );
}

const permStyles = StyleSheet.create({
  card: { flexDirection: 'row', alignItems: 'center', padding: 16, gap: 14 },
  iconWrap: { width: 44, height: 44, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1 },
  statusBadge: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  grantBtn: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
});

// --- Screen ---

export default function PermsScreen() {
  const theme = useTheme();
  const [postNotifGranted, setPostNotifGranted] = useState(false);
  const [nlsGranted, setNlsGranted] = useState(false);

  const checkPerms = useCallback(async () => {
    try {
      const [pn, nls] = await Promise.all([
        TwinotifyCoreModule.isPostNotificationsGranted(),
        TwinotifyCoreModule.isNotificationListenerGranted(),
      ]);
      setPostNotifGranted(pn);
      setNlsGranted(nls);
    } catch {
      // non-fatal; buttons still actionable
    }
  }, []);

  // Check on focus (user returning from system settings) + poll every 1.5s while on screen.
  // Polling is needed because Android re-focus can be delayed after the NLS settings screen
  // closes; without it the card stays stale until user taps back manually.
  useFocusEffect(
    useCallback(() => {
      void checkPerms();
      const id = setInterval(() => void checkPerms(), 1500);
      return () => clearInterval(id);
    }, [checkPerms]),
  );

  async function grantPostNotif() {
    try {
      // Direct Android 13+ runtime prompt via expo-notifications.
      const res = await Notifications.requestPermissionsAsync();
      if (res.granted) {
        setPostNotifGranted(true);
      } else {
        // User denied at runtime prompt — offer app-settings fallback
        // (may be needed if user previously selected "don't ask again").
        await TwinotifyCoreModule.openAppSettings();
      }
    } catch {
      // Fallback to app settings if expo-notifications errors.
      try { await TwinotifyCoreModule.openAppSettings(); } catch { /* ignore */ }
    }
  }

  async function grantNls() {
    try {
      await TwinotifyCoreModule.openListenerSettings();
    } catch {
      // ignore
    }
  }

  const allGranted = postNotifGranted && nlsGranted;

  const cardProps = {
    accent: theme.accent,
    border: theme.border,
    fill: theme.fill,
    ink: theme.ink,
    ink3: theme.ink3,
    sem: theme.sem,
    uiSemi: theme.fonts.uiSemi,
    ui: theme.fonts.ui,
    radius: theme.radius.lg,
  };

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.header}>
          <Text
            style={[
              theme.type.display,
              { color: theme.ink, fontFamily: theme.fonts.uiBold },
            ]}
          >
            Grant permissions
          </Text>
          <Text
            style={[
              theme.type.body,
              styles.subtitle,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            Twinotify needs two permissions to mirror notifications.
          </Text>
        </View>

        <View style={styles.cards}>
          <PermCard
            {...cardProps}
            granted={postNotifGranted}
            onGrant={grantPostNotif}
            icon={<BellIcon color={theme.ink3} />}
            title="Post notifications"
            description="Shows mirrored alerts on this device's lock screen and notification shade."
          />
          <PermCard
            {...cardProps}
            granted={nlsGranted}
            onGrant={grantNls}
            icon={<NotifListIcon color={theme.ink3} />}
            title="Notification access"
            description="Reads notifications so they can be sent to your paired device. Tap to open settings and enable Twinotify."
          />
        </View>

        <View style={styles.footer}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            disabled={!allGranted}
            onPress={() => router.push('/onboarding/oem')}
          >
            Continue
          </TwButton>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 8 },
  header: { marginBottom: 32 },
  subtitle: { marginTop: 10 },
  cards: { gap: 12, flex: 1 },
  footer: { paddingBottom: 8 },
});
