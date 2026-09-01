import fs from 'node:fs';
import path from 'node:path';
import React from 'react';
import { render } from '@testing-library/react-native';

import {
  buildTraceGeometry,
  HandoffTrace,
  traceTicketOffset,
  type HandoffTraceState,
} from '../HandoffTrace';

const width = 320;
const height = 96;
const states: readonly HandoffTraceState[] = [
  'direct',
  'relay',
  'reconnecting',
  'queued',
  'paused',
  'stopped',
  'unpaired',
];

interface Point {
  x: number;
  y: number;
}

function pathPoints(pathValue: string): Point[] {
  const tokens = pathValue.match(/[MLHVZ]|-?\d+(?:\.\d+)?/g) ?? [];
  const points: Point[] = [];
  let cursor: Point = { x: 0, y: 0 };
  let index = 0;

  while (index < tokens.length) {
    const command = tokens[index++];
    if (command === 'Z') continue;
    if (command === 'H') {
      cursor = { ...cursor, x: Number(tokens[index++]) };
    } else if (command === 'V') {
      cursor = { ...cursor, y: Number(tokens[index++]) };
    } else {
      cursor = { x: Number(tokens[index++]), y: Number(tokens[index++]) };
    }
    points.push(cursor);
  }

  return points;
}

function geometryPoints(geometry: ReturnType<typeof buildTraceGeometry>): Point[] {
  return [
    geometry.leftBracket,
    geometry.rightBracket,
    ...geometry.routePaths,
    ...geometry.waypointPaths,
    geometry.ticket.path,
  ].flatMap(pathPoints);
}

function signature(state: HandoffTraceState) {
  const geometry = buildTraceGeometry(state, width, height);
  return JSON.stringify({ routePaths: geometry.routePaths, waypointPaths: geometry.waypointPaths });
}

function writeGeometryEvidence() {
  const target = process.env.HANDOFF_GEOMETRY_EVIDENCE;
  if (!target) return;

  const result = {
    schema: 'twinotify.handoff-geometry.v1',
    input: { width, height },
    states: states.map((state) => ({
      state,
      geometry: buildTraceGeometry(state, width, height),
      signature: signature(state),
    })),
    assertions: {
      stateCount: states.length,
      uniqueSignatures: new Set(states.map(signature)).size,
      twoBracketsOneTicket: states.every((state) => {
        const geometry = buildTraceGeometry(state, width, height);
        return Boolean(geometry.leftBracket && geometry.rightBracket && geometry.ticket.path);
      }),
    },
  };

  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, `${JSON.stringify(result, null, 2)}\n`);
}

