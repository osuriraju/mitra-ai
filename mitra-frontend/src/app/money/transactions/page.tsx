import type { Metadata } from 'next';
import { TransactionsScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Transactions' };
export default function Page() { return <TransactionsScreen />; }
