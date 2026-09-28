'use client';
/* Registers /sw.js so Android Chrome treats Mitra as an installable app (opens standalone, not in a tab). */
import { useEffect } from 'react';

export function ServiceWorkerRegister() {
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {});
  }, []);
  return null;
}
