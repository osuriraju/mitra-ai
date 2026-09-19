import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { fromISODate, toMajor, toMinor, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { recurringOut } from './mapper';
import { CreateRecurring, PayRecurring, UpdateRecurring } from './schemas';
import { TransactionsService } from './transactions.service';

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

  /** "Mark paid": records this month's occurrence as a real transaction, tagged `source: recurring`. */
  async pay(userId: string, id: string, input: PayRecurring) {
    const r = await this.own(userId, id);
    const accountId = input.accountId ?? r.accountId ?? (await this.prisma.account.findFirst({ where: { userId, archived: false }, orderBy: { createdAt: 'asc' } }))?.id;
    if (!accountId) throw new BadRequestException({ code: 'NO_ACCOUNT', message: 'Add an account first' });
    const kind = r.kind === 'income' ? 'income' : r.kind === 'transfer' ? 'transfer' : 'expense';
    if (kind === 'transfer' && !r.toAccountId) throw new BadRequestException({ code: 'NO_TO_ACCOUNT', message: 'Set a destination account on this recurring transfer first' });
    const categoryId = r.categoryId ?? (await this.prisma.category.findFirst({ where: { userId, slug: kind === 'transfer' ? 'transfer' : kind === 'income' ? 'salary' : r.kind === 'bill' ? 'home' : undefined }, select: { id: true } }))?.id ?? null;
    const date = input.date ?? todayISO();
    const tx = await this.transactions.create(userId, { id: input.id, kind, amount: input.amount ?? toMajor(r.amountMinor)!, categoryId, accountId, toAccountId: r.toAccountId, merchant: r.name, date, time: new Date().toTimeString().slice(0, 5), source: 'recurring', recurringId: r.id, tags: [], note: null });
    const updated = await this.prisma.recurring.update({ where: { id }, data: { lastPaidOn: fromISODate(date) } });
    return { transaction: tx, recurring: recurringOut(updated) };
  }

  private async own(userId: string, id: string) {
    const r = await this.prisma.recurring.findFirst({ where: { id, userId } });
    if (!r) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Recurring item not found' });
    return r;
  }
}
