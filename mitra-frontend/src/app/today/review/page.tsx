import type { Metadata } from 'next';
import { DailyReviewScreen } from '@/screens/today';

export const metadata: Metadata = { title: 'Daily review' };
export default function Page() { return <DailyReviewScreen />; }
