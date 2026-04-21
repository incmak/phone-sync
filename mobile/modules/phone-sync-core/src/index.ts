import PhoneSyncCoreModule from './PhoneSyncCoreModule';

export async function pingRelay(relayUrl: string): Promise<string> {
  return await PhoneSyncCoreModule.ping(relayUrl);
}
