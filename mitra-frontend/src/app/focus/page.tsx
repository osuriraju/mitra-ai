import type { Metadata } from 'next';
import { FocusScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Focus' };
export default async function Page({ searchParams }: { searchParams: Promise<{ task?: string }> }) { const { task } = await searchParams; return <FocusScreen taskId={task} />; }
