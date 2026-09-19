import { z } from 'zod';

/* Client-generated ids are accepted so the PWA can write optimistically (and offline) and reconcile later. */
export const id = z.string().uuid();
export const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date must be YYYY-MM-DD');
export const hhmm = z.string().regex(/^\d{2}:\d{2}$/, 'Time must be HH:MM');
/** Rupees with at most 2 decimals; stored as paise. */
export const amount = z.number().positive('Enter an amount').max(1e9).refine((n) => Math.round(n * 100) === n * 100, 'Amounts can have at most 2 decimals');
const emoji = z.string().trim().min(1).max(8);

export const CreateAccountSchema = z.object({ id: id.optional(), name: z.string().trim().min(1, 'Enter an account name').max(60), mask: z.string().trim().max(40).default(''), kind: z.enum(['bank', 'credit', 'cash']).default('bank'), balance: z.number().max(1e10).min(-1e10).default(0) });
/* Update schemas are spelled out (not `.partial()` of the create ones): in Zod 4 `.partial()` keeps `.default()`s, which would reset untouched fields on PATCH. */
export const UpdateAccountSchema = z.object({ name: z.string().trim().min(1).max(60).optional(), mask: z.string().trim().max(40).optional(), kind: z.enum(['bank', 'credit', 'cash']).optional(), balance: z.number().max(1e10).min(-1e10).optional(), archived: z.boolean().optional() });

export const CreateCategorySchema = z.object({ id: id.optional(), name: z.string().trim().min(1, 'Enter a category name').max(40), emoji: emoji.default('💸'), kind: z.enum(['expense', 'income']).default('expense'), budget: amount.nullable().optional() });
export const UpdateCategorySchema = z.object({ name: z.string().trim().min(1).max(40).optional(), emoji: emoji.optional(), budget: amount.nullable().optional(), archived: z.boolean().optional() });

export const CreateTransactionSchema = z.object({
  id: id.optional(), kind: z.enum(['expense', 'income', 'transfer']).default('expense'), amount,
  categoryId: id.nullable().optional(), accountId: id, toAccountId: id.nullable().optional(),
  merchant: z.string().trim().max(120).default(''), note: z.string().trim().max(500).nullable().optional(),
  date: isoDate, time: hhmm.default('00:00'), source: z.enum(['user', 'import', 'ai', 'recurring']).default('user'), recurringId: id.nullable().optional(), tags: z.array(z.string().max(30)).max(20).default([]),
}).refine((t) => t.kind !== 'transfer' || (t.toAccountId && t.toAccountId !== t.accountId), { message: 'Pick a different destination account for the transfer', path: ['toAccountId'] });
export const UpdateTransactionSchema = z.object({
  kind: z.enum(['expense', 'income', 'transfer']).optional(), amount: amount.optional(), categoryId: id.nullable().optional(), accountId: id.optional(), toAccountId: id.nullable().optional(),
  merchant: z.string().trim().max(120).optional(), note: z.string().trim().max(500).nullable().optional(), date: isoDate.optional(), time: hhmm.optional(), tags: z.array(z.string().max(30)).max(20).optional(),
});
export const ListTransactionsSchema = z.object({
  month: z.string().regex(/^\d{4}-\d{2}$/).optional(), from: isoDate.optional(), to: isoDate.optional(),
  categoryId: id.optional(), accountId: id.optional(), kind: z.enum(['expense', 'income', 'transfer']).optional(), q: z.string().trim().max(100).optional(),
  limit: z.coerce.number().int().positive().max(5000).default(2000),
});

export const CreateRecurringSchema = z.object({
  id: id.optional(), name: z.string().trim().min(1, 'Enter a name').max(80), emoji: emoji.default('🔁'), amount, day: z.number().int().min(1).max(28), method: z.string().trim().max(60).default('UPI'),
  kind: z.enum(['bill', 'income', 'transfer']).default('bill'), reminder: z.boolean().default(false), categoryId: id.nullable().optional(), accountId: id.nullable().optional(), toAccountId: id.nullable().optional(),
});
export const UpdateRecurringSchema = z.object({
  name: z.string().trim().min(1).max(80).optional(), emoji: emoji.optional(), amount: amount.optional(), day: z.number().int().min(1).max(28).optional(), method: z.string().trim().max(60).optional(),
  kind: z.enum(['bill', 'income', 'transfer']).optional(), reminder: z.boolean().optional(), categoryId: id.nullable().optional(), accountId: id.nullable().optional(), toAccountId: id.nullable().optional(),
});
export const PayRecurringSchema = z.object({ id: id.optional(), accountId: id.optional(), date: isoDate.optional(), amount: amount.optional() });

export const CreateSubscriptionSchema = z.object({
  id: id.optional(), name: z.string().trim().min(1, 'Enter a service name').max(80), amount, cycle: z.enum(['Monthly', 'Yearly']).default('Monthly'), next: isoDate.optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).default('#6366F1'), letter: z.string().trim().min(1).max(2).optional(), lastUsed: isoDate.nullable().optional(), categoryId: id.nullable().optional(), accountId: id.nullable().optional(),
});
export const UpdateSubscriptionSchema = z.object({
  name: z.string().trim().min(1).max(80).optional(), amount: amount.optional(), cycle: z.enum(['Monthly', 'Yearly']).optional(), next: isoDate.optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(), letter: z.string().trim().min(1).max(2).optional(), lastUsed: isoDate.nullable().optional(), categoryId: id.nullable().optional(), accountId: id.nullable().optional(),
});

export type CreateAccount = z.infer<typeof CreateAccountSchema>; export type UpdateAccount = z.infer<typeof UpdateAccountSchema>;
export type CreateCategory = z.infer<typeof CreateCategorySchema>; export type UpdateCategory = z.infer<typeof UpdateCategorySchema>;
export type CreateTransaction = z.infer<typeof CreateTransactionSchema>; export type UpdateTransaction = z.infer<typeof UpdateTransactionSchema>; export type ListTransactions = z.infer<typeof ListTransactionsSchema>;
export type CreateRecurring = z.infer<typeof CreateRecurringSchema>; export type UpdateRecurring = z.infer<typeof UpdateRecurringSchema>; export type PayRecurring = z.infer<typeof PayRecurringSchema>;
export type CreateSubscription = z.infer<typeof CreateSubscriptionSchema>; export type UpdateSubscription = z.infer<typeof UpdateSubscriptionSchema>;
