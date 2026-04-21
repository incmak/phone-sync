import * as React from 'react';

import { PhoneSyncCoreViewProps } from './PhoneSyncCore.types';

export default function PhoneSyncCoreView(props: PhoneSyncCoreViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
