import { View, Text, Button, StyleSheet } from 'react-native';
import { useState } from 'react';

export default function Home() {
  const [status, setStatus] = useState<string>('idle');
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phone-Sync</Text>
      <Text>Status: {status}</Text>
      <Button title="Ping relay" onPress={() => setStatus('not implemented yet')} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24, gap: 12 },
  title: { fontSize: 24, fontWeight: '600' },
});
