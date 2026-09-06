import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { useBluetoothAssociation } from '../../hooks/useBluetoothAssociation';

/**
 * Offered once, right after pairing, because association needs a confirmed pair and both phones
 * inside the picker at the same moment. That is only reliably true while someone is holding both,
 * which is exactly now. It can still be set up later from paired-device settings.
 */
export default function PairBluetoothScreen() {
  const theme = useTheme();
  const [checking, setChecking] = useState(true);

  const finish = useCallback(() => router.replace('/home'), []);

  const { associate, busy } = useBluetoothAssociation(useCallback(async () => {
    const settings = await TwinotifyCoreModule.getBluetoothRouteSettings().catch(() => null);
    if (settings?.associated) finish();
  }, [finish]));

  useEffect(() => {
    // Already set up, so do not ask again.
    TwinotifyCoreModule.getBluetoothRouteSettings()
      .then((settings) => {
        if (settings.associated) router.replace('/home');
        else setChecking(false);
      })
      .catch(() => setChecking(false));
  }, []);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View>
          <Text style={[
            theme.type.display,
            { color: theme.ink, fontFamily: theme.fonts.uiBold, fontWeight: '700' },
          ]}>
            Add Bluetooth backup
          </Text>
          <Text style={[styles.lead, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
            Notifications keep arriving when one phone has no Wi-Fi and no mobile data, as long as
            the phones are near each other.
          </Text>
        </View>

        <View style={[styles.panel, { backgroundColor: theme.fill, borderRadius: 14 }]}>
          <Text style={[styles.panelTitle, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
            Set this up on both phones now
          </Text>
          <Text style={[styles.panelBody, { color: theme.ink2, fontFamily: theme.fonts.ui }]}>
            Each phone has to pick the other from a list, and both have to be doing it at the same
            time. Start it here, then start it on your other phone straight away.
          </Text>
        </View>

        <Text style={[styles.note, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          Contents stay encrypted end to end. Call audio is never routed over Bluetooth.
        </Text>

        <View style={styles.actions}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            loading={busy}
            disabled={checking}
            onPress={() => { void associate(); }}
          >
            Set up Bluetooth
          </TwButton>
          <View style={styles.skipRow}>
            <TwButton
              variant="ghost"
              size="md"
              fullWidth
              disabled={busy}
              accessibilityLabel="Skip Bluetooth backup"
              onPress={finish}
            >
              Skip for now
            </TwButton>
          </View>
          <Text style={[styles.skipNote, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
            You can add it later from paired-device settings.
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 32, paddingBottom: 24 },
  lead: { fontSize: 15, lineHeight: 22, marginTop: 12 },
  panel: { marginTop: 32, paddingHorizontal: 16, paddingVertical: 16 },
  panelTitle: { fontSize: 15, lineHeight: 21 },
  panelBody: { fontSize: 13, lineHeight: 19, marginTop: 6 },
  note: { fontSize: 13, lineHeight: 19, marginTop: 20 },
  actions: { flex: 1, justifyContent: 'flex-end', paddingTop: 32 },
  skipRow: { marginTop: 12 },
  skipNote: { fontSize: 12, lineHeight: 17, marginTop: 12 },
});
