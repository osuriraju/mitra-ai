/* Derived data — pure functions over store state so screens stay thin. */
import { addDays, diffDays, dowMon, monthKey, todayISO } from '@/lib/dates';
import type { State } from './index';
import type { Goal, Habit, Task, Transaction } from './types';

export const T = () => todayISO();

/* ---------- tasks ---------- */
export const isInbox = (t: Task) => !t.projectId && !t.due && !t.someday && !t.goalId && !t.done && !t.parentId;
export const children = (tasks: Task[], id: string) => tasks.filter((t) => t.parentId === id);
export const subProgress = (tasks: Task[], id: string) => { const c = children(tasks, id); return { done: c.filter((t) => t.done).length, total: c.length }; };
export const isOverdue = (t: Task) => !!t.due && t.due < T() && !t.done;
export const isToday = (t: Task) => t.due === T();
export const isUpcoming = (t: Task) => !!t.due && t.due > T() && !t.done;
export const openToday = (tasks: Task[]) => tasks.filter((t) => isToday(t) && !t.done);
export const doneToday = (tasks: Task[]) => tasks.filter((t) => t.done && t.completedAt === T());
export const priorityTasks = (tasks: Task[]) => { const order = { high: 0, med: 1, low: 2, undefined: 3 } as Record<string, number>; return [...tasks.filter((t) => !t.done && (isOverdue(t) || isToday(t)))].sort((a, b) => (isOverdue(b) ? 1 : 0) - (isOverdue(a) ? 1 : 0) || order[String(a.pri)] - order[String(b.pri)]).slice(0, 3); };
export const plannedMin = (tasks: Task[]) => tasks.filter((t) => isToday(t)).reduce((a, t) => a + (t.estMin || 0), 0);
export const doneMin = (tasks: Task[]) => tasks.filter((t) => isToday(t) && t.done).reduce((a, t) => a + (t.estMin || 0), 0);
export const groupUpcoming = (tasks: Task[]) => { const g: Record<string, Task[]> = {}; tasks.filter(isUpcoming).sort((a, b) => a.due!.localeCompare(b.due!)).forEach((t) => { const n = diffDays(t.due!, T()); const k = n <= 7 ? t.due! : n <= 14 ? 'next-week' : 'later'; (g[k] ||= []).push(t); }); return g; };

/* ---------- habits ---------- */
export const habitDue = (h: Habit, date = T()) => h.days[dowMon(date)];
export const habitDone = (h: Habit, date = T()) => (h.logs[date] || 0) >= h.target;
export const habitValue = (h: Habit, date = T()) => h.logs[date] || 0;
export const streak = (h: Habit) => { let n = 0; let d = T(); if (!habitDone(h, d)) d = addDays(d, -1); for (let i = 0; i < 400; i++) { if (!habitDue(h, d)) { d = addDays(d, -1); continue; } if (habitDone(h, d)) { n++; d = addDays(d, -1); } else break; } return n; };
export const bestStreak = (h: Habit) => { let best = 0, cur = 0; for (let i = 90; i >= 0; i--) { const d = addDays(T(), -i); if (!habitDue(h, d)) continue; if (habitDone(h, d)) { cur++; best = Math.max(best, cur); } else cur = 0; } return best; };
export const weekRate = (h: Habit) => { let due = 0, done = 0; for (let i = 6; i >= 0; i--) { const d = addDays(T(), -i); if (!habitDue(h, d)) continue; due++; if (habitDone(h, d)) done++; } return due ? Math.round((done / due) * 100) : 0; };
export const rateDays = (h: Habit, days: number) => { let due = 0, done = 0; for (let i = days - 1; i >= 0; i--) { const d = addDays(T(), -i); if (!habitDue(h, d)) continue; due++; if (habitDone(h, d)) done++; } return { due, done, pct: due ? Math.round((done / due) * 100) : 0 }; };
export const weekDots = (h: Habit) => { const start = addDays(T(), -dowMon(T())); return Array.from({ length: 7 }, (_, i) => habitDone(h, addDays(start, i)) ? 1 : 0); };
export const habitsDueToday = (habits: Habit[]) => habits.filter((h) => !h.archived && habitDue(h));
export const habitsDoneToday = (habits: Habit[]) => habitsDueToday(habits).filter((h) => habitDone(h));
export const habitsWeekRate = (habits: Habit[]) => { const hs = habits.filter((h) => !h.archived); return hs.length ? Math.round(hs.reduce((a, h) => a + weekRate(h), 0) / hs.length) : 0; };

