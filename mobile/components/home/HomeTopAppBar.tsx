import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import React from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useTheme } from '../Theme';
import { TwWordmark } from '../primitives/TwWordmark';

export function HomeTopAppBar({ onOpenSettings }: { onOpenSettings: () => void }) {
  const theme = useTheme();
  return (
    <View style={styles.bar}>
      <TwWordmark size={19} />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Open settings"
        hitSlop={4}
        onPress={onOpenSettings}
        style={({ pressed }) => [styles.action, { backgroundColor: pressed ? theme.colors.surfaceContainerHighest : 'transparent' }]}
      >
        <MaterialIcons name="settings" size={24} color={theme.colors.onSurface as string} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { minHeight: 64, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  action: { width: 48, height: 48, minWidth: 48, minHeight: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center' },
});
