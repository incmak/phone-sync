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
  addListener(event: 'onPeerUnpair', handler: () => void): { remove: () => void };
  // User-controlled app denylist
  getUserDenylist(): Promise<string[]>;
  addToDenylist(pkg: string): Promise<void>;
  removeFromDenylist(pkg: string): Promise<void>;
}
