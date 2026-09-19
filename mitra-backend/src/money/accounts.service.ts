import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { toMinor } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { accountOut } from './mapper';
import { CreateAccount, UpdateAccount } from './schemas';

@Injectable()
export class AccountsService {
  constructor(private readonly prisma: PrismaService) {}

  async list(userId: string) { return (await this.prisma.account.findMany({ where: { userId, archived: false }, orderBy: { createdAt: 'asc' } })).map(accountOut); }

  async create(userId: string, input: CreateAccount) {
    const a = await this.prisma.account.create({ data: { id: input.id, userId, name: input.name, mask: input.mask, kind: input.kind, balanceMinor: toMinor(input.balance) } });
    return accountOut(a);
  }

  async update(userId: string, id: string, patch: UpdateAccount) {
    await this.own(userId, id);
    const { balance, ...rest } = patch;
    const a = await this.prisma.account.update({ where: { id }, data: { ...rest, ...(balance !== undefined ? { balanceMinor: toMinor(balance) } : {}), version: { increment: 1 } } });
    return accountOut(a);
  }

  /** Accounts with history are archived (transactions keep pointing at them); empty ones are deleted outright. */
  async remove(userId: string, id: string) {
    await this.own(userId, id);
    const used = await this.prisma.transaction.count({ where: { OR: [{ accountId: id }, { toAccountId: id }] } });
    if (used) await this.prisma.account.update({ where: { id }, data: { archived: true } });
    else await this.prisma.account.delete({ where: { id } });
    return { ok: true, archived: used > 0 };
  }

  async own(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const a = await db.account.findFirst({ where: { id, userId } });
    if (!a) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Account not found' });
    return a;
  }
}
