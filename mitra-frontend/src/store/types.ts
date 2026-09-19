/* Domain types for the client-side store. Auth + money shapes are exactly what the API returns; the rest is still local. */
export type User = { id: string; name: string; email: string; username: string | null; phone: string | null; settings: Record<string, unknown>; onboardedAt: string | null; deletedAt: string | null; memberSince: string };
export type AuthStatus = 'unknown' | 'guest' | 'authed';
export type Pri = 'high' | 'med' | 'low';
export type TaskStatus = 'todo' | 'inprogress' | 'blocked' | 'done';
export type Task = {
  id: string; title: string; projectId?: string; goalId?: string; habitId?: string;
  due?: string; time?: string; estMin?: number; pri?: Pri; status: TaskStatus; done: boolean; completedAt?: string;
  parentId?: string; notes: string; recurring?: string; amount?: number; someday?: boolean; source: 'user' | 'ai'; createdAt: string;
};
export type Project = { id: string; name: string; emoji: string; goalId?: string; createdAt: string };

export type HabitType = 'binary' | 'count' | 'duration' | 'quantity';
export type Habit = {
  id: string; name: string; type: HabitType; target: number; unit: string; icon: string; timeOfDay: 'morning' | 'anytime' | 'evening';
  days: boolean[]; reminder?: string; goalId?: string; gentle: boolean; logs: Record<string, number>; createdAt: string; archived?: boolean;
};

export type GoalType = 'numeric' | 'milestone' | 'habit' | 'date';
export type Milestone = { id: string; title: string; target?: number; targetDate?: string; reachedOn?: string; notes?: string };
export type Goal = {
  id: string; name: string; type: GoalType; term: 'Short-term' | 'Medium-term' | 'Long-term'; due: string; startedOn: string;
  current: number; target: number; unit: '₹' | ''; formula?: string; nextAction: string; reviewEvery: 'Weekly' | 'Monthly' | 'Quarterly'; reviewOn: string;
  milestones: Milestone[]; status: 'active' | 'paused' | 'completed'; progressLog: { date: string; value: number; note?: string }[]; notes: string; createdAt: string;
};

export type TxKind = 'expense' | 'income' | 'transfer';
export type Transaction = { id: string; kind: TxKind; amount: number; categoryId?: string; accountId: string; toAccountId?: string; merchant: string; note?: string; date: string; time: string; source: 'user' | 'ai' | 'recurring'; recurringId?: string; tags?: string[] };
/** `slug` is set on the system categories every user starts with (food, transport, transfer, salary…); custom ones have none. */
export type Category = { id: string; name: string; emoji: string; kind: 'expense' | 'income'; slug?: string; budget?: number };
export type Account = { id: string; name: string; mask: string; balance: number; kind: 'bank' | 'credit' | 'cash' };
export type Subscription = { id: string; name: string; amount: number; cycle: 'Monthly' | 'Yearly'; next: string; color: string; letter: string; lastUsed?: string; categoryId?: string; accountId?: string };
export type Recurring = { id: string; name: string; emoji: string; amount: number; day: number; method: string; kind: 'bill' | 'income' | 'transfer'; reminder: boolean; categoryId?: string; accountId?: string; toAccountId?: string; lastPaidOn?: string };

export type WellnessEntry = { mood?: number; sleepStart?: string; sleepEnd?: string; steps?: number; water?: number; weight?: number; note?: string; savedAt?: string };
export type WellnessSettings = { height: number; sleepTarget: number; stepsTarget: number; waterTarget: number; track: Record<'mood' | 'sleep' | 'steps' | 'water' | 'bmi', boolean>; shareAI: boolean; showOnToday: boolean; morningReminder: string; eveningReminder: string };

export type Note = { id: string; title: string; body: string; folder: string; tags: string[]; updatedAt: string; createdAt: string; journalDate?: string; ai?: boolean; mood?: number };
export type Notification = { id: string; t: string; s: string; when: string; icon: string; tone: string; read: boolean; goto?: string };

export type AiMessage = { id: string; who: 'user' | 'ai'; text: string; preview?: { kind: 'expense' | 'task' | 'plan' | 'reschedule'; title: string; sub: string; payload?: unknown; applied?: boolean } ; chart?: number[] };
/** Client-executable undo steps stored with every AI action so the log can revert it later. */
export type RevertOp = { op: 'deleteTransaction' | 'deleteTask' | 'deleteGoal' | 'deleteHabit' | 'deleteRecurring'; id: string } | { op: 'updateTask' | 'updateHabit' | 'updateGoal' | 'updateCategory' | 'updateRecurring'; id: string; patch: Record<string, unknown> };
export type AiAction = { id: string; when: string; at?: string; action: string; detail: string; state: string; tone: string; reverted?: boolean; revertible: boolean; revert?: RevertOp[] };
export type SuggestionAction =
  | { kind: 'set_habit_reminder'; habitId: string; time: string } | { kind: 'add_recurring'; name: string; amount: number; day: number; categoryId?: string | null }
  | { kind: 'add_subtasks'; taskId: string; titles: string[] } | { kind: 'triage_inbox'; moves: { taskId: string; projectId?: string | null; due?: string | null }[] }
  | { kind: 'pause_goal'; goalId: string } | { kind: 'set_budget'; categoryId: string; amount: number } | { kind: 'add_task'; title: string; due?: string | null };
export type Suggestion = { id: string; ic: string; t: string; p: string; src: string; tone: string; state: 'open' | 'applied' | 'dismissed'; preview?: string; action?: SuggestionAction };

export type Settings = {
  reduceMotion: boolean; compact: boolean; modules: { money: boolean; wellness: boolean; ai: boolean };
  todaySections: string[]; weekStart: 'Monday' | 'Sunday'; dayStart: string;
  quietHours: boolean; digest: boolean; autoSave: 'Always ask' | 'Small only' | 'Never'; shareWellness: boolean;
  focusMinutes: number;
};
export type ActivityRow = { id: string; when: string; date: string; text: string; icon: string; tone: string; module?: string; at?: string };
export type Toast = { id: string; msg: string; tone?: 'default' | 'danger' | 'accent'; undo?: () => void };
