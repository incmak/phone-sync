import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { HandoffDisclosureMark } from '../HandoffTrace';
import { useTheme } from '../Theme';

export function HomeFilterAction({ onPress }: { onPress: () => void }) {
  const theme = useTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel="Choose mirrored apps"
      style={({ pressed }) => [styles.action, { backgroundColor: pressed ? theme.colors.surfaceContainerLow : 'transparent' }]}
    >
      <MaterialIcons name="notifications-none" size={24} color={theme.colors.onSurface as string} />
      <View style={styles.copy}>
        <Text style={[styles.title, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>Choose mirrored apps</Text>
        <Text style={[styles.detail, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>Control which apps can appear on your other phone</Text>
      </View>
      <HandoffDisclosureMark color={theme.colors.primary} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  action: { minHeight: 72, marginHorizontal: -8, paddingHorizontal: 8, borderRadius: 16, flexDirection: 'row', alignItems: 'center', gap: 16 },
  copy: { flex: 1, minWidth: 0 },
  title: { fontSize: 16, lineHeight: 22 },
  detail: { marginTop: 2, fontSize: 14, lineHeight: 20 },
});
