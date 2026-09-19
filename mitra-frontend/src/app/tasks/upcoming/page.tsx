import type { Metadata } from 'next';
import { TasksUpcomingScreen } from '@/screens/tasks';

export const metadata: Metadata = { title: 'Upcoming' };
export default function Page() { return <TasksUpcomingScreen />; }
