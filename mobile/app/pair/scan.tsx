import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { router, useLocalSearchParams } from 'expo-router';

import { TwButton, useTheme } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { OnboardingState, type PairingMode } from '../../state/onboardingState';

interface RelayQRPayload {
  relayUrl: string;
  deviceId: string;
  encPubkey: string;
  signPubkey: string;
  pairToken: string;
  displayName?: string;
}

interface RawRelayQR {
  relay_url?: string;
  device_id?: string;
  enc_pubkey?: string;
  sign_pubkey?: string;
  pair_token?: string;
  display_name?: string;
}

type JoinOwnership = 'scanner' | 'abandoned' | 'handed_off';

function parseRelayPayload(data: string): RelayQRPayload | null {
  try {
    const raw = JSON.parse(data) as RawRelayQR;
    if (!raw || typeof raw !== 'object') return null;
    if (!raw.pair_token || !raw.enc_pubkey || !raw.sign_pubkey || !raw.relay_url || !raw.device_id) return null;
    return {
      relayUrl: raw.relay_url,
      deviceId: raw.device_id,
      encPubkey: raw.enc_pubkey,
      signPubkey: raw.sign_pubkey,
      pairToken: raw.pair_token,
      displayName: raw.display_name,
    };
  } catch {
    return null;
  }
}

