import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { fromISODate, toMajor, toMinor, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { recurringOut, transactionOut } from './mapper';
import { CreateRecurring, PayRecurring, UpdateRecurring } from './schemas';
import { TransactionsService } from './transactions.service';

const idInUse = () => new ConflictException({ code: 'ID_CONFLICT', message: 'That transaction id is already in use' });

@Injectable()
export class RecurringService {
  constructor(private readonly prisma: PrismaService, private readonly transactions: TransactionsService) {}

  async list(userId: string) { return (await this.prisma.recurring.findMany({ where: { userId }, orderBy: { day: 'asc' } })).map(recurringOut); }

  async create(userId: string, input: CreateRecurring) {
    const { amount, ...rest } = input;
    return recurringOut(await this.prisma.recurring.create({ data: { ...rest, userId, amountMinor: toMinor(amount) } }));
  }

  async update(userId: string, id: string, patch: UpdateRecurring) {
    await this.own(userId, id);
    const { amount, ...rest } = patch;
    return recurringOut(await this.prisma.recurring.update({ where: { id }, data: { ...rest, ...(amount !== undefined ? { amountMinor: toMinor(amount) } : {}), version: { increment: 1 } } }));
  }

  /** Past transactions are kept (their recurringId becomes null). */
  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.recurring.delete({ where: { id } }); return { ok: true }; }

  /**
   * "Mark paid": records this month's occurrence as a real transaction, tagged `source: recurring`.
   * Atomic (transaction row, balance effects, activity and `lastPaidOn` commit together) and idempotent on the
   * client's transaction id: a retry of a pay that already landed returns that payment without moving money again;
   * an id already used by any other transaction is a 409.
   */
  async pay(userId: string, id: string, input: PayRecurring) {
    if (input.id) { const prior = await this.priorPayment(userId, id, input.id); if (prior) return prior; }
    try {
      return await this.prisma.$transaction(async (db) => {
        const r = await this.own(userId, id, db);
        const accountId = input.accountId ?? r.accountId ?? (await db.account.findFirst({ where: { userId, archived: false }, orderBy: { createdAt: 'asc' } }))?.id;
        if (!accountId) throw new BadRequestException({ code: 'NO_ACCOUNT', message: 'Add an account first' });
        const kind = r.kind === 'income' ? 'income' : r.kind === 'transfer' ? 'transfer' : 'expense';
        if (kind === 'transfer' && !r.toAccountId) throw new BadRequestException({ code: 'NO_TO_ACCOUNT', message: 'Set a destination account on this recurring transfer first' });
        const categoryId = r.categoryId ?? (await db.category.findFirst({ where: { userId, slug: kind === 'transfer' ? 'transfer' : kind === 'income' ? 'salary' : r.kind === 'bill' ? 'home' : undefined }, select: { id: true } }))?.id ?? null;
        const date = input.date ?? todayISO();
        const tx = await this.transactions.createIn(db, userId, { id: input.id, kind, amount: input.amount ?? toMajor(r.amountMinor)!, categoryId, accountId, toAccountId: r.toAccountId, merchant: r.name, date, time: new Date().toTimeString().slice(0, 5), source: 'recurring', recurringId: r.id, tags: [], note: null });
        const updated = await db.recurring.update({ where: { id }, data: { lastPaidOn: fromISODate(date) } });
        return { transaction: transactionOut(tx), recurring: recurringOut(updated) };
      });
    } catch (e) {
      // Lost a race with a concurrent request carrying the same id: the winner's payment is the answer.
      if (input.id && e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
        const prior = await this.priorPayment(userId, id, input.id);
        if (prior) return prior;
        throw idInUse();
      }
      throw e;
    }
  }

  /** The earlier pay of this recurring item that used `txId`, null when the id is unused; 409 when it's taken by anything else. */
  private async priorPayment(userId: string, id: string, txId: string) {
    const t = await this.prisma.transaction.findUnique({ where: { id: txId } });
    if (!t) return null;
    if (t.userId !== userId || t.recurringId !== id || t.source !== 'recurring') throw idInUse();
    return { transaction: transactionOut(t), recurring: recurringOut(await this.own(userId, id)) };
  }

  private async own(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const r = await db.recurring.findFirst({ where: { id, userId } });
    if (!r) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Recurring item not found' });
    return r;
  }
}
