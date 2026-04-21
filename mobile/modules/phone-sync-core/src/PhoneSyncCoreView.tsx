import { requireNativeView } from 'expo';
import * as React from 'react';

import { PhoneSyncCoreViewProps } from './PhoneSyncCore.types';

const NativeView: React.ComponentType<PhoneSyncCoreViewProps> =
  requireNativeView('PhoneSyncCore');

export default function PhoneSyncCoreView(props: PhoneSyncCoreViewProps) {
  return <NativeView {...props} />;
}
