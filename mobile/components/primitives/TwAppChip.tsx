import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';

export interface TwApp {
  name: string;
  glyph: string;
  color: string;
}

export const TW_APPS: Record<string, TwApp> = {
  signal:    { name: 'Signal',     glyph: 'S',  color: '#3a76f0' },
  whatsapp:  { name: 'WhatsApp',   glyph: 'W',  color: '#25d366' },
  slack:     { name: 'Slack',      glyph: '#',  color: '#4a154b' },
  gmail:     { name: 'Gmail',      glyph: 'M',  color: '#ea4335' },
  linear:    { name: 'Linear',     glyph: 'L',  color: '#5e6ad2' },
  github:    { name: 'GitHub',     glyph: 'G',  color: '#1a1a1a' },
  spotify:   { name: 'Spotify',    glyph: '\u266a', color: '#1db954' },
  calendar:  { name: 'Calendar',   glyph: '\u25f7', color: '#4285f4' },
  maps:      { name: 'Maps',       glyph: '\u25c9', color: '#34a853' },
  chase:     { name: 'Chase',      glyph: 'C',  color: '#0a5aa4' },
  authy:     { name: 'Authy',      glyph: '\u2713', color: '#ec1c24' },
  uber:      { name: 'Uber',       glyph: 'U',  color: '#1a1a1a' },
  instagram: { name: 'Instagram',  glyph: '\ud83d\udcf7', color: '#e4405f' },
  discord:   { name: 'Discord',    glyph: 'D',  color: '#5865f2' },
  telegram:  { name: 'Telegram',   glyph: '\u2708', color: '#2aabee' },
};

export type TwAppChipSize = 'sm' | 'md' | 'lg';

interface TwAppChipProps {
  app: TwApp;
  size?: TwAppChipSize;
  showBadge?: number | string;
}

const SIZE_PX: Record<TwAppChipSize, number> = { sm: 24, md: 32, lg: 40 };

export function TwAppChip({ app, size = 'md', showBadge }: TwAppChipProps) {
  const theme = useTheme();
  const px = SIZE_PX[size];

  return (
    <View style={styles.row}>
      <View
        style={[
          styles.icon,
          {
            width: px,
            height: px,
            borderRadius: px * 0.25,
            backgroundColor: app.color,
          },
        ]}
      >
        <Text style={[
          styles.glyph,
          { fontSize: px * 0.45, fontFamily: theme.fonts.uiBold, fontWeight: '700' },
        ]}>
          {app.glyph}
        </Text>

        {showBadge !== undefined && (
          <View style={[
            styles.badge,
            {
              borderColor: theme.sem.danger.foreground,
              backgroundColor: theme.sem.danger.surface,
            },
          ]}>
            <Text style={[styles.badgeText, { color: theme.sem.danger.foreground }]}>{showBadge}</Text>
          </View>
        )}
      </View>

      {size !== 'sm' && (
        <Text
          style={[
            styles.label,
            {
              color: theme.ink,
              fontSize: size === 'lg' ? 15 : 13,
              fontFamily: theme.fonts.uiMedium,
            },
          ]}
        >
          {app.name}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  icon: {
    flexShrink: 0,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
  },
  glyph: {
    color: '#ffffff',
  },
  badge: {
    position: 'absolute',
    top: -2,
    right: -2,
    minWidth: 14,
    height: 14,
    paddingHorizontal: 4,
    borderRadius: 7,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: {
    fontSize: 9,
    fontWeight: '700',
  },
  label: {
    fontWeight: '500',
  },
});