describe('HandoffTrace', () => {
  test('seven state geometries are bounded, unique, and always retain two brackets with one ticket', () => {
    const seen = new Set<string>();

    states.forEach((state) => {
      const geometry = buildTraceGeometry(state, width, height);
      const points = geometryPoints(geometry);

      expect(geometry.state).toBe(state);
      expect(geometry.leftBracket).not.toEqual('');
      expect(geometry.rightBracket).not.toEqual('');
      expect(geometry.ticket.path).not.toEqual('');
      expect(geometry.ticket.width).toBeGreaterThan(0);
      expect(geometry.ticket.height).toBeGreaterThan(0);
      expect(geometry.ticket.x).toBeGreaterThanOrEqual(0);
      expect(geometry.ticket.y).toBeGreaterThanOrEqual(0);
      expect(geometry.ticket.x + geometry.ticket.width).toBeLessThanOrEqual(width);
      expect(geometry.ticket.y + geometry.ticket.height).toBeLessThanOrEqual(height);
      expect(points.every(({ x }) => x >= 0 && x <= width)).toBe(true);
      expect(points.every(({ y }) => y >= 0 && y <= height)).toBe(true);

      seen.add(signature(state));
    });

    expect(seen.size).toBe(states.length);
    writeGeometryEvidence();
  });

  test('all path coordinates honor the supplied short canvas height as well as its wider width', () => {
    const shortHeight = 48;

    states.forEach((state) => {
      const geometry = buildTraceGeometry(state, width, shortHeight);
      const points = geometryPoints(geometry);

      expect(points.length).toBeGreaterThan(0);
      expect(points.every(({ x }) => x >= 0 && x <= width)).toBe(true);
      expect(points.every(({ y }) => y >= 0 && y <= shortHeight)).toBe(true);
      expect(geometry.ticket.x + geometry.ticket.width).toBeLessThanOrEqual(width);
      expect(geometry.ticket.y + geometry.ticket.height).toBeLessThanOrEqual(shortHeight);
    });
  });

  test('direct geometry carries one uninterrupted route between the endpoint brackets', () => {
    const geometry = buildTraceGeometry('direct', width, height);

    expect(geometry.routePaths).toHaveLength(1);
    expect(geometry.waypointPaths).toHaveLength(0);
    expect(geometry.ticket.x).toBeGreaterThan(width * 0.35);
    expect(geometry.ticket.x).toBeLessThan(width * 0.65);
  });

  test('relay geometry meets at one elevated waypoint and leaves the ticket outbound', () => {
    const geometry = buildTraceGeometry('relay', width, height);

    expect(geometry.routePaths).toHaveLength(2);
    expect(geometry.waypointPaths).toHaveLength(1);
    expect(geometry.routePaths[0]).toMatch(/L/);
    expect(geometry.routePaths[1]).toMatch(/L/);
    expect(geometry.ticket.x).toBeLessThan(width / 2);
    expect(geometry.ticket.y + geometry.ticket.height / 2).toBeLessThan(height * 0.6);
  });

  test('reconnecting geometry leaves a central gap and the ticket only moves a bounded distance', () => {
    const geometry = buildTraceGeometry('reconnecting', width, height);
    const approachEnd = width / 2 - 24;

    expect(geometry.routePaths).toHaveLength(2);
    expect(geometry.routePaths.join(' ')).not.toContain(`H ${width / 2}`);
    expect(traceTicketOffset('reconnecting', false, -1)).toBe(0);
    expect(traceTicketOffset('reconnecting', false, 0.5)).toBeGreaterThan(0);
    expect(traceTicketOffset('reconnecting', false, 2)).toBe(8);
    expect(traceTicketOffset('direct', false, 0.5)).toBe(0);
    expect(geometry.ticket.x + geometry.ticket.width + traceTicketOffset('reconnecting', false, 1)).toBeLessThanOrEqual(approachEnd);
  });

  test('queued geometry docks one ticket in a three-notch bay and never joins the right endpoint', () => {
    const geometry = buildTraceGeometry('queued', width, height);
    const bayPoints = pathPoints(geometry.waypointPaths[0]);
    const bayLeft = Math.min(...bayPoints.map(({ x }) => x));
    const bayRight = Math.max(...bayPoints.map(({ x }) => x));

    expect(geometry.routePaths).toHaveLength(2);
    expect(geometry.waypointPaths).toHaveLength(1);
    expect(geometry.waypointPaths[0].match(/V/g)).toHaveLength(6);
    expect(geometry.ticket.x).toBeGreaterThanOrEqual(bayLeft);
    expect(geometry.ticket.x + geometry.ticket.width).toBeLessThanOrEqual(bayRight);
    expect(bayRight).toBeLessThanOrEqual(width / 2);
  });

  test('paused geometry uses one two-stroke gate with the ticket immediately before it', () => {
    const geometry = buildTraceGeometry('paused', width, height);
    const [leftGate] = pathPoints(geometry.waypointPaths[0]);
    const [rightGate] = pathPoints(geometry.waypointPaths[1]);
    const leftRouteEnd = pathPoints(geometry.routePaths[0]).at(-1);
    const [rightRouteStart] = pathPoints(geometry.routePaths[1]);

    expect(geometry.routePaths).toHaveLength(2);
    expect(geometry.waypointPaths).toHaveLength(2);
    expect(leftRouteEnd).toEqual({ x: leftGate.x, y: height * 0.6 });
    expect(rightRouteStart).toEqual({ x: rightGate.x, y: height * 0.6 });
    expect(geometry.ticket.x + geometry.ticket.width).toBe(leftGate.x);
  });

  test('stopped geometry leaves the ticket at the source with no implied connection', () => {
    const geometry = buildTraceGeometry('stopped', width, height);

    expect(geometry.routePaths).toHaveLength(1);
    expect(geometry.waypointPaths).toHaveLength(0);
    expect(geometry.ticket.x + geometry.ticket.width).toBeLessThan(width * 0.35);
    expect(pathPoints(geometry.routePaths[0]).at(-1)?.x).toBeLessThan(width * 0.35);
  });

  test('unpaired geometry keeps only endpoints and a ticket beside the left bracket', () => {
    const geometry = buildTraceGeometry('unpaired', width, height);

    expect(geometry.routePaths).toHaveLength(0);
    expect(geometry.waypointPaths).toHaveLength(0);
    expect(geometry.ticket.x + geometry.ticket.width).toBeLessThan(width * 0.35);
  });

  test('reduced motion freezes every ticket offset at its truthful resting position', () => {
    states.forEach((state) => {
      [-1, 0, 0.5, 1, 2].forEach((progress) => {
        expect(traceTicketOffset(state, true, progress)).toBe(0);
      });
    });
  });

  test('forbidden rendering never introduces gradients, circles, shadows, scale, or a second ticket', () => {
    const { toJSON } = render(<HandoffTrace state="reconnecting" width={width} height={height} testID="trace" />);
    const tree = JSON.stringify(toJSON());
    const root = toJSON() as { props: Record<string, unknown> };

    expect(root.props.accessible).toBe(false);
    expect(root.props.importantForAccessibility).toBe('no-hide-descendants');
    expect(tree).not.toMatch(/LinearGradient|RadialGradient|Circle|shadow|filter|scale/i);
    expect((tree.match(/ticket/g) ?? []).length).toBe(1);
  });
});
