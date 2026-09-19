'use client';
/* =====================================================================
   APP STORE — single client-side store (Zustand + localStorage persist).
   All screens read from here; every action mutates here.

   Auth, profile and Money are backed by the API: actions update the store
   optimistically (client-generated UUIDs, so Undo keeps working), then call
   the server and roll back with a toast if it fails. The other modules
   (tasks, habits, goals, notes, wellness, AI) are still local-only and will
   be wired the same way in later phases.
   ===================================================================== */
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { api, ApiError, errMsg } from '@/lib/api';
import { addDays, todayISO, uid, uuid } from '@/lib/dates';
import * as seed from './seed';
export type * from './types';
import type { Account, ActivityRow, AiAction, AiMessage, AuthStatus, Category, Goal, Habit, Milestone, Note, Notification, Project, Recurring, Settings, Subscription, Suggestion, Task, Toast, Transaction, User, WellnessEntry, WellnessSettings } from './types';

type TasksBootstrap = { tasks: Task[]; projects: Project[] };
type MoneyBootstrap = { accounts: Account[]; categories: Category[]; transactions: Transaction[]; recurring: Recurring[]; subscriptions: Subscription[] };
type ServerActivity = { id: string; text: string; icon: string; tone: string; module: string; at: string };
/** JSON drops `undefined`, so a patch that clears a field must send `null` for the server to clear it. */
const nulls = <T extends object>(o: T) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, v === undefined ? null : v])) as T;
/** For resources whose fields are not nullable server-side: drop undefined keys instead of sending null. */
const strip = <T extends object>(o: T) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;
/* Debounce state for note autosave: latest patch per note id + its timer. */
const pendingNote: Record<string, Partial<Note>> = {}; const noteTimers: Record<string, ReturnType<typeof setTimeout>> = {};
const timeLabel = (d: Date) => { const h = d.getHours(); return `${h % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };
const localISO = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const fromServerActivity = (a: ServerActivity): ActivityRow => { const d = new Date(a.at); return { id: a.id, when: timeLabel(d), date: localISO(d), text: a.text, icon: a.icon, tone: a.tone, module: a.module, at: a.at }; };

export type State = {
  hydrated: boolean;
  user: User | null; auth: AuthStatus; moneyLoaded: boolean; tasksLoaded: boolean; habitsLoaded: boolean; goalsLoaded: boolean; notesLoaded: boolean; wellnessLoaded: boolean;
  tasks: Task[]; projects: Project[]; habits: Habit[]; goals: Goal[];
  transactions: Transaction[]; categories: Category[]; accounts: Account[]; subscriptions: Subscription[]; recurring: Recurring[];
  wellness: Record<string, WellnessEntry>; wellnessSettings: WellnessSettings;
  notes: Note[]; notifications: Notification[];
  aiMessages: AiMessage[]; aiActions: AiAction[]; suggestions: Suggestion[];
  settings: Settings; toasts: Toast[];
  activity: ActivityRow[];
};

type Actions = {
  // auth & profile (API)
  fetchMe: () => Promise<void>;
  login: (identifier: string, password: string, remember: boolean) => Promise<User>;
  signup: (name: string, email: string, password: string) => Promise<User>;
  logout: () => Promise<void>;
  forgotPassword: (email: string) => Promise<void>;
  resetPassword: (token: string, password: string) => Promise<void>;
  updateProfile: (patch: { name?: string; email?: string; phone?: string | null }) => Promise<User>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  markOnboarded: (settings?: Partial<Settings>) => Promise<void>;
  deleteAccount: (password: string) => Promise<void>;
  exportData: () => Promise<unknown>;
  loadMoney: () => Promise<void>;
  loadTasks: () => Promise<void>;
  loadHabits: () => Promise<void>;
  loadGoals: () => Promise<void>;
  loadNotes: () => Promise<void>;
  loadWellness: () => Promise<void>;
  loadActivity: () => Promise<void>;
  // tasks
  addTask: (t: Partial<Task> & { title: string }) => Task;
  updateTask: (id: string, patch: Partial<Task>) => void;
  toggleTask: (id: string) => void;
  deleteTask: (id: string) => void;
  addSubtask: (parentId: string, title: string, extra?: Partial<Task>) => Task;
  addProject: (p: { name: string; emoji: string; goalId?: string }) => Project;
  updateProject: (id: string, patch: Partial<Project>) => void;
  deleteProject: (id: string) => void;
  // habits
  addHabit: (h: Partial<Habit> & { name: string }) => Habit;
  updateHabit: (id: string, patch: Partial<Habit>) => void;
  deleteHabit: (id: string) => void;
  logHabit: (id: string, date: string, value: number | null) => void;
  // goals
  addGoal: (g: Partial<Goal> & { name: string }) => Goal;
  updateGoal: (id: string, patch: Partial<Goal>) => void;
  deleteGoal: (id: string) => void;
  logGoalProgress: (id: string, value: number, note?: string) => void;
  addMilestone: (goalId: string, m: Partial<Milestone> & { title: string }) => void;
  updateMilestone: (goalId: string, id: string, patch: Partial<Milestone>) => void;
  deleteMilestone: (goalId: string, id: string) => void;
  // money
  addTransaction: (t: Partial<Transaction> & { amount: number; accountId: string }) => Transaction;
  updateTransaction: (id: string, patch: Partial<Transaction>) => void;
  deleteTransaction: (id: string) => void;
  addCategory: (c: { name: string; emoji: string; kind?: 'expense' | 'income'; budget?: number }) => Category;
  updateCategory: (id: string, patch: Partial<Category>) => void;
  deleteCategory: (id: string) => void;
  addAccount: (a: { name: string; mask?: string; balance?: number; kind?: Account['kind'] }) => void;
  updateAccount: (id: string, patch: Partial<Account>) => void;
  removeAccount: (id: string) => void;
  addSubscription: (s: Partial<Subscription> & { name: string; amount: number }) => void;
  updateSubscription: (id: string, patch: Partial<Subscription>) => void;
  deleteSubscription: (id: string) => void;
  payRecurring: (id: string) => Promise<void>;
  addRecurring: (r: Partial<Recurring> & { name: string; amount: number; day: number }) => void;
  updateRecurring: (id: string, patch: Partial<Recurring>) => void;
  deleteRecurring: (id: string) => void;
  // wellness
  saveWellness: (date: string, e: Partial<WellnessEntry>) => void;
  updateWellnessSettings: (patch: Partial<WellnessSettings>) => void;
  // notes
  addNote: (n: Partial<Note> & { title: string }) => Note;
  updateNote: (id: string, patch: Partial<Note>) => void;
  deleteNote: (id: string) => void;
  // notifications
  markRead: (id: string | 'all') => void;
  // ai
  pushAiMessage: (m: Omit<AiMessage, 'id'>) => AiMessage;
  updateAiMessage: (id: string, patch: Partial<AiMessage>) => void;
  clearAi: () => void;
  logAiAction: (a: Omit<AiAction, 'id' | 'when'>) => void;
  revertAiAction: (id: string) => void;
  setSuggestion: (id: string, state: Suggestion['state']) => void;
  // settings / misc
  updateSettings: (patch: Partial<Settings>) => void;
  addActivity: (text: string, icon?: string, tone?: string, module?: string) => void;
  toast: (msg: string, opts?: { undo?: () => void; tone?: Toast['tone'] }) => void;
  dismissToast: (id: string) => void;
  resetAll: () => void;
};

/* Every module except AI/notifications is loaded from the API after sign-in; those two are still local sample data. */
const initial = (): State => ({
  hydrated: false,
  user: null, auth: 'unknown', moneyLoaded: false, tasksLoaded: false, habitsLoaded: false, goalsLoaded: false, notesLoaded: false, wellnessLoaded: false,
  tasks: [], projects: [], habits: [], goals: [],
  transactions: [], categories: [], accounts: [], subscriptions: [], recurring: [],
  wellness: {}, wellnessSettings: seed.WELLNESS_SETTINGS,
  notes: [], notifications: seed.NOTIFICATIONS,
  aiMessages: [], aiActions: seed.AI_ACTIONS, suggestions: seed.SUGGESTIONS,
  settings: seed.SETTINGS, toasts: [],
  activity: [
    { id: 'ac2', when: '11:05 AM', date: todayISO(), text: 'Meditate ✓', icon: 'sun', tone: 'success' },
    { id: 'ac3', when: '10:00 AM', date: todayISO(), text: 'Focus started · Auth refactor', icon: 'timer', tone: 'accent' },
    { id: 'ac4', when: '7:30 AM', date: todayISO(), text: 'Morning walk · 32 min ✓', icon: 'activity', tone: 'success' },
  ],
});

const nowLabel = () => timeLabel(new Date());
/** Settings the client keeps that are worth syncing to the user's profile document. */
const settingsFromUser = (u: User): Partial<Settings> => { const s = u.settings as Partial<Settings>; const out: Partial<Settings> = {}; (['reduceMotion', 'compact', 'modules', 'todaySections', 'weekStart', 'dayStart', 'quietHours', 'digest', 'autoSave', 'shareWellness', 'focusMinutes'] as (keyof Settings)[]).forEach((k) => { if (s[k] !== undefined) (out as Record<string, unknown>)[k] = s[k]; }); return out; };

export const useStore = create<State & Actions>()(persist((set, get) => {
  /** Optimistic write helper: run the request; on failure restore the given slice and tell the user. */
  const remote = (req: () => Promise<unknown>, rollback: () => void, what = 'save') => { req().catch((e) => { rollback(); if (e instanceof ApiError && e.unauthenticated) { set({ auth: 'guest', user: null }); return; } get().toast(`Couldn't ${what} · ${errMsg(e)}`, { tone: 'danger' }); }); };
  const setUser = (user: User) => set((s) => ({ user, auth: 'authed', settings: { ...s.settings, ...settingsFromUser(user) } }));

  return ({
  ...initial(),

  /* ---------- auth & profile ---------- */
  fetchMe: async () => {
    try { const { user } = await api.get<{ user: User }>('/auth/me'); setUser(user); await Promise.all([get().loadMoney(), get().loadTasks(), get().loadHabits(), get().loadGoals(), get().loadNotes(), get().loadWellness(), get().loadActivity()]); }
    catch (e) { if (e instanceof ApiError && e.unauthenticated) set({ auth: 'guest', user: null }); else if (get().user) set({ auth: 'authed' }); /* offline with a cached profile: keep the shell usable */ else set({ auth: 'guest' }); }
  },
  login: async (identifier, password, remember) => { const { user } = await api.post<{ user: User }>('/auth/login', { identifier, password, remember }); setUser(user); void Promise.all([get().loadMoney(), get().loadTasks(), get().loadHabits(), get().loadGoals(), get().loadNotes(), get().loadWellness(), get().loadActivity()]); return user; },
  signup: async (name, email, password) => { const { user } = await api.post<{ user: User }>('/auth/signup', { name, email, password }); setUser(user); void Promise.all([get().loadMoney(), get().loadTasks(), get().loadHabits(), get().loadGoals(), get().loadNotes(), get().loadWellness()]); return user; },
  logout: async () => { try { await api.post('/auth/logout'); } catch {} set({ user: null, auth: 'guest', moneyLoaded: false, tasksLoaded: false, habitsLoaded: false, goalsLoaded: false, notesLoaded: false, wellnessLoaded: false, transactions: [], categories: [], accounts: [], subscriptions: [], recurring: [], tasks: [], projects: [], habits: [], goals: [], notes: [], wellness: {}, aiMessages: [] }); },
  forgotPassword: (email) => api.post('/auth/forgot-password', { email }).then(() => undefined),
  resetPassword: (token, password) => api.post('/auth/reset-password', { token, password }).then(() => undefined),
  updateProfile: async (patch) => { const { user } = await api.patch<{ user: User }>('/users/me', patch); setUser(user); return user; },
  changePassword: (currentPassword, newPassword) => api.patch('/users/me/password', { currentPassword, newPassword }).then(() => undefined),
  markOnboarded: async (settings) => { const { user } = await api.post<{ user: User }>('/users/me/onboarded', { settings }); setUser(user); },
  deleteAccount: async (password) => { await api.del('/users/me', { password }); set({ ...initial(), hydrated: true, auth: 'guest' }); },
  exportData: () => api.get('/users/me/export'),
  loadMoney: async () => { const m = await api.get<MoneyBootstrap>('/money/bootstrap'); set({ ...m, moneyLoaded: true }); },
  loadTasks: async () => { const t = await api.get<TasksBootstrap>('/tasks/bootstrap'); set({ ...t, tasksLoaded: true }); },
  loadHabits: async () => { const habits = await api.get<Habit[]>('/habits'); set({ habits, habitsLoaded: true }); },
  loadGoals: async () => { const goals = await api.get<Goal[]>('/goals'); set({ goals, goalsLoaded: true }); },
  loadNotes: async () => { const notes = await api.get<Note[]>('/notes'); set({ notes, notesLoaded: true }); },
  loadWellness: async () => { const w = await api.get<{ entries: Record<string, WellnessEntry>; settings: WellnessSettings }>('/wellness/bootstrap'); set({ wellness: w.entries, wellnessSettings: w.settings, wellnessLoaded: true }); },
  loadActivity: async () => { const rows = await api.get<ServerActivity[]>('/activity', { limit: 200 }); set((s) => ({ activity: [...rows.map(fromServerActivity), ...s.activity.filter((a) => !a.module)].sort((a, b) => (b.at || b.date + 'T23:59').localeCompare(a.at || a.date + 'T23:59')).slice(0, 300) })); },

  /* ---------- tasks & projects (API-backed, optimistic) ---------- */
  addTask: (t) => {
    const task: Task = { status: 'todo', done: false, notes: '', source: 'user', createdAt: new Date().toISOString(), ...t, id: t.id || uuid() };
    const before = get().tasks; set((s) => ({ tasks: [task, ...s.tasks] }));
    get().addActivity(`Task added · ${task.title}`, 'check-square', '', 'tasks');
    remote(async () => { const saved = await api.post<Task>('/tasks', nulls({ id: task.id, title: task.title, projectId: task.projectId, goalId: task.goalId, habitId: task.habitId, due: task.due, time: task.time, estMin: task.estMin, pri: task.pri, status: task.status, done: task.done, completedAt: task.completedAt, parentId: task.parentId, notes: task.notes, recurring: task.recurring, amount: task.amount, someday: task.someday || false, source: task.source })); set((s) => ({ tasks: s.tasks.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ tasks: before }));
    return task;
  },
  updateTask: (id, patch) => {
    const before = get().tasks; set((s) => ({ tasks: s.tasks.map((t) => t.id === id ? { ...t, ...patch } : t) }));
    const { id: _i, createdAt: _c, source: _s, ...rest } = patch as Partial<Task>;
    remote(async () => { const saved = await api.patch<Task>(`/tasks/${id}`, nulls(rest)); set((s) => ({ tasks: s.tasks.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ tasks: before }));
  },
  toggleTask: (id) => {
    const t = get().tasks.find((x) => x.id === id); if (!t) return; const done = !t.done; const before = get().tasks;
    set((s) => ({ tasks: s.tasks.map((x) => x.id === id ? { ...x, done, status: done ? 'done' : 'todo', completedAt: done ? todayISO() : undefined } : x) }));
    if (done) get().addActivity(`${t.title} ✓`, 'check-square', 'success', 'tasks');
    remote(async () => { const r = await api.post<{ task: Task; spawned?: Task }>(`/tasks/${id}/toggle`); set((s) => ({ tasks: [...(r.spawned && !s.tasks.some((x) => x.id === r.spawned!.id) ? [r.spawned] : []), ...s.tasks.map((x) => x.id === r.task.id ? r.task : x)] })); if (r.spawned) get().toast(`Next “${t.title}” scheduled for ${r.spawned.due}`); }, () => set({ tasks: before }));
  },
  deleteTask: (id) => { const before = get().tasks; set((s) => ({ tasks: s.tasks.filter((t) => t.id !== id && t.parentId !== id) })); remote(() => api.del(`/tasks/${id}`), () => set({ tasks: before }), 'delete'); },
  addSubtask: (parentId, title, extra = {}) => { const parent = get().tasks.find((t) => t.id === parentId); return get().addTask({ title, parentId, projectId: parent?.projectId, goalId: parent?.goalId, ...extra }); },
  addProject: (p) => { const project: Project = { id: uuid(), createdAt: new Date().toISOString(), ...p }; const before = get().projects; set((s) => ({ projects: [...s.projects, project] })); remote(async () => { const saved = await api.post<Project>('/projects', nulls({ id: project.id, name: project.name, emoji: project.emoji, goalId: project.goalId })); set((s) => ({ projects: s.projects.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ projects: before })); return project; },
  updateProject: (id, patch) => { const before = get().projects; set((s) => ({ projects: s.projects.map((p) => p.id === id ? { ...p, ...patch } : p) })); const { id: _i, createdAt: _c, ...rest } = patch; remote(() => api.patch(`/projects/${id}`, nulls(rest)), () => set({ projects: before })); },
  deleteProject: (id) => { const before = { projects: get().projects, tasks: get().tasks }; set((s) => ({ projects: s.projects.filter((p) => p.id !== id), tasks: s.tasks.map((t) => t.projectId === id ? { ...t, projectId: undefined } : t) })); remote(() => api.del(`/projects/${id}`), () => set(before), 'delete'); },

  /* ---------- habits (API-backed, optimistic) ---------- */
  addHabit: (h) => {
    const habit: Habit = { type: 'binary', target: 1, unit: '', icon: 'repeat', timeOfDay: 'anytime', days: [true, true, true, true, true, true, true], gentle: true, logs: {}, createdAt: new Date().toISOString(), ...h, id: h.id || uuid() };
    const before = get().habits; set((s) => ({ habits: [...s.habits, habit] }));
    remote(async () => { const saved = await api.post<Habit>('/habits', nulls({ id: habit.id, name: habit.name, type: habit.type, target: habit.target, unit: habit.unit, icon: habit.icon, timeOfDay: habit.timeOfDay, days: habit.days, reminder: habit.reminder, goalId: habit.goalId, gentle: habit.gentle })); set((s) => ({ habits: s.habits.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ habits: before }));
    return habit;
  },
  updateHabit: (id, patch) => { const before = get().habits; set((s) => ({ habits: s.habits.map((h) => h.id === id ? { ...h, ...patch } : h) })); const { id: _i, logs: _l, createdAt: _c, ...rest } = patch; remote(async () => { const saved = await api.patch<Habit>(`/habits/${id}`, nulls(rest)); set((s) => ({ habits: s.habits.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ habits: before })); },
  deleteHabit: (id) => { const before = get().habits; set((s) => ({ habits: s.habits.filter((h) => h.id !== id) })); remote(() => api.del(`/habits/${id}`), () => set({ habits: before }), 'delete'); },
  logHabit: (id, date, value) => {
    const h = get().habits.find((x) => x.id === id); if (!h) return; const before = get().habits;
    const logs = { ...h.logs }; if (value === null) delete logs[date]; else logs[date] = value;
    set((s) => ({ habits: s.habits.map((x) => x.id === id ? { ...x, logs } : x) }));
    if (value !== null && value >= h.target && (h.logs[date] || 0) < h.target && date === todayISO()) get().addActivity(`${h.name} ✓`, h.icon, 'success', 'habits');
    remote(async () => { const saved = await api.put<Habit>(`/habits/${id}/logs`, { date, value }); set((s) => ({ habits: s.habits.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ habits: before }), 'log');
  },

  /* ---------- goals (API-backed, optimistic; the server owns progress maths and returns the whole goal) ---------- */
  addGoal: (g) => {
    const goal: Goal = { type: 'numeric', term: 'Medium-term', due: addDays(todayISO(), 180), startedOn: todayISO(), current: 0, target: 100, unit: '', nextAction: '', reviewEvery: 'Monthly', reviewOn: addDays(todayISO(), 30), milestones: [], status: 'active', progressLog: [], notes: '', createdAt: new Date().toISOString(), ...g, id: g.id || uuid() };
    goal.milestones = goal.milestones.map((m) => ({ ...m, id: uuid() }));
    const before = get().goals; set((s) => ({ goals: [...s.goals, goal] })); get().addActivity(`Goal set · ${goal.name}`, 'target', '', 'goals');
    remote(async () => { const saved = await api.post<Goal>('/goals', nulls({ id: goal.id, name: goal.name, type: goal.type, term: goal.term, due: goal.due, startedOn: goal.startedOn, current: goal.current, target: goal.target, unit: goal.unit, formula: goal.formula, nextAction: goal.nextAction, reviewEvery: goal.reviewEvery, reviewOn: goal.reviewOn, status: goal.status, notes: goal.notes, milestones: goal.milestones.map((m) => nulls({ id: m.id, title: m.title, target: m.target, targetDate: m.targetDate, reachedOn: m.reachedOn, notes: m.notes })) })); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before }));
    return goal;
  },
  updateGoal: (id, patch) => { const before = get().goals; set((s) => ({ goals: s.goals.map((g) => g.id === id ? { ...g, ...patch } : g) })); const { id: _i, createdAt: _c, milestones: _m, progressLog: _p, ...rest } = patch; const body = { ...strip(rest), ...('formula' in rest ? { formula: rest.formula ?? null } : {}) }; if (Object.keys(body).length === 0) return; remote(async () => { const saved = await api.patch<Goal>(`/goals/${id}`, body); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before })); },
  deleteGoal: (id) => { const before = get().goals; set((s) => ({ goals: s.goals.filter((g) => g.id !== id) })); remote(() => api.del(`/goals/${id}`), () => set({ goals: before }), 'delete'); },
  logGoalProgress: (id, value, note) => {
    const g = get().goals.find((x) => x.id === id); if (!g) return; const before = get().goals;
    const current = g.type === 'numeric' ? g.current + value : value; const milestones = g.milestones.map((m) => m.target && !m.reachedOn && current >= m.target ? { ...m, reachedOn: todayISO() } : m);
    set((s) => ({ goals: s.goals.map((x) => x.id === id ? { ...x, current, milestones, progressLog: [...x.progressLog, { date: todayISO(), value: current, note }], status: current >= x.target && x.type !== 'milestone' && x.type !== 'habit' ? 'completed' : x.status } : x) }));
    get().addActivity(`Progress logged · ${g.name}`, 'target', 'accent', 'goals');
    remote(async () => { const saved = await api.post<Goal>(`/goals/${id}/progress`, { value, note: note || null }); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before }));
  },
  addMilestone: (goalId, m) => { const ms: Milestone = { ...m, id: uuid() }; const before = get().goals; set((s) => ({ goals: s.goals.map((g) => g.id === goalId ? { ...g, milestones: [...g.milestones, ms] } : g) })); remote(async () => { const saved = await api.post<Goal>(`/goals/${goalId}/milestones`, nulls({ id: ms.id, title: ms.title, target: ms.target, targetDate: ms.targetDate, reachedOn: ms.reachedOn, notes: ms.notes })); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before })); },
  updateMilestone: (goalId, id, patch) => {
    const before = get().goals;
    set((s) => ({ goals: s.goals.map((g) => { if (g.id !== goalId) return g; const milestones = g.milestones.map((m) => m.id === id ? { ...m, ...patch } : m); const reached = milestones.filter((m) => m.reachedOn).length; return { ...g, milestones, current: g.type === 'milestone' ? reached : g.current, target: g.type === 'milestone' ? milestones.length : g.target }; }) }));
    const { id: _i, ...rest } = patch as Partial<Milestone>;
    remote(async () => { const saved = await api.patch<Goal>(`/goals/${goalId}/milestones/${id}`, nulls(rest)); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before }));
  },
  deleteMilestone: (goalId, id) => { const before = get().goals; set((s) => ({ goals: s.goals.map((g) => g.id === goalId ? { ...g, milestones: g.milestones.filter((m) => m.id !== id) } : g) })); remote(async () => { const saved = await api.del<Goal>(`/goals/${goalId}/milestones/${id}`); set((s) => ({ goals: s.goals.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ goals: before }), 'delete'); },

  /* ---------- money (API-backed, optimistic) ---------- */
  addTransaction: (t) => {
    const tx: Transaction = { id: uuid(), kind: 'expense', merchant: '', date: todayISO(), time: new Date().toTimeString().slice(0, 5), source: 'user', ...t };
    const before = { transactions: get().transactions, accounts: get().accounts };
    set((s) => ({ transactions: [tx, ...s.transactions], accounts: s.accounts.map((a) => a.id === tx.accountId ? { ...a, balance: a.balance + (tx.kind === 'income' ? tx.amount : -tx.amount) } : a.id === tx.toAccountId && tx.kind === 'transfer' ? { ...a, balance: a.balance + tx.amount } : a) }));
    const cat = get().categories.find((c) => c.id === tx.categoryId);
    get().addActivity(`${tx.kind === 'income' ? 'Income ' : tx.kind === 'transfer' ? 'Transfer ' : ''}₹${tx.amount.toLocaleString('en-IN')} ${tx.merchant || cat?.name || ''}${tx.source === 'ai' ? ' · captured by AI' : tx.source === 'recurring' ? ' · recurring' : ''}`.trim(), 'wallet', tx.source === 'ai' ? 'accent' : '', 'money');
    remote(async () => { const saved = await api.post<Transaction>('/money/transactions', { ...tx, categoryId: tx.categoryId || null, toAccountId: tx.kind === 'transfer' ? tx.toAccountId : null, note: tx.note || null, tags: tx.tags || [] }); set((s) => ({ transactions: s.transactions.map((x) => x.id === saved.id ? saved : x) })); void get().loadActivity(); }, () => set(before));
    return tx;
  },
  updateTransaction: (id, patch) => {
    const old = get().transactions.find((t) => t.id === id); if (!old) return;
    const before = { transactions: get().transactions, accounts: get().accounts };
    const next = { ...old, ...patch };
    const fx = (t: Transaction, sign: 1 | -1) => (a: Account) => a.id === t.accountId ? { ...a, balance: a.balance + sign * (t.kind === 'income' ? t.amount : -t.amount) } : a.id === t.toAccountId && t.kind === 'transfer' ? { ...a, balance: a.balance + sign * t.amount } : a;
    set((s) => ({ transactions: s.transactions.map((t) => t.id === id ? next : t), accounts: s.accounts.map(fx(old, -1)).map(fx(next, 1)) }));
    remote(async () => { const saved = await api.patch<Transaction>(`/money/transactions/${id}`, { kind: next.kind, amount: next.amount, categoryId: next.categoryId || null, accountId: next.accountId, toAccountId: next.kind === 'transfer' ? next.toAccountId : null, merchant: next.merchant, note: next.note || null, date: next.date, time: next.time }); set((s) => ({ transactions: s.transactions.map((x) => x.id === saved.id ? saved : x) })); }, () => set(before));
  },
  deleteTransaction: (id) => {
    const tx = get().transactions.find((t) => t.id === id); if (!tx) return;
    const before = { transactions: get().transactions, accounts: get().accounts };
    set((s) => ({ transactions: s.transactions.filter((t) => t.id !== id), accounts: s.accounts.map((a) => a.id === tx.accountId ? { ...a, balance: a.balance - (tx.kind === 'income' ? tx.amount : -tx.amount) } : a.id === tx.toAccountId && tx.kind === 'transfer' ? { ...a, balance: a.balance - tx.amount } : a) }));
    remote(() => api.del(`/money/transactions/${id}`), () => set(before), 'delete');
  },
  addCategory: (c) => { const cat: Category = { id: uuid(), kind: 'expense', ...c }; const before = get().categories; set((s) => ({ categories: [...s.categories, cat] })); remote(() => api.post('/money/categories', { ...cat, budget: cat.budget ?? null }), () => set({ categories: before })); return cat; },
  updateCategory: (id, patch) => { const before = get().categories; set((s) => ({ categories: s.categories.map((c) => c.id === id ? { ...c, ...patch } : c) })); const { budget, name, emoji } = patch; remote(() => api.patch(`/money/categories/${id}`, { ...(name !== undefined ? { name } : {}), ...(emoji !== undefined ? { emoji } : {}), ...('budget' in patch ? { budget: budget ?? null } : {}) }), () => set({ categories: before })); },
  deleteCategory: (id) => { const before = { categories: get().categories, transactions: get().transactions }; set((s) => ({ categories: s.categories.filter((c) => c.id !== id), transactions: s.transactions.map((t) => t.categoryId === id ? { ...t, categoryId: undefined } : t) })); remote(() => api.del(`/money/categories/${id}`), () => set(before), 'delete'); },
  addAccount: (a) => { const acc: Account = { id: uuid(), mask: '', balance: 0, kind: 'bank', ...a }; const before = get().accounts; set((s) => ({ accounts: [...s.accounts, acc] })); remote(() => api.post('/money/accounts', acc), () => set({ accounts: before })); },
  updateAccount: (id, patch) => { const before = get().accounts; set((s) => ({ accounts: s.accounts.map((a) => a.id === id ? { ...a, ...patch } : a) })); remote(() => api.patch(`/money/accounts/${id}`, patch), () => set({ accounts: before })); },
  removeAccount: (id) => { const before = get().accounts; set((s) => ({ accounts: s.accounts.filter((a) => a.id !== id) })); remote(() => api.del(`/money/accounts/${id}`), () => set({ accounts: before }), 'delete'); },
  addSubscription: (sub) => { const x: Subscription = { id: uuid(), cycle: 'Monthly', next: addDays(todayISO(), sub.cycle === 'Yearly' ? 365 : 30), color: '#6366F1', letter: sub.name[0].toUpperCase(), ...sub }; const before = get().subscriptions; set((s) => ({ subscriptions: [...s.subscriptions, x] })); remote(() => api.post('/money/subscriptions', x), () => set({ subscriptions: before })); },
  updateSubscription: (id, patch) => { const before = get().subscriptions; set((s) => ({ subscriptions: s.subscriptions.map((x) => x.id === id ? { ...x, ...patch } : x) })); remote(() => api.patch(`/money/subscriptions/${id}`, patch), () => set({ subscriptions: before })); },
  deleteSubscription: (id) => { const before = get().subscriptions; set((s) => ({ subscriptions: s.subscriptions.filter((x) => x.id !== id) })); remote(() => api.del(`/money/subscriptions/${id}`), () => set({ subscriptions: before }), 'delete'); },
  addRecurring: (r) => { const rec: Recurring = { id: uuid(), emoji: '🔁', method: 'UPI', kind: 'bill', reminder: false, ...r }; const before = get().recurring; set((s) => ({ recurring: [...s.recurring, rec] })); remote(() => api.post('/money/recurring', rec), () => set({ recurring: before })); },
  updateRecurring: (id, patch) => { const before = get().recurring; set((s) => ({ recurring: s.recurring.map((r) => r.id === id ? { ...r, ...patch } : r) })); remote(() => api.patch(`/money/recurring/${id}`, patch), () => set({ recurring: before })); },
  deleteRecurring: (id) => { const before = get().recurring; set((s) => ({ recurring: s.recurring.filter((r) => r.id !== id) })); remote(() => api.del(`/money/recurring/${id}`), () => set({ recurring: before }), 'delete'); },
  /** "Mark paid" — the server creates the transaction (it knows the account/category defaults) and we merge the result. */
  payRecurring: async (id) => {
    const { transaction, recurring } = await api.post<{ transaction: Transaction; recurring: Recurring }>(`/money/recurring/${id}/pay`);
    set((s) => ({ transactions: [transaction, ...s.transactions.filter((t) => t.id !== transaction.id)], recurring: s.recurring.map((r) => r.id === id ? recurring : r) }));
    const acc = get().accounts; const fx = (a: Account) => a.id === transaction.accountId ? { ...a, balance: a.balance + (transaction.kind === 'income' ? transaction.amount : -transaction.amount) } : a.id === transaction.toAccountId && transaction.kind === 'transfer' ? { ...a, balance: a.balance + transaction.amount } : a;
    set({ accounts: acc.map(fx) }); void get().loadActivity();
  },

  /* ---------- wellness (API-backed; the server merge-saves one day at a time) ---------- */
  saveWellness: (date, e) => { const before = get().wellness; set((s) => ({ wellness: { ...s.wellness, [date]: { ...s.wellness[date], ...e } } })); const { savedAt: _s, ...rest } = e; remote(async () => { const r = await api.put<{ date: string; entry: WellnessEntry }>(`/wellness/entries/${date}`, nulls(rest)); set((s) => ({ wellness: { ...s.wellness, [r.date]: r.entry } })); }, () => set({ wellness: before })); },
  updateWellnessSettings: (patch) => { const before = get().wellnessSettings; set((s) => ({ wellnessSettings: { ...s.wellnessSettings, ...patch } })); remote(async () => { const saved = await api.patch<WellnessSettings>('/wellness/settings', strip(patch)); set({ wellnessSettings: saved }); }, () => set({ wellnessSettings: before })); },

  /* ---------- notes (API-backed; body/title edits are debounced so the editor autosave doesn't flood the API) ---------- */
  addNote: (n) => { const note: Note = { body: '', folder: 'Personal', tags: [], updatedAt: todayISO(), createdAt: todayISO(), ...n, id: n.id || uuid() }; const before = get().notes; set((s) => ({ notes: [note, ...s.notes] })); remote(async () => { const saved = await api.post<Note>('/notes', nulls({ id: note.id, title: note.title, body: note.body, folder: note.folder, tags: note.tags, journalDate: note.journalDate, ai: note.ai || false, mood: note.mood })); set((s) => ({ notes: s.notes.map((x) => x.id === saved.id ? saved : x) })); }, () => set({ notes: before })); return note; },
  updateNote: (id, patch) => {
    set((s) => ({ notes: s.notes.map((n) => n.id === id ? { ...n, ...patch, updatedAt: todayISO() } : n) }));
    const { id: _i, createdAt: _c, updatedAt: _u, ...rest } = patch; if (Object.keys(rest).length === 0) return;
    pendingNote[id] = { ...(pendingNote[id] || {}), ...rest }; clearTimeout(noteTimers[id]);
    noteTimers[id] = setTimeout(() => { const body = pendingNote[id]; delete pendingNote[id]; remote(async () => { const saved = await api.patch<Note>(`/notes/${id}`, nulls(body)); set((s) => ({ notes: s.notes.map((x) => x.id === saved.id ? { ...saved, ...(pendingNote[id] || {}) } : x) })); }, () => { void get().loadNotes(); }); }, 600);
  },
  deleteNote: (id) => { clearTimeout(noteTimers[id]); delete pendingNote[id]; const before = get().notes; set((s) => ({ notes: s.notes.filter((n) => n.id !== id) })); remote(() => api.del(`/notes/${id}`), () => set({ notes: before }), 'delete'); },

  /* ---------- notifications ---------- */
  markRead: (id) => set((s) => ({ notifications: s.notifications.map((n) => id === 'all' || n.id === id ? { ...n, read: true } : n) })),

  /* ---------- ai ---------- */
  pushAiMessage: (m) => { const msg = { id: uid(), ...m }; set((s) => ({ aiMessages: [...s.aiMessages, msg] })); return msg; },
  updateAiMessage: (id, patch) => set((s) => ({ aiMessages: s.aiMessages.map((m) => m.id === id ? { ...m, ...patch } : m) })),
  clearAi: () => set({ aiMessages: [] }),
  logAiAction: (a) => set((s) => ({ aiActions: [{ id: uid(), when: 'Today ' + nowLabel(), ...a }, ...s.aiActions] })),
  revertAiAction: (id) => set((s) => ({ aiActions: [{ id: uid(), when: 'Today ' + nowLabel(), action: 'Reverted', detail: s.aiActions.find((a) => a.id === id)?.detail || '', state: 'By you', tone: 'warning', revertible: false }, ...s.aiActions.map((a) => a.id === id ? { ...a, reverted: true, state: a.state + ' · reverted' } : a)] })),
  setSuggestion: (id, state) => set((s) => ({ suggestions: s.suggestions.map((x) => x.id === id ? { ...x, state } : x) })),

  /* ---------- settings / misc ---------- */
  updateSettings: (patch) => { set((s) => ({ settings: { ...s.settings, ...patch } })); if (get().auth === 'authed') api.patch('/users/me/settings', patch).catch(() => {}); },
  addActivity: (text, icon = 'circle', tone = '', module?: string) => set((s) => ({ activity: [{ id: uid(), when: nowLabel(), date: todayISO(), text, icon, tone, module, at: new Date().toISOString() }, ...s.activity].slice(0, 300) })),
  toast: (msg, opts = {}) => { const id = uid(); set((s) => ({ toasts: [...s.toasts.slice(-2), { id, msg, undo: opts.undo, tone: opts.tone }] })); setTimeout(() => get().dismissToast(id), opts.undo ? 6000 : 3200); },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  /** Restores the sample data of the still-local bits (AI chat/suggestions, notifications); everything server-backed is untouched. */
  resetAll: () => set((s) => ({ ...initial(), hydrated: true, user: s.user, auth: s.auth, moneyLoaded: s.moneyLoaded, tasksLoaded: s.tasksLoaded, habitsLoaded: s.habitsLoaded, goalsLoaded: s.goalsLoaded, notesLoaded: s.notesLoaded, wellnessLoaded: s.wellnessLoaded, habits: s.habits, goals: s.goals, tasks: s.tasks, projects: s.projects, notes: s.notes, wellness: s.wellness, wellnessSettings: s.wellnessSettings, transactions: s.transactions, categories: s.categories, accounts: s.accounts, subscriptions: s.subscriptions, recurring: s.recurring, settings: s.settings, activity: s.activity.filter((a) => a.module) })),
  }); }, {
  name: 'mitra.store.v6',
  storage: createJSONStorage(() => localStorage),
  skipHydration: true,
  partialize: (s) => { const { toasts: _t, hydrated: _h, auth: _a, ...rest } = s; return rest as unknown as State; }, // money + profile are cached for offline reading; auth status is always re-checked
  onRehydrateStorage: () => () => { useStore.setState({ hydrated: true }); },
  merge: (persisted, current) => ({ ...current, ...(persisted as object), toasts: [], auth: 'unknown' }),
}));

/** Call once on the client after mount: replays localStorage into the store (skipHydration avoids SSR mismatch). */
export const rehydrateStore = () => { useStore.persist.rehydrate(); };
