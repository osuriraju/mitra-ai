import type { Metadata } from 'next';
import { AiScreen } from '@/screens/ai';

export const metadata: Metadata = { title: 'Mitra AI' };
export default function Page() { return <AiScreen />; }
