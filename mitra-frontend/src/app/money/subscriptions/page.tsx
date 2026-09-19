import type { Metadata } from 'next';
import { SubscriptionsScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Subscriptions' };
export default function Page() { return <SubscriptionsScreen />; }
