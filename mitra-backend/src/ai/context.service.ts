import { Injectable } from '@nestjs/common';
import { toISODate, toMajor, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';

/** Builds the *minimum* context a request needs — ids the model must echo back, current numbers, and nothing personal beyond that. */
@Injectable()
export class AiContextService {
  constructor(private readonly prisma: PrismaService) {}

  async build(userId: string, opts: { wellness?: boolean } = {}) {
    const today = todayISO(); const [y, m] = today.split('-').map(Number); const monthStart = new Date(Date.UTC(y, m - 1, 1)); const lastMonthStart = new Date(Date.UTC(y, m - 2, 1));
    const [user, categories, accounts, projects, tasks, goals, habits, txs, recurring, wellness, ws] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId }, select: { name: true, settings: true } }),
      this.prisma.category.findMany({ where: { userId, archived: false } }), this.prisma.account.findMany({ where: { userId, archived: false } }), this.prisma.project.findMany({ where: { userId, archived: false } }),
      this.prisma.task.findMany({ where: { userId, done: false }, orderBy: [{ due: 'asc' }], take: 60 }),
      this.prisma.goal.findMany({ where: { userId }, include: { milestones: true, progress: { orderBy: { date: 'desc' }, take: 1 } } }),
      this.prisma.habit.findMany({ where: { userId, archived: false }, include: { logs: { where: { date: { gte: new Date(Date.now() - 30 * 86_400_000) } } } } }),
      this.prisma.transaction.findMany({ where: { userId, date: { gte: lastMonthStart } }, orderBy: [{ date: 'desc' }, { time: 'desc' }] }),
      this.prisma.recurring.findMany({ where: { userId } }),
      opts.wellness ? this.prisma.wellnessEntry.findMany({ where: { userId, date: { gte: new Date(Date.now() - 7 * 86_400_000) } } }) : Promise.resolve([]),
      opts.wellness ? this.prisma.wellnessSettings.findUnique({ where: { userId } }) : Promise.resolve(null),
    ]);
    const thisMonth = txs.filter((t) => t.date >= monthStart); const lastMonth = txs.filter((t) => t.date < monthStart);
    const byCat = (list: typeof txs) => { const o: Record<string, number> = {}; list.filter((t) => t.kind === 'expense').forEach((t) => { const k = t.categoryId || 'none'; o[k] = (o[k] || 0) + toMajor(t.amountMinor)!; }); return o; };
    const spentNow = byCat(thisMonth), spentLast = byCat(lastMonth);
    const merchants = (catId: string) => [...new Set(txs.filter((t) => t.categoryId === catId && t.merchant).map((t) => t.merchant))].slice(0, 3);
    const rate30 = (h: (typeof habits)[number]) => { const due = Array.from({ length: 30 }, (_, i) => new Date(Date.now() - i * 86_400_000)).filter((d) => h.days[(d.getUTCDay() + 6) % 7]).length; const done = h.logs.filter((l) => l.value >= h.target).length; return due ? Math.round((done / due) * 100) : 0; };
    const sleepMins = (a?: string | null, b?: string | null) => { if (!a || !b) return 0; const [sh, sm] = a.split(':').map(Number); const [eh, em] = b.split(':').map(Number); let v = eh * 60 + em - (sh * 60 + sm); if (v < 0) v += 1440; return v; };
    return {
      today, weekday: new Date().toLocaleDateString('en-IN', { weekday: 'long' }), userFirstName: user?.name.split(' ')[0] ?? 'there', currency: 'INR (₹)', autoSave: (user?.settings as { autoSave?: string })?.autoSave ?? 'Small only',
      categories: categories.map((c) => ({ id: c.id, name: c.name, kind: c.kind, budget: toMajor(c.budgetMinor), spentThisMonth: Math.round(spentNow[c.id] || 0), spentLastMonth: Math.round(spentLast[c.id] || 0), recentMerchants: merchants(c.id) })),
      accounts: accounts.map((a) => ({ id: a.id, name: `${a.name} ${a.mask}`.trim(), kind: a.kind, balance: toMajor(a.balanceMinor) })),
      money: { income: Math.round(thisMonth.filter((t) => t.kind === 'income').reduce((x, t) => x + toMajor(t.amountMinor)!, 0)), spent: Math.round(Object.values(spentNow).reduce((a, b) => a + b, 0)), spentLastMonth: Math.round(Object.values(spentLast).reduce((a, b) => a + b, 0)), recent: thisMonth.slice(0, 15).map((t) => ({ date: toISODate(t.date), merchant: t.merchant, amount: toMajor(t.amountMinor), kind: t.kind, categoryId: t.categoryId })), recurring: recurring.map((r) => ({ name: r.name, amount: toMajor(r.amountMinor), day: r.day, kind: r.kind })) },
      projects: projects.map((p) => ({ id: p.id, name: p.name })),
      openTasks: tasks.map((t) => ({ id: t.id, title: t.title, due: toISODate(t.due), time: t.time, pri: t.pri, estMin: t.estMin, projectId: t.projectId, parentId: t.parentId, status: t.status })),
      goals: goals.map((g) => ({ id: g.id, name: g.name, type: g.type, status: g.status, current: g.type === 'milestone' ? g.milestones.filter((x) => x.reachedOn).length : g.current, target: g.type === 'milestone' ? g.milestones.length : g.target, unit: g.unit, due: toISODate(g.due), lastLogged: toISODate(g.progress[0]?.date), nextAction: g.nextAction })),
      habits: habits.map((h) => ({ id: h.id, name: h.name, type: h.type, target: h.target, unit: h.unit, reminder: h.reminder, consistency30d: rate30(h), goalId: h.goalId })),
      ...(opts.wellness ? { wellness: { last7days: wellness.map((w) => ({ date: toISODate(w.date), mood: w.mood, sleepMin: sleepMins(w.sleepStart, w.sleepEnd), steps: w.steps, water: w.water })), targets: ws ? { sleepMin: ws.sleepTarget, steps: ws.stepsTarget, water: ws.waterTarget } : undefined } } : {}),
    };
  }
}
