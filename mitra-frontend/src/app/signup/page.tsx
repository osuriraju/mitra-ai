import type { Metadata } from 'next';
import { SignupScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Sign up' };
export default function Page() { return <SignupScreen />; }
