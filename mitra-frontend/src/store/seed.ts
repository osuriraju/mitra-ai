/* Seed data — the prototype's sample data, re-expressed as real records relative to today's date. */
import { addDays, todayISO, dowMon } from '@/lib/dates';
import type { Account, AiAction, Category, Goal, Habit, Note, Notification, Project, Recurring, Settings, Subscription, Suggestion, Task, Transaction, WellnessEntry, WellnessSettings } from './types';

const T = todayISO();
const d = (n: number) => addDays(T, n);
const NOW = new Date().toISOString();

export const PROJECTS: Project[] = [
  { id: 'p-mitra', name: 'Mitra AI', emoji: '🚀', goalId: 'g-beta', createdAt: NOW },
  { id: 'p-freelance', name: 'Freelance', emoji: '💼', goalId: 'g-fund', createdAt: NOW },
  { id: 'p-personal', name: 'Personal', emoji: '🙂', createdAt: NOW },
  { id: 'p-home', name: 'Home', emoji: '🏠', createdAt: NOW },
  { id: 'p-learning', name: 'Learning', emoji: '📚', goalId: 'g-books', createdAt: NOW },
  { id: 'p-family', name: 'Family', emoji: '👨‍👩‍👧', createdAt: NOW },
];

const task = (id: string, title: string, o: Partial<Task> = {}): Task => ({ id, title, status: o.done ? 'done' : 'todo', done: false, notes: '', source: 'user', createdAt: NOW, ...o });
const sub = (id: string, parentId: string, title: string, o: Partial<Task> = {}): Task => task(id, title, { parentId, ...o });
export const TASKS: Task[] = [
  task('t1', 'Send Q3 invoice to Northwind Studio', { projectId: 'p-freelance', due: T, time: '17:00', pri: 'high', estMin: 30, goalId: 'g-fund', notes: 'Rate ₹2,500/hr × 18h. Include the September retainer line. PO number from last email.' }),
  sub('s1', 't1', 'Export hours from time tracker', { projectId: 'p-freelance', done: true, status: 'done', completedAt: d(-1) }),
  sub('s2', 't1', 'Fill invoice template', { projectId: 'p-freelance', due: T, estMin: 15, status: 'inprogress' }),
  sub('s3', 't1', 'Email + mark as sent', { projectId: 'p-freelance', due: T, pri: 'high' }),
  task('t3', 'Review PR #214 — auth refactor', { projectId: 'p-mitra', due: T, time: '15:00', pri: 'high', estMin: 45, status: 'inprogress' }),
  task('t2', 'Book dentist appointment', { projectId: 'p-personal', due: T, pri: 'med', estMin: 10 }),
  task('t6', 'Call Amma', { projectId: 'p-family', due: T, time: '19:00', pri: 'low', source: 'ai' }),
  task('t7', 'Read 20 pages — Atomic Habits', { projectId: 'p-learning', due: T, pri: 'low', estMin: 25, habitId: 'h-read' }),
  task('t5', 'Renew car insurance', { projectId: 'p-personal', due: d(-2), pri: 'high' }),
  task('t4', 'Prepare slides for Friday demo', { projectId: 'p-mitra', due: d(2), pri: 'med', estMin: 120 }),
  sub('s4', 't4', 'Outline', { projectId: 'p-mitra', done: true, status: 'done', completedAt: d(-2), estMin: 30 }),
  sub('s5', 't4', 'Build slides', { projectId: 'p-mitra', done: true, status: 'done', completedAt: d(-1), estMin: 60 }),
  sub('s6', 't4', 'Rehearse', { projectId: 'p-mitra', due: d(1), estMin: 30, status: 'inprogress' }),
  sub('s7', 't4', 'Collect feedback', { projectId: 'p-mitra', due: d(2) }),
  sub('s8', 't4', 'Send deck', { projectId: 'p-mitra', due: d(2), pri: 'med' }),
  task('t8', 'Groceries: milk, eggs, spinach', { projectId: 'p-home', due: d(1) }),
  task('t10', 'Pay electricity bill', { projectId: 'p-home', due: d(3), amount: 2140, recurring: 'Monthly' }),
  task('t9', 'Write weekly review', { projectId: 'p-personal', due: d(4), recurring: 'Weekly' }),
  task('t11', 'Draft onboarding copy', { projectId: 'p-mitra', due: d(5), estMin: 60 }),
  task('t16', 'Quarterly goal review', { projectId: 'p-personal', due: d(7), recurring: 'Quarterly' }),
  task('t17', 'Dentist appointment', { projectId: 'p-personal', due: d(8), time: '10:30' }),
  task('t12', 'Idea: habit streak recovery UX'),
  task('t13', 'Buy birthday gift for Priya'),
  task('t14', 'Check flight prices for Goa trip'),
  task('t15', 'Reply to accountant about GST filing'),
  task('t18', 'Standup notes to team', { projectId: 'p-mitra', due: T, done: true, status: 'done', completedAt: T }),
  task('t19', 'Water the plants', { projectId: 'p-home', due: T, done: true, status: 'done', completedAt: T }),
  task('t20', 'Empty states for Money', { projectId: 'p-mitra', estMin: 120 }),
  task('t21', 'PWA install prompt', { projectId: 'p-mitra', estMin: 60 }),
  task('t22', 'Auth refactor', { projectId: 'p-mitra', estMin: 180, pri: 'high', status: 'inprogress' }),
  ...['Token refresh', 'Session store', 'Password hashing', 'Login endpoint', 'Signup endpoint', 'Reset flow', 'Rate limiting', 'Audit events', 'E2E tests'].map((t, i) => sub('ar' + i, 't22', t, { projectId: 'p-mitra', done: i < 6, status: i < 6 ? 'done' : i === 6 ? 'inprogress' : 'todo', completedAt: i < 6 ? d(-(8 - i)) : undefined, pri: i === 6 ? 'high' : undefined })),
  task('t23', 'Receipt upload on iOS Safari', { projectId: 'p-mitra', pri: 'high', status: 'blocked' }),
  task('t24', 'Analytics event schema', { projectId: 'p-mitra', done: true, status: 'done', completedAt: d(-3) }),
  task('t25', 'Design tokens', { projectId: 'p-mitra', done: true, status: 'done', completedAt: d(-5) }),
  task('t26', 'Transfer ₹8,000 — October', { goalId: 'g-fund', due: d(14), recurring: 'Monthly', amount: 8000 }),
  task('t27', 'Move festival bonus to savings', { goalId: 'g-fund', due: d(40), pri: 'med' }),
];

