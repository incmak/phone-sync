import { registerWebModule, NativeModule } from 'expo';

import { ChangeEventPayload } from './PhoneSyncCore.types';

type PhoneSyncCoreModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
}

class PhoneSyncCoreModule extends NativeModule<PhoneSyncCoreModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
};

export default registerWebModule(PhoneSyncCoreModule, 'PhoneSyncCoreModule');
