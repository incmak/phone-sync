import { NativeModule, requireNativeModule } from 'expo-modules-core';

export type KeyPair = { encPubkey: string; signPubkey: string };
export type EncryptResult = { ciphertext: string; nonce: string };
export type MetricsSnapshot = { mirroredToday: number; blockedToday: number; latencyMs: number };

export type SyncState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'OFFLINE_QUEUED';

export interface SyncStatus {
  state: SyncState;
  queuedCount: number;
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
}> {
  getDeviceId(): Promise<string>;
  getPublicKeys(): Promise<KeyPair>;
  getDeviceDisplayName(): Promise<string>;
  // Updated signature: requires displayName
  startPairInitiator(relayUrl: string, displayName: string): Promise<string>;
  sendPeerHello(relayUrl: string, pairToken: string, displayName: string): Promise<void>;
  awaitPeerHello(relayUrl: string, pairToken: string): Promise<string>; // raw JSON text
  sendConfirmationSig(relayUrl: string, pairToken: string, sigB64: string): Promise<void>;
  computeFingerprint(encPubkeyB64: string, signPubkeyB64: string): Promise<string>;
  deviceASignConfirmation(pairToken: string, bEncB64: string, bSignB64: string): Promise<string>;
  // Backward-compat: waits for pair.sig on role=B, returns base64 sig
  awaitPairSig(relayUrl: string, pairToken: string): Promise<string>;
  deviceBCompletePairing(relayUrl: string, pairToken: string, confirmationSigB64: string): Promise<void>;
  storePeerPubkeys(encB64: string, signB64: string, peerDeviceId: string, peerDisplayName: string): Promise<void>;
  mintAuthJwt(): Promise<string>;
  encryptToPeer(plaintextB64: string): Promise<EncryptResult>;
  decryptFromPeer(ciphertextB64: string, nonceB64: string): Promise<string>;
  unpair(): Promise<void>;
  ping(relayUrl: string, authed: boolean): Promise<string>;
  // Sync service lifecycle
  startSyncService(relayUrl: string): Promise<void>;
  stopSyncService(): Promise<void>;
  getSyncStatus(): Promise<SyncStatus>;
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
}

export default requireNativeModule<TwinotifyCoreModuleType>('TwinotifyCore');