/* Habits: 12 weeks of deterministic pseudo-random logs so streaks/heatmaps look real */
const seeded = (seed: number) => () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
const logs = (seed: number, rate: number, value: () => number, days: boolean[], lastDone: number) => {
  const r = seeded(seed); const out: Record<string, number> = {};
  for (let i = 84; i >= 1; i--) { const day = d(-i); if (!days[dowMon(day)]) continue; if (r() < rate) out[day] = value(); }
  for (let i = 1; i <= lastDone; i++) out[d(-i)] = value();
  return out;
};
const vr = seeded(99);
const ALL = [true, true, true, true, true, true, true], WEEKDAYS = [true, true, true, true, true, false, false];
export const HABITS: Habit[] = [
  { id: 'h-walk', name: 'Morning walk', type: 'duration', target: 30, unit: 'min', icon: 'activity', timeOfDay: 'morning', days: ALL, reminder: '07:00', goalId: 'g-10k', gentle: true, createdAt: NOW, logs: { ...logs(11, 0.86, () => 25 + Math.round(vr() * 15), ALL, 12), [T]: 32 } },
  { id: 'h-water', name: 'Drink water', type: 'count', target: 8, unit: 'glasses', icon: 'droplet', timeOfDay: 'anytime', days: ALL, gentle: true, createdAt: NOW, logs: { ...logs(22, 0.71, () => 8, ALL, 4), [T]: 5 } },
  { id: 'h-read', name: 'Read', type: 'duration', target: 20, unit: 'min', icon: 'book', timeOfDay: 'anytime', days: ALL, goalId: 'g-books', gentle: true, createdAt: NOW, logs: logs(33, 0.58, () => 20, ALL, 0) },
  { id: 'h-meditate', name: 'Meditate', type: 'duration', target: 10, unit: 'min', icon: 'sun', timeOfDay: 'morning', days: ALL, gentle: true, createdAt: NOW, logs: { ...logs(44, 0.64, () => 10, ALL, 7), [T]: 10 } },
  { id: 'h-sugar', name: 'No sugar', type: 'binary', target: 1, unit: '', icon: 'x', timeOfDay: 'anytime', days: WEEKDAYS, gentle: true, createdAt: NOW, logs: logs(55, 0.45, () => 1, WEEKDAYS, 2) },
  { id: 'h-sleep', name: 'Sleep by 11 pm', type: 'binary', target: 1, unit: '', icon: 'moon', timeOfDay: 'evening', days: ALL, reminder: '22:30', gentle: true, createdAt: NOW, logs: logs(66, 0.52, () => 1, ALL, 3) },
];
// remove any "today" logs for habits that should still be open
delete HABITS[2].logs[T]; delete HABITS[4].logs[T]; delete HABITS[5].logs[T];

