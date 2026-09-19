import type { Metadata } from 'next';
import { TaskDetailScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Task' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <TaskDetailScreen id={id} />; }
