import { registerWebModule, NativeModule } from 'expo';

import { ChangeEventPayload } from './TwinotifyCore.types';

type TwinotifyCoreModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
}

class TwinotifyCoreModule extends NativeModule<TwinotifyCoreModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
};

export default registerWebModule(TwinotifyCoreModule, 'TwinotifyCore');
