import React, { useRef, useState } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { router } from 'expo-router';
import { useTheme, TwButton } from '../../components';

interface QRPayload {
  relayUrl: string;
  deviceId: string;
  encPubkey: string;
  signPubkey: string;
  pairToken: string;
}

function parsePayload(data: string): QRPayload | null {
  try {
    const parsed = JSON.parse(data) as unknown;
    if (
      parsed !== null &&
      typeof parsed === 'object' &&
      'pairToken' in parsed &&
      'encPubkey' in parsed &&
      'signPubkey' in parsed &&
      'relayUrl' in parsed &&
      'deviceId' in parsed
    ) {
      return parsed as QRPayload;
    }
    return null;
  } catch {
    return null;
  }
}

export default function PairScanScreen() {
  const theme = useTheme();
  const [permission, requestPermission] = useCameraPermissions();
  const [scanned, setScanned] = useState(false);
  const scannedRef = useRef(false);

  function onBarcode({ data }: { data: string }) {
    if (scannedRef.current) return;
    const payload = parsePayload(data);
    if (!payload) return; // not our QR — keep scanning
    scannedRef.current = true;
    setScanned(true);
    router.push({
      pathname: '/pair/fingerprint',
      params: {
        role: 'B',
        relayUrl: payload.relayUrl,
        pairToken: payload.pairToken,
        peerDeviceId: payload.deviceId,
        peerEncB64: payload.encPubkey,
        peerSignB64: payload.signPubkey,
      },
    });
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
        </View>
      </SafeAreaView>
    </View>
  );
}
