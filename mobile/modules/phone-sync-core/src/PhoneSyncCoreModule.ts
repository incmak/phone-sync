import { NativeModule, requireNativeModule } from 'expo-modules-core';

declare class PhoneSyncCoreModuleType extends NativeModule {
  ping(relayUrl: string): Promise<string>;
}

export default requireNativeModule<PhoneSyncCoreModuleType>('PhoneSyncCore');
