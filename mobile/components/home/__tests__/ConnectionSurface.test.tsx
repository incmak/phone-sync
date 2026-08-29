import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';

import { ConnectionSurface } from '../ConnectionSurface';

test('keeps route truth, switch, trace, and peer action in one surface', () => {
  const toggle = jest.fn();
  const openPeer = jest.fn();
  const screen = render(
    <ConnectionSurface
      route={{ state: 'direct', label: 'Direct on Wi-Fi', explanation: 'Phones connected.', queuedCount: 0 }}
      enabled
      onToggle={toggle}
      peerName="POCO F1"
      peerReachable
      onOpenPeer={openPeer}
      traceWidth={260}
    />,
  );

  expect(screen.getByTestId('connection-surface')).toBeTruthy();
  expect(screen.UNSAFE_getByProps({ testID: 'handoff-trace-direct' })).toBeTruthy();
  fireEvent.press(screen.getByRole('switch', { name: 'Mirror notifications' }));
  fireEvent.press(screen.getByRole('button', { name: 'Open paired phone' }));
  expect(toggle).toHaveBeenCalledWith(false);
  expect(openPeer).toHaveBeenCalledTimes(1);
});
