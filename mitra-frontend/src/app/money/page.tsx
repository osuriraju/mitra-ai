import type { Metadata } from 'next';
import { MoneyScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Money' };
export default function Page() { return <MoneyScreen />; }
