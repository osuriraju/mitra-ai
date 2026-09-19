import type { Metadata } from 'next';
import { HabitHistoryScreen } from '@/screens/habits';

export const metadata: Metadata = { title: 'Habit history' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <HabitHistoryScreen id={id} />; }
