export type SyncState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'LEGACY_ONLINE_ONLY'
  | 'OFFLINE_QUEUED';

export interface SyncStatus {
  state: SyncState;
  queuedCount: number;
  enabled?: boolean;
  service?: 'stopped' | 'connecting' | 'connected' | 'degraded';
  transport?: 'offline' | 'connecting' | 'online';
  protocolFloor?: number;
  queuedBytes?: number;
  totalActiveCount?: number;
  totalActiveBytes?: number;
  listenerConnected?: boolean;
  listenerPermission?: boolean;
  postPermission?: boolean;
  lastReceiptAt?: number | null;
  lastErrorCode?: string | null;
  callCaptureEnabled?: boolean;
  callCaptureDisabledReason?: string | null;
  callCaptureHealthCode?: string | null;
  callNotificationMode?: 'call_style_deferred_no_controls' | 'call_style_conditional_controls' | null;
  lastCallEventAt?: number | null;
}

export interface KeyPair {
  encPubkey: string;
  signPubkey: string;
}

export interface PairStatus {
  paired: boolean;
  peerDeviceId?: string;
  peerEncPubkey?: string;
  peerSignPubkey?: string;
  peerDisplayName?: string;
}

export interface PairPayloadJson {
  relayUrl: string;
  deviceId: string;
  encPubkey: string;
  signPubkey: string;
  pairToken: string;
  displayName?: string;
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

export interface MetricsSnapshot {
  mirroredToday: number;
  blockedToday: number;
  latencyMs: number | null;
}

export interface TwinotifyCoreAPI {
  getDeviceId(): Promise<string>;
  getPublicKeys(): Promise<KeyPair>;
  getDeviceDisplayName(): Promise<string>;
  // Updated signature: requires displayName
  startPairInitiator(relayUrl: string, displayName: string): Promise<string>;
  sendPeerHello(relayUrl: string, pairToken: string, displayName: string): Promise<void>;
  awaitPeerHello(relayUrl: string, pairToken: string): Promise<string>; // raw JSON text
  sendConfirmationSig(relayUrl: string, pairToken: string, sigB64: string): Promise<void>;
  computeFingerprint(encB64: string, signB64: string): Promise<string>;
  deviceASignConfirmation(pairToken: string, bEncB64: string, bSignB64: string): Promise<string>;
  deviceBCompletePairing(relayUrl: string, pairToken: string, sigB64: string): Promise<void>;
  storePeerPubkeys(encB64: string, signB64: string, peerDeviceId: string, peerDisplayName: string): Promise<void>;
  unpair(): Promise<void>;
  startSyncService(relayUrl: string): Promise<void>;
  startLanOnlySyncService(): Promise<void>;
  stopSyncService(): Promise<void>;
  getCallCaptureEnabled(): Promise<boolean>;
  setCallCaptureEnabled(enabled: boolean): Promise<boolean>;
  getCallControlsEnabled(): Promise<boolean>;
  setCallControlsEnabled(enabled: boolean): Promise<boolean>;
  getCallStatePermissionAsync(): Promise<import('expo-modules-core').PermissionResponse>;
  requestCallStatePermissionAsync(): Promise<import('expo-modules-core').PermissionResponse>;
  getBluetoothRoutePermissionAsync(): Promise<import('expo-modules-core').PermissionResponse>;
  requestBluetoothRoutePermissionAsync(): Promise<import('expo-modules-core').PermissionResponse>;
  startBluetoothAssociation(): Promise<{ associated: boolean }>;
  getBluetoothRouteSettings(): Promise<{ associated: boolean; enabled: boolean }>;
  getBluetoothRouteEnabled(): Promise<boolean>;
  setBluetoothRouteEnabled(enabled: boolean): Promise<boolean>;
  removeBluetoothAssociation(): Promise<void>;
  getSyncStatus(): Promise<SyncStatus>;
  getPairStatus(): Promise<PairStatus>;
  getPreferLan(): Promise<boolean>;
  setPreferLan(preferLan: boolean): Promise<void>;
  isNotificationListenerGranted(): Promise<boolean>;
  openListenerSettings(): Promise<void>;
  isPostNotificationsGranted(): Promise<boolean>;
  openAppSettings(): Promise<void>;
  addListener(event: 'onSyncStatus', handler: (evt: SyncStatus) => void): { remove: () => void };
  addListener(event: 'onPeerUnpair', handler: () => void): { remove: () => void };
  // User-controlled app denylist
  getUserDenylist(): Promise<string[]>;
  addToDenylist(pkg: string): Promise<void>;
  removeFromDenylist(pkg: string): Promise<void>;
  // Home screen metrics
  getMetrics(): Promise<MetricsSnapshot>;
}
