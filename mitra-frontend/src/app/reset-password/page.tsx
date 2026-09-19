import type { Metadata } from 'next';
import { Suspense } from 'react';
import { ResetPasswordScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'New password' };
// useSearchParams needs a Suspense boundary for static rendering
export default function Page() { return <Suspense fallback={null}><ResetPasswordScreen /></Suspense>; }
