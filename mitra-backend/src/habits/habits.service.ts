import { Injectable, NotFoundException } from '@nestjs/common';
import { Habit, HabitLog } from '@prisma/client';
import { ActivityService } from '../activity/activity.service';
import { fromISODate, toISODate, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { CreateHabit, LogInput, UpdateHabit } from './schemas';

/** Client shape: logs as { 'YYYY-MM-DD': value } — that's what every streak/heatmap selector reads. */
export const habitOut = (h: Habit & { logs: HabitLog[] }) => ({
  id: h.id, name: h.name, type: h.type, target: h.target, unit: h.unit, icon: h.icon, timeOfDay: h.timeOfDay, days: h.days, reminder: h.reminder ?? undefined, goalId: h.goalId ?? undefined, gentle: h.gentle,
  archived: h.archived || undefined, createdAt: h.createdAt.toISOString(), logs: Object.fromEntries(h.logs.map((l) => [toISODate(l.date)!, l.value])),
});

@Injectable()
export class HabitsService {
  constructor(private readonly prisma: PrismaService, private readonly activity: ActivityService) {}
  private readonly withLogs = { logs: { where: { date: { gte: new Date(Date.now() - 400 * 86_400_000) } } } } as const; // streaks look back ≤400 days

  async list(userId: string) { return (await this.prisma.habit.findMany({ where: { userId }, include: this.withLogs, orderBy: { createdAt: 'asc' } })).map(habitOut); }
  async create(userId: string, input: CreateHabit) {
    const h = await this.prisma.habit.create({ data: { ...input, userId }, include: this.withLogs });
    await this.activity.log(userId, { module: 'habits', entityId: h.id, icon: h.icon, text: `Habit created · ${h.name}` });
    return habitOut(h);
  }
  async update(userId: string, id: string, patch: UpdateHabit) { await this.own(userId, id); return habitOut(await this.prisma.habit.update({ where: { id }, data: { ...patch, version: { increment: 1 } }, include: this.withLogs })); }
  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.habit.delete({ where: { id } }); return { ok: true }; }

  /** Upsert one day's value; null removes the log. Hitting the target today writes an activity row. */
  async log(userId: string, id: string, input: LogInput) {
    const h = await this.own(userId, id); const date = fromISODate(input.date);
    const before = await this.prisma.habitLog.findUnique({ where: { habitId_date: { habitId: id, date } } });
    if (input.value === null || input.value === 0) await this.prisma.habitLog.deleteMany({ where: { habitId: id, date } });
    else await this.prisma.habitLog.upsert({ where: { habitId_date: { habitId: id, date } }, create: { habitId: id, date, value: input.value }, update: { value: input.value } });
    if (input.value !== null && input.value >= h.target && (!before || before.value < h.target) && input.date === todayISO()) await this.activity.log(userId, { module: 'habits', entityId: id, icon: h.icon, tone: 'success', text: `${h.name} ✓` });
    return habitOut((await this.prisma.habit.findUnique({ where: { id }, include: this.withLogs }))!);
  }

  private async own(userId: string, id: string) {
    const h = await this.prisma.habit.findFirst({ where: { id, userId } });
    if (!h) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Habit not found' });
    return h;
  }
}
