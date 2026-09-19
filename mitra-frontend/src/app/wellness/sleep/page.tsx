import type { Metadata } from 'next';
import { SleepDetailScreen } from '@/screens/wellness';

export const metadata: Metadata = { title: 'Sleep' };
export default function Page() { return <SleepDetailScreen />; }
