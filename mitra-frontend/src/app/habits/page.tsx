import type { Metadata } from 'next';
import { HabitsScreen } from '@/screens/habits';

export const metadata: Metadata = { title: 'Habits' };
export default function Page() { return <HabitsScreen />; }
