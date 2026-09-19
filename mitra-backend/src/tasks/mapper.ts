import type { Project, Task } from '@prisma/client';
import { toISODate, toMajor } from '../common/money';

export const projectOut = (p: Project) => ({ id: p.id, name: p.name, emoji: p.emoji, goalId: p.goalId ?? undefined, createdAt: p.createdAt.toISOString() });
export const taskOut = (t: Task) => ({
  id: t.id, title: t.title, projectId: t.projectId ?? undefined, goalId: t.goalId ?? undefined, habitId: t.habitId ?? undefined,
  due: toISODate(t.due), time: t.time ?? undefined, estMin: t.estMin ?? undefined, pri: t.pri ?? undefined, status: t.status, done: t.done, completedAt: toISODate(t.completedAt),
  parentId: t.parentId ?? undefined, notes: t.notes, recurring: t.recurring ?? undefined, amount: toMajor(t.amountMinor), someday: t.someday || undefined, source: t.source, createdAt: t.createdAt.toISOString(),
});
