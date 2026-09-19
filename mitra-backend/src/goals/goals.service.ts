import { Injectable, NotFoundException } from '@nestjs/common';
import { Goal, GoalProgress, Milestone, Prisma } from '@prisma/client';
import { ActivityService } from '../activity/activity.service';
import { fromISODate, toISODate, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { CreateGoal, LogProgress, MilestoneInput, UpdateGoal, UpdateMilestone } from './schemas';

type Full = Goal & { milestones: Milestone[]; progress: GoalProgress[] };
const INCLUDE = { milestones: { orderBy: [{ order: 'asc' }, { createdAt: 'asc' }] }, progress: { orderBy: { date: 'asc' } } } satisfies Prisma.GoalInclude;
const reviewGap = (every: string) => (every === 'Weekly' ? 7 : every === 'Quarterly' ? 90 : 30);
const plusDays = (iso: string, n: number) => { const d = fromISODate(iso); d.setUTCDate(d.getUTCDate() + n); return d; };

export const milestoneOut = (m: Milestone) => ({ id: m.id, title: m.title, target: m.target ?? undefined, targetDate: toISODate(m.targetDate), reachedOn: toISODate(m.reachedOn), notes: m.notes ?? undefined });
/** Milestone-type goals derive current/target from their milestones; everything else is stored. */
export const goalOut = (g: Full) => {
  const reached = g.milestones.filter((m) => m.reachedOn).length;
  return {
    id: g.id, name: g.name, type: g.type, term: g.term, due: toISODate(g.due)!, startedOn: toISODate(g.startedOn)!,
    current: g.type === 'milestone' ? reached : g.current, target: g.type === 'milestone' ? g.milestones.length : g.target, unit: g.unit, formula: g.formula ?? undefined, nextAction: g.nextAction,
    reviewEvery: g.reviewEvery, reviewOn: toISODate(g.reviewOn)!, status: g.status, notes: g.notes, createdAt: g.createdAt.toISOString(),
    milestones: g.milestones.map(milestoneOut), progressLog: g.progress.map((p) => ({ date: toISODate(p.date)!, value: p.value, note: p.note ?? undefined })),
  };
};

@Injectable()
export class GoalsService {
  constructor(private readonly prisma: PrismaService, private readonly activity: ActivityService) {}

  async list(userId: string) { return (await this.prisma.goal.findMany({ where: { userId }, include: INCLUDE, orderBy: { createdAt: 'asc' } })).map(goalOut); }

  async create(userId: string, input: CreateGoal) {
    const { milestones, due, startedOn, reviewOn, ...rest } = input; const start = startedOn ?? todayISO();
    const g = await this.prisma.goal.create({ data: {
      ...rest, userId, due: fromISODate(due), startedOn: fromISODate(start), reviewOn: reviewOn ? fromISODate(reviewOn) : plusDays(start, reviewGap(input.reviewEvery)),
      milestones: { create: milestones.map((m, i) => this.msData(m, i)) },
    }, include: INCLUDE });
    await this.activity.log(userId, { module: 'goals', entityId: g.id, icon: 'target', text: `Goal set · ${g.name}` });
    return goalOut(g);
  }

  async update(userId: string, id: string, patch: UpdateGoal) {
    const old = await this.own(userId, id);
    const { due, startedOn, reviewOn, ...rest } = patch;
    const g = await this.prisma.goal.update({ where: { id }, data: { ...rest, ...(due ? { due: fromISODate(due) } : {}), ...(startedOn ? { startedOn: fromISODate(startedOn) } : {}), ...(reviewOn ? { reviewOn: fromISODate(reviewOn) } : {}), version: { increment: 1 } }, include: INCLUDE });
    if (patch.status === 'completed' && old.status !== 'completed') await this.activity.log(userId, { module: 'goals', entityId: id, icon: 'target', tone: 'success', text: `Goal completed · ${g.name} 🎉` });
    return goalOut(g);
  }

  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.goal.delete({ where: { id } }); return { ok: true }; }

  /** Numeric goals add the value; other types set it. Auto-ticks value milestones and completes the goal at target. */
  async logProgress(userId: string, id: string, input: LogProgress) {
    const g = await this.prisma.goal.findFirst({ where: { id, userId }, include: INCLUDE }); if (!g) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Goal not found' });
    const current = g.type === 'numeric' ? g.current + input.value : input.value; const date = fromISODate(input.date ?? todayISO());
    const out = await this.prisma.$transaction(async (db) => {
      for (const m of g.milestones) if (m.target != null && !m.reachedOn && current >= m.target) await db.milestone.update({ where: { id: m.id }, data: { reachedOn: date } });
      await db.goalProgress.create({ data: { goalId: id, date, value: current, note: input.note ?? null } });
      const done = g.type !== 'milestone' && g.type !== 'habit' && current >= g.target;
      const updated = await db.goal.update({ where: { id }, data: { current, ...(done && g.status === 'active' ? { status: 'completed' } : {}), version: { increment: 1 } }, include: INCLUDE });
      await this.activity.log(userId, { module: 'goals', entityId: id, icon: 'target', tone: 'accent', text: `Progress logged · ${g.name}` }, db);
      return updated;
    });
    return goalOut(out);
  }

  /* ---------- milestones ---------- */
  async addMilestone(userId: string, goalId: string, input: MilestoneInput) {
    const g = await this.own(userId, goalId); const n = await this.prisma.milestone.count({ where: { goalId } });
    await this.prisma.milestone.create({ data: { ...this.msData(input, n), goalId } });
    return this.get(g.id);
  }
  async updateMilestone(userId: string, goalId: string, id: string, patch: UpdateMilestone) {
    await this.own(userId, goalId); const m = await this.prisma.milestone.findFirst({ where: { id, goalId } }); if (!m) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Milestone not found' });
    const { targetDate, reachedOn, ...rest } = patch;
    await this.prisma.milestone.update({ where: { id }, data: { ...rest, ...(targetDate !== undefined ? { targetDate: targetDate ? fromISODate(targetDate) : null } : {}), ...(reachedOn !== undefined ? { reachedOn: reachedOn ? fromISODate(reachedOn) : null } : {}) } });
    if (reachedOn && !m.reachedOn) {
      await this.activity.log(userId, { module: 'goals', entityId: goalId, icon: 'target', tone: 'success', text: `Milestone reached · ${m.title}` });
      // milestone-type goals: each reached milestone is a progress point, so the chart moves too
      const g = await this.prisma.goal.findUnique({ where: { id: goalId }, include: { milestones: true } });
      if (g?.type === 'milestone') await this.prisma.goalProgress.create({ data: { goalId, date: fromISODate(reachedOn), value: g.milestones.filter((x) => x.reachedOn).length, note: m.title } });
    }
    return this.get(goalId);
  }
  async deleteMilestone(userId: string, goalId: string, id: string) { await this.own(userId, goalId); await this.prisma.milestone.deleteMany({ where: { id, goalId } }); return this.get(goalId); }

  private msData(m: MilestoneInput, order: number) { return { id: m.id, title: m.title, target: m.target ?? null, targetDate: m.targetDate ? fromISODate(m.targetDate) : null, reachedOn: m.reachedOn ? fromISODate(m.reachedOn) : null, notes: m.notes ?? null, order }; }
  private async get(id: string) { return goalOut((await this.prisma.goal.findUnique({ where: { id }, include: INCLUDE }))!); }
  private async own(userId: string, id: string) {
    const g = await this.prisma.goal.findFirst({ where: { id, userId } });
    if (!g) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Goal not found' });
    return g;
  }
}
