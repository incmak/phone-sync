import { Stack } from 'expo-router';

export default function PairLayout() {
  return <Stack screenOptions={{ headerShown: false, animation: 'slide_from_right' }} />;
}
