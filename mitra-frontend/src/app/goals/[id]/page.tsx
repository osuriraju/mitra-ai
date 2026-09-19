import type { Metadata } from 'next';
import { GoalDetailScreen } from '@/screens/goals';

export const metadata: Metadata = { title: 'Goal' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <GoalDetailScreen id={id} />; }
