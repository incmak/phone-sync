import type { ColorSchemeName } from 'react-native';

export function resolveColorScheme(colorScheme: ColorSchemeName | null): 'light' | 'dark' {
  return colorScheme === 'dark' ? 'dark' : 'light';
}
