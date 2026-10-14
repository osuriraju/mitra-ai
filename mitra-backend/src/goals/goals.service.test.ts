import { ConflictException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { beforeEach, describe, expect, it } from 'vitest';
import { ActivityService } from '../activity/activity.service';
import type { PrismaService } from '../prisma/prisma.service';
import { GoalsService } from './goals.service';

/* A tiny in-memory stand-in for the Prisma calls `logProgress` makes. `$transaction` snapshots the tables and restores
 * them when the callback throws, like a rolled-back database transaction. */
type Row = Record<string, any>;
type Tables = Record<'goal' | 'milestone' | 'goalProgress' | 'activity', Row[]>;
const matches = (row: Row, where: Row = {}) => Object.entries(where).every(([k, v]) => v === undefined || row[k] === v);
const clone = (t: Tables): Tables => Object.fromEntries(Object.entries(t).map(([k, rows]) => [k, rows.map((r) => ({ ...r }))])) as Tables;
const p2002 = () => new Prisma.PrismaClientKnownRequestError('Unique constraint failed on the fields: (`id`)', { code: 'P2002', clientVersion: 'test' });

function fakePrisma(tables: Tables, hooks: { onProgressCreate?: (data: Row) => void; onMilestoneUpdate?: () => void } = {}, external: (() => void)[] = []) {
  const withIncludes = (name: keyof Tables, row: Row, include?: Row) => {
    if (!include) return { ...row };
    const out: Row = { ...row };
    if (name === 'goal') {
      if (include.milestones) out.milestones = tables.milestone.filter((m) => m.goalId === row.id).sort((a, b) => a.order - b.order).map((m) => ({ ...m }));
      if (include.progress) out.progress = tables.goalProgress.filter((p) => p.goalId === row.id).sort((a, b) => a.date - b.date).map((p) => ({ ...p }));
    }
    if (name === 'goalProgress' && include.goal) out.goal = { ...tables.goal.find((g) => g.id === row.goalId) };
    return out;
  };
  const model = (name: keyof Tables) => ({
    findFirst: async ({ where, include }: Row) => { const r = tables[name].find((x) => matches(x, where)); return r ? withIncludes(name, r, include) : null; },
    findUnique: async ({ where, include }: Row) => { const r = tables[name].find((x) => matches(x, where)); return r ? withIncludes(name, r, include) : null; },
    create: async ({ data }: Row) => {
      if (name === 'goalProgress') hooks.onProgressCreate?.(data);
      const row = { ...data, id: data.id ?? `${name}-${tables[name].length + 1}` };
      if (tables[name].some((r) => r.id === row.id)) throw p2002();
      tables[name].push(row);
      return { ...row };
    },
    update: async ({ where, data, include }: Row) => {
      if (name === 'milestone') hooks.onMilestoneUpdate?.();
      const row = tables[name].find((r) => matches(r, where));
      if (!row) throw new Error(`${name} ${where.id} not found`);
      for (const [k, v] of Object.entries(data)) row[k] = v && typeof v === 'object' && 'increment' in (v as Row) ? row[k] + (v as Row).increment : v;
      return withIncludes(name, row, include);
    },
  });
  const client: Row = { goal: model('goal'), milestone: model('milestone'), goalProgress: model('goalProgress'), activity: model('activity') };
  client.$transaction = async (fn: (db: Row) => Promise<unknown>) => {
    const snapshot = clone(tables);
    // A rollback undoes only this transaction's writes; anything another request committed meanwhile (`external`) stays.
    try { return await fn(client); } catch (e) { Object.assign(tables, snapshot); external.splice(0).forEach((commit) => commit()); throw e; }
  };
  return client as unknown as PrismaService;
}

const USER = 'user-1';
const GOAL = '11111111-1111-4111-8111-111111111111';
const OTHER_GOAL = '33333333-3333-4333-8333-333333333333';
const P_ID = '22222222-2222-4222-8222-222222222222';
const d = (iso: string) => new Date(`${iso}T00:00:00.000Z`);

const goalRow = (id: string, userId: string, name: string, current: number): Row => ({
  id, userId, name, type: 'numeric', term: 'Medium-term', due: d('2027-03-31'), startedOn: d('2026-01-01'), current, target: 100000, unit: '₹', formula: null, nextAction: '',
  reviewEvery: 'Monthly', reviewOn: d('2026-11-01'), status: 'active', notes: '', version: 1, createdAt: new Date(0), updatedAt: new Date(0),
});

function seed(): Tables {
  return {
    goal: [goalRow(GOAL, USER, 'Emergency fund', 50000), goalRow(OTHER_GOAL, USER, 'Car', 0)],
    milestone: [
      { id: 'ms-1', goalId: GOAL, title: '25k', target: 25000, targetDate: null, reachedOn: d('2026-05-01'), notes: null, order: 0, createdAt: new Date(0) },
      { id: 'ms-2', goalId: GOAL, title: '75k', target: 75000, targetDate: null, reachedOn: null, notes: null, order: 1, createdAt: new Date(0) },
      { id: 'ms-3', goalId: GOAL, title: '100k', target: 100000, targetDate: null, reachedOn: null, notes: null, order: 2, createdAt: new Date(0) },
    ],
    goalProgress: [{ id: 'gp-0', goalId: GOAL, date: d('2026-09-01'), value: 50000, note: null, createdAt: new Date(0) }],
    activity: [],
  };
}

const service = (tables: Tables, hooks?: Parameters<typeof fakePrisma>[1], external?: (() => void)[]) => {
  const prisma = fakePrisma(tables, hooks, external);
  return new GoalsService(prisma, new ActivityService(prisma));
};

describe('GoalsService.logProgress', () => {
  let tables: Tables;
  beforeEach(() => { tables = seed(); });

  it('a numeric goal adds the value, auto-ticks milestones and logs a point', async () => {
    const res = await service(tables).logProgress(USER, GOAL, { id: P_ID, value: 30000, note: 'bonus', date: '2026-10-14' });
    expect(res).toMatchObject({ id: GOAL, current: 80000, target: 100000, status: 'active' });
    expect(res.milestones.map((m) => m.reachedOn)).toEqual(['2026-05-01', '2026-10-14', undefined]);
    expect(res.progressLog.at(-1)).toEqual({ date: '2026-10-14', value: 80000, note: 'bonus' });
    expect(tables.goalProgress.at(-1)!.id).toBe(P_ID);
    expect(tables.goal[0].version).toBe(2);
    expect(tables.activity).toHaveLength(1);
  });

  it('a retry with the same id returns the original result and adds the value once', async () => {
    const svc = service(tables);
    const first = await svc.logProgress(USER, GOAL, { id: P_ID, value: 30000, date: '2026-10-14' });
    const retry = await svc.logProgress(USER, GOAL, { id: P_ID, value: 30000, date: '2026-10-14' });
    expect(retry).toEqual(first);
    expect(tables.goal[0].current).toBe(80000);
    expect(tables.goalProgress).toHaveLength(2);
    expect(tables.activity).toHaveLength(1);
    expect(tables.goal[0].version).toBe(2);
  });

  it('a concurrent duplicate that loses the insert race (P2002) returns the winner\'s result', async () => {
    // The pre-check sees no row; the "other request" commits the same id just before this one inserts.
    const external: (() => void)[] = [];
    let raced = false;
    const svc = service(tables, { onProgressCreate: (data) => {
      if (raced) return;
      raced = true;
      external.push(() => {
        tables.goalProgress.push({ ...data, id: P_ID, createdAt: new Date(0) });
        tables.goal[0].current = data.value;
        tables.goal[0].version += 1;
      });
      throw p2002();
    } }, external);
    const res = await svc.logProgress(USER, GOAL, { id: P_ID, value: 30000, date: '2026-10-14' });
    expect(res.current).toBe(80000);
    expect(tables.goal[0].current).toBe(80000); // only the winner's effect
    expect(tables.goalProgress.filter((p) => p.id === P_ID)).toHaveLength(1);
    expect(tables.activity).toHaveLength(0); // the loser's activity rolled back
  });

  it('an id used by another user\'s progress is a 409 and changes nothing', async () => {
    tables.goal.push(goalRow('44444444-4444-4444-8444-444444444444', 'user-2', 'Theirs', 0));
    tables.goalProgress.push({ id: P_ID, goalId: '44444444-4444-4444-8444-444444444444', date: d('2026-10-01'), value: 10, note: null, createdAt: new Date(0) });
    const before = clone(tables);
    const err = await service(tables).logProgress(USER, GOAL, { id: P_ID, value: 30000 }).catch((e) => e);
    expect(err).toBeInstanceOf(ConflictException);
    expect((err as ConflictException).getStatus()).toBe(409);
    expect((err as ConflictException).getResponse()).toMatchObject({ code: 'ID_CONFLICT' });
    expect(tables).toEqual(before);
  });

  it('an id used by the same user on a different goal is a 409 and changes nothing', async () => {
    const svc = service(tables);
    await svc.logProgress(USER, OTHER_GOAL, { id: P_ID, value: 5000, date: '2026-10-14' });
    const before = clone(tables);
    await expect(svc.logProgress(USER, GOAL, { id: P_ID, value: 30000, date: '2026-10-14' })).rejects.toBeInstanceOf(ConflictException);
    expect(tables).toEqual(before);
    expect(tables.goal[0].current).toBe(50000);
  });

  it('a failure after the progress point is written rolls everything back', async () => {
    const before = clone(tables);
    const svc = service(tables, { onMilestoneUpdate: () => { throw new Error('db went away'); } });
    await expect(svc.logProgress(USER, GOAL, { id: P_ID, value: 30000, date: '2026-10-14' })).rejects.toThrow('db went away');
    expect(tables).toEqual(before);
  });

  it('without an id (web) every call applies, as before', async () => {
    const svc = service(tables);
    await svc.logProgress(USER, GOAL, { value: 10000, date: '2026-10-13' });
    const res = await svc.logProgress(USER, GOAL, { value: 10000, date: '2026-10-14' });
    expect(res.current).toBe(70000);
    expect(res.progressLog.map((p) => p.value)).toEqual([50000, 60000, 70000]);
    expect(tables.activity).toHaveLength(2);
  });

  it('a non-numeric goal sets the value and completes only at target', async () => {
    tables.goal[1] = { ...tables.goal[1], type: 'date', current: 10, target: 20 };
    const res = await service(tables).logProgress(USER, OTHER_GOAL, { value: 20, date: '2026-10-14' });
    expect(res).toMatchObject({ current: 20, status: 'completed' });
  });

  it('an unknown goal is a 404', async () => {
    await expect(service(tables).logProgress('user-2', GOAL, { id: P_ID, value: 1 })).rejects.toMatchObject({ status: 404 });
    expect(tables.goalProgress).toHaveLength(1);
  });
});
