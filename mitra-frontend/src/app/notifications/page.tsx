import type { Metadata } from 'next';
import { NotificationsScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Notifications' };
export default function Page() { return <NotificationsScreen />; }
