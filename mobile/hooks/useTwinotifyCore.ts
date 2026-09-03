import * as Notifications from 'expo-notifications';
import TwinotifyCoreModule from '../modules/twinotify-core/src/TwinotifyCoreModule';
import type {
  SyncStatus,
  PairStatus,
  KeyPair,
  MetricsSnapshot,
  NotificationDetail,
  MirrorActionInvocationResult,
  BluetoothRouteSettings,
} from '../modules/twinotify-core/src/TwinotifyCoreModule';

export function useTwinotifyCore() {
  return {
    getDeviceId: (): Promise<string> =>
      TwinotifyCoreModule.getDeviceId(),
    getPublicKeys: (): Promise<KeyPair> =>
      TwinotifyCoreModule.getPublicKeys(),
    getDeviceDisplayName: (): Promise<string> =>
      TwinotifyCoreModule.getDeviceDisplayName(),
    startPairInitiator: (relayUrl: string, displayName: string): Promise<string> =>
      TwinotifyCoreModule.startPairInitiator(relayUrl, displayName),
    sendPeerHello: (relayUrl: string, pairToken: string, displayName: string): Promise<void> =>
      TwinotifyCoreModule.sendPeerHello(relayUrl, pairToken, displayName),
    awaitPeerHello: (relayUrl: string, pairToken: string): Promise<string> =>
      TwinotifyCoreModule.awaitPeerHello(relayUrl, pairToken),
    sendConfirmationSig: (relayUrl: string, pairToken: string, sigB64: string): Promise<void> =>
      TwinotifyCoreModule.sendConfirmationSig(relayUrl, pairToken, sigB64),
    computeFingerprint: (encB64: string, signB64: string): Promise<string> =>
      TwinotifyCoreModule.computeFingerprint(encB64, signB64),
    deviceASignConfirmation: (pairToken: string, bEncB64: string, bSignB64: string): Promise<string> =>
      TwinotifyCoreModule.deviceASignConfirmation(pairToken, bEncB64, bSignB64),
    awaitPairSig: (relayUrl: string, pairToken: string): Promise<string> =>
      TwinotifyCoreModule.awaitPairSig(relayUrl, pairToken),
    deviceBCompletePairing: (
      relayUrl: string,
      pairToken: string,
      initiatorEncPubkeyB64: string,
      initiatorSignPubkeyB64: string,
      sigB64: string,
    ): Promise<void> => TwinotifyCoreModule.deviceBCompletePairing(
      relayUrl,
      pairToken,
      initiatorEncPubkeyB64,
      initiatorSignPubkeyB64,
      sigB64,
    ),
    storePeerPubkeys: (encB64: string, signB64: string, peerDeviceId: string, peerDisplayName: string = ''): Promise<void> =>
      TwinotifyCoreModule.storePeerPubkeys(encB64, signB64, peerDeviceId, peerDisplayName),
    unpair: (): Promise<void> =>
      TwinotifyCoreModule.unpair(),
    startSyncService: (relayUrl: string): Promise<void> =>
      TwinotifyCoreModule.startSyncService(relayUrl),
    stopSyncService: (): Promise<void> =>
      TwinotifyCoreModule.stopSyncService(),
    getCallCaptureEnabled: (): Promise<boolean> =>
      TwinotifyCoreModule.getCallCaptureEnabled(),
    setCallCaptureEnabled: (enabled: boolean): Promise<boolean> =>
      TwinotifyCoreModule.setCallCaptureEnabled(enabled),
    getCallControlsEnabled: (): Promise<boolean> =>
      TwinotifyCoreModule.getCallControlsEnabled(),
    setCallControlsEnabled: (enabled: boolean): Promise<boolean> =>
      TwinotifyCoreModule.setCallControlsEnabled(enabled),
    getCallStatePermissionAsync: () =>
      TwinotifyCoreModule.getCallStatePermissionAsync(),
    requestCallStatePermissionAsync: () =>
      TwinotifyCoreModule.requestCallStatePermissionAsync(),
    getBluetoothRoutePermissionAsync: () =>
      TwinotifyCoreModule.getBluetoothRoutePermissionAsync(),
    requestBluetoothRoutePermissionAsync: () =>
      TwinotifyCoreModule.requestBluetoothRoutePermissionAsync(),
    startBluetoothAssociation: (): Promise<{ associated: boolean }> =>
      TwinotifyCoreModule.startBluetoothAssociation(),
    getBluetoothRouteSettings: (): Promise<BluetoothRouteSettings> =>
      TwinotifyCoreModule.getBluetoothRouteSettings(),
    getBluetoothRouteEnabled: (): Promise<boolean> =>
      TwinotifyCoreModule.getBluetoothRouteEnabled(),
    setBluetoothRouteEnabled: (enabled: boolean): Promise<boolean> =>
      TwinotifyCoreModule.setBluetoothRouteEnabled(enabled),
    removeBluetoothAssociation: (): Promise<void> =>
      TwinotifyCoreModule.removeBluetoothAssociation(),
    getSyncStatus: (): Promise<SyncStatus> =>
      TwinotifyCoreModule.getSyncStatus(),
    getPairStatus: (): Promise<PairStatus> =>
      TwinotifyCoreModule.getPairStatus(),
    isNotificationListenerGranted: (): Promise<boolean> =>
      TwinotifyCoreModule.isNotificationListenerGranted(),
    openListenerSettings: (): Promise<void> =>
      TwinotifyCoreModule.openListenerSettings(),
    isPostNotificationsGranted: (): Promise<boolean> =>
      TwinotifyCoreModule.isPostNotificationsGranted(),
    openAppSettings: (): Promise<void> =>
      TwinotifyCoreModule.openAppSettings(),
    requestPostNotifications: async (): Promise<boolean> => {
      const res = await Notifications.requestPermissionsAsync();
      return res.granted;
    },
    getUserDenylist: (): Promise<string[]> =>
      TwinotifyCoreModule.getUserDenylist(),
    addToDenylist: (pkg: string): Promise<void> =>
      TwinotifyCoreModule.addToDenylist(pkg),
    removeFromDenylist: (pkg: string): Promise<void> =>
      TwinotifyCoreModule.removeFromDenylist(pkg),
    getMetrics: (): Promise<MetricsSnapshot> =>
      TwinotifyCoreModule.getMetrics(),
    getNotificationDetail: (detailId: string): Promise<NotificationDetail | null> =>
      TwinotifyCoreModule.getNotificationDetail(detailId),
    invokeMirrorAction: (
      detailId: string,
      actionId: string,
      replyText: string | null = null,
    ): Promise<MirrorActionInvocationResult> =>
      TwinotifyCoreModule.invokeMirrorAction(detailId, actionId, replyText),
    canLaunchSourceApp: (packageName: string): Promise<boolean> =>
      TwinotifyCoreModule.canLaunchSourceApp(packageName),
    openNotificationSourceApp: (detailId: string): Promise<boolean> =>
      TwinotifyCoreModule.openNotificationSourceApp(detailId),
  };
}
