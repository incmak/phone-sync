import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTheme } from '../../components';

// Task 11 placeholder — pair/scan screen will be fully implemented in Task 11.
export default function PairScanScreen() {
  const theme = useTheme();
  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.bg }]}>
      <View style={styles.center}>
        <Text style={[theme.type.title2, { color: theme.ink, fontFamily: theme.fonts.uiSemi }]}>
          Pair — Scan code
        </Text>
        <Text style={[theme.type.body, styles.sub, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
          Coming in Task 11
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 },
  sub: { marginTop: 8, textAlign: 'center' },
});