export const GOALS: Goal[] = [
  { id: 'g-fund', name: 'Build ₹1,00,000 emergency fund', type: 'numeric', term: 'Medium-term', due: '2027-03-31', startedOn: '2026-01-05', current: 62000, target: 100000, unit: '₹', formula: 'SBI Savings balance tagged “Emergency” (₹54,000) + recurring transfers scheduled (₹8,000)', nextAction: 'Transfer ₹8,000 on 1 Oct', reviewEvery: 'Monthly', reviewOn: d(14), status: 'active', notes: '', createdAt: NOW,
    milestones: [{ id: 'm1', title: '₹25,000', target: 25000, reachedOn: '2026-03-20' }, { id: 'm2', title: '₹50,000', target: 50000, reachedOn: '2026-07-08' }, { id: 'm3', title: '₹75,000', target: 75000, targetDate: '2026-11-30', notes: 'If the bonus lands, this milestone can close a month early.' }, { id: 'm4', title: '₹1,00,000', target: 100000, targetDate: '2027-03-31' }],
    progressLog: [8, 15, 25, 31, 38, 44, 50, 54, 62].map((v, i) => ({ date: `2026-${String(i + 1).padStart(2, '0')}-01`, value: v * 1000 })) },
  { id: 'g-10k', name: 'Run a 10K', type: 'milestone', term: 'Short-term', due: '2026-12-15', startedOn: '2026-06-01', current: 2, target: 5, unit: '', nextAction: '5K run on Saturday', reviewEvery: 'Monthly', reviewOn: d(-1), status: 'active', notes: '', createdAt: NOW,
    milestones: [{ id: 'm5', title: 'Walk 30 min daily for 4 weeks', reachedOn: '2026-07-01' }, { id: 'm6', title: 'Run 2K without stopping', reachedOn: '2026-08-10' }, { id: 'm7', title: 'Run 5K', targetDate: d(3) }, { id: 'm8', title: 'Run 8K', targetDate: '2026-11-15' }, { id: 'm9', title: 'Race day 10K', targetDate: '2026-12-15' }],
    progressLog: [{ date: '2026-07-01', value: 1 }, { date: '2026-08-10', value: 2 }] },
  { id: 'g-beta', name: 'Ship Mitra AI beta', type: 'milestone', term: 'Short-term', due: '2026-11-30', startedOn: '2026-05-01', current: 3, target: 6, unit: '', nextAction: 'Finish auth refactor', reviewEvery: 'Weekly', reviewOn: d(5), status: 'active', notes: '', createdAt: NOW,
    milestones: [{ id: 'm10', title: 'Design system', reachedOn: '2026-06-15' }, { id: 'm11', title: 'Data model + API', reachedOn: '2026-07-30' }, { id: 'm12', title: 'Today + Tasks', reachedOn: '2026-09-01' }, { id: 'm13', title: 'Auth + onboarding', targetDate: '2026-10-05' }, { id: 'm14', title: 'Money module', targetDate: '2026-11-01' }, { id: 'm15', title: 'Beta invite', targetDate: '2026-11-30' }],
    progressLog: [{ date: '2026-06-15', value: 1 }, { date: '2026-07-30', value: 2 }, { date: '2026-09-01', value: 3 }] },
  { id: 'g-books', name: 'Read 24 books this year', type: 'numeric', term: 'Long-term', due: '2026-12-31', startedOn: '2026-01-01', current: 14, target: 24, unit: '', nextAction: 'Finish Atomic Habits', reviewEvery: 'Monthly', reviewOn: d(20), status: 'active', notes: '', createdAt: NOW,
    milestones: [{ id: 'm16', title: '6 books', target: 6, reachedOn: '2026-03-30' }, { id: 'm17', title: '12 books', target: 12, reachedOn: '2026-07-20' }, { id: 'm18', title: '18 books', target: 18, targetDate: '2026-10-15' }, { id: 'm19', title: '24 books', target: 24, targetDate: '2026-12-31' }],
    progressLog: [2, 4, 6, 8, 9, 11, 12, 13, 14].map((v, i) => ({ date: `2026-${String(i + 1).padStart(2, '0')}-28`, value: v })) },
  { id: 'g-spanish', name: 'Learn Spanish to B1', type: 'habit', term: 'Long-term', due: '2027-06-30', startedOn: '2026-04-01', current: 22, target: 100, unit: '', nextAction: 'Complete Unit 4', reviewEvery: 'Monthly', reviewOn: d(-6), status: 'active', notes: '', createdAt: NOW,
    milestones: [{ id: 'm20', title: 'A1 — units 1–6', reachedOn: '2026-06-30' }, { id: 'm21', title: 'A2 — units 7–14', targetDate: '2026-12-31' }, { id: 'm22', title: 'B1 — conversation practice', targetDate: '2027-06-30' }],
    progressLog: [{ date: '2026-05-01', value: 8 }, { date: '2026-06-30', value: 18 }, { date: '2026-08-01', value: 22 }] },
];

