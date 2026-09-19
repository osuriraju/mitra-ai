import type { Metadata } from 'next';
import { NoteEditorScreen } from '@/screens/notes';

export const metadata: Metadata = { title: 'Note' };
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <NoteEditorScreen id={id} />; }
