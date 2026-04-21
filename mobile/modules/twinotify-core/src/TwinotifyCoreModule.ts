import { NativeModule, requireNativeModule } from 'expo-modules-core';

export type KeyPair = { encPubkey: string; signPubkey: string };
export type EncryptResult = { ciphertext: string; nonce: string };

declare class TwinotifyCoreModuleType extends NativeModule {
  getDeviceId(): Promise<string>;
  getPublicKeys(): Promise<KeyPair>;
  startPairInitiator(relayUrl: string): Promise<string>;
  computeFingerprint(encPubkeyB64: string, signPubkeyB64: string): Promise<string>;
  deviceASignConfirmation(pairToken: string, bEncB64: string, bSignB64: string): Promise<string>;
  deviceBCompletePairing(relayUrl: string, pairToken: string, confirmationSigB64: string): Promise<void>;
  storePeerPubkeys(encB64: string, signB64: string, peerDeviceId: string): Promise<void>;
  mintAuthJwt(): Promise<string>;
  encryptToPeer(plaintextB64: string): Promise<EncryptResult>;
  decryptFromPeer(ciphertextB64: string, nonceB64: string): Promise<string>;
  unpair(): Promise<void>;
  ping(relayUrl: string, authed: boolean): Promise<string>;
}

export default requireNativeModule<TwinotifyCoreModuleType>('TwinotifyCore');
