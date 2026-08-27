import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { ThemeProvider, useTheme } from '../components';
import { usePeerUnpairListener } from '../hooks/usePeerUnpairListener';

function AppSystemChrome() {
  const theme = useTheme();
  return <StatusBar style={theme.dark ? 'light' : 'dark'} />;
}

export default function RootLayout() {
  usePeerUnpairListener();

  return (
    <ThemeProvider>
      <AppSystemChrome />
      <Stack screenOptions={{ headerShown: false }} />
    </ThemeProvider>
  );
}
