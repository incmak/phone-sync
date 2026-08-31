import { NativeModule, requireNativeModule } from 'expo';
import type { PermissionResponse } from 'expo';
import type {
  OfflinePairingStatus,
  OfflinePairingStatusEvent,
  RouteStatus,
  RouteStatusEvent,
  NotificationDetail,
  MirrorActionInvocationResult,
} from './TwinotifyCore.types';

export type {
  DeliveryRoute,
  DeliveryRoutePhase,
  PeerEvidence,
  DeliveryReason,
  UserContentKind,
  RouteStatus,
  RouteStatusEvent,
  OfflinePairingErrorCode,
  OfflinePairingPhase,
  OfflinePairingRole,
  OfflinePairingStatus,
  OfflinePairingStatusEvent,
  NotificationDetail,
  NotificationDetailAction,
  NotificationDetailState,
  NotificationActionInvocationState,
  MirrorActionInvocationResult,
  MirrorActionInvocationStatus,
} from './TwinotifyCore.types';

export type KeyPair = { encPubkey: string; signPubkey: string };
export type EncryptResult = { ciphertext: string; nonce: string };
export type MetricsSnapshot = { mirroredToday: number; blockedToday: number; latencyMs: number };
export type RecentActivityItem = {
  appName: string | null;
  artworkDataUri: string | null;
  direction: 'SENT' | 'RECEIVED';
  kind: 'NOTIFICATION' | 'DISMISSAL' | 'CALL';
  status: 'QUEUED' | 'APPLIED' | 'DELIVERED' | 'DISMISSED' | 'EXPIRED' | 'FAILED';
  route: 'LAN' | 'RELAY' | null;
  occurredAt: number;
};

export type SyncState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'LEGACY_ONLINE_ONLY' | 'OFFLINE_QUEUED';

export interface SyncStatus {
  state: SyncState;
  queuedCount: number;
  totalActiveCount?: number;
  totalActiveBytes?: number;
  callCaptureEnabled?: boolean;
  callCaptureDisabledReason?: string | null;
  callCaptureHealthCode?: string | null;
  callNotificationMode?: 'call_style_deferred_no_controls' | null;
  lastCallEventAt?: number | null;
}

export interface PairStatus {
  paired: boolean;
  peerDeviceId?: string;
  peerEncPubkey?: string;
  peerSignPubkey?: string;
  peerDisplayName?: string;
}

export interface PeerHelloPayload {
  v: 1;
  type: 'peer.hello';
  pair_token: string;
  device_id: string;
  enc_pubkey: string;
  sign_pubkey: string;
  display_name?: string;
}

declare class TwinotifyCoreModuleType extends NativeModule<{
  onSyncStatus: (evt: SyncStatus) => void;
  onPeerUnpair: () => void;
  onOfflinePairingStatus: OfflinePairingStatusEvent;
  onRouteStatus: RouteStatusEvent;
}> {
  getDeviceId(): Promise<string>;
  getPublicKeys(): Promise<KeyPair>;
  getDeviceDisplayName(): Promise<string>;
  startOfflinePairing(displayName: string): Promise<string>;
  joinOfflinePairing(qrJson: string, displayName: string): Promise<void>;
  confirmOfflinePairing(sessionId: string): Promise<void>;
  cancelOfflinePairing(sessionId: string): Promise<void>;
  getOfflinePairingStatus(): Promise<OfflinePairingStatus>;
  // Updated signature: requires displayName
  startPairInitiator(relayUrl: string, displayName: string): Promise<string>;
  sendPeerHello(relayUrl: string, pairToken: string, displayName: string): Promise<void>;
  awaitPeerHello(relayUrl: string, pairToken: string): Promise<string>; // raw JSON text
  sendConfirmationSig(relayUrl: string, pairToken: string, sigB64: string): Promise<void>;
  computeFingerprint(encPubkeyB64: string, signPubkeyB64: string): Promise<string>;
  deviceASignConfirmation(pairToken: string, bEncB64: string, bSignB64: string): Promise<string>;
  // Backward-compat: waits for pair.sig on role=B, returns base64 sig
  awaitPairSig(relayUrl: string, pairToken: string): Promise<string>;
  deviceBCompletePairing(
    relayUrl: string,
    pairToken: string,
    initiatorEncPubkeyB64: string,
    initiatorSignPubkeyB64: string,
    confirmationSigB64: string,
  ): Promise<void>;
  storePeerPubkeys(encB64: string, signB64: string, peerDeviceId: string, peerDisplayName: string): Promise<void>;
  mintAuthJwt(): Promise<string>;
  encryptToPeer(plaintextB64: string): Promise<EncryptResult>;
  decryptFromPeer(ciphertextB64: string, nonceB64: string): Promise<string>;
  unpair(): Promise<void>;
  ping(relayUrl: string, authed: boolean): Promise<string>;
  // Sync service lifecycle
  startSyncService(relayUrl: string): Promise<void>;
  /** Start a peer that pairs and delivers over the LAN and has no relay at all. */
  startLanOnlySyncService(): Promise<void>;
  stopSyncService(): Promise<void>;
  getCallCaptureEnabled(): Promise<boolean>;
  setCallCaptureEnabled(enabled: boolean): Promise<boolean>;
  getCallStatePermissionAsync(): Promise<PermissionResponse>;
  requestCallStatePermissionAsync(): Promise<PermissionResponse>;
  getSyncStatus(): Promise<SyncStatus>;
  getRouteStatus(): Promise<RouteStatus>;
  /** Try a direct LAN route before the relay. */
  setPreferLan(preferLan: boolean): Promise<void>;
  getPreferLan(): Promise<boolean>;
  /** Reconnect now instead of waiting out the current backoff. */
  retryRoute(): Promise<void>;
  getPairStatus(): Promise<PairStatus>;
  // Permission helpers
  isNotificationListenerGranted(): Promise<boolean>;
  openListenerSettings(): Promise<void>;
  isPostNotificationsGranted(): Promise<boolean>;
  openAppSettings(): Promise<void>;
  // User-controlled app denylist
  getUserDenylist(): Promise<string[]>;
  addToDenylist(pkg: string): Promise<void>;
  removeFromDenylist(pkg: string): Promise<void>;
  // Home screen metrics
  getMetrics(): Promise<MetricsSnapshot>;
  getRecentActivity(limit: number): Promise<RecentActivityItem[]>;
  getNotificationDetail(detailId: string): Promise<NotificationDetail | null>;
  invokeMirrorAction(
    detailId: string,
    actionId: string,
    replyText?: string | null,
  ): Promise<MirrorActionInvocationResult>;
  canLaunchSourceApp(packageName: string): Promise<boolean>;
  openNotificationSourceApp(detailId: string): Promise<boolean>;
}

export default requireNativeModule<TwinotifyCoreModuleType>('TwinotifyCore');
