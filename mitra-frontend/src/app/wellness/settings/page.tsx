import type { Metadata } from 'next';
import { WellnessSettingsScreen } from '@/screens/wellness';

export const metadata: Metadata = { title: 'Wellness settings' };
export default function Page() { return <WellnessSettingsScreen />; }
