import { Injectable, NotFoundException } from '@nestjs/common';
import { fromISODate, toMinor } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { subscriptionOut } from './mapper';
import { CreateSubscription, UpdateSubscription } from './schemas';

const plusDays = (n: number) => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

@Injectable()
export class SubscriptionsService {
  constructor(private readonly prisma: PrismaService) {}

  async list(userId: string) { return (await this.prisma.subscription.findMany({ where: { userId }, orderBy: { next: 'asc' } })).map(subscriptionOut); }

  async create(userId: string, input: CreateSubscription) {
    const { amount, next, lastUsed, letter, ...rest } = input;
    const s = await this.prisma.subscription.create({ data: { ...rest, userId, amountMinor: toMinor(amount), next: fromISODate(next ?? plusDays(input.cycle === 'Yearly' ? 365 : 30)), lastUsed: lastUsed ? fromISODate(lastUsed) : null, letter: letter ?? input.name[0].toUpperCase() } });
    return subscriptionOut(s);
  }

  async update(userId: string, id: string, patch: UpdateSubscription) {
    await this.own(userId, id);
    const { amount, next, lastUsed, ...rest } = patch;
    const s = await this.prisma.subscription.update({ where: { id }, data: { ...rest, ...(amount !== undefined ? { amountMinor: toMinor(amount) } : {}), ...(next ? { next: fromISODate(next) } : {}), ...(lastUsed !== undefined ? { lastUsed: lastUsed ? fromISODate(lastUsed) : null } : {}), version: { increment: 1 } } });
    return subscriptionOut(s);
  }

  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.subscription.delete({ where: { id } }); return { ok: true }; }

  private async own(userId: string, id: string) {
    const s = await this.prisma.subscription.findFirst({ where: { id, userId } });
    if (!s) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Subscription not found' });
    return s;
  }
}
