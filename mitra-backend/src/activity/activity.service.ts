import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';

export type ActivityInput = { module: string; text: string; icon?: string; tone?: string; entityId?: string; meta?: Prisma.InputJsonValue };

/** Every important action writes one row here — feeds Today's Activity panel, timeline and (later) AI context. */
@Injectable()
export class ActivityService {
  constructor(private readonly prisma: PrismaService) {}

  /** Accepts a transaction client so the activity row commits together with the change it describes. */
  log(userId: string, a: ActivityInput, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    return db.activity.create({ data: { userId, module: a.module, text: a.text, icon: a.icon ?? 'circle', tone: a.tone ?? '', entityId: a.entityId, meta: a.meta } });
  }

  async list(userId: string, opts: { date?: string; limit?: number }) {
    const where: Prisma.ActivityWhereInput = { userId };
    if (opts.date) { const from = new Date(`${opts.date}T00:00:00`); const to = new Date(from.getTime() + 86_400_000); where.createdAt = { gte: from, lt: to }; }
    const rows = await this.prisma.activity.findMany({ where, orderBy: { createdAt: 'desc' }, take: Math.min(opts.limit ?? 100, 500) });
    return rows.map(toClient);
  }
}

export const toClient = (a: { id: string; text: string; icon: string; tone: string; module: string; entityId: string | null; createdAt: Date }) => ({
  id: a.id, text: a.text, icon: a.icon, tone: a.tone, module: a.module, entityId: a.entityId ?? undefined, at: a.createdAt.toISOString(),
});
