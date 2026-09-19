import type { Metadata } from 'next';
import { ProjectsScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Projects' };
export default function Page() { return <ProjectsScreen />; }
