import type { Metadata } from 'next';
import { StatesScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'UI states' };
export default function Page() { return <StatesScreen />; }
