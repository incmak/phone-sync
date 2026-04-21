import * as React from 'react';

import { TwinotifyCoreViewProps } from './TwinotifyCore.types';

export default function TwinotifyCoreView(props: TwinotifyCoreViewProps) {
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
