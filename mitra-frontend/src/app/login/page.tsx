import type { Metadata } from 'next';
import { Suspense } from 'react';
import { LoginScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Login' };
export default function Page() { return <Suspense fallback={null}><LoginScreen /></Suspense>; }
