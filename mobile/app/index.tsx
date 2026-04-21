import { useEffect } from 'react';
import { View } from 'react-native';
import { router } from 'expo-router';
import AsyncStorage from '@react-native-async-storage/async-storage';

export default function Index() {
  useEffect(() => {
    AsyncStorage.getItem('twinotify_onboarding_complete').then((done) => {
      router.replace(done === 'true' ? '/home' : '/onboarding/welcome');
    });
  }, []);

  // Invisible splash while AsyncStorage resolves
  return <View />;
}
