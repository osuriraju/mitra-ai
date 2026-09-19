import type { Metadata } from 'next';
import { QuickAddScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Quick add' };
export default function Page() { return <QuickAddScreen />; }
