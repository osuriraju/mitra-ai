import type { Metadata } from 'next';
import { HabitEditScreen } from '@/screens/habits';

export const metadata: Metadata = { title: 'New habit' };
export default function Page() { return <HabitEditScreen />; }
