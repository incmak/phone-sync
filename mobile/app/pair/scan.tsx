import React, { useRef, useState } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { router } from 'expo-router';
import { useTheme, TwButton } from '../../components';
import TwinotifyCoreModule from '../../modules/twinotify-core/src/TwinotifyCoreModule';

interface QRPayload {
  relayUrl: string;
  deviceId: string;
  encPubkey: string;
  signPubkey: string;
  pairToken: string;
  displayName?: string;
}

// Wire format (from PairPayload.toJson on the Kotlin side) uses snake_case keys.
interface RawQR {
  relay_url?: string;
  device_id?: string;
  enc_pubkey?: string;
  sign_pubkey?: string;
  pair_token?: string;
  display_name?: string;
}

function parsePayload(data: string): QRPayload | null {
  try {
    const raw = JSON.parse(data) as RawQR;
    if (!raw || typeof raw !== 'object') return null;
    if (!raw.pair_token || !raw.enc_pubkey || !raw.sign_pubkey || !raw.relay_url || !raw.device_id) {
      return null;
    }
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
  const [permission, requestPermission] = useCameraPermissions();
  const [scanned, setScanned] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const scannedRef = useRef(false);

  async function onBarcode({ data }: { data: string }) {
    if (scannedRef.current) return;
    const payload = parsePayload(data);
    if (!payload) return; // not our QR — keep scanning
    scannedRef.current = true;
    setScanned(true);

    try {
      // Get B's display name and announce B's pubkeys to the relay
      const displayName = await TwinotifyCoreModule.getDeviceDisplayName();
      await TwinotifyCoreModule.sendPeerHello(payload.relayUrl, payload.pairToken, displayName);

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
    } catch (err: unknown) {
      // Allow retry on error
      scannedRef.current = false;
      setScanned(false);
      setErrorMsg(err instanceof Error ? err.message : 'Failed to announce to relay.');
    }
  }

  if (!permission) return null;

  if (!permission.granted) {
    return (
      <SafeAreaView
        edges={['top', 'bottom']}
        style={{ flex: 1, backgroundColor: theme.bg, padding: 20 }}
      >
        <Text style={{
          fontSize: 20,
          fontFamily: theme.fonts.uiSemi,
          color: theme.ink,
          marginBottom: 12,
        }}>
          Camera access needed
        </Text>
        <Text style={{
          fontSize: 15,
          color: theme.ink3,
          fontFamily: theme.fonts.ui,
          lineHeight: 22,
          marginBottom: 28,
        }}>
          We need camera access to scan the QR code from your other phone.
        </Text>
        <TwButton variant="primary" fullWidth onPress={() => { void requestPermission(); }}>
          Grant access
        </TwButton>
        <View style={{ marginTop: 12 }}>
          <TwButton variant="secondary" fullWidth onPress={() => router.back()}>
            Go back
          </TwButton>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: '#000' }}>
      <CameraView
        style={StyleSheet.absoluteFill}
        facing="back"
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={scanned ? undefined : onBarcode}
      />
      <SafeAreaView style={{ flex: 1 }} edges={['top', 'bottom']}>
        {/* Back button */}
        <View style={{ padding: 16 }}>
          <Pressable
            onPress={() => router.back()}
            style={{
              width: 40, height: 40,
              borderRadius: 20,
              backgroundColor: 'rgba(255,255,255,0.18)',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Text style={{ color: '#fff', fontSize: 20, lineHeight: 24 }}>‹</Text>
          </Pressable>
        </View>

        {/* Viewfinder */}
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <View style={{
            width: 240, height: 240,
            borderRadius: 14,
            borderWidth: 2.5,
            borderColor: theme.accent,
          }} />
          <Text style={{
            color: '#fff',
            marginTop: 28,
            fontSize: 15,
            fontFamily: theme.fonts.ui,
            textAlign: 'center',
            paddingHorizontal: 32,
          }}>
            Point at the QR code shown on your other phone
          </Text>
          {errorMsg !== null && (
            <Text style={{
              color: '#ff6b6b',
              marginTop: 12,
              fontSize: 13,
              fontFamily: theme.fonts.ui,
              textAlign: 'center',
              paddingHorizontal: 32,
            }}>
              {errorMsg}
            </Text>
          )}
        </View>
      </SafeAreaView>
    </View>
  );
}
