import type { Metadata } from 'next';
import { AiLogScreen } from '@/screens/ai';

export const metadata: Metadata = { title: 'AI activity' };
export default function Page() { return <AiLogScreen />; }
