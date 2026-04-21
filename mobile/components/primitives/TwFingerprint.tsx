import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useTheme } from '../Theme';

interface TwFingerprintProps {
  hex?: string;
  columns?: number;
  highlightGroups?: number[];
}

export function TwFingerprint({ hex, columns = 4, highlightGroups = [] }: TwFingerprintProps) {
  const theme = useTheme();

  // Normalize to 16 groups of 4 uppercase hex chars
  const raw = (hex ?? '').replace(/\s/g, '').toUpperCase();
  const groups = Array.from({ length: 16 }, (_, i) =>
    (raw.slice(i * 4, i * 4 + 4) || '0000').padEnd(4, '0'),
  );

  return (
    <View
      style={[
        styles.container,
        {
          backgroundColor: theme.fill,
          borderColor: theme.border,
        },
      ]}
    >
      {/* Render rows: each row has `columns` groups */}
      {Array.from({ length: Math.ceil(16 / columns) }, (_, rowIdx) => (
        <View key={rowIdx} style={styles.row}>
          {groups.slice(rowIdx * columns, rowIdx * columns + columns).map((g, colIdx) => {
            const groupIdx = rowIdx * columns + colIdx;
            const isHighlighted = highlightGroups.includes(groupIdx);
            return (
              <Text
                key={colIdx}
                style={[
                  styles.group,
                  {
                    color: isHighlighted ? theme.accent : theme.ink,
                    fontFamily: isHighlighted ? theme.fonts.monoMedium : theme.fonts.mono,
                    fontWeight: isHighlighted ? '700' : '500',
                    flex: 1,
                  },
                ]}
              >
                {g}
              </Text>
            );
          })}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    borderWidth: 1,
    borderRadius: 12,
    padding: 16,
    paddingHorizontal: 18,
    gap: 4,
  },
  row: {
    flexDirection: 'row',
    gap: 14,
  },
  group: {
    fontSize: 15,
    letterSpacing: 0.5,
    lineHeight: 24,
  },
});
