import type { Metadata } from 'next';
import { BudgetsScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Budgets' };
export default function Page() { return <BudgetsScreen />; }
