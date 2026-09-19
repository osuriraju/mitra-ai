import type { Metadata } from 'next';
import { GoalsScreen } from '@/screens/goals';

export const metadata: Metadata = { title: 'Goals' };
export default function Page() { return <GoalsScreen />; }
