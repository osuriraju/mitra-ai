import type { Metadata } from 'next';
import { CheckinScreen } from '@/screens/wellness';

export const metadata: Metadata = { title: 'Wellness' };
export default function Page() { return <CheckinScreen />; }
