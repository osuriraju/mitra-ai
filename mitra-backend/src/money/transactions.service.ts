import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, Transaction, TxKind } from '@prisma/client';
import { ActivityService } from '../activity/activity.service';
import { fromISODate, toMajor, toMinor } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { AccountsService } from './accounts.service';
import { CategoriesService } from './categories.service';
import { transactionOut } from './mapper';
import { CreateTransaction, ListTransactions, UpdateTransaction } from './schemas';

type Effect = { accountId: string; delta: bigint };
/** How a transaction moves money between the user's accounts. Reversing = negating the deltas. */
const effects = (t: { kind: TxKind; amountMinor: bigint; accountId: string; toAccountId: string | null }): Effect[] =>
  t.kind === 'expense' ? [{ accountId: t.accountId, delta: -t.amountMinor }]
  : t.kind === 'income' ? [{ accountId: t.accountId, delta: t.amountMinor }]
  : [{ accountId: t.accountId, delta: -t.amountMinor }, { accountId: t.toAccountId!, delta: t.amountMinor }];

const fmtInr = (n: number) => '₹' + Math.round(n).toLocaleString('en-IN');

@Injectable()
export class TransactionsService {
  constructor(private readonly prisma: PrismaService, private readonly accounts: AccountsService, private readonly categories: CategoriesService, private readonly activity: ActivityService) {}

  async list(userId: string, q: ListTransactions) {
    const where: Prisma.TransactionWhereInput = { userId };
    if (q.month) { const [y, m] = q.month.split('-').map(Number); where.date = { gte: new Date(Date.UTC(y, m - 1, 1)), lt: new Date(Date.UTC(y, m, 1)) }; }
    else if (q.from || q.to) where.date = { ...(q.from ? { gte: fromISODate(q.from) } : {}), ...(q.to ? { lte: fromISODate(q.to) } : {}) };
    if (q.categoryId) where.categoryId = q.categoryId; if (q.accountId) where.accountId = q.accountId; if (q.kind) where.kind = q.kind;
    if (q.q) where.OR = [{ merchant: { contains: q.q, mode: 'insensitive' } }, { note: { contains: q.q, mode: 'insensitive' } }];
    const rows = await this.prisma.transaction.findMany({ where, orderBy: [{ date: 'desc' }, { time: 'desc' }, { createdAt: 'desc' }], take: q.limit });
    return rows.map(transactionOut);
  }

  async create(userId: string, input: CreateTransaction) {
    const t = await this.prisma.$transaction(async (db) => {
      await this.validateRefs(db, userId, input);
      const row = await db.transaction.create({ data: {
        id: input.id, userId, kind: input.kind, amountMinor: toMinor(input.amount), categoryId: input.categoryId ?? null, accountId: input.accountId, toAccountId: input.kind === 'transfer' ? input.toAccountId : null,
        merchant: input.merchant, note: input.note ?? null, date: fromISODate(input.date), time: input.time, source: input.source, recurringId: input.recurringId ?? null, tags: input.tags,
      } });
      await this.apply(db, effects(row));
      const cat = row.categoryId ? await db.category.findUnique({ where: { id: row.categoryId } }) : null;
      const label = row.kind === 'income' ? 'Income' : row.kind === 'transfer' ? 'Transfer' : '';
      await this.activity.log(userId, { module: 'money', entityId: row.id, icon: 'wallet', tone: row.source === 'ai' ? 'accent' : '', text: `${label ? label + ' ' : ''}${fmtInr(toMajor(row.amountMinor)!)} ${row.merchant || cat?.name || ''}${row.source === 'ai' ? ' · captured by AI' : row.source === 'recurring' ? ' · recurring' : ''}`.trim() }, db);
      return row;
    });
    return transactionOut(t);
  }

  async update(userId: string, id: string, patch: UpdateTransaction) {
    const t = await this.prisma.$transaction(async (db) => {
      const old = await this.own(userId, id, db);
      const next = { kind: patch.kind ?? old.kind, amountMinor: patch.amount !== undefined ? toMinor(patch.amount) : old.amountMinor, accountId: patch.accountId ?? old.accountId, toAccountId: patch.toAccountId !== undefined ? patch.toAccountId : old.toAccountId, categoryId: patch.categoryId !== undefined ? patch.categoryId : old.categoryId };
      if (next.kind !== 'transfer') next.toAccountId = null;
      else if (!next.toAccountId || next.toAccountId === next.accountId) throw new BadRequestException({ code: 'VALIDATION', message: 'Pick a different destination account for the transfer' });
      await this.validateRefs(db, userId, { accountId: next.accountId, toAccountId: next.toAccountId, categoryId: next.categoryId, kind: next.kind });
      await this.apply(db, effects(old).map((e) => ({ ...e, delta: -e.delta }))); // undo the old movement…
      await this.apply(db, effects(next));                                          // …then apply the new one
      return db.transaction.update({ where: { id }, data: {
        ...next, merchant: patch.merchant ?? old.merchant, note: patch.note !== undefined ? patch.note : old.note, date: patch.date ? fromISODate(patch.date) : old.date, time: patch.time ?? old.time, tags: patch.tags ?? old.tags, version: { increment: 1 },
      } });
    });
    return transactionOut(t);
  }

  async remove(userId: string, id: string) {
    await this.prisma.$transaction(async (db) => {
      const old = await this.own(userId, id, db);
      await this.apply(db, effects(old).map((e) => ({ ...e, delta: -e.delta })));
      await db.transaction.delete({ where: { id } });
    });
    return { ok: true };
  }

  private async apply(db: Prisma.TransactionClient, fx: Effect[]) {
    for (const e of fx) await db.account.update({ where: { id: e.accountId }, data: { balanceMinor: { increment: e.delta }, version: { increment: 1 } } });
  }

  private async validateRefs(db: Prisma.TransactionClient, userId: string, t: { accountId: string; toAccountId?: string | null; categoryId?: string | null; kind: TxKind }) {
    await this.accounts.own(userId, t.accountId, db);
    if (t.kind === 'transfer' && t.toAccountId) await this.accounts.own(userId, t.toAccountId, db);
    if (t.categoryId) await this.categories.own(userId, t.categoryId, db);
  }

  async own(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma): Promise<Transaction> {
    const t = await db.transaction.findFirst({ where: { id, userId } });
    if (!t) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Transaction not found' });
    return t;
  }
}
