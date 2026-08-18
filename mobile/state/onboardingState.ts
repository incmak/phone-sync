import AsyncStorage from '@react-native-async-storage/async-storage';

const KEYS = {
  role: 'twinotify_onboarding_role',
  pairingMode: 'twinotify_onboarding_pairing_mode',
  relayUrl: 'twinotify_onboarding_relay_url',
  complete: 'twinotify_onboarding_complete',
} as const;

export type Role = 'A' | 'B';
export type PairingMode = 'nearby' | 'relay';

export const OnboardingState = {
  setRole: (r: Role) => AsyncStorage.setItem(KEYS.role, r),
  getRole: async (): Promise<Role | null> =>
    (await AsyncStorage.getItem(KEYS.role)) as Role | null,
  setPairingMode: (mode: PairingMode) => AsyncStorage.setItem(KEYS.pairingMode, mode),
  getPairingMode: async (): Promise<PairingMode | null> =>
    (await AsyncStorage.getItem(KEYS.pairingMode)) as PairingMode | null,
  setRelayUrl: (u: string) => AsyncStorage.setItem(KEYS.relayUrl, u),
  getRelayUrl: () => AsyncStorage.getItem(KEYS.relayUrl),
  markComplete: () => AsyncStorage.setItem(KEYS.complete, 'true'),
  isComplete: async (): Promise<boolean> =>
    (await AsyncStorage.getItem(KEYS.complete)) === 'true',
  reset: async () => {
    await AsyncStorage.multiRemove([KEYS.role, KEYS.pairingMode, KEYS.relayUrl, KEYS.complete]);
  },
};
