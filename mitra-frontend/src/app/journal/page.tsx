import type { Metadata } from 'next';
import { JournalScreen } from '@/screens/notes';

export const metadata: Metadata = { title: 'Journal' };
export default function Page() { return <JournalScreen />; }
