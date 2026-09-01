import React from 'react';
import { render } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import { HomeMetrics } from '../HomeMetrics';

test('uses a wrapping comparison grid with aligned metric roles', () => {
  const screen = render(<HomeMetrics mirroredToday={12} blockedToday={2} latencyMs={84} />);
  const metrics = screen.getByTestId('home-metrics');

  expect(StyleSheet.flatten(metrics.props.style).flexWrap).toBe('wrap');
  expect(screen.getByText('Mirrored')).toBeTruthy();
  expect(screen.getByText('Latency')).toBeTruthy();
  expect(screen.getByText('Blocked')).toBeTruthy();
  expect(screen.getByLabelText('84 milliseconds')).toBeTruthy();
});

test('distinguishes absent latency from a measured zero', () => {
  const screen = render(<HomeMetrics mirroredToday={1} blockedToday={0} latencyMs={null} />);
  expect(screen.getByText('No data')).toBeTruthy();
  expect(screen.getByLabelText('Latency not measured')).toBeTruthy();

  screen.rerender(<HomeMetrics mirroredToday={1} blockedToday={0} latencyMs={0} />);
  expect(screen.getByText('0 ms')).toBeTruthy();
  expect(screen.getByLabelText('0 milliseconds')).toBeTruthy();
});
