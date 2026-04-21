// Twinotify icon registry — 21 thin-line SVG icons, all 20x20 (or noted size).
// Uses react-native-svg primitives. Export as named function + keyed registry.

import React from 'react';
import Svg, { Path, Circle, Rect, Line, G } from 'react-native-svg';

export interface TwIconProps {
  size?: number;
  color?: string;
  strokeWidth?: number;
}

interface IconRecord {
  (props: TwIconProps): React.ReactElement;
}

// ── Individual icon components ─────────────────────────────────────────────

export function IconChevronRight({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M7 4l6 6-6 6" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconChevronLeft({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M13 4l-6 6 6 6" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconCheck({ size = 20, color = 'currentColor', strokeWidth = 2 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M4 10l4 4 8-8" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconX({ size = 20, color = 'currentColor', strokeWidth = 1.8 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M5 5l10 10M15 5L5 15" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
    </Svg>
  );
}

export function IconCopy({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Rect x="6" y="6" width="10" height="10" rx="2" stroke={color} strokeWidth={strokeWidth} />
      <Path d="M4 12H3a1 1 0 01-1-1V3a1 1 0 011-1h8a1 1 0 011 1v1" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconSearch({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Circle cx="8" cy="8" r="5.5" stroke={color} strokeWidth={strokeWidth} />
      <Path d="M12 12l4 4" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
    </Svg>
  );
}

export function IconSettings({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Circle cx="10" cy="10" r="2.5" stroke={color} strokeWidth={strokeWidth} />
      <Path
        d="M10 2v2M10 16v2M2 10h2M16 10h2M4.5 4.5l1.4 1.4M14.1 14.1l1.4 1.4M4.5 15.5l1.4-1.4M14.1 5.9l1.4-1.4"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

export function IconBell({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M5 8a5 5 0 0110 0v3l1.5 3h-13L5 11V8z" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M8 17a2 2 0 004 0" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconShield({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M10 2l7 3v5c0 4-3 7-7 8-4-1-7-4-7-8V5l7-3z" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconBattery({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Rect x="2" y="7" width="14" height="6" rx="1.5" stroke={color} strokeWidth={strokeWidth} />
      <Rect x="17" y="9" width="1.5" height="2" rx=".5" fill={color} />
    </Svg>
  );
}

export function IconLink({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Path d="M8 10a3 3 0 004 0l3-3a3 3 0 00-4-4l-1 1" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M10 8a3 3 0 00-4 0l-3 3a3 3 0 004 4l1-1" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconQR({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Rect x="3" y="3" width="5" height="5" rx="1" stroke={color} strokeWidth={strokeWidth} />
      <Rect x="12" y="3" width="5" height="5" rx="1" stroke={color} strokeWidth={strokeWidth} />
      <Rect x="3" y="12" width="5" height="5" rx="1" stroke={color} strokeWidth={strokeWidth} />
      <Rect x="12" y="12" width="2" height="2" fill={color} />
      <Rect x="15" y="15" width="2" height="2" fill={color} />
      <Rect x="12" y="15" width="2" height="2" fill={color} />
      <Rect x="15" y="12" width="2" height="2" fill={color} />
    </Svg>
  );
}

export function IconCamera({ size = 20, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 20 20" fill="none">
      <Path d="M3 7h2l1.5-2h7L15 7h2a1 1 0 011 1v8a1 1 0 01-1 1H3a1 1 0 01-1-1V8a1 1 0 011-1z" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <Circle cx="10" cy="11.5" r="3" stroke={color} strokeWidth={strokeWidth} />
    </Svg>
  );
}

export function IconUnpair({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Path d="M7 5l-2 2a2.8 2.8 0 000 4l2 2" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
      <Path d="M11 13l2-2a2.8 2.8 0 000-4l-2-2" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
      <Path d="M3 15L15 3" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
    </Svg>
  );
}

export function IconArrowDown({ size = 14, color = 'currentColor', strokeWidth = 1.8 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 14 14" fill="none">
      <Path d="M7 2v10M3 8l4 4 4-4" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconArrowUp({ size = 14, color = 'currentColor', strokeWidth = 1.8 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 14 14" fill="none">
      <Path d="M7 12V2M3 6l4-4 4 4" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconAlert({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Path d="M9 2l7.5 13h-15L9 2z" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
      <Path d="M9 7v4M9 13v.01" stroke={color} strokeWidth="1.8" strokeLinecap="round" />
    </Svg>
  );
}

export function IconPair({ size = 22, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 22 22" fill="none">
      <Circle cx="8" cy="11" r="5" stroke={color} strokeWidth={strokeWidth} />
      <Circle cx="14" cy="11" r="5" stroke={color} strokeWidth={strokeWidth} />
    </Svg>
  );
}

export function IconRefresh({ size = 18, color = 'currentColor', strokeWidth = 1.6 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Path d="M15 4v4h-4" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M3 14v-4h4" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M14 8a5.5 5.5 0 00-10-1M4 10a5.5 5.5 0 0010 1" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </Svg>
  );
}

export function IconKeyboard({ size = 18, color = 'currentColor', strokeWidth = 1.5 }: TwIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 18 18" fill="none">
      <Rect x="1.5" y="5" width="15" height="9" rx="1.5" stroke={color} strokeWidth={strokeWidth} />
      <Path d="M4 8h.01M7 8h.01M10 8h.01M13 8h.01M5 11h8" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" />
    </Svg>
  );
}

// ── Keyed registry (mirrors original TwIcon object) ─────────────────────────

export type TwIconName =
  | 'chevronRight' | 'chevronLeft' | 'check' | 'x' | 'copy' | 'search'
  | 'settings' | 'bell' | 'shield' | 'battery' | 'link' | 'qr' | 'camera'
  | 'unpair' | 'arrowDown' | 'arrowUp' | 'alert' | 'pair' | 'refresh' | 'keyboard';

const ICON_MAP: Record<TwIconName, IconRecord> = {
  chevronRight: IconChevronRight,
  chevronLeft:  IconChevronLeft,
  check:        IconCheck,
  x:            IconX,
  copy:         IconCopy,
  search:       IconSearch,
  settings:     IconSettings,
  bell:         IconBell,
  shield:       IconShield,
  battery:      IconBattery,
  link:         IconLink,
  qr:           IconQR,
  camera:       IconCamera,
  unpair:       IconUnpair,
  arrowDown:    IconArrowDown,
  arrowUp:      IconArrowUp,
  alert:        IconAlert,
  pair:         IconPair,
  refresh:      IconRefresh,
  keyboard:     IconKeyboard,
};

interface TwIconComponentProps extends TwIconProps {
  name: TwIconName;
}

export function TwIcon({ name, ...props }: TwIconComponentProps) {
  const Icon = ICON_MAP[name];
  return <Icon {...props} />;
}
