import type { Metadata } from 'next';
import { RecurringScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Recurring' };
export default function Page() { return <RecurringScreen />; }
