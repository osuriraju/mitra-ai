import type { Metadata } from 'next';
import { SettingsScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Settings' };
export default function Page() { return <SettingsScreen />; }
