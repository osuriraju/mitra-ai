import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, Task } from '@prisma/client';
import { ActivityService } from '../activity/activity.service';
import { fromISODate, toMinor, todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { projectOut, taskOut } from './mapper';
import { CreateProject, CreateTask, ListTasks, UpdateProject, UpdateTask } from './schemas';

/** Next due date for a recurring task, counted from its current due date (or today if it had none). */
const nextDue = (due: Date | null, recurring: string) => {
  const d = due ? new Date(due) : fromISODate(todayISO());
  if (recurring === 'Daily') d.setUTCDate(d.getUTCDate() + 1);
  else if (recurring === 'Weekly') d.setUTCDate(d.getUTCDate() + 7);
  else if (recurring === 'Monthly') d.setUTCMonth(d.getUTCMonth() + 1);
  else if (recurring === 'Quarterly') d.setUTCMonth(d.getUTCMonth() + 3);
  return d;
};

@Injectable()
export class TasksService {
  constructor(private readonly prisma: PrismaService, private readonly activity: ActivityService) {}

  /* ---------- projects ---------- */
  async listProjects(userId: string) { return (await this.prisma.project.findMany({ where: { userId, archived: false }, orderBy: { createdAt: 'asc' } })).map(projectOut); }
  async createProject(userId: string, input: CreateProject) { return projectOut(await this.prisma.project.create({ data: { ...input, userId } })); }
  async updateProject(userId: string, id: string, patch: UpdateProject) { await this.ownProject(userId, id); return projectOut(await this.prisma.project.update({ where: { id }, data: { ...patch, version: { increment: 1 } } })); }
  /** Tasks go back to the Inbox (FK is SET NULL), nothing is lost. */
  async deleteProject(userId: string, id: string) { await this.ownProject(userId, id); await this.prisma.project.delete({ where: { id } }); return { ok: true }; }

  /* ---------- tasks ---------- */
  /** Open tasks always; completed ones from the last 90 days by default (enough for streaks, reviews and "hide completed"). */
  async list(userId: string, q: ListTasks) {
    const where: Prisma.TaskWhereInput = { userId };
    if (q.projectId) where.projectId = q.projectId;
    if (q.includeDone !== 'true') { const since = fromISODate(q.doneSince ?? new Date(Date.now() - 90 * 86_400_000).toISOString().slice(0, 10)); where.OR = [{ done: false }, { completedAt: { gte: since } }]; }
    return (await this.prisma.task.findMany({ where, orderBy: [{ createdAt: 'desc' }] })).map(taskOut);
  }

  async create(userId: string, input: CreateTask) {
    const t = await this.prisma.$transaction(async (db) => {
      await this.validateRefs(db, userId, input);
      const row = await db.task.create({ data: { ...this.toData(input), userId } as Prisma.TaskUncheckedCreateInput });
      await this.activity.log(userId, { module: 'tasks', entityId: row.id, icon: 'check-square', tone: row.source === 'ai' ? 'accent' : '', text: `Task added · ${row.title}${row.source === 'ai' ? ' · by AI' : ''}` }, db);
      return row;
    });
    return taskOut(t);
  }

  async update(userId: string, id: string, patch: UpdateTask) {
    const old = await this.own(userId, id);
    await this.validateRefs(this.prisma, userId, { ...patch, parentId: patch.parentId }, id);
    const data = this.toData(patch) as Prisma.TaskUncheckedUpdateInput;
    // keep done/status/completedAt coherent whichever one the client changed
    if (patch.status && patch.done === undefined) { data.done = patch.status === 'done'; data.completedAt = patch.status === 'done' ? fromISODate(patch.completedAt ?? todayISO()) : null; }
    if (patch.done !== undefined && !patch.status) { data.status = patch.done ? 'done' : old.status === 'done' ? 'todo' : old.status; data.completedAt = patch.done ? fromISODate(patch.completedAt ?? todayISO()) : null; }
    const t = await this.prisma.task.update({ where: { id }, data: { ...data, version: { increment: 1 } } });
    if (t.done && !old.done) await this.activity.log(userId, { module: 'tasks', entityId: t.id, icon: 'check-square', tone: 'success', text: `${t.title} ✓` });
    return taskOut(t);
  }

  /** Complete ⇄ reopen. Completing a recurring task also creates the next occurrence (returned as `spawned`). */
  async toggle(userId: string, id: string) {
    const old = await this.own(userId, id); const done = !old.done;
    const [t, spawned] = await this.prisma.$transaction(async (db) => {
      const t = await db.task.update({ where: { id }, data: { done, status: done ? 'done' : 'todo', completedAt: done ? fromISODate(todayISO()) : null, version: { increment: 1 } } });
      let next: Task | null = null;
      if (done && old.recurring) {
        const already = await db.task.findFirst({ where: { userId, title: old.title, recurring: old.recurring, done: false, id: { not: id } } });
        if (!already) next = await db.task.create({ data: { userId, title: old.title, projectId: old.projectId, goalId: old.goalId, habitId: old.habitId, due: nextDue(old.due, old.recurring), time: old.time, estMin: old.estMin, pri: old.pri, notes: old.notes, recurring: old.recurring, amountMinor: old.amountMinor, parentId: old.parentId, source: 'recurring' } });
      }
      if (done) await this.activity.log(userId, { module: 'tasks', entityId: t.id, icon: 'check-square', tone: 'success', text: `${t.title} ✓` }, db);
      return [t, next] as const;
    });
    return { task: taskOut(t), spawned: spawned ? taskOut(spawned) : undefined };
  }

  /** Subtasks cascade with their parent. */
  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.task.delete({ where: { id } }); return { ok: true }; }

  private toData(i: Partial<CreateTask>) {
    const { due, completedAt, amount, ...rest } = i;
    return { ...rest, ...(due !== undefined ? { due: due ? fromISODate(due) : null } : {}), ...(completedAt !== undefined ? { completedAt: completedAt ? fromISODate(completedAt) : null } : {}), ...(amount !== undefined ? { amountMinor: amount == null ? null : toMinor(amount) } : {}) };
  }

  private async validateRefs(db: Prisma.TransactionClient | PrismaService, userId: string, i: { projectId?: string | null; parentId?: string | null }, selfId?: string) {
    if (i.projectId) await this.ownProject(userId, i.projectId, db);
    if (i.parentId) { if (i.parentId === selfId) throw new BadRequestException({ code: 'VALIDATION', message: 'A task cannot be its own subtask' }); await this.own(userId, i.parentId, db); }
  }
  async own(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const t = await db.task.findFirst({ where: { id, userId } });
    if (!t) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Task not found' });
    return t;
  }
  async ownProject(userId: string, id: string, db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const p = await db.project.findFirst({ where: { id, userId } });
    if (!p) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Project not found' });
    return p;
  }
}