export const CATEGORIES: Category[] = [
  { id: 'c-food', name: 'Food & dining', emoji: '🍛', kind: 'expense', budget: 8000 }, { id: 'c-transport', name: 'Transport', emoji: '🚇', kind: 'expense', budget: 3000 },
  { id: 'c-groceries', name: 'Groceries', emoji: '🛒', kind: 'expense', budget: 7000 }, { id: 'c-shopping', name: 'Shopping', emoji: '🛍️', kind: 'expense', budget: 4000 },
  { id: 'c-health', name: 'Health', emoji: '💊', kind: 'expense', budget: 2000 }, { id: 'c-fun', name: 'Entertainment', emoji: '🎬', kind: 'expense', budget: 3000 },
  { id: 'c-home', name: 'Housing', emoji: '🏠', kind: 'expense', budget: 30000 }, { id: 'c-learning', name: 'Learning', emoji: '📚', kind: 'expense', budget: 1500 },
  { id: 'c-subs', name: 'Subscriptions', emoji: '📺', kind: 'expense', budget: 3500 }, { id: 'c-transfer', name: 'Transfer', emoji: '🏦', kind: 'expense' },
  { id: 'c-salary', name: 'Salary', emoji: '💼', kind: 'income' }, { id: 'c-freelance', name: 'Freelance', emoji: '🧾', kind: 'income' },
];
export const ACCOUNTS: Account[] = [
  { id: 'a-hdfc', name: 'HDFC Savings', mask: '••4021', balance: 118400, kind: 'bank' }, { id: 'a-icici', name: 'ICICI Credit', mask: '••7710', balance: -28120, kind: 'credit' },
  { id: 'a-sbi', name: 'SBI Savings', mask: '••3388 · Emergency', balance: 390000, kind: 'bank' }, { id: 'a-cash', name: 'Cash', mask: 'Wallet', balance: 2300, kind: 'cash' },
];
const m1 = T.slice(0, 8); // current month prefix
const tx = (id: string, merchant: string, amount: number, categoryId: string, accountId: string, date: string, time: string, o: Partial<Transaction> = {}): Transaction => ({ id, kind: 'expense', merchant, amount, categoryId, accountId, date, time, source: 'user', ...o });
export const TRANSACTIONS: Transaction[] = [
  tx('x1', 'Swiggy', 450, 'c-food', 'a-hdfc', T, '13:12', { source: 'ai', note: 'UPI' }),
  tx('x2', 'Metro recharge', 500, 'c-transport', 'a-hdfc', T, '08:40'),
  tx('x3', 'Blinkit', 1230, 'c-groceries', 'a-icici', d(-1), '19:05'),
  tx('x4', 'Uber', 312, 'c-transport', 'a-hdfc', d(-2), '21:10'),
  tx('x5', 'Decathlon', 2499, 'c-shopping', 'a-icici', d(-3), '16:30'),
  tx('x6', 'Netflix', 649, 'c-subs', 'a-icici', d(-4), '06:00', { source: 'recurring', recurringId: 'sub-netflix' }),
  tx('x7', 'Apollo Pharmacy', 780, 'c-health', 'a-cash', d(-5), '11:20'),
  tx('x8', 'Zomato', 620, 'c-food', 'a-hdfc', d(-6), '20:45'),
  tx('x9', 'BigBasket', 2140, 'c-groceries', 'a-icici', d(-8), '10:00'),
  tx('x10', 'Amazon', 1801, 'c-shopping', 'a-icici', d(-9), '14:00'),
  tx('x11', 'BookMyShow', 900, 'c-fun', 'a-hdfc', d(-10), '18:30'),
  tx('x12', 'Rent', 28000, 'c-home', 'a-hdfc', m1 + '03', '09:00', { source: 'recurring', recurringId: 'r-rent', note: 'NEFT' }),
  tx('x13', 'Transfer to Savings', 8000, 'c-transfer', 'a-hdfc', m1 + '02', '09:05', { kind: 'transfer', toAccountId: 'a-sbi', note: 'IMPS' }),
  tx('x14', 'Salary — Beyond Labs', 125000, 'c-salary', 'a-hdfc', m1 + '01', '00:10', { kind: 'income', note: 'NEFT' }),
  tx('x15', 'Airtel Fiber', 1199, 'c-subs', 'a-hdfc', m1 + '08', '06:00', { source: 'recurring', recurringId: 'r-airtel' }),
  tx('x16', 'Cult.fit', 1500, 'c-health', 'a-icici', m1 + '01', '06:00', { source: 'recurring', recurringId: 'sub-cult' }),
  tx('x17', 'Cafe Coffee Day', 380, 'c-food', 'a-cash', d(-12), '10:15'),
  tx('x18', 'Ola', 260, 'c-transport', 'a-hdfc', d(-13), '09:30'),
  tx('x19', 'Udemy', 499, 'c-learning', 'a-icici', d(-11), '22:00'),
];
export const SUBSCRIPTIONS: Subscription[] = [
  { id: 'sub-netflix', name: 'Netflix', amount: 649, cycle: 'Monthly', next: addDays(d(-4), 30), color: '#E50914', letter: 'N' },
  { id: 'sub-spotify', name: 'Spotify', amount: 119, cycle: 'Monthly', next: d(3), color: '#1DB954', letter: 'S' },
  { id: 'sub-icloud', name: 'iCloud+ 200GB', amount: 219, cycle: 'Monthly', next: d(16), color: '#3B82F6', letter: 'i' },
  { id: 'sub-cult', name: 'Gym — Cult.fit', amount: 1500, cycle: 'Monthly', next: d(14), color: '#F97316', letter: 'C' },
  { id: 'sub-notion', name: 'Notion Plus', amount: 830, cycle: 'Monthly', next: d(11), color: '#111827', letter: 'N', lastUsed: d(-34) },
  { id: 'sub-prime', name: 'Amazon Prime', amount: 1499, cycle: 'Yearly', next: '2027-01-14', color: '#0EA5E9', letter: 'a' },
];
export const RECURRING: Recurring[] = [
  { id: 'r-rent', name: 'Rent', emoji: '🏠', amount: 28000, day: 3, method: 'NEFT · HDFC', kind: 'bill', reminder: true },
  { id: 'r-savings', name: 'Transfer to Savings', emoji: '🏦', amount: 8000, day: 2, method: 'IMPS', kind: 'transfer', reminder: false },
  { id: 'r-airtel', name: 'Airtel Fiber', emoji: '📶', amount: 1199, day: 8, method: 'auto-debit', kind: 'bill', reminder: false },
  { id: 'r-electric', name: 'Electricity — BESCOM', emoji: '💡', amount: 2140, day: 19, method: 'varies · last ₹1,980', kind: 'bill', reminder: true },
  { id: 'r-jio', name: 'Phone — Jio', emoji: '📱', amount: 299, day: 15, method: 'UPI', kind: 'bill', reminder: false },
  { id: 'r-salary', name: 'Salary — Beyond Labs', emoji: '💼', amount: 125000, day: 1, method: 'NEFT', kind: 'income', reminder: false },
];

