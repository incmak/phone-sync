import TwinotifyCoreModule, { KeyPair, EncryptResult } from './TwinotifyCoreModule';

export type { KeyPair, EncryptResult };

export async function getDeviceId(): Promise<string> {
  return await TwinotifyCoreModule.getDeviceId();
}

export async function getPublicKeys(): Promise<KeyPair> {
  return await TwinotifyCoreModule.getPublicKeys();
}

export async function startPairInitiator(relayUrl: string): Promise<string> {
  return await TwinotifyCoreModule.startPairInitiator(relayUrl);
}

export async function computeFingerprint(encB64: string, signB64: string): Promise<string> {
  return await TwinotifyCoreModule.computeFingerprint(encB64, signB64);
}

export async function deviceASignConfirmation(
  pairToken: string,
  bEncB64: string,
  bSignB64: string,
): Promise<string> {
  return await TwinotifyCoreModule.deviceASignConfirmation(pairToken, bEncB64, bSignB64);
}

export async function deviceBCompletePairing(
  relayUrl: string,
  pairToken: string,
  sigB64: string,
): Promise<void> {
  return await TwinotifyCoreModule.deviceBCompletePairing(relayUrl, pairToken, sigB64);
}

export async function storePeerPubkeys(
  encB64: string,
  signB64: string,
  peerDeviceId: string,
): Promise<void> {
  return await TwinotifyCoreModule.storePeerPubkeys(encB64, signB64, peerDeviceId);
}

export async function mintAuthJwt(): Promise<string> {
  return await TwinotifyCoreModule.mintAuthJwt();
}

export async function encryptToPeer(plaintextB64: string): Promise<EncryptResult> {
  return await TwinotifyCoreModule.encryptToPeer(plaintextB64);
}

export async function decryptFromPeer(ciphertextB64: string, nonceB64: string): Promise<string> {
  return await TwinotifyCoreModule.decryptFromPeer(ciphertextB64, nonceB64);
}

export async function unpair(): Promise<void> {
  return await TwinotifyCoreModule.unpair();
}

export async function pingRelay(relayUrl: string, authed: boolean = false): Promise<string> {
  return await TwinotifyCoreModule.ping(relayUrl, authed);
}
