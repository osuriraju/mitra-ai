import type { Metadata } from 'next';
import { WellnessTrendsScreen } from '@/screens/wellness';

export const metadata: Metadata = { title: 'Trends' };
export default function Page() { return <WellnessTrendsScreen />; }
