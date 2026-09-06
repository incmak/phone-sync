import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert } from 'react-native';

import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';

/**
 * Bounded native codes become copy the user can act on. Anything that fails while proving the
 * other phone means the two setups did not overlap, which is the one thing a person can fix.
 */
export function bluetoothFailureCopy(code: string): [string, string] {
  if (code === 'bluetooth_association_in_progress') {
    return ['Setup already running', 'Finish the Bluetooth setup already open on this phone, then try again.'];
  }
  if (code === 'bluetooth_device_unusable') {
    return ['Choose the other phone', 'That selection cannot be used for Bluetooth sync. Nothing changed.'];
  }
  if (code === 'bluetooth_peer_not_confirmed') {
    return ['Pair the phones first', 'Bluetooth fallback needs a confirmed pair. Nothing changed.'];
  }
  if (code.startsWith('bluetooth_connect') || code.startsWith('bluetooth_handshake') ||
    code.startsWith('bluetooth_identity') || code.startsWith('bluetooth_role') ||
    code.startsWith('bluetooth_protocol') || code.startsWith('bluetooth_replayed') ||
    code.startsWith('bluetooth_signature') || code.startsWith('bluetooth_frame')) {
    return [
      'Could not confirm the other phone',
      'Start Bluetooth setup on both phones at the same time and choose each other. Nothing changed.',
    ];
  }
  return ['Bluetooth fallback unavailable', 'Nothing changed. Try again.'];
}

/**
 * The Bluetooth association flow, shared by the setup step and paired-device settings so the two
 * cannot drift. Association needs a confirmed pair and both phones inside the picker at once, so
 * every failure path says what happened and promises nothing changed.
 */
export function useBluetoothAssociation(onAssociated?: () => void | Promise<void>) {
  const [busy, setBusy] = useState(false);

  // Set after the callback exists, so turning Bluetooth on can resume the setup the user already
  // asked for instead of making them tap again.
  const associateRef = useRef<() => Promise<void>>(async () => {});

  const turnOnBluetoothAndRetry = useCallback(() => {
    void (async () => {
      let enabled = false;
      try {
        enabled = await TwinotifyCoreModule.requestBluetoothEnable();
      } catch {
        enabled = false;
      }
      if (enabled) {
        await associateRef.current();
        return;
      }
      Alert.alert('Bluetooth is still off', 'Nothing changed. Turn Bluetooth on to set up the fallback.');
    })();
  }, []);

  const associate = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    try {
      const permission = await TwinotifyCoreModule.requestBluetoothRoutePermissionAsync();
      if (!permission.granted) {
        if (permission.canAskAgain) {
          Alert.alert(
            'Nearby devices permission needed',
            'Bluetooth fallback needs the Nearby devices permission. Nothing changed.',
          );
        } else {
          Alert.alert(
            'Nearby devices permission needed',
            'Allow Nearby devices in Android settings to set up Bluetooth fallback. Nothing changed.',
            [
              { text: 'Not now', style: 'cancel' },
              {
                text: 'Open settings',
                onPress: () => { void TwinotifyCoreModule.openAppSettings().catch(() => {}); },
              },
            ],
          );
        }
        return;
      }
      // The picker can be cancelled; the durable settings stay authoritative either way, so a
      // cancelled association returns quietly.
      await TwinotifyCoreModule.startBluetoothAssociation();
      await onAssociated?.();
    } catch (error) {
      // Bounded native failure codes arrive as the rejection message.
      const code = (error as { message?: string } | null)?.message ?? '';
      if (code === 'bluetooth_unavailable') {
        Alert.alert(
          'Turn on Bluetooth',
          'Bluetooth is off on this phone, so the fallback cannot be set up. Nothing changed.',
          [
            { text: 'Not now', style: 'cancel' },
            { text: 'Turn on', onPress: turnOnBluetoothAndRetry },
          ],
        );
      } else {
        const [title, body] = bluetoothFailureCopy(code);
        Alert.alert(title, body);
      }
    } finally {
      setBusy(false);
    }
  }, [busy, onAssociated, turnOnBluetoothAndRetry]);

  useEffect(() => {
    associateRef.current = associate;
  }, [associate]);

  return { associate, busy };
}
