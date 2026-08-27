import { Stack } from 'expo-router';
import { ThemeProvider } from '../components';
import { usePeerUnpairListener } from '../hooks/usePeerUnpairListener';

export default function RootLayout() {
  usePeerUnpairListener();

  return (
    <ThemeProvider>
      <Stack screenOptions={{ headerShown: false }} />
    </ThemeProvider>
  );
}
