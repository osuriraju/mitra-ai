import type { Metadata } from 'next';
import { NotesSearchScreen } from '@/screens/notes';

export const metadata: Metadata = { title: 'Search notes' };
export default function Page() { return <NotesSearchScreen />; }
