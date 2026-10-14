import { ConflictException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { beforeEach, describe, expect, it } from 'vitest';
import { ActivityService } from '../activity/activity.service';
import type { PrismaService } from '../prisma/prisma.service';
import { AccountsService } from './accounts.service';
import { CategoriesService } from './categories.service';
import { RecurringService } from './recurring.service';
import { TransactionsService } from './transactions.service';

/* A tiny in-memory stand-in for the Prisma calls `pay` makes. `$transaction` snapshots the tables and restores them
 * when the callback throws, like a rolled-back database transaction. */
type Row = Record<string, any>;
type Tables = Record<'account' | 'category' | 'transaction' | 'recurring' | 'activity', Row[]>;
const matches = (row: Row, where: Row = {}) => Object.entries(where).every(([k, v]) => v === undefined || row[k] === v);
const clone = (t: Tables): Tables => Object.fromEntries(Object.entries(t).map(([k, rows]) => [k, rows.map((r) => ({ ...r }))])) as Tables;
const p2002 = () => new Prisma.PrismaClientKnownRequestError('Unique constraint failed on the fields: (`id`)', { code: 'P2002', clientVersion: 'test' });

function fakePrisma(tables: Tables, hooks: { beforeRecurringUpdate?: () => void; onTransactionCreate?: (data: Row) => void } = {}, external: (() => void)[] = []) {
  const model = (name: keyof Tables) => ({
    findFirst: async ({ where }: Row) => tables[name].find((r) => matches(r, where)) ?? null,
    findUnique: async ({ where }: Row) => tables[name].find((r) => matches(r, where)) ?? null,
    create: async ({ data }: Row) => {
      if (name === 'transaction') hooks.onTransactionCreate?.(data);
      const row = { ...data, id: data.id ?? `${name}-${tables[name].length + 1}` };
      if (tables[name].some((r) => r.id === row.id)) throw p2002();
      tables[name].push(row);
      return { ...row };
    },
    update: async ({ where, data }: Row) => {
      if (name === 'recurring') hooks.beforeRecurringUpdate?.();
      const row = tables[name].find((r) => matches(r, where));
      if (!row) throw new Error(`${name} ${where.id} not found`);
      for (const [k, v] of Object.entries(data)) row[k] = v && typeof v === 'object' && 'increment' in (v as Row) ? row[k] + (v as Row).increment : v;
      return { ...row };
    },
  });
  const client: Row = { account: model('account'), category: model('category'), transaction: model('transaction'), recurring: model('recurring'), activity: model('activity') };
  client.$transaction = async (fn: (db: Row) => Promise<unknown>) => {
    const snapshot = clone(tables);
    // A rollback undoes only this transaction's writes; anything another request committed meanwhile (`external`) stays.
    try { return await fn(client); } catch (e) { Object.assign(tables, snapshot); external.splice(0).forEach((commit) => commit()); throw e; }
  };
  return client as unknown as PrismaService;
}

const USER = 'user-1';
const RENT = '11111111-1111-4111-8111-111111111111';
const TX_ID = '22222222-2222-4222-8222-222222222222';

function seed(): Tables {
  return {
    account: [{ id: 'acc-1', userId: USER, archived: false, balanceMinor: 100_000_00n, version: 1, createdAt: new Date(0) }],
    category: [{ id: 'cat-home', userId: USER, slug: 'home', name: 'Home' }],
    transaction: [],
    recurring: [{ id: RENT, userId: USER, name: 'Rent', emoji: '🏠', amountMinor: 25_000_00n, day: 5, method: 'UPI', kind: 'bill', reminder: false, categoryId: null, accountId: 'acc-1', toAccountId: null, lastPaidOn: null, version: 1 }],
    activity: [],
  };
}

function service(tables: Tables, hooks?: Parameters<typeof fakePrisma>[1], external?: (() => void)[]) {
  const prisma = fakePrisma(tables, hooks, external);
  const tx = new TransactionsService(prisma, new AccountsService(prisma), new CategoriesService(prisma), new ActivityService(prisma));
  return new RecurringService(prisma, tx);
}

describe('RecurringService.pay', () => {
  let tables: Tables;
  beforeEach(() => { tables = seed(); });

  it('records the payment, moves the balance and sets lastPaidOn', async () => {
    const res = await service(tables).pay(USER, RENT, { id: TX_ID, date: '2026-10-05' });
    expect(res.transaction).toMatchObject({ id: TX_ID, kind: 'expense', amount: 25000, accountId: 'acc-1', categoryId: 'cat-home', merchant: 'Rent', date: '2026-10-05', source: 'recurring', recurringId: RENT });
    expect(res.recurring).toMatchObject({ id: RENT, lastPaidOn: '2026-10-05' });
    expect(tables.account[0].balanceMinor).toBe(75_000_00n);
    expect(tables.transaction).toHaveLength(1);
    expect(tables.activity).toHaveLength(1);
  });

  it('a retry with the same id returns the original payment and moves money once', async () => {
    const svc = service(tables);
    const first = await svc.pay(USER, RENT, { id: TX_ID, date: '2026-10-05' });
    const retry = await svc.pay(USER, RENT, { id: TX_ID, date: '2026-10-05' });
    expect(retry).toEqual(first);
    expect(tables.transaction).toHaveLength(1);
    expect(tables.activity).toHaveLength(1);
    expect(tables.account[0].balanceMinor).toBe(75_000_00n);
    expect(tables.recurring[0].lastPaidOn).toEqual(new Date('2026-10-05T00:00:00.000Z'));
  });

  it('a concurrent duplicate that loses the insert race (P2002) returns the winner\'s payment', async () => {
    // The pre-check sees no row; the "other request" commits the same id just before this one inserts.
    const external: (() => void)[] = [];
    const commitWinner = (data: Row) => () => {
      tables.transaction.push({ ...data, id: TX_ID });
      tables.account[0].balanceMinor -= data.amountMinor;
      tables.recurring[0].lastPaidOn = data.date;
    };
    let raced = false;
    const svc = service(tables, { onTransactionCreate: (data) => {
      if (raced) return;
      raced = true;
      external.push(commitWinner(data));
      throw p2002();
    } }, external);
    const res = await svc.pay(USER, RENT, { id: TX_ID, date: '2026-10-05' });
    expect(res.transaction).toMatchObject({ id: TX_ID, recurringId: RENT, amount: 25000 });
    expect(res.recurring.lastPaidOn).toBe('2026-10-05');
    expect(tables.transaction).toHaveLength(1);
    expect(tables.account[0].balanceMinor).toBe(75_000_00n); // only the winner's effect
  });

  it('an id already used by another (non-recurring) transaction is a 409', async () => {
    tables.transaction.push({ id: TX_ID, userId: USER, kind: 'expense', amountMinor: 500_00n, accountId: 'acc-1', toAccountId: null, categoryId: null, merchant: 'Coffee', note: null, date: new Date('2026-10-01T00:00:00Z'), time: '09:00', source: 'user', recurringId: null, tags: [] });
    await expect(service(tables).pay(USER, RENT, { id: TX_ID })).rejects.toBeInstanceOf(ConflictException);
    expect(tables.account[0].balanceMinor).toBe(100_000_00n);
    expect(tables.recurring[0].lastPaidOn).toBeNull();
  });

  it('an id used by another user\'s transaction is a 409, not their data', async () => {
    tables.transaction.push({ id: TX_ID, userId: 'user-2', kind: 'expense', amountMinor: 25_000_00n, accountId: 'acc-x', toAccountId: null, categoryId: null, merchant: 'Rent', note: null, date: new Date('2026-10-05T00:00:00Z'), time: '09:00', source: 'recurring', recurringId: RENT, tags: [] });
    const err = await service(tables).pay(USER, RENT, { id: TX_ID }).catch((e) => e);
    expect(err).toBeInstanceOf(ConflictException);
    expect((err as ConflictException).getStatus()).toBe(409);
    expect(tables.transaction).toHaveLength(1);
  });

  it('a failure after the transaction is written rolls everything back', async () => {
    const svc = service(tables, { beforeRecurringUpdate: () => { throw new Error('db went away'); } });
    await expect(svc.pay(USER, RENT, { id: TX_ID, date: '2026-10-05' })).rejects.toThrow('db went away');
    expect(tables.transaction).toHaveLength(0);
    expect(tables.activity).toHaveLength(0);
    expect(tables.account[0].balanceMinor).toBe(100_000_00n);
    expect(tables.recurring[0].lastPaidOn).toBeNull();
  });
});
