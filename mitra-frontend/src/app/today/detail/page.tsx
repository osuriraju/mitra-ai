import type { Metadata } from 'next';
import { TodayDetailScreen } from '@/screens/today';

export const metadata: Metadata = { title: 'Daily detail' };
export default function Page() { return <TodayDetailScreen />; }