const wr = seeded(7);
export const WELLNESS: Record<string, WellnessEntry> = {};
for (let i = 30; i >= 1; i--) { const sleep = 5.9 + wr() * 2; const sh = Math.floor(sleep), sm = Math.round((sleep - sh) * 60); WELLNESS[d(-i)] = { mood: 2 + Math.round(wr() * 2), sleepStart: '23:30', sleepEnd: `${String(5 + sh - 1).padStart(2, '0')}:${String(30 + sm > 59 ? 30 + sm - 60 : 30 + sm).padStart(2, '0')}`, steps: 5000 + Math.round(wr() * 6000), water: 4 + Math.round(wr() * 4), weight: +(74.1 - (30 - i) * 0.057).toFixed(1), savedAt: d(-i) }; }
WELLNESS[T] = { mood: 3, sleepStart: '23:40', sleepEnd: '06:20', steps: 6210, water: 5, weight: 72.4 };
export const WELLNESS_SETTINGS: WellnessSettings = { height: 175, sleepTarget: 450, stepsTarget: 8000, waterTarget: 8, track: { mood: true, sleep: true, steps: true, water: true, bmi: true }, shareAI: false, showOnToday: true, morningReminder: '07:30', eveningReminder: '21:00' };

const note = (id: string, title: string, body: string, folder: string, tags: string[], daysAgo: number, o: Partial<Note> = {}): Note => ({ id, title, body, folder, tags, updatedAt: d(-daysAgo), createdAt: d(-daysAgo - 3), ...o });
export const NOTES: Note[] = [
  note('n1', 'Beta launch checklist', `Target: [[Ship Mitra AI beta]] · Nov 2026\n\n## Must ship\n- [x] Auth refactor (PR #214)\n- [ ] Onboarding copy — see [[Draft onboarding copy]]\n- [ ] Analytics events for Quick Add → Task → Today\n- [ ] PWA install prompt + offline shell\n- [ ] Empty states for Money\n\n## Blockers\nReceipt upload fails on iOS Safari when the image is over 8 MB. Need client-side compression before upload.\n\n## Open questions\nShould the AI briefing be above the fold on mobile? Decision in [[Meeting notes — design review]].`, 'Work', ['mitra-ai', 'checklist'], 0),
  note('n2', 'Idea: streak recovery UX', 'Missed days should not reset to zero visually. Show "recovering" state and a soft nudge instead of a broken chain. Compare with Duolingo streak freeze.', 'Ideas', ['ideas', 'habits'], 1),
  note('n3', 'Book notes — Atomic Habits', 'Make it obvious, attractive, easy, satisfying. Habit stacking: after [current habit], I will [new habit]. Environment design beats motivation.\n\n"…never miss twice. A missed day breaks the streak only in the app…"', 'Learning', ['books', 'learning'], 3),
  note('n4', 'Journal — ' + d(-1).slice(5), 'Slept late again. Good focus block in the morning though. Need to move the run earlier; evenings keep getting eaten by calls.', 'Journal', ['journal'], 1, { journalDate: d(-1), ai: true, mood: 2 }),
  note('n5', 'Goa trip plan', "Dates: 10–14 Dec. Budget ₹25,000. Flights ~₹9k return, stay ~₹10k. Check Priya's leave. Ideas: Palolem, Fontainhas walk, Saturday night market.", 'Personal', ['travel', 'money'], 2),
  note('n6', 'Meeting notes — design review', 'Keep Today under 5 sections by default. Money card only shows today spend + budget bar. Move AI briefing above the fold on desktop only.\n\nMentions [[Beta launch checklist]].', 'Work', ['mitra-ai', 'meeting'], 5),
  note('n7', 'Journal — ' + d(-2).slice(5), 'Insurance renewal slipped. Long run felt great — 6K at an easy pace. Dinner ran until 11:15 so sleep suffered.', 'Journal', ['journal'], 2, { journalDate: d(-2), mood: 3 }),
  note('n8', 'Emergency fund plan', 'AI-generated plan, edited. 4 milestones · ₹8,000 monthly transfer on the 2nd · revisit after the festival bonus.', 'Personal', ['money', 'goals'], 6, { ai: true }),
];

