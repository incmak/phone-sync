import React, { useCallback, useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { TwRow } from './primitives/TwRow';
import { TwSwitch } from './primitives/TwSwitch';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';

export function RepeatProtectionSettings() {
  const [settings, setSettings] = useState<{ enabled: boolean; blockedCount: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useFocusEffect(useCallback(() => {
    let active = true;
    TwinotifyCoreModule.getRepeatProtectionSettings()
      .then((value) => { if (active) { setSettings(value); setFailed(false); } })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, []));

  const update = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    try {
      await action();
      setSettings(await TwinotifyCoreModule.getRepeatProtectionSettings());
      setFailed(false);
    } catch {
      Alert.alert('Repeat protection', 'Could not update repeat protection. Please try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <TwRow
        title="Repeat protection"
        subtitle={failed
          ? 'Could not load the setting. Tap to retry.'
          : 'Three updates to the same notification in 15 seconds snooze it for one minute on this phone. If updates continue, it is blocked with an Undo notice.'}
        onPress={failed ? () => update(async () => {}) : undefined}
        trailing={
          <View style={styles.controlSlot}>
            <TwSwitch
              checked={settings?.enabled ?? true}
              disabled={!settings || busy || failed}
              onChange={(enabled) => update(() => TwinotifyCoreModule.setRepeatProtectionEnabled(enabled))}
              touchTargetSize={48}
              accessibilityLabel="Repeat protection"
            />
          </View>
        }
        style={styles.row}
      />
      {(settings?.blockedCount ?? 0) > 0 ? (
        <TwRow
          title="Re-enable blocked notifications"
          subtitle={`${settings!.blockedCount} blocked. Re-enabled notifications are exempt from repeat protection.`}
          onPress={busy ? undefined : () => update(() => TwinotifyCoreModule.restoreRepeatBlockedNotifications())}
          accessibilityLabel="Re-enable blocked notifications"
          style={styles.row}
        />
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  row: { paddingHorizontal: 0, paddingVertical: 12, alignItems: 'flex-start' },
  controlSlot: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center', marginTop: -2 },
});
