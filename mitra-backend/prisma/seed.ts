/* Dev seed: the demo user from the design prototype with a month of realistic INR data.
   Run `pnpm db:seed` — safe to re-run: keeps the user row (so open sessions survive) and replaces only its Money data.
   `pnpm db:seed -- you@example.com` reseeds Money for any existing user instead. */
import { PrismaClient } from '@prisma/client';
import * as argon2 from 'argon2';
import { DEFAULT_CATEGORIES } from '../src/money/money-defaults.service';

const prisma = new PrismaClient();
const P = (rupees: number) => BigInt(Math.round(rupees * 100));
const iso = (d: Date) => d.toISOString().slice(0, 10);
const today = new Date(); today.setUTCHours(0, 0, 0, 0);
const d = (n: number) => { const x = new Date(today); x.setUTCDate(x.getUTCDate() + n); return x; };
const monthDay = (day: number) => new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), day));

async function main() {
  const email = process.argv[2] || 'aarav@example.com';
  let user = await prisma.user.findUnique({ where: { email } });
  if (!user) {
    if (email !== 'aarav@example.com') throw new Error(`No user with email ${email}`);
    user = await prisma.user.create({ data: { name: 'Aarav Mehta', email, username: 'aarav', phone: '+91 98450 12345', passwordHash: await argon2.hash('password123', { type: argon2.argon2id }), onboardedAt: new Date(), settings: { modules: { money: true, wellness: true, ai: true }, autoSave: 'Small only' } } });
  }
  const uid = user.id;
  // Replace this user's Money data only (cascade order: transactions first, then the rows they point at)
  await prisma.transaction.deleteMany({ where: { userId: uid } });
  await prisma.subscription.deleteMany({ where: { userId: uid } });
  await prisma.recurring.deleteMany({ where: { userId: uid } });
  await prisma.account.deleteMany({ where: { userId: uid } });
  await prisma.category.deleteMany({ where: { userId: uid } });
  await prisma.activity.deleteMany({ where: { userId: uid, module: { in: ['money', 'tasks', 'habits', 'goals', 'wellness'] } } });
  await prisma.task.deleteMany({ where: { userId: uid } });
  await prisma.project.deleteMany({ where: { userId: uid } });
  await prisma.habit.deleteMany({ where: { userId: uid } });
  await prisma.goal.deleteMany({ where: { userId: uid } });
  await prisma.note.deleteMany({ where: { userId: uid } });
  await prisma.wellnessEntry.deleteMany({ where: { userId: uid } });
  await prisma.wellnessSettings.deleteMany({ where: { userId: uid } });

  const budgets: Record<string, number> = { food: 8000, transport: 3000, groceries: 7000, shopping: 4000, health: 2000, fun: 3000, home: 30000, learning: 1500, subs: 3500 };
  await prisma.category.createMany({ data: DEFAULT_CATEGORIES.map((c) => ({ ...c, userId: uid, budgetMinor: budgets[c.slug] ? P(budgets[c.slug]) : null })) });
  const cats = Object.fromEntries((await prisma.category.findMany({ where: { userId: uid } })).map((c) => [c.slug!, c.id]));

  const [hdfc, icici, sbi, cash] = await Promise.all([
    prisma.account.create({ data: { userId: uid, name: 'HDFC Savings', mask: '••4021', kind: 'bank', balanceMinor: P(118400) } }),
    prisma.account.create({ data: { userId: uid, name: 'ICICI Credit', mask: '••7710', kind: 'credit', balanceMinor: P(-28120) } }),
    prisma.account.create({ data: { userId: uid, name: 'SBI Savings', mask: '••3388 · Emergency', kind: 'bank', balanceMinor: P(390000) } }),
    prisma.account.create({ data: { userId: uid, name: 'Cash', mask: 'Wallet', kind: 'cash', balanceMinor: P(2300) } }),
  ]);

  const rec = async (name: string, emoji: string, amount: number, day: number, method: string, kind: 'bill' | 'income' | 'transfer', reminder: boolean, categoryId: string, accountId: string, toAccountId?: string) =>
    prisma.recurring.create({ data: { userId: uid, name, emoji, amountMinor: P(amount), day, method, kind, reminder, categoryId, accountId, toAccountId } });
  const rRent = await rec('Rent', '🏠', 28000, 3, 'NEFT · HDFC', 'bill', true, cats.home, hdfc.id);
  await rec('Transfer to Savings', '🏦', 8000, 2, 'IMPS', 'transfer', false, cats.transfer, hdfc.id, sbi.id);
  const rAirtel = await rec('Airtel Fiber', '📶', 1199, 8, 'auto-debit', 'bill', false, cats.subs, hdfc.id);
  await rec('Electricity — BESCOM', '💡', 2140, 19, 'varies · last ₹1,980', 'bill', true, cats.home, hdfc.id);
  await rec('Phone — Jio', '📱', 299, 15, 'UPI', 'bill', false, cats.subs, hdfc.id);
  await rec('Salary — Beyond Labs', '💼', 125000, 1, 'NEFT', 'income', false, cats.salary, hdfc.id);

  const sub = (name: string, amount: number, cycle: 'Monthly' | 'Yearly', next: Date, color: string, letter: string, lastUsed?: Date) =>
    prisma.subscription.create({ data: { userId: uid, name, amountMinor: P(amount), cycle, next, color, letter, lastUsed, categoryId: cats.subs, accountId: icici.id } });
  await sub('Netflix', 649, 'Monthly', d(26), '#E50914', 'N'); await sub('Spotify', 119, 'Monthly', d(3), '#1DB954', 'S'); await sub('iCloud+ 200GB', 219, 'Monthly', d(16), '#3B82F6', 'i');
  await sub('Gym — Cult.fit', 1500, 'Monthly', d(14), '#F97316', 'C'); await sub('Notion Plus', 830, 'Monthly', d(11), '#111827', 'N', d(-34)); await sub('Amazon Prime', 1499, 'Yearly', new Date(Date.UTC(2027, 0, 14)), '#0EA5E9', 'a');

  type T = [string, number, string, string, Date, string, Partial<{ kind: 'expense' | 'income' | 'transfer'; source: 'user' | 'ai' | 'recurring'; note: string; toAccountId: string; recurringId: string }>?];
  const txs: T[] = [
    ['Swiggy', 450, cats.food, hdfc.id, d(0), '13:12', { source: 'ai', note: 'UPI' }], ['Metro recharge', 500, cats.transport, hdfc.id, d(0), '08:40'],
    ['Blinkit', 1230, cats.groceries, icici.id, d(-1), '19:05'], ['Uber', 312, cats.transport, hdfc.id, d(-2), '21:10'], ['Decathlon', 2499, cats.shopping, icici.id, d(-3), '16:30'],
    ['Netflix', 649, cats.subs, icici.id, d(-4), '06:00', { source: 'recurring' }], ['Apollo Pharmacy', 780, cats.health, cash.id, d(-5), '11:20'], ['Zomato', 620, cats.food, hdfc.id, d(-6), '20:45'],
    ['BigBasket', 2140, cats.groceries, icici.id, d(-8), '10:00'], ['Amazon', 1801, cats.shopping, icici.id, d(-9), '14:00'], ['BookMyShow', 900, cats.fun, hdfc.id, d(-10), '18:30'],
    ['Rent', 28000, cats.home, hdfc.id, monthDay(3), '09:00', { source: 'recurring', recurringId: rRent.id, note: 'NEFT' }],
    ['Transfer to Savings', 8000, cats.transfer, hdfc.id, monthDay(2), '09:05', { kind: 'transfer', toAccountId: sbi.id, note: 'IMPS' }],
    ['Salary — Beyond Labs', 125000, cats.salary, hdfc.id, monthDay(1), '00:10', { kind: 'income', note: 'NEFT' }],
    ['Airtel Fiber', 1199, cats.subs, hdfc.id, monthDay(8), '06:00', { source: 'recurring', recurringId: rAirtel.id }], ['Cult.fit', 1500, cats.health, icici.id, monthDay(1), '06:00', { source: 'recurring' }],
    ['Cafe Coffee Day', 380, cats.food, cash.id, d(-12), '10:15'], ['Ola', 260, cats.transport, hdfc.id, d(-13), '09:30'], ['Udemy', 499, cats.learning, icici.id, d(-11), '22:00'],
    ['Apollo Pharmacy', 780, cats.health, cash.id, d(-35), '11:20'], ['Apollo Pharmacy', 780, cats.health, cash.id, d(-65), '11:20'],
    ['Salary — Beyond Labs', 125000, cats.salary, hdfc.id, d(-40), '00:10', { kind: 'income' }], ['Rent', 28000, cats.home, hdfc.id, d(-38), '09:00', { source: 'recurring', recurringId: rRent.id }],
  ];
  for (const [merchant, amount, categoryId, accountId, date, time, o = {}] of txs) {
    if (date > today) continue;
    await prisma.transaction.create({ data: { userId: uid, merchant, amountMinor: P(amount), categoryId, accountId, date, time, kind: o.kind ?? 'expense', source: o.source ?? 'user', note: o.note, toAccountId: o.toAccountId, recurringId: o.recurringId } });
  }
  /* ---------- goals (created first so habits/projects/tasks can point at them) ---------- */
  const ms = (title: string, o: Partial<{ target: number; targetDate: string; reachedOn: string; notes: string }> = {}, order = 0) => ({ title, target: o.target ?? null, targetDate: o.targetDate ? new Date(o.targetDate) : null, reachedOn: o.reachedOn ? new Date(o.reachedOn) : null, notes: o.notes ?? null, order });
  const goal = (name: string, o: { type: 'numeric' | 'milestone' | 'habit' | 'date'; term: string; due: string; startedOn: string; current: number; target: number; unit?: string; formula?: string; nextAction: string; reviewEvery: string; reviewOn: Date; milestones: ReturnType<typeof ms>[]; progress: { date: string; value: number }[] }) =>
    prisma.goal.create({ data: { userId: uid, name, type: o.type, term: o.term, due: new Date(o.due), startedOn: new Date(o.startedOn), current: o.current, target: o.target, unit: o.unit ?? '', formula: o.formula, nextAction: o.nextAction, reviewEvery: o.reviewEvery, reviewOn: o.reviewOn, milestones: { create: o.milestones.map((m, i) => ({ ...m, order: i })) }, progress: { create: o.progress.map((p) => ({ date: new Date(p.date), value: p.value })) } } });
  const gFund = await goal('Build ₹1,00,000 emergency fund', { type: 'numeric', term: 'Medium-term', due: '2027-03-31', startedOn: '2026-01-05', current: 62000, target: 100000, unit: '₹', formula: 'SBI Savings balance tagged “Emergency” (₹54,000) + recurring transfers scheduled (₹8,000)', nextAction: 'Transfer ₹8,000 on 1 Oct', reviewEvery: 'Monthly', reviewOn: d(14),
    milestones: [ms('₹25,000', { target: 25000, reachedOn: '2026-03-20' }), ms('₹50,000', { target: 50000, reachedOn: '2026-07-08' }), ms('₹75,000', { target: 75000, targetDate: '2026-11-30', notes: 'If the bonus lands, this milestone can close a month early.' }), ms('₹1,00,000', { target: 100000, targetDate: '2027-03-31' })],
    progress: [8, 15, 25, 31, 38, 44, 50, 54, 62].map((v, i) => ({ date: `2026-${String(i + 1).padStart(2, '0')}-01`, value: v * 1000 })) });
  const g10k = await goal('Run a 10K', { type: 'milestone', term: 'Short-term', due: '2026-12-15', startedOn: '2026-06-01', current: 2, target: 5, nextAction: '5K run on Saturday', reviewEvery: 'Monthly', reviewOn: d(-1),
    milestones: [ms('Walk 30 min daily for 4 weeks', { reachedOn: '2026-07-01' }), ms('Run 2K without stopping', { reachedOn: '2026-08-10' }), ms('Run 5K', { targetDate: iso(d(3)) }), ms('Run 8K', { targetDate: '2026-11-15' }), ms('Race day 10K', { targetDate: '2026-12-15' })],
    progress: [{ date: '2026-07-01', value: 1 }, { date: '2026-08-10', value: 2 }] });
  const gBeta = await goal('Ship Mitra AI beta', { type: 'milestone', term: 'Short-term', due: '2026-11-30', startedOn: '2026-05-01', current: 3, target: 6, nextAction: 'Finish auth refactor', reviewEvery: 'Weekly', reviewOn: d(5),
    milestones: [ms('Design system', { reachedOn: '2026-06-15' }), ms('Data model + API', { reachedOn: '2026-07-30' }), ms('Today + Tasks', { reachedOn: '2026-09-01' }), ms('Auth + onboarding', { targetDate: '2026-10-05' }), ms('Money module', { targetDate: '2026-11-01' }), ms('Beta invite', { targetDate: '2026-11-30' })],
    progress: [{ date: '2026-06-15', value: 1 }, { date: '2026-07-30', value: 2 }, { date: '2026-09-01', value: 3 }] });
  const gBooks = await goal('Read 24 books this year', { type: 'numeric', term: 'Long-term', due: '2026-12-31', startedOn: '2026-01-01', current: 14, target: 24, nextAction: 'Finish Atomic Habits', reviewEvery: 'Monthly', reviewOn: d(20),
    milestones: [ms('6 books', { target: 6, reachedOn: '2026-03-30' }), ms('12 books', { target: 12, reachedOn: '2026-07-20' }), ms('18 books', { target: 18, targetDate: '2026-10-15' }), ms('24 books', { target: 24, targetDate: '2026-12-31' })],
    progress: [2, 4, 6, 8, 9, 11, 12, 13, 14].map((v, i) => ({ date: `2026-${String(i + 1).padStart(2, '0')}-28`, value: v })) });
  await goal('Learn Spanish to B1', { type: 'habit', term: 'Long-term', due: '2027-06-30', startedOn: '2026-04-01', current: 22, target: 100, nextAction: 'Complete Unit 4', reviewEvery: 'Monthly', reviewOn: d(-6),
    milestones: [ms('A1 — units 1–6', { reachedOn: '2026-06-30' }), ms('A2 — units 7–14', { targetDate: '2026-12-31' }), ms('B1 — conversation practice', { targetDate: '2027-06-30' })],
    progress: [{ date: '2026-05-01', value: 8 }, { date: '2026-06-30', value: 18 }, { date: '2026-08-01', value: 22 }] });

  /* ---------- habits with 12 weeks of deterministic logs ---------- */
  const seeded = (seed: number) => () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
  const dowMon = (x: Date) => (x.getUTCDay() + 6) % 7;
  const mkLogs = (seed: number, rate: number, value: () => number, days: boolean[], lastDone: number, todayVal?: number) => { const r = seeded(seed); const out = new Map<string, number>(); for (let i = 84; i >= 1; i--) { const day = d(-i); if (!days[dowMon(day)]) continue; if (r() < rate) out.set(iso(day), value()); } for (let i = 1; i <= lastDone; i++) out.set(iso(d(-i)), value()); if (todayVal) out.set(iso(d(0)), todayVal); return [...out].map(([date, v]) => ({ date: new Date(date), value: v })); };
  const vr = seeded(99); const ALL = [true, true, true, true, true, true, true], WEEKDAYS = [true, true, true, true, true, false, false];
  const habit = (name: string, o: { type: 'binary' | 'count' | 'duration' | 'quantity'; target: number; unit: string; icon: string; timeOfDay: 'morning' | 'anytime' | 'evening'; days: boolean[]; reminder?: string; goalId?: string; logs: { date: Date; value: number }[] }) =>
    prisma.habit.create({ data: { userId: uid, name, type: o.type, target: o.target, unit: o.unit, icon: o.icon, timeOfDay: o.timeOfDay, days: o.days, reminder: o.reminder, goalId: o.goalId, logs: { create: o.logs } } });
  await habit('Morning walk', { type: 'duration', target: 30, unit: 'min', icon: 'activity', timeOfDay: 'morning', days: ALL, reminder: '07:00', goalId: g10k.id, logs: mkLogs(11, 0.86, () => 25 + Math.round(vr() * 15), ALL, 12, 32) });
  await habit('Drink water', { type: 'count', target: 8, unit: 'glasses', icon: 'droplet', timeOfDay: 'anytime', days: ALL, logs: mkLogs(22, 0.71, () => 8, ALL, 4, 5) });
  const hRead = await habit('Read', { type: 'duration', target: 20, unit: 'min', icon: 'book', timeOfDay: 'anytime', days: ALL, goalId: gBooks.id, logs: mkLogs(33, 0.58, () => 20, ALL, 0) });
  await habit('Meditate', { type: 'duration', target: 10, unit: 'min', icon: 'sun', timeOfDay: 'morning', days: ALL, logs: mkLogs(44, 0.64, () => 10, ALL, 7, 10) });
  await habit('No sugar', { type: 'binary', target: 1, unit: '', icon: 'x', timeOfDay: 'anytime', days: WEEKDAYS, logs: mkLogs(55, 0.45, () => 1, WEEKDAYS, 2) });
  await habit('Sleep by 11 pm', { type: 'binary', target: 1, unit: '', icon: 'moon', timeOfDay: 'evening', days: ALL, reminder: '22:30', logs: mkLogs(66, 0.52, () => 1, ALL, 3) });

  /* ---------- tasks & projects ---------- */
  const proj = async (name: string, emoji: string, goalId?: string) => prisma.project.create({ data: { userId: uid, name, emoji, goalId } });
  const pMitra = await proj('Mitra AI', '🚀', gBeta.id); const pFree = await proj('Freelance', '💼', gFund.id); const pPers = await proj('Personal', '🙂'); const pHome = await proj('Home', '🏠'); const pLearn = await proj('Learning', '📚', gBooks.id); const pFam = await proj('Family', '👨‍👩‍👧');
  type TO = Partial<{ projectId: string; due: Date; time: string; pri: 'high' | 'med' | 'low'; estMin: number; goalId: string; habitId: string; notes: string; done: boolean; status: 'todo' | 'inprogress' | 'blocked' | 'done'; completedAt: Date; recurring: string; amount: number; parentId: string; source: 'user' | 'ai' }>;
  const task = (title: string, o: TO = {}) => { const { amount, done, ...rest } = o; return prisma.task.create({ data: { userId: uid, title, ...rest, done: done ?? o.status === 'done', status: o.status ?? (done ? 'done' : 'todo'), amountMinor: amount ? P(amount) : null } }); };
  const t1 = await task('Send Q3 invoice to Northwind Studio', { projectId: pFree.id, due: d(0), time: '17:00', pri: 'high', estMin: 30, goalId: gFund.id, notes: 'Rate ₹2,500/hr × 18h. Include the September retainer line. PO number from last email.' });
  await task('Export hours from time tracker', { parentId: t1.id, projectId: pFree.id, done: true, status: 'done', completedAt: d(-1) });
  await task('Fill invoice template', { parentId: t1.id, projectId: pFree.id, due: d(0), estMin: 15, status: 'inprogress' });
  await task('Email + mark as sent', { parentId: t1.id, projectId: pFree.id, due: d(0), pri: 'high' });
  await task('Review PR #214 — auth refactor', { projectId: pMitra.id, due: d(0), time: '15:00', pri: 'high', estMin: 45, status: 'inprogress' });
  await task('Book dentist appointment', { projectId: pPers.id, due: d(0), pri: 'med', estMin: 10 });
  await task('Call Amma', { projectId: pFam.id, due: d(0), time: '19:00', pri: 'low', source: 'ai' });
  await task('Read 20 pages — Atomic Habits', { projectId: pLearn.id, due: d(0), pri: 'low', estMin: 25, habitId: hRead.id });
  await task('Renew car insurance', { projectId: pPers.id, due: d(-2), pri: 'high' });
  const t4 = await task('Prepare slides for Friday demo', { projectId: pMitra.id, due: d(2), pri: 'med', estMin: 120 });
  await task('Outline', { parentId: t4.id, projectId: pMitra.id, done: true, status: 'done', completedAt: d(-2), estMin: 30 });
  await task('Build slides', { parentId: t4.id, projectId: pMitra.id, done: true, status: 'done', completedAt: d(-1), estMin: 60 });
  await task('Rehearse', { parentId: t4.id, projectId: pMitra.id, due: d(1), estMin: 30, status: 'inprogress' });
  await task('Collect feedback', { parentId: t4.id, projectId: pMitra.id, due: d(2) });
  await task('Send deck', { parentId: t4.id, projectId: pMitra.id, due: d(2), pri: 'med' });
  await task('Groceries: milk, eggs, spinach', { projectId: pHome.id, due: d(1) });
  await task('Pay electricity bill', { projectId: pHome.id, due: d(3), amount: 2140, recurring: 'Monthly' });
  await task('Write weekly review', { projectId: pPers.id, due: d(4), recurring: 'Weekly' });
  await task('Draft onboarding copy', { projectId: pMitra.id, due: d(5), estMin: 60 });
  await task('Quarterly goal review', { projectId: pPers.id, due: d(7), recurring: 'Quarterly' });
  await task('Dentist appointment', { projectId: pPers.id, due: d(8), time: '10:30' });
  for (const t of ['Idea: habit streak recovery UX', 'Buy birthday gift for Priya', 'Check flight prices for Goa trip', 'Reply to accountant about GST filing']) await task(t);
  await task('Morning planning', { projectId: pPers.id, done: true, status: 'done', completedAt: d(0), estMin: 10, due: d(0) });

  /* ---------- notes & journal ---------- */
  const note = (title: string, body: string, folder: string, tags: string[], ageDays: number, o: Partial<{ journalDate: Date; ai: boolean; mood: number }> = {}) =>
    prisma.note.create({ data: { userId: uid, title, body, folder, tags, journalDate: o.journalDate, ai: o.ai ?? false, mood: o.mood, createdAt: d(-ageDays - 2), updatedAt: d(-ageDays) } });
  await note('Beta launch checklist', 'Target: [[Ship Mitra AI beta]] · Nov 2026\n\n## Must ship\n- [x] Auth refactor (PR #214)\n- [ ] Onboarding copy — see [[Draft onboarding copy]]\n- [ ] Analytics events for Quick Add → Task → Today\n- [ ] PWA install prompt + offline shell\n- [ ] Empty states for Money\n\n## Nice to have\n- Receipt upload\n- Weekly review email', 'Work', ['mitra-ai', 'launch'], 0);
  await note('Idea: streak recovery UX', 'Missed days should not reset to zero visually. Show "recovering" state and a soft nudge instead of a broken chain. Compare with Duolingo streak freeze.', 'Ideas', ['ideas', 'habits'], 1);
  await note('Book notes — Atomic Habits', 'Make it obvious, attractive, easy, satisfying. Habit stacking: after [current habit], I will [new habit]. Environment design beats motivation.', 'Learning', ['books', 'learning'], 3);
  await note('Journal — ' + iso(d(-1)).slice(5), 'Slept late again. Good focus block in the morning though. Need to move the run earlier; evenings keep getting eaten by calls.', 'Journal', ['journal'], 1, { journalDate: d(-1), ai: true, mood: 2 });
  await note('Goa trip plan', "Dates: 10–14 Dec. Budget ₹25,000. Flights ~₹9k return, stay ~₹10k. Check Priya's leave. Ideas: Palolem, Fontainhas walk, Saturday night market.", 'Personal', ['travel', 'money'], 2);
  await note('Meeting notes — design review', 'Keep Today under 5 sections by default. Money card only shows today spend + budget bar. Move AI briefing above the fold on desktop only.\n\nMentions [[Beta launch checklist]].', 'Work', ['mitra-ai', 'meeting'], 5);
  await note('Journal — ' + iso(d(-2)).slice(5), 'Insurance renewal slipped. Long run felt great — 6K at an easy pace. Dinner ran until 11:15 so sleep suffered.', 'Journal', ['journal'], 2, { journalDate: d(-2), mood: 3 });
  await note('Emergency fund plan', 'AI-generated plan, edited. 4 milestones · ₹8,000 monthly transfer on the 2nd · revisit after the festival bonus.', 'Personal', ['money', 'goals'], 6, { ai: true });

  /* ---------- wellness: 30 days of check-ins ---------- */
  const wr = seeded(7); const wrows = [];
  for (let i = 30; i >= 1; i--) { const sleep = 5.9 + wr() * 2; const endMin = (23 * 60 + 30 + Math.round(sleep * 60)) % 1440; wrows.push({ userId: uid, date: d(-i), mood: 2 + Math.round(wr() * 2), sleepStart: '23:30', sleepEnd: `${String(Math.floor(endMin / 60)).padStart(2, '0')}:${String(endMin % 60).padStart(2, '0')}`, steps: 5000 + Math.round(wr() * 6000), water: 4 + Math.round(wr() * 4), weight: +(74.1 - (30 - i) * 0.057).toFixed(1), savedAt: d(-i) }); }
  wrows.push({ userId: uid, date: d(0), mood: 3, sleepStart: '23:40', sleepEnd: '06:20', steps: 6210, water: 5, weight: 72.4, savedAt: d(0) });
  await prisma.wellnessEntry.createMany({ data: wrows });
  await prisma.wellnessSettings.create({ data: { userId: uid, height: 175, sleepTarget: 450, stepsTarget: 8000, waterTarget: 8 } });

  await prisma.activity.createMany({ data: [
    { userId: uid, module: 'money', text: '₹450 Swiggy · captured by AI', icon: 'wallet', tone: 'accent' },
    { userId: uid, module: 'money', text: '₹500 Metro recharge', icon: 'wallet' },
  ] });
  console.log(`Seeded Money + Tasks + Habits + Goals + Notes + Wellness for ${email} (${iso(today)})${email === 'aarav@example.com' ? ' · password123' : ''}`);
}

main().catch((e) => { console.error(e); process.exit(1); }).finally(() => prisma.$disconnect());
