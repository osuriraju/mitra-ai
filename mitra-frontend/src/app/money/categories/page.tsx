import type { Metadata } from 'next';
import { CategoriesScreen } from '@/screens/money';

export const metadata: Metadata = { title: 'Categories' };
export default function Page() { return <CategoriesScreen />; }
