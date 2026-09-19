import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { toMinor } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { categoryOut } from './mapper';
import { CreateCategory, UpdateCategory } from './schemas';

@Injectable()
export class CategoriesService {
  constructor(private readonly prisma: PrismaService) {}

  async list(userId: string) { return (await this.prisma.category.findMany({ where: { userId, archived: false }, orderBy: { createdAt: 'asc' } })).map(categoryOut); }

  async create(userId: string, input: CreateCategory) {
    const c = await this.prisma.category.create({ data: { id: input.id, userId, name: input.name, emoji: input.emoji, kind: input.kind, budgetMinor: input.budget != null ? toMinor(input.budget) : null } });
    return categoryOut(c);
  }

  async update(userId: string, id: string, patch: UpdateCategory) {
    await this.own(userId, id);
    const { budget, ...rest } = patch;
    const c = await this.prisma.category.update({ where: { id }, data: { ...rest, ...(budget !== undefined ? { budgetMinor: budget == null ? null : toMinor(budget) } : {}), version: { increment: 1 } } });
    return categoryOut(c);
  }

  /** Transactions keep their history and simply become uncategorised (FK is SET NULL). */
  async remove(userId: string, id: string) {
    const c = await this.own(userId, id);
    if (c.slug === 'transfer') await this.prisma.category.update({ where: { id }, data: { archived: true } }); // needed by transfers — hide, don't drop
    else await this.prisma.category.delete({ where: { id } });
    return { ok: true };
  }

  async own(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const c = await db.category.findFirst({ where: { id, userId } });
    if (!c) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Category not found' });
    return c;
  }
}
