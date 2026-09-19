import type { Metadata } from 'next';
import { TasksTodayScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Tasks' };
export default function Page() { return <TasksTodayScreen />; }
