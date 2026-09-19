import type { Account, Category, Recurring, Subscription, Transaction } from '@prisma/client';
import { toISODate, toMajor } from '../common/money';

/* DB rows → the exact shapes the client store uses (rupees as numbers, ISO day strings, no nulls where the UI expects undefined). */
export const accountOut = (a: Account) => ({ id: a.id, name: a.name, mask: a.mask, kind: a.kind, balance: toMajor(a.balanceMinor)!, archived: a.archived });
export const categoryOut = (c: Category) => ({ id: c.id, name: c.name, emoji: c.emoji, kind: c.kind, slug: c.slug ?? undefined, budget: toMajor(c.budgetMinor), archived: c.archived });
export const transactionOut = (t: Transaction) => ({
  id: t.id, kind: t.kind, amount: toMajor(t.amountMinor)!, categoryId: t.categoryId ?? undefined, accountId: t.accountId, toAccountId: t.toAccountId ?? undefined,
  merchant: t.merchant, note: t.note ?? undefined, date: toISODate(t.date)!, time: t.time, source: t.source, recurringId: t.recurringId ?? undefined, tags: t.tags,
});
export const recurringOut = (r: Recurring) => ({
  id: r.id, name: r.name, emoji: r.emoji, amount: toMajor(r.amountMinor)!, day: r.day, method: r.method, kind: r.kind, reminder: r.reminder,
  categoryId: r.categoryId ?? undefined, accountId: r.accountId ?? undefined, toAccountId: r.toAccountId ?? undefined, lastPaidOn: toISODate(r.lastPaidOn),
});
export const subscriptionOut = (s: Subscription) => ({
  id: s.id, name: s.name, amount: toMajor(s.amountMinor)!, cycle: s.cycle, next: toISODate(s.next)!, color: s.color, letter: s.letter, lastUsed: toISODate(s.lastUsed),
  categoryId: s.categoryId ?? undefined, accountId: s.accountId ?? undefined,
});
