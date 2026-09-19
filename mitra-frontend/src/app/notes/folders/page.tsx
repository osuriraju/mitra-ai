import type { Metadata } from 'next';
import { NotesFoldersScreen } from '@/screens/notes';

export const metadata: Metadata = { title: 'Folders & tags' };
export default function Page() { return <NotesFoldersScreen />; }