export default function PairScanScreen() {
  const theme = useTheme();
  const params = useLocalSearchParams<{ mode?: string }>();
  const [permission, requestPermission] = useCameraPermissions();
  const [mode, setMode] = useState<PairingMode | null>(
    params.mode === 'nearby' || params.mode === 'relay' ? params.mode : null,
  );
  const [scanned, setScanned] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const scannedRef = useRef(false);
  const mountedRef = useRef(true);
  const ownershipRef = useRef<JoinOwnership>('scanner');
  const cancelledSessionIdsRef = useRef(new Set<string>());
  const modeRef = useRef<PairingMode | null>(mode);
  const scannerWasAbandoned = useCallback(
    () => !mountedRef.current || ownershipRef.current === 'abandoned',
    [],
  );

  const cancelCurrentNearbySession = useCallback(async () => {
    let sessionId: string | null = null;
    try {
      const current = await TwinotifyCoreModule.getOfflinePairingStatus();
      if (!current.sessionId || cancelledSessionIdsRef.current.has(current.sessionId)) return;
      sessionId = current.sessionId;
      cancelledSessionIdsRef.current.add(sessionId);
      await TwinotifyCoreModule.cancelOfflinePairing(sessionId);
    } catch {
      if (sessionId) cancelledSessionIdsRef.current.delete(sessionId);
    }
  }, []);

  const leaveScanner = useCallback(async () => {
    ownershipRef.current = 'abandoned';
    if (mode === 'nearby') await cancelCurrentNearbySession();
    if (mountedRef.current) router.back();
  }, [cancelCurrentNearbySession, mode]);

  useEffect(() => {
    modeRef.current = mode;
  }, [mode]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (ownershipRef.current !== 'handed_off') {
        ownershipRef.current = 'abandoned';
        if (modeRef.current === 'nearby') void cancelCurrentNearbySession();
      }
    };
  }, [cancelCurrentNearbySession]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      void leaveScanner();
      return true;
    });
    return () => subscription.remove();
  }, [leaveScanner]);

  useEffect(() => {
    if (mode) return;
    void OnboardingState.getPairingMode().then((stored) => {
      if (mountedRef.current) setMode(stored ?? 'relay');
    });
  }, [mode]);

  async function onBarcode({ data }: { data: string }) {
    if (scannedRef.current || !mode) return;

    if (mode === 'nearby') {
      scannedRef.current = true;
      setScanned(true);
      setErrorMsg(null);
      try {
        const displayName = await TwinotifyCoreModule.getDeviceDisplayName();
        if (scannerWasAbandoned()) return;
        await TwinotifyCoreModule.joinOfflinePairing(data, displayName);
        if (scannerWasAbandoned()) {
          await cancelCurrentNearbySession();
          return;
        }
        ownershipRef.current = 'handed_off';
        router.replace('/pair/nearby');
      } catch {
        if (ownershipRef.current === 'handed_off') ownershipRef.current = 'scanner';
        if (scannerWasAbandoned()) {
          await cancelCurrentNearbySession();
          return;
        }
        scannedRef.current = false;
        setScanned(false);
        setErrorMsg('This is not a valid nearby pairing code. Create a new code on the other phone and scan again.');
      }
      return;
    }

    const payload = parseRelayPayload(data);
    if (!payload) return;
    scannedRef.current = true;
    setScanned(true);
    try {
      const displayName = await TwinotifyCoreModule.getDeviceDisplayName();
      if (scannerWasAbandoned()) return;
      await TwinotifyCoreModule.sendPeerHello(payload.relayUrl, payload.pairToken, displayName);
      if (scannerWasAbandoned()) return;
      router.push({
        pathname: '/pair/fingerprint',
        params: {
          role: 'B',
          relayUrl: payload.relayUrl,
          pairToken: payload.pairToken,
          peerDeviceId: payload.deviceId,
          peerEncB64: payload.encPubkey,
          peerSignB64: payload.signPubkey,
          peerDisplayName: payload.displayName ?? '',
        },
      });
    } catch (error: unknown) {
      if (scannerWasAbandoned()) return;
      scannedRef.current = false;
      setScanned(false);
      setErrorMsg(error instanceof Error ? error.message : 'Could not contact the relay.');
    }
  }

  if (!mode || !permission) {
    return (
      <SafeAreaView edges={['top', 'bottom']} style={[styles.preparing, { backgroundColor: theme.bg }]}>
        <Text accessibilityLiveRegion="polite" style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          Preparing the scanner…
        </Text>
      </SafeAreaView>
    );
  }

  if (!permission.granted) {
    return (
      <SafeAreaView edges={['top', 'bottom']} style={[styles.permission, { backgroundColor: theme.bg }]}>
        <Text style={[styles.title, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>Camera permission is off</Text>
        <Text style={[styles.body, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          Allow camera access so Twinotify can scan the pairing code. The image is not saved.
        </Text>
        <View style={styles.permissionActions}>
          <TwButton variant="primary" fullWidth onPress={() => { void requestPermission(); }}>Allow camera</TwButton>
          <TwButton variant="ghost" fullWidth onPress={() => { void leaveScanner(); }}>Go back</TwButton>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <View style={styles.cameraPage}>
      <CameraView
        style={StyleSheet.absoluteFill}
        facing="back"
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={scanned ? undefined : onBarcode}
      />
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        <View style={styles.backRow}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Go back"
            onPress={() => { void leaveScanner(); }}
            style={({ pressed }) => [styles.backButton, { opacity: pressed ? 0.72 : 1 }]}
          >
            <Text style={styles.backGlyph}>‹</Text>
          </Pressable>
        </View>

        <View style={styles.viewfinderArea}>
          <View style={[styles.viewfinder, { borderColor: theme.accent }]} accessible={false} />
          <Text style={[styles.cameraCopy, { fontFamily: theme.fonts.ui }]}>
            {mode === 'nearby'
              ? 'Scan the nearby pairing code on your other phone'
              : 'Scan the relay pairing code on your other phone'}
          </Text>
          {errorMsg !== null && (
            <Text accessibilityRole="alert" style={[styles.error, { fontFamily: theme.fonts.ui }]}>
              {errorMsg}
            </Text>
          )}
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  preparing: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  permission: { flex: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 24 },
  title: { fontSize: 22, lineHeight: 28, marginBottom: 10 },
  body: { fontSize: 15, lineHeight: 22 },
  permissionActions: { gap: 8, marginTop: 28 },
  cameraPage: { flex: 1, backgroundColor: '#000000' },
  backRow: { padding: 16 },
  backButton: { width: 48, height: 48, borderRadius: 24, backgroundColor: 'rgba(0,0,0,0.58)', alignItems: 'center', justifyContent: 'center' },
  backGlyph: { color: '#ffffff', fontSize: 28, lineHeight: 32 },
  viewfinderArea: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24, paddingBottom: 48 },
  viewfinder: { width: 232, height: 232, borderRadius: 14, borderWidth: 3 },
  cameraCopy: { color: '#ffffff', marginTop: 24, fontSize: 15, lineHeight: 22, textAlign: 'center', maxWidth: 320 },
  error: { color: '#ffffff', backgroundColor: 'rgba(94, 16, 16, 0.92)', marginTop: 14, padding: 12, borderRadius: 10, fontSize: 13, lineHeight: 19, textAlign: 'center', maxWidth: 340 },
});
