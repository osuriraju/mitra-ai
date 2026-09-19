/* View-model adapters: store records → the props the design-system rows expect. */
import { fmtDay, fmtTime, minsToHm, inr } from '@/lib/dates';
import type { State } from '@/store';
import type { Task } from '@/store/types';
import { isOverdue } from '@/store/selectors';

export type TaskRowVM = { id?: number; title: string; project?: string; due?: string; pri?: 'high' | 'med' | 'low'; est?: string; goal?: string; overdue?: boolean; sub?: string; amount?: string; recurring?: boolean; done?: boolean; ai?: boolean; parent?: string };

export function taskVM(t: Task, s: Pick<State, 'projects' | 'goals' | 'tasks'>, opts: { showTime?: boolean } = {}): TaskRowVM {
  const project = s.projects.find((p) => p.id === t.projectId)?.name;
  const goal = s.goals.find((g) => g.id === t.goalId)?.name;
  const dueLabel = t.due ? (isOverdue(t) ? `Overdue · ${fmtDay(t.due, { relative: false })}` : t.due && t.time && opts.showTime !== false ? (fmtDay(t.due) === 'Today' ? fmtTime(t.time) : `${fmtDay(t.due)} ${fmtTime(t.time)}`) : fmtDay(t.due)) : undefined;
  const kids = s.tasks.filter((x) => x.parentId === t.id); const subDone = kids.filter((x) => x.done).length; const parent = t.parentId ? s.tasks.find((x) => x.id === t.parentId) : undefined;
  return {
    title: t.title, project, due: dueLabel, pri: t.pri, est: t.estMin ? minsToHm(t.estMin) : undefined,
    goal: goal ? goal.replace(/^Build |^Ship /, '').slice(0, 24) : undefined, overdue: isOverdue(t),
    sub: kids.length ? `${subDone}/${kids.length}` : undefined, parent: parent?.title, amount: t.amount ? inr(t.amount) : undefined,
    recurring: !!t.recurring, done: t.done, ai: t.source === 'ai',
  };
}
