'use client';

import React, { createContext, useContext, useEffect, useState, useSyncExternalStore } from 'react';

export type Theme = 'dark' | 'light';
export type ThemePreference = Theme | 'auto';

interface ThemeContextType {
  theme: Theme;
  themePreference: ThemePreference;
  toggleTheme: () => void;
  setTheme: (theme: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);
const THEME_STORAGE_KEY = 'trinyx-theme-v1';

export function useOptionalTheme() {
  return useContext(ThemeContext);
}

export function useTheme() {
  const context = useOptionalTheme();
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}

interface ThemeProviderProps {
  children: React.ReactNode;
}

function isThemePreference(value: string | null): value is ThemePreference {
  return value === 'dark' || value === 'light' || value === 'auto';
}

function getSystemTheme(): Theme {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return 'dark';
  }

  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function getStoredThemePreference(): ThemePreference {
  if (typeof window === 'undefined') {
    return 'dark';
  }

  // Use a Trinyx-specific key so legacy LiveContext `theme=light/auto`
  // preferences cannot override the new brand default on first visit.
  const savedTheme = localStorage.getItem(THEME_STORAGE_KEY);
  return isThemePreference(savedTheme) ? savedTheme : 'dark';
}

function applyThemeClasses(theme: Theme) {
  const root = document.documentElement;
  const body = document.body;

  root.classList.remove('light', 'dark');
  body.classList.remove('light', 'dark');

  root.classList.add(theme);
  body.classList.add(theme);
}

const subscribeToClientReady = () => () => {};
const getClientSnapshot = () => true;
const getServerSnapshot = () => false;

function useIsClient() {
  return useSyncExternalStore(subscribeToClientReady, getClientSnapshot, getServerSnapshot);
}

export function ThemeProvider({ children }: ThemeProviderProps) {
  const isClient = useIsClient();
  const [themePreference, setThemePreference] = useState<ThemePreference>(getStoredThemePreference);
  const [systemTheme, setSystemTheme] = useState<Theme>(getSystemTheme);
  // Trinyx defaults to its dark brand theme during SSR/hydration and for visitors
  // with no Trinyx-specific saved preference. Explicit choices remain supported.
  const effectivePreference: ThemePreference = isClient ? themePreference : 'dark';
  const effectiveSystemTheme: Theme = isClient ? systemTheme : 'dark';
  const theme: Theme = effectivePreference === 'auto' ? effectiveSystemTheme : effectivePreference;

  // Apply the resolved theme to the document and persist the selected preference.
  useEffect(() => {
    if (!isClient) return;

    applyThemeClasses(theme);
    localStorage.setItem(THEME_STORAGE_KEY, themePreference);
  }, [theme, themePreference, isClient]);

  // Keep auto mode synchronized with the OS preference.
  useEffect(() => {
    if (!isClient) return;
    if (themePreference !== 'auto') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handleSystemThemeChange = (event: MediaQueryListEvent) => {
      setSystemTheme(event.matches ? 'dark' : 'light');
    };

    if (typeof mediaQuery.addEventListener === 'function') {
      mediaQuery.addEventListener('change', handleSystemThemeChange);
      return () => {
        mediaQuery.removeEventListener('change', handleSystemThemeChange);
      };
    }

    mediaQuery.addListener(handleSystemThemeChange);
    return () => {
      mediaQuery.removeListener(handleSystemThemeChange);
    };
  }, [themePreference, isClient]);

  const toggleTheme = () => {
    setThemePreference(theme === 'dark' ? 'light' : 'dark');
  };

  const setTheme = (newTheme: ThemePreference) => {
    if (newTheme === 'auto') {
      setSystemTheme(getSystemTheme());
    }
    setThemePreference(newTheme);
  };

  return (
    <ThemeContext.Provider value={{ theme, themePreference: effectivePreference, toggleTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}
