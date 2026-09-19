'use client';
/* Light / dark mode for the .frame root. Persisted in localStorage (per-device convenience). */
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { rehydrateStore } from '@/store';
import { AuthGate } from '@/components/auth/AuthGate';

export type Mode = 'dark' | 'light' | 'auto';
type Ctx = { mode: Mode; setMode: (m: Mode) => void; resolved: 'dark' | 'light' };
const ThemeCtx = createContext<Ctx>({ mode: 'dark', setMode: () => {}, resolved: 'dark' });
export const useTheme = () => useContext(ThemeCtx);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<Mode>('dark');
  const [system, setSystem] = useState<'dark' | 'light'>('dark');
  useEffect(() => {
    rehydrateStore();
    try { const a = localStorage.getItem('mitra.accent'); if (a && a !== '#2DD4BF') document.querySelector<HTMLElement>('.frame')?.style.setProperty('--accent', a); } catch {}
    try { const m = localStorage.getItem('mitra.mode') as Mode | null; if (m) setModeState(m); } catch {}
    const mq = window.matchMedia('(prefers-color-scheme: light)');
    const apply = () => setSystem(mq.matches ? 'light' : 'dark'); apply();
    mq.addEventListener('change', apply); return () => mq.removeEventListener('change', apply);
  }, []);
  const setMode = (m: Mode) => { setModeState(m); try { localStorage.setItem('mitra.mode', m); } catch {} };
  const resolved = mode === 'auto' ? system : mode;
  return (
    <ThemeCtx.Provider value={{ mode, setMode, resolved }}>
      <div className="frame" data-theme="noir" data-mode={resolved}><AuthGate>{children}</AuthGate></div>
    </ThemeCtx.Provider>
  );
}
