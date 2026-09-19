import type { Metadata } from 'next';
import { AiSuggestionsScreen } from '@/screens/ai';

export const metadata: Metadata = { title: 'Suggested actions' };
export default function Page() { return <AiSuggestionsScreen />; }
