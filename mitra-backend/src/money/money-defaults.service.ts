import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';

/** What a brand-new user starts with: the standard Indian-household categories (no budgets yet) and a cash wallet. */
export const DEFAULT_CATEGORIES: { slug: string; name: string; emoji: string; kind: 'expense' | 'income' }[] = [
  { slug: 'food', name: 'Food & dining', emoji: '🍛', kind: 'expense' }, { slug: 'transport', name: 'Transport', emoji: '🚇', kind: 'expense' },
  { slug: 'groceries', name: 'Groceries', emoji: '🛒', kind: 'expense' }, { slug: 'shopping', name: 'Shopping', emoji: '🛍️', kind: 'expense' },
  { slug: 'health', name: 'Health', emoji: '💊', kind: 'expense' }, { slug: 'fun', name: 'Entertainment', emoji: '🎬', kind: 'expense' },
  { slug: 'home', name: 'Housing', emoji: '🏠', kind: 'expense' }, { slug: 'learning', name: 'Learning', emoji: '📚', kind: 'expense' },
  { slug: 'subs', name: 'Subscriptions', emoji: '📺', kind: 'expense' }, { slug: 'transfer', name: 'Transfer', emoji: '🏦', kind: 'expense' },
  { slug: 'salary', name: 'Salary', emoji: '💼', kind: 'income' }, { slug: 'freelance', name: 'Freelance', emoji: '🧾', kind: 'income' },
];

@Injectable()
export class MoneyDefaultsService {
  async createForUser(db: Prisma.TransactionClient, userId: string) {
    await db.category.createMany({ data: DEFAULT_CATEGORIES.map((c) => ({ ...c, userId })) });
    await db.account.create({ data: { userId, name: 'Cash', mask: 'Wallet', kind: 'cash', balanceMinor: 0n } });
  }
}
