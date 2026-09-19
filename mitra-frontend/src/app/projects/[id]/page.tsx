import type { Metadata } from 'next';
import { ProjectDetailScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Project' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <ProjectDetailScreen id={id} />; }