/* ---------- goals ---------- */
export const goalProgress = (g: Goal, habits: Habit[] = []) => {
  if (g.type === 'milestone') return g.milestones.length ? Math.round((g.milestones.filter((m) => m.reachedOn).length / g.milestones.length) * 100) : 0;
  if (g.type === 'date') { const total = Math.max(1, diffDays(g.due, g.startedOn)); return Math.min(100, Math.round((diffDays(T(), g.startedOn) / total) * 100)); }
  if (g.type === 'habit') { const linked = habits.filter((h) => h.goalId === g.id); if (linked.length) return Math.round(linked.reduce((a, h) => a + rateDays(h, 30).pct, 0) / linked.length); }
  return Math.min(100, Math.round((g.current / Math.max(1, g.target)) * 100));
};
export type GoalStatus = 'on-track' | 'attention' | 'behind' | 'slipping' | 'completed' | 'paused';
export const goalStatus = (g: Goal, habits: Habit[] = []): GoalStatus => {
  if (g.status === 'completed') return 'completed'; if (g.status === 'paused') return 'paused';
  const p = goalProgress(g, habits); const total = Math.max(1, diffDays(g.due, g.startedOn)); const expected = Math.min(100, Math.max(0, (diffDays(T(), g.startedOn) / total) * 100));
  const lastLog = g.progressLog[g.progressLog.length - 1]?.date; const stale = lastLog ? diffDays(T(), lastLog) > 21 : true;
  if (stale && p < expected - 10) return 'slipping'; if (p >= expected - 5) return 'on-track'; if (p >= expected - 15) return 'attention'; return 'behind';
};
export const STATUS_LABEL: Record<GoalStatus, string> = { 'on-track': 'On track', attention: 'Needs attention', behind: 'Behind', slipping: 'Slipping', completed: 'Completed', paused: 'Paused' };
export const STATUS_TONE: Record<GoalStatus, string> = { 'on-track': 'success', attention: 'warning', behind: 'warning', slipping: 'danger', completed: 'accent', paused: '' };
export const goalValueLabel = (g: Goal) => g.unit === '₹' ? `₹${g.current.toLocaleString('en-IN')}` : String(g.current);
export const goalTargetLabel = (g: Goal) => g.unit === '₹' ? `₹${g.target.toLocaleString('en-IN')}` : String(g.target);
export const reviewDue = (g: Goal) => g.status === 'active' && g.reviewOn <= T();

/* ---------- money ---------- */
export const thisMonth = (txs: Transaction[], month = monthKey(T())) => txs.filter((t) => monthKey(t.date) === month);
export const spent = (txs: Transaction[]) => txs.filter((t) => t.kind === 'expense').reduce((a, t) => a + t.amount, 0);
export const income = (txs: Transaction[]) => txs.filter((t) => t.kind === 'income').reduce((a, t) => a + t.amount, 0);
export const transferred = (txs: Transaction[]) => txs.filter((t) => t.kind === 'transfer').reduce((a, t) => a + t.amount, 0);
export const spentToday = (txs: Transaction[]) => spent(txs.filter((t) => t.date === T()));
export const spentByCategory = (txs: Transaction[]) => { const m: Record<string, number> = {}; txs.filter((t) => t.kind === 'expense').forEach((t) => { const k = t.categoryId || 'uncategorised'; m[k] = (m[k] || 0) + t.amount; }); return m; };
export const totalBudget = (s: State) => s.categories.filter((c) => c.kind === 'expense' && c.budget).reduce((a, c) => a + (c.budget || 0), 0);
export const dailySpend = (txs: Transaction[], days: number) => Array.from({ length: days }, (_, i) => { const d = addDays(T(), -(days - 1 - i)); return spent(txs.filter((t) => t.date === d)); });
export const netWorth = (s: State) => s.accounts.reduce((a, x) => a + x.balance, 0);

/* ---------- wellness ---------- */
export const sleepMinutes = (start?: string, end?: string) => { if (!start || !end) return 0; const [sh, sm] = start.split(':').map(Number); const [eh, em] = end.split(':').map(Number); let m = eh * 60 + em - (sh * 60 + sm); if (m < 0) m += 1440; return m; };
export const bmi = (w?: number, h?: number) => w && h ? +(w / ((h / 100) ** 2)).toFixed(1) : 0;
export const bmiBand = (v: number) => v === 0 ? '—' : v < 18.5 ? 'Under' : v < 25 ? 'Normal range' : v < 30 ? 'Over' : 'High';
export const hm = (mins: number) => `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}m`;

/* ---------- notes ---------- */
export const noteSearch = (s: State, q: string) => { const k = q.trim().toLowerCase(); if (!k) return { notes: [], tasks: [], goals: [], habits: [] }; return { notes: s.notes.filter((n) => (n.title + ' ' + n.body + ' ' + n.tags.join(' ')).toLowerCase().includes(k)), tasks: s.tasks.filter((t) => t.title.toLowerCase().includes(k)), goals: s.goals.filter((g) => g.name.toLowerCase().includes(k)), habits: s.habits.filter((h) => h.name.toLowerCase().includes(k)) }; };
