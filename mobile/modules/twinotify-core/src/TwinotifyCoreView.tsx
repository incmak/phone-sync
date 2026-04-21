import { requireNativeView } from 'expo';
import * as React from 'react';

import { TwinotifyCoreViewProps } from './TwinotifyCore.types';

const NativeView: React.ComponentType<TwinotifyCoreViewProps> =
  requireNativeView('TwinotifyCore');

export default function TwinotifyCoreView(props: TwinotifyCoreViewProps) {
  return <NativeView {...props} />;
}
