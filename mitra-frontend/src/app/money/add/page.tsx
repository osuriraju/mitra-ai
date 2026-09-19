import type { Metadata } from 'next';
import { ExpenseEntryScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Add expense' };
export default function Page() { return <ExpenseEntryScreen />; }
