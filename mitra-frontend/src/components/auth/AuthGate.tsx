'use client';
/* Decides between the app shell and the auth screens. Runs once on mount (GET /auth/me), then:
   guest on a private route → /login · signed in on an auth route → /today · signed in but not onboarded → /onboarding.
   Renders nothing until the session is known so private screens never flash for a guest. */
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, type ReactNode } from 'react';
import { href } from '@/lib/routes';
import { useStore } from '@/store';

const AUTH_ROUTES = ['/login', '/signup', '/forgot-password', '/reset-password'];
const PUBLIC_ROUTES = [...AUTH_ROUTES, '/design-system', '/states'];

export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useStore((s) => s.auth); const user = useStore((s) => s.user); const fetchMe = useStore((s) => s.fetchMe); const hydrated = useStore((s) => s.hydrated);
  const pathname = usePathname(); const router = useRouter();
  const isAuthRoute = AUTH_ROUTES.includes(pathname); const isPublic = PUBLIC_ROUTES.includes(pathname);

  useEffect(() => { if (hydrated && auth === 'unknown') void fetchMe(); }, [hydrated, auth, fetchMe]);
  useEffect(() => {
    if (auth === 'unknown') return;
    if (auth === 'guest' && !isPublic) router.replace(`${href('login')}${pathname && pathname !== '/' && pathname !== '/today' ? `?next=${encodeURIComponent(pathname)}` : ''}`);
    else if (auth === 'authed' && isAuthRoute) router.replace(href('today'));
    else if (auth === 'authed' && user && !user.onboardedAt && pathname !== '/onboarding' && !isPublic) router.replace(href('onboarding'));
  }, [auth, user, isAuthRoute, isPublic, pathname, router]);

  if (isPublic) return <>{children}</>;
  if (auth !== 'authed') return <div className="app" aria-busy="true"><div className="content center"><div className="wrap narrow"><div className="row" style={{ gap: 10, justifyContent: 'center', padding: 40 }}><div className="mark">M</div><span className="muted sm">{auth === 'unknown' ? 'Signing you in…' : 'Redirecting…'}</span></div></div></div></div>;
  return <>{children}</>;
}
