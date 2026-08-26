import React from 'react';
import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '../../Theme';
import { TwRow } from '../TwRow';

describe('TwRow accessibility contract', () => {
  test('includes the subtitle in the default pressable name', () => {
    render(
      <ThemeProvider>
        <TwRow title="Relay server" subtitle="Direct Wi-Fi only" onPress={() => {}} />
      </ThemeProvider>,
    );

    expect(screen.getByRole('button', { name: 'Relay server, Direct Wi-Fi only' })).toBeTruthy();
  });

  test('keeps an explicit complete name unchanged', () => {
    render(
      <ThemeProvider>
        <TwRow
          title="Paired device"
          subtitle="Phone, online"
          onPress={() => {}}
          accessibilityLabel="Open paired device settings"
        />
      </ThemeProvider>,
    );

    expect(screen.getByRole('button', { name: 'Open paired device settings' })).toBeTruthy();
  });
});
