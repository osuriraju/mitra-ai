import type { Metadata } from 'next';
import { CommandScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Search' };
export default function Page() { return <CommandScreen />; }
