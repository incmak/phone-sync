import { Stack } from 'expo-router';
import { ThemeProvider, useAppFonts } from '../components';
import { usePeerUnpairListener } from '../hooks/usePeerUnpairListener';

export default function RootLayout() {
  const fontsLoaded = useAppFonts();
  usePeerUnpairListener();
  if (!fontsLoaded) return null; // expo-splash-screen holds until fonts ready

  return (
    <ThemeProvider>
      <Stack screenOptions={{ headerShown: false }} />
    </ThemeProvider>
  );
}
