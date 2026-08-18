import { Stack } from 'expo-router';

export default function PairLayout() {
  return (
    <Stack screenOptions={{ headerShown: false, animation: 'slide_from_right' }}>
      <Stack.Screen name="nearby" options={{ gestureEnabled: false }} />
      <Stack.Screen name="verify" options={{ gestureEnabled: false }} />
    </Stack>
  );
}
