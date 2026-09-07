import React, { useCallback, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router, useFocusEffect } from 'expo-router';
import { TwButton, TwRow, useTheme } from '../../components';
import TwinotifyCoreModule, { type PeerLinkStatus } from '../../modules/twinotify-core/src/TwinotifyCoreModule';

export default function PeerListScreen() {
  const theme = useTheme();
  const [peers, setPeers] = useState<PeerLinkStatus[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useFocusEffect(useCallback(() => {
    let active = true;
    TwinotifyCoreModule.getPeerLinks().then((links) => {
      if (active) { setPeers(links); setLoaded(true); setError(null); }
    }).catch(() => { if (active) setError('Could not load paired devices.'); });
    const subscription = TwinotifyCoreModule.addListener('onPeerRoutes', ({ peers: routes }) => {
      if (active) setPeers((current) => current.map((peer) => ({
        ...peer, routeStatus: routes[peer.peerLinkId] ?? peer.routeStatus,
      })));
    });
    return () => { active = false; subscription.remove(); };
  }, []));

  return (
    <SafeAreaView edges={['top', 'bottom']} style={{ flex: 1, backgroundColor: theme.bg }}>
      <ScrollView contentContainerStyle={{ padding: 24, gap: 24 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="Back to settings" onPress={() => router.back()} hitSlop={8}>
          <Text style={{ color: theme.accent, fontFamily: theme.fonts.ui, fontSize: 16 }}>‹ Settings</Text>
        </Pressable>
        <Text style={{ color: theme.ink, fontFamily: theme.fonts.display, fontSize: 28 }}>Paired devices</Text>
        {error ? <Text accessibilityRole="alert" style={{ color: theme.sem.danger.foreground }}>{error}</Text> : null}
        {!loaded && !error ? <Text style={{ color: theme.ink3 }}>Loading…</Text> : null}
        {loaded && peers.length === 0 ? <Text style={{ color: theme.ink3, fontFamily: theme.fonts.ui }}>No devices paired.</Text> : null}
        <View>
          {peers.map((peer) => (
            <TwRow
              key={peer.peerLinkId}
              title={peer.peerDisplayName?.trim() || 'Paired device'}
              subtitle={peer.lifecycle === 'REMOVING' ? 'Removing · cleanup will retry when connected'
                : peer.routeStatus.presentation?.label ?? 'Not connected'}
              onPress={peer.lifecycle === 'ACTIVE' ? () => router.push({ pathname: '/settings/pair', params: { peerLinkId: peer.peerLinkId } }) : undefined}
              trailing={peer.lifecycle === 'ACTIVE' ? <Text style={{ color: theme.ink3 }}>›</Text> : undefined}
            />
          ))}
        </View>
        {loaded && peers.length < 2 && peers.every((peer) => peer.lifecycle === 'ACTIVE') ? (
          <TwButton variant="primary" onPress={() => router.push('/onboarding/role')}>Pair another device</TwButton>
        ) : null}
        {loaded && peers.length === 2 ? <Text style={{ color: theme.ink3, fontFamily: theme.fonts.ui }}>You can pair up to two devices.</Text> : null}
      </ScrollView>
    </SafeAreaView>
  );
}
