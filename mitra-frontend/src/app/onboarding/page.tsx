import type { Metadata } from 'next';
import { OnboardingScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Onboarding' };
export default function Page() { return <OnboardingScreen />; }
