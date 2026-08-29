import React, { useEffect, useState } from 'react';
import type { ColorValue } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { useReducedMotion } from 'react-native-reanimated';

import { useTheme } from './Theme';

export type HandoffTraceState =
  | 'direct'
  | 'relay'
  | 'reconnecting'
  | 'queued'
  | 'paused'
  | 'unpaired';

export interface HandoffTraceProps {
  state: HandoffTraceState;
  width: number;
  height?: number;
  compact?: boolean;
  testID?: string;
}

export interface TraceGeometry {
  state: HandoffTraceState;
  leftBracket: string;
  rightBracket: string;
  routePaths: readonly string[];
  ticket: { x: number; y: number; width: number; height: number; path: string };
  waypointPaths: readonly string[];
}

const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));

function bracketPath(x: number, baseline: number, side: 'left' | 'right', arm: number, reach: number) {
  return side === 'left'
    ? `M ${x + arm} ${baseline - reach} H ${x} V ${baseline + reach} H ${x + arm}`
    : `M ${x - arm} ${baseline - reach} H ${x} V ${baseline + reach} H ${x - arm}`;
}

function ticketPath(x: number, y: number, width: number, height: number) {
  const cut = Math.min(4, width / 4, height / 4);
  return [
    `M ${x + cut} ${y}`,
    `H ${x + width - cut}`,
    `L ${x + width} ${y + cut}`,
    `V ${y + height - cut}`,
    `L ${x + width - cut} ${y + height}`,
    `H ${x + cut}`,
    `L ${x} ${y + height - cut}`,
    `V ${y + cut}`,
    'Z',
  ].join(' ');
}

function translateTicket(ticket: TraceGeometry['ticket'], offset: number): TraceGeometry['ticket'] {
  const x = ticket.x + offset;
  return { ...ticket, x, path: ticketPath(x, ticket.y, ticket.width, ticket.height) };
}

/**
 * The route artifact is intentionally a pure geometry builder. Screens own the
 * human-readable delivery state, while this component only makes that state
 * tangible through the same two endpoints and one in-flight notification.
 */
export function buildTraceGeometry(
  state: HandoffTraceState,
  width: number,
  height: number,
): TraceGeometry {
  const canvasWidth = Math.max(64, Math.round(width));
  const canvasHeight = Math.max(48, Math.round(height));
  const padding = clamp(canvasWidth * 0.065, 10, 20);
  const baseline = clamp(canvasHeight * 0.6, 24, canvasHeight - 20);
  const bracketArm = clamp(canvasWidth * 0.03, 7, 11);
  const bracketReach = clamp(canvasHeight * 0.12, 8, 13);
  const left = padding;
  const right = canvasWidth - padding;
  const innerLeft = left + bracketArm + 3;
  const innerRight = right - bracketArm - 3;
  const midpoint = (innerLeft + innerRight) / 2;
  const ticketWidth = clamp(canvasWidth * 0.12, 22, 38);
  const ticketHeight = clamp(canvasHeight * 0.2, 14, 20);
  const leftBracket = bracketPath(left, baseline, 'left', bracketArm, bracketReach);
  const rightBracket = bracketPath(right, baseline, 'right', bracketArm, bracketReach);
  const baseTicket = (x: number, centerY = baseline) => ({
    x: clamp(x, 1, canvasWidth - ticketWidth - 1),
    y: clamp(centerY - ticketHeight / 2, 2, canvasHeight - ticketHeight - 2),
    width: ticketWidth,
    height: ticketHeight,
    path: '',
  });
  const finalTicket = (x: number, centerY = baseline) => {
    const ticket = baseTicket(x, centerY);
    return { ...ticket, path: ticketPath(ticket.x, ticket.y, ticket.width, ticket.height) };
  };

  switch (state) {
    case 'direct':
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [`M ${innerLeft} ${baseline} H ${innerRight}`],
        ticket: finalTicket(midpoint - ticketWidth / 2),
        waypointPaths: [],
      };
    case 'relay': {
      const relayY = baseline - clamp(canvasHeight * 0.22, 12, 22);
      const outboundTicketCenter = innerLeft + (midpoint - innerLeft) * 0.42;
      const outboundProgress = (outboundTicketCenter - innerLeft) / (midpoint - innerLeft);
      const outboundTicketY = baseline + (relayY - baseline) * outboundProgress;
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [
          `M ${innerLeft} ${baseline} L ${midpoint} ${relayY}`,
          `M ${midpoint} ${relayY} L ${innerRight} ${baseline}`,
        ],
        ticket: finalTicket(outboundTicketCenter - ticketWidth / 2, outboundTicketY),
        waypointPaths: [
          `M ${midpoint} ${relayY - 4} L ${midpoint + 4} ${relayY} L ${midpoint} ${relayY + 4} L ${midpoint - 4} ${relayY} Z`,
        ],
      };
    }
    case 'reconnecting': {
      const gap = clamp(canvasWidth * 0.075, 18, 28);
      const leftStop = midpoint - gap;
      const rightStart = midpoint + gap;
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [`M ${innerLeft} ${baseline} H ${leftStop}`, `M ${rightStart} ${baseline} H ${innerRight}`],
        ticket: finalTicket(leftStop - ticketWidth - 8),
        waypointPaths: [],
      };
    }
    case 'queued': {
      const notch = Math.max(clamp(canvasWidth * 0.018, 4, 6), (ticketWidth + 8) / 6);
      const baySpan = notch * 6;
      const bayStart = midpoint - baySpan;
      const bayDepth = clamp(canvasHeight * 0.09, 5, 8);
      const bayPath = [
        `M ${bayStart} ${baseline}`,
        `H ${bayStart + notch}`,
        `V ${baseline - bayDepth}`,
        `H ${bayStart + notch * 2}`,
        `V ${baseline}`,
        `H ${bayStart + notch * 3}`,
        `V ${baseline - bayDepth}`,
        `H ${bayStart + notch * 4}`,
        `V ${baseline}`,
        `H ${bayStart + notch * 5}`,
        `V ${baseline - bayDepth}`,
        `H ${bayStart + notch * 6}`,
        `V ${baseline}`,
      ].join(' ');
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [`M ${innerLeft} ${baseline} H ${bayStart}`, bayPath],
        ticket: finalTicket(bayStart + (baySpan - ticketWidth) / 2),
        waypointPaths: [bayPath],
      };
    }
    case 'paused': {
      const gateLeft = midpoint - 5;
      const gateRight = midpoint + 5;
      const gateReach = clamp(canvasHeight * 0.12, 8, 12);
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [`M ${innerLeft} ${baseline} H ${gateLeft}`, `M ${gateRight} ${baseline} H ${innerRight}`],
        ticket: finalTicket(gateLeft - ticketWidth),
        waypointPaths: [
          `M ${gateLeft} ${baseline - gateReach} V ${baseline + gateReach}`,
          `M ${gateRight} ${baseline - gateReach} V ${baseline + gateReach}`,
        ],
      };
    }
    case 'unpaired':
      return {
        state,
        leftBracket,
        rightBracket,
        routePaths: [],
        ticket: finalTicket(innerLeft + bracketArm + 5),
        waypointPaths: [],
      };
  }
}

