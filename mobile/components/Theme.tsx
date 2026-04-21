import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useColorScheme } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { twTheme, Theme, TwHue, TW_HUES } from './tokens';

interface ThemeContextValue {
  theme: Theme;
  hue: TwHue;
  setHue: (h: TwHue) => void;
  darkOverride: 'light' | 'dark' | null;
  setDarkOverride: (v: 'light' | 'dark' | null) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

const STORAGE_KEY = 'twinotify_theme_prefs';

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const system = useColorScheme();
  const [hue, setHueState] = useState<TwHue>('mint');
  const [darkOverride, setDarkOverrideState] = useState<'light' | 'dark' | null>(null);
  const [ready, setReady] = useState(false);

  // Load persisted prefs on mount; gate render until complete to avoid color flash
  useEffect(() => {
    AsyncStorage.getItem(STORAGE_KEY).then((raw) => {
      if (raw) {
        try {
          const parsed: unknown = JSON.parse(raw);
          if (parsed && typeof parsed === 'object') {
            const p = parsed as Record<string, unknown>;
            if (typeof p['hue'] === 'string' && p['hue'] in TW_HUES) {
              setHueState(p['hue'] as TwHue);
            }
            if (p['darkOverride'] === 'light' || p['darkOverride'] === 'dark') {
              setDarkOverrideState(p['darkOverride']);
            }
          }
        } catch {
          // malformed storage — ignore
        }
      }
      setReady(true);
    }).catch(() => setReady(true));
  }, []);

  const persist = useCallback((h: TwHue, d: 'light' | 'dark' | null) => {
    AsyncStorage.setItem(STORAGE_KEY, JSON.stringify({ hue: h, darkOverride: d })).catch(() => {});
  }, []);

  const setHue = useCallback((h: TwHue) => {
    setHueState(h);
    persist(h, darkOverride);
  }, [darkOverride, persist]);

  const setDarkOverride = useCallback((v: 'light' | 'dark' | null) => {
    setDarkOverrideState(v);
    persist(hue, v);
  }, [hue, persist]);

  const effectiveDark = darkOverride === 'dark' || (darkOverride === null && system === 'dark');
  const theme = useMemo(
    () => twTheme({ hue: TW_HUES[hue], dark: effectiveDark }),
    [hue, effectiveDark],
  );

  if (!ready) return null;

  return (
    <ThemeContext.Provider value={{ theme, hue, setHue, darkOverride, setDarkOverride }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): Theme {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx.theme;
}

export function useThemeControls(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useThemeControls must be used within ThemeProvider');
  return ctx;
}
