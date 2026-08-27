import React from 'react';
import { render } from '@testing-library/react-native';

import RootLayout from '../_layout';

jest.mock('expo-status-bar', () => ({
  StatusBar: (props: Record<string, unknown>) => {
    const actualReact = jest.requireActual<typeof import('react')>('react');
    const actualReactNative = jest.requireActual<typeof import('react-native')>('react-native');
    return actualReact.createElement(actualReactNative.View, { ...props, testID: 'app-status-bar' });
  },
}));

describe('theme-matched Android system chrome', () => {
  beforeEach(() => {
    global.__RESET_OFFLINE_TEST_STATE__();
  });

  it('uses dark icons on the light mineral surface', () => {
    const screen = render(<RootLayout />);
    const statusBar = screen.getByTestId('app-status-bar');

    expect(statusBar.props.style).toBe('dark');
  });

  it('uses light icons on the dark mineral surface', () => {
    global.__SET_DARK_THEME__(true);
    const screen = render(<RootLayout />);
    const statusBar = screen.getByTestId('app-status-bar');

    expect(statusBar.props.style).toBe('light');
  });
});