export function traceTicketOffset(
  state: HandoffTraceState,
  reduceMotion: boolean,
  progress: number,
): number {
  if (reduceMotion || state !== 'reconnecting') return 0;
  return clamp(progress, 0, 1) * 8;
}

export function HandoffDisclosureMark({ size = 16, color }: { size?: number; color?: ColorValue }): React.ReactElement {
  const theme = useTheme();
  const stroke = color ?? theme.colors.primary;

  return (
    <Svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      accessible={false}
      importantForAccessibility="no"
    >
      <Path d="M 3 2 H 12 V 14 H 3" fill="none" stroke={stroke} strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function HandoffTrace({
  state,
  width,
  height: providedHeight,
  compact = false,
  testID,
}: HandoffTraceProps): React.ReactElement {
  const theme = useTheme();
  const reduceMotion = Boolean(useReducedMotion());
  const height = providedHeight ?? (compact ? 56 : 96);
  const [motionProgress, setMotionProgress] = useState(0);
  const geometry = buildTraceGeometry(state, width, height);

  useEffect(() => {
    if (state !== 'reconnecting' || reduceMotion) return;

    let frame: number | undefined;
    let startedAt: number | undefined;
    const advance = (timestamp: number) => {
      startedAt ??= timestamp;
      setMotionProgress(((timestamp - startedAt) % 1200) / 1200);
      frame = requestAnimationFrame(advance);
    };

    frame = requestAnimationFrame(advance);
    return () => {
      if (frame !== undefined) cancelAnimationFrame(frame);
    };
  }, [reduceMotion, state]);

  const ticket = translateTicket(geometry.ticket, traceTicketOffset(state, reduceMotion, motionProgress));
  const routeColor = state === 'unpaired' ? theme.colors.outlineVariant : theme.colors.primary;
  const waypointColor = state === 'queued' || state === 'paused' ? theme.colors.outline : theme.colors.primary;

  return (
    <Svg
      width={width}
      height={height}
      viewBox={`0 0 ${Math.max(64, Math.round(width))} ${Math.max(48, Math.round(height))}`}
      fill="none"
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      pointerEvents="none"
      testID={testID}
    >
      <Path d={geometry.leftBracket} stroke={theme.colors.outline} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d={geometry.rightBracket} stroke={theme.colors.outline} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      {geometry.routePaths.map((route, index) => (
        <Path key={`route-${index}`} d={route} stroke={routeColor} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      ))}
      {geometry.waypointPaths.map((waypoint, index) => (
        <Path
          key={`waypoint-${index}`}
          d={waypoint}
          fill={state === 'relay' ? theme.colors.primaryContainer : 'none'}
          stroke={waypointColor}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
      <Path
        testID="handoff-trace-ticket"
        d={ticket.path}
        fill={theme.colors.primaryContainer}
        stroke={theme.colors.primary}
        strokeWidth={2}
        strokeLinejoin="round"
      />
    </Svg>
  );
}