export const NOTIFICATIONS: Notification[] = [
  { id: 'nf1', t: 'Invoice to Northwind due at 5:00 PM', s: 'Task · high priority', when: '10m', icon: 'check-square', tone: 'accent', read: false, goto: '/tasks/t1' },
  { id: 'nf2', t: 'Shopping budget exceeded by ₹300', s: 'Money · Shopping · this month', when: '1h', icon: 'wallet', tone: 'warning', read: false, goto: '/money/budgets' },
  { id: 'nf3', t: 'You slept 6h 40m — under your 7h 30m target', s: 'Wellness · from check-in', when: '8h', icon: 'moon', tone: 'info', read: false, goto: '/wellness/sleep' },
  { id: 'nf4', t: 'Netflix renews in 3 days (₹649)', s: 'Subscriptions', when: 'Yesterday', icon: 'repeat', tone: '', read: true, goto: '/money/subscriptions' },
  { id: 'nf5', t: 'AI: 3 tasks were rescheduled from Monday', s: 'Review the changes in AI activity', when: 'Yesterday', icon: 'sparkles', tone: 'accent', read: true, goto: '/ai/log' },
  { id: 'nf6', t: 'Water: 3 glasses to go', s: 'Wellness · daily target', when: 'Sun', icon: 'droplet', tone: 'info', read: true, goto: '/wellness' },
  { id: 'nf7', t: 'Goal check-in: Run a 10K', s: 'Review date reached', when: 'Sat', icon: 'target', tone: '', read: true, goto: '/goals/review' },
];

