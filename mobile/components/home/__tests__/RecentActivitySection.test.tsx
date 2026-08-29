import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';

import { RecentActivitySection } from '../RecentActivitySection';

const safeItem = {
  appName: 'Messages',
  artworkDataUri: null,
  direction: 'RECEIVED' as const,
  kind: 'NOTIFICATION' as const,
  status: 'APPLIED' as const,
  route: 'LAN' as const,
  occurredAt: 2_000,
};

describe('RecentActivitySection', () => {
  it('distinguishes empty and first-load failure states', () => {
    const screen = render(<RecentActivitySection state={{ kind: 'empty' }} peerName="POCO F1" now={3_000} />);
    expect(screen.getByText('No activity yet')).toBeTruthy();
    expect(screen.getByText(/first mirrored notification/i)).toBeTruthy();

    const retry = jest.fn();
    screen.rerender(<RecentActivitySection state={{ kind: 'error', retry }} peerName="POCO F1" now={3_000} />);
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('renders only privacy-safe typed fields from populated activity', () => {
    const unexpected = { ...safeItem, title: 'secret', text: 'secret' } as never;
    const screen = render(
      <RecentActivitySection state={{ kind: 'populated', items: [unexpected] }} peerName="POCO F1" now={3_000} />,
    );

    expect(screen.getByText('Mirrored from POCO F1')).toBeTruthy();
    expect(screen.getByText(/Messages/)).toBeTruthy();
    expect(screen.queryByText('secret')).toBeNull();
  });
});
