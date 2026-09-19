import type { Metadata } from 'next';
import { MilestoneDetailScreen } from '@/screens/goals';

export const metadata: Metadata = { title: 'Milestone' };
export default async function Page({ params }: { params: Promise<{ id: string; mid: string }> }) { const { id, mid } = await params; return <MilestoneDetailScreen goalId={id} id={mid} />; }
