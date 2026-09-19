'use client';
/* Working theme switcher for the Settings screen (the only interactive control in the prototype). */
import { useTheme, type Mode } from '@/components/theme/ThemeProvider';

const OPTS: [string, Mode][] = [['Light', 'light'], ['Dark', 'dark'], ['Auto', 'auto']];
export function ThemeSegment() {
  const { mode, setMode } = useTheme();
  return <div className="segmented">{OPTS.map(([l, m]) => <button type="button" key={m} className={mode === m ? 'on' : ''} onClick={() => setMode(m)}>{l}</button>)}</div>;
}
