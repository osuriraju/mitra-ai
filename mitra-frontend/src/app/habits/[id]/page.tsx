import type { Metadata } from 'next';
import { HabitDetailScreen } from '@/screens/habits';

export const metadata: Metadata = { title: 'Habit' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <HabitDetailScreen id={id} />; }
