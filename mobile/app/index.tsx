import { View, Text, Button, TextInput, StyleSheet } from 'react-native';
import { useState } from 'react';
import { pingRelay } from '../modules/twinotify-core/src';

export default function Home() {
  const [url, setUrl] = useState<string>('ws://10.0.2.2:8080/ws');
  const [status, setStatus] = useState<string>('idle');

  async function handlePing() {
    setStatus('pinging…');
    try {
      const res = await pingRelay(url, false);
      setStatus(`ok: ${res}`);
    } catch (e: unknown) {
      setStatus(`error: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phone-Sync</Text>
      <TextInput style={styles.input} value={url} onChangeText={setUrl} autoCapitalize="none" />
      <Button title="Ping relay" onPress={handlePing} />
      <Text style={styles.status}>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'stretch', padding: 24, gap: 12 },
  title: { fontSize: 24, fontWeight: '600', textAlign: 'center' },
  input: { borderWidth: 1, borderColor: '#888', padding: 8, borderRadius: 4 },
  status: { marginTop: 16, textAlign: 'center', fontFamily: 'monospace' },
});
