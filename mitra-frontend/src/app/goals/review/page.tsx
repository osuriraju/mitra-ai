import type { Metadata } from 'next';
import { GoalReviewScreen } from '@/screens/goals';

export const metadata: Metadata = { title: 'Goal review' };
export default function Page() { return <GoalReviewScreen />; }
