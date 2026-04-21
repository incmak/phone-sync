export type SyncState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'OFFLINE_QUEUED';

export interface SyncStatus {
  state: SyncState;
  queuedCount: number;
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
}

export interface PairPayloadJson {
  relayUrl: string;
  deviceId: string;
  encPubkey: string;
  signPubkey: string;
  pairToken: string;
}

export interface TwinotifyCoreAPI {
  getDeviceId(): Promise<string>;
  getPublicKeys(): Promise<KeyPair>;
  startPairInitiator(relayUrl: string): Promise<string>;
  computeFingerprint(encB64: string, signB64: string): Promise<string>;
  deviceASignConfirmation(pairToken: string, bEncB64: string, bSignB64: string): Promise<string>;
  deviceBCompletePairing(relayUrl: string, pairToken: string, sigB64: string): Promise<void>;
  storePeerPubkeys(encB64: string, signB64: string, peerDeviceId: string): Promise<void>;
  unpair(): Promise<void>;
  startSyncService(relayUrl: string): Promise<void>;
  stopSyncService(): Promise<void>;
  getSyncStatus(): Promise<SyncStatus>;
  getPairStatus(): Promise<PairStatus>;
  isNotificationListenerGranted(): Promise<boolean>;
  openListenerSettings(): Promise<void>;
  isPostNotificationsGranted(): Promise<boolean>;
  openAppSettings(): Promise<void>;
  addListener(event: 'onSyncStatus', handler: (evt: SyncStatus) => void): { remove: () => void };
}
