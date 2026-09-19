import type { Metadata } from 'next';
import { DesignSystemScreen } from '@/screens/global';

export const metadata: Metadata = { title: 'Design system' };
export default function Page() { return <DesignSystemScreen />; }
