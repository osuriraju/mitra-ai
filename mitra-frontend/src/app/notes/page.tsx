import type { Metadata } from 'next';
import { NotesScreen } from '@/screens/notes';

export const metadata: Metadata = { title: 'Notes' };
export default function Page() { return <NotesScreen />; }