export const AI_ACTIONS: AiAction[] = [
  { id: 'aa1', when: 'Today 1:12 PM', action: 'Created expense', detail: 'Swiggy · ₹450 · Food', state: 'Approved by you', tone: 'success', revertible: true },
  { id: 'aa2', when: 'Today 9:02 AM', action: 'Drafted journal', detail: 'Journal — today (draft, not saved)', state: 'Pending', tone: 'accent', revertible: false },
  { id: 'aa3', when: 'Yesterday', action: 'Rescheduled 3 tasks', detail: 'Dentist → Thu · Slides → Thu · Insurance → Sat', state: 'Applied · reverted 1', tone: 'warning', revertible: true },
  { id: 'aa4', when: 'Mon', action: 'Created task', detail: 'Call Amma · 7 PM · Family', state: 'Approved', tone: 'success', revertible: true },
  { id: 'aa5', when: 'Sun', action: 'Summarised your week', detail: 'last week · in chat', state: '—', tone: '', revertible: false },
  { id: 'aa6', when: 'Sat', action: 'Proposed plan', detail: 'Emergency fund: 4 milestones, 12 tasks', state: 'Approved with edits', tone: 'success', revertible: true },
  { id: 'aa7', when: 'Fri', action: 'Detected recurring', detail: 'Apollo Pharmacy ₹780', state: 'Suggested (not applied)', tone: '', revertible: false },
];
export const SUGGESTIONS: Suggestion[] = [
  { id: 'sg1', ic: 'bell', t: 'Add a 10:30 PM wind-down reminder', p: 'Sleep has been under the 7h 30m target three nights running. A reminder supports the “Sleep by 11 pm” habit.', src: 'Wellness · sleep trend', tone: 'accent', state: 'open' },
  { id: 'sg2', ic: 'repeat', t: 'Make “Apollo Pharmacy ₹780” a monthly recurring expense', p: 'Seen on the 11th for 3 months. Would add to Health budget forecasts.', src: 'Money · pattern detection', tone: '', state: 'open' },
  { id: 'sg3', ic: 'check-square', t: 'Split “Prepare slides for Friday demo” into 3 subtasks', p: 'Outline (30m) · Build slides (1h) · Rehearse (30m). Fits Thu afternoon free slots.', src: 'Tasks · from estimate 2h', tone: '', state: 'open' },
  { id: 'sg4', ic: 'folder', t: 'File 4 inbox items', p: 'Idea → Ideas note · Gift → Personal, Sat · Flights → Goa trip note · Accountant → Freelance, Mon', src: 'Inbox triage', tone: '', state: 'open' },
  { id: 'sg5', ic: 'target', t: 'Pause “Learn Spanish to B1”', p: 'No linked activity for 3 weeks. Pausing keeps history and removes it from Today.', src: 'Goals · review', tone: 'warning', state: 'open' },
];
export const SETTINGS: Settings = { reduceMotion: false, compact: false, modules: { money: true, wellness: true, ai: true }, todaySections: ['priorities', 'activity', 'habits', 'checkin', 'money', 'goals'], weekStart: 'Monday', dayStart: '06:00', quietHours: true, digest: true, autoSave: 'Small only', shareWellness: false, focusMinutes: 25 };
