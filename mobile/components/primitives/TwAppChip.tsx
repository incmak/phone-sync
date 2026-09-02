import React from 'react';
import { Image, View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';

export interface TwApp {
  name: string;
  artworkDataUri?: string | null;
}

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
  const radius = px * 0.25;

  return (
    <View style={styles.row}>
      <View
        style={[
          styles.icon,
          {
            width: px,
            height: px,
            borderRadius: radius,
          },
        ]}
      >
        {app.artworkDataUri ? (
          <Image
            accessibilityIgnoresInvertColors
            source={{ uri: app.artworkDataUri }}
            style={{ width: px, height: px, borderRadius: radius }}
          />
        ) : (
          <Text
            style={[
              styles.glyph,
              {
                color: theme.ink2,
                fontSize: px * 0.45,
                fontFamily: theme.fonts.uiBold,
                fontWeight: '700',
              },
            ]}
          >
            {Array.from(app.name.trim())[0]?.toUpperCase() ?? 'A'}
          </Text>
        )}

        {showBadge !== undefined && (
          <View
            style={[
              styles.badge,
              {
                borderColor: theme.sem.danger.foreground,
                backgroundColor: theme.sem.danger.surface,
              },
            ]}
          >
            <Text style={[styles.badgeText, { color: theme.sem.danger.foreground }]}>
              {showBadge}
            </Text>
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
    textAlign: 'center',
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
