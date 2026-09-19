import type { Metadata } from 'next';
import { TasksInboxScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Inbox' };
export default function Page() { return <TasksInboxScreen />; }
