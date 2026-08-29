import React, { createContext, useContext, useMemo } from 'react';
import { Platform, useColorScheme } from 'react-native';
import { androidMonetScheme, resolveMaterialScheme, twTheme, Theme } from './tokens';

interface ThemeContextValue {
  theme: Theme;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const system = useColorScheme();
  const dark = system === 'dark';
  const theme = useMemo(() => {
    const dynamic = Platform.OS === 'android' && Number(Platform.Version) >= 31
      ? androidMonetScheme(dark)
      : undefined;
    return twTheme({ dark, colors: resolveMaterialScheme({ dark, dynamic }) });
  }, [dark]);

  return (
    <ThemeContext.Provider value={{ theme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): Theme {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx.theme;
}
