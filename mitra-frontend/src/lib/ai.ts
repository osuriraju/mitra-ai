/* Local "assistant" — deterministic natural-language handling so the frontend feels real before the backend/Claude exists.
   Returns a reply plus an optional reviewable proposal; nothing is applied until the user confirms. */
import { addDays, fmtDay, fmtTime, inr, todayISO, monthKey } from '@/lib/dates';
import type { State } from '@/store';
import { goalProgress, goalStatus, STATUS_LABEL, spentByCategory, thisMonth, openToday, isOverdue, habitsWeekRate, doneToday } from '@/store/selectors';

export type Proposal =
  | { kind: 'expense'; title: string; sub: string; amount: number; categoryId: string; merchant: string; accountId: string }
  | { kind: 'task'; title: string; sub: string; taskTitle: string; due?: string; time?: string; projectId?: string }
  | { kind: 'plan'; title: string; sub: string; goalName: string; target: number; months: number; monthly: number }
  | { kind: 'reschedule'; title: string; sub: string; moves: { id: string; title: string; to: string; label: string }[] };
export type AiReply = { text: string; proposal?: Proposal; chart?: number[] };

/** Keyword → category *slug* (system categories carry a stable slug; ids differ per user). */
const CAT_WORDS: [RegExp, string][] = [[/dinner|lunch|breakfast|food|swiggy|zomato|restaurant|coffee|cafe|snack|pizza|biryani|meal/i, 'food'], [/metro|uber|ola|auto|cab|taxi|bus|fuel|petrol|train|flight/i, 'transport'], [/grocer|blinkit|bigbasket|zepto|vegetables|milk/i, 'groceries'], [/shop|amazon|flipkart|clothes|shoes|myntra|decathlon|gift/i, 'shopping'], [/pharma|medicine|doctor|gym|apollo|health/i, 'health'], [/movie|netflix|game|concert|fun|bookmyshow/i, 'fun'], [/rent|electric|water bill|maintenance/i, 'home'], [/book|course|udemy|class/i, 'learning'], [/subscription|spotify|icloud/i, 'subs']];
const parseAmount = (t: string) => { const m = t.match(/(?:₹|rs\.?|inr)?\s*([\d,]+(?:\.\d+)?)\s*(k|lakh|l|thousand)?/i); if (!m) return 0; let n = +m[1].replace(/,/g, ''); const u = (m[2] || '').toLowerCase(); if (u === 'k' || u === 'thousand') n *= 1000; if (u === 'lakh' || u === 'l') n *= 100000; return n; };
const parseWhen = (t: string) => { const l = t.toLowerCase(); let due: string | undefined; if (/\btomorrow\b/.test(l)) due = addDays(todayISO(), 1); else if (/\btoday\b|\btonight\b/.test(l)) due = todayISO(); else if (/next week/.test(l)) due = addDays(todayISO(), 7); else { const d = l.match(/\b(mon|tue|wed|thu|fri|sat|sun)/); if (d) { const idx = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'].indexOf(d[1]); const cur = new Date().getDay(); due = addDays(todayISO(), ((idx - cur + 7) % 7) || 7); } } const tm = l.match(/\b(?:at\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b/); let time: string | undefined; if (tm && (tm[3] || /\bat\b/.test(l))) { let h = +tm[1]; const mm = tm[2] || '00'; if (tm[3] === 'pm' && h < 12) h += 12; if (tm[3] === 'am' && h === 12) h = 0; if (!tm[3] && h < 8) h += 12; time = `${String(h).padStart(2, '0')}:${mm}`; } return { due, time }; };
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function ask(input: string, s: State): AiReply {
  const t = input.trim(); const l = t.toLowerCase(); const cats = s.categories;
  // capture: expense
  if (/^(spent|paid|bought|spend)\b/.test(l) || (/\b(₹|rs)\s*\d/.test(l) && /\bon\b|\bfor\b|\bat\b/.test(l))) {
    const amount = parseAmount(t); const on = t.match(/\b(?:on|for|at)\s+(.+?)(?:\s+(?:today|yesterday|tomorrow|via|using|from|with)\b|$)/i)?.[1] || 'expense';
    // CAT_WORDS yields a slug; resolve it to this user's category id (system categories carry slugs, ids differ per user).
    const bySlug = (slug?: string) => (slug ? cats.find((c) => c.slug === slug) : undefined);
    const cat = bySlug(CAT_WORDS.find(([re]) => re.test(on))?.[1]) || bySlug(CAT_WORDS.find(([re]) => re.test(t))?.[1]) || cats.find((c) => on.toLowerCase().includes(c.name.toLowerCase().split(' ')[0])) || bySlug('shopping') || cats.find((c) => c.kind === 'expense') || cats[0];
    if (!cat) return { text: 'I can’t log expenses until your categories are set up — head to Money first.' };
    const categoryId = cat.id; const prev = s.transactions.filter((x) => x.categoryId === categoryId && x.merchant).map((x) => x.merchant); const atM = t.match(/\b(?:at|from|@)\s+([A-Z][\w&'.-]*(?:\s+[A-Z][\w&'.-]*)*)/); const brand = on.match(/swiggy|zomato|uber|ola|amazon|blinkit|netflix|starbucks|flipkart|myntra|zepto|bigbasket/i);
    const generic = CAT_WORDS.some(([re]) => re.test(on.split(/\s+at\s+/i)[0]));
    const merchant = atM ? atM[1].trim() : brand ? cap(brand[0].toLowerCase()) : generic && prev[0] ? prev[0] : cap(on.split(/\s+at\s+/i)[0].replace(/^(a|an|the)\s+/i, ''));
    const acc = s.accounts.find((a) => a.id === s.transactions.find((x) => x.categoryId === categoryId)?.accountId) || s.accounts[0];
    if (!amount) return { text: `How much was it? Say something like “Spent ₹450 on ${on}”.` };
    if (!acc) return { text: 'Add an account in Money first, then I can log this expense.' };
    const accountId = acc.id;
    return { text: 'Got it — here’s what I’ll save:', proposal: { kind: 'expense', title: `${merchant} · ${inr(amount)}`, sub: `${cat.emoji} ${cat.name} · Today ${fmtTime(new Date().toTimeString().slice(0, 5))} · ${acc.name} ${acc.mask}${prev.length ? ` · merchant guessed from your last ${Math.min(4, prev.length)} ${cat.name.toLowerCase()} entries` : ''}`, amount, categoryId, merchant, accountId } };
  }
  // capture: task / reminder
  if (/^(remind me|add (a )?task|todo|task:|call|book|buy|email|send|pay|schedule|plan to|need to)\b/.test(l)) {
    const { due, time } = parseWhen(t); const title = cap(t.replace(/^(remind me to|remind me|add a task to|add task to|add task|todo|task:)\s*/i, '').replace(/\b(tomorrow|today|tonight|next week|at\s*\d{1,2}(:\d{2})?\s*(am|pm)?|on (mon|tue|wed|thu|fri|sat|sun)\w*)\b/gi, '').replace(/\s+/g, ' ').trim());
    const byName = (n: string) => s.projects.find((x) => x.name.toLowerCase() === n)?.id; const projectId = /amma|mom|dad|priya|family/i.test(t) ? byName('family') : /pr|deploy|bug|design|mitra/i.test(t) ? byName('mitra ai') : /invoice|client|freelance/i.test(t) ? byName('freelance') : undefined; const p = s.projects.find((x) => x.id === projectId);
    return { text: 'Sure — I’ll add this task:', proposal: { kind: 'task', title, sub: `${due ? fmtDay(due) : 'No date'}${time ? ' · ' + fmtTime(time) : ''}${p ? ' · ' + p.name : ' · Inbox'}`, taskTitle: title, due, time, projectId } };
  }
  // plan a goal
  if (/plan|goal|save\b.*(lakh|k|₹)/.test(l) && /sav|fund|run|learn|read/.test(l)) {
    const target = parseAmount(t) || 100000; const months = +(t.match(/(\d+)\s*months?/)?.[1] || 6); const monthly = Math.ceil(target / months / 500) * 500; const goalName = /sav|fund/.test(l) ? `Save ${inr(target)} in ${months} months` : cap(t.replace(/^plan (my goal of |to |my )?/i, ''));
    return { text: `Here’s a plan for **${goalName}**: ${inr(monthly)} a month for ${months} months, with a milestone every ${Math.max(1, Math.round(months / 4))} month${Math.round(months / 4) > 1 ? 's' : ''}. Your current surplus is about ${inr(Math.max(0, (s.transactions.filter((x) => x.kind === 'income' && monthKey(x.date) === monthKey(todayISO())).reduce((a, x) => a + x.amount, 0) - thisMonth(s.transactions).filter((x) => x.kind === 'expense').reduce((a, x) => a + x.amount, 0)) / 1000) * 1000)}/month, so this is ${monthly < 20000 ? 'realistic' : 'ambitious'}.`, proposal: { kind: 'plan', title: goalName, sub: `4 milestones · ${inr(monthly)}/month · recurring transfer task on the 2nd`, goalName, target, months, monthly } };
  }
  // overload → reschedule
  if (/too much|overwhelm|too many|can'?t do all|help me prioriti/.test(l)) {
    const open = openToday(s.tasks).concat(s.tasks.filter(isOverdue)); const keep = open.filter((x) => x.pri === 'high' || x.time).slice(0, 2); const rest = open.filter((x) => !keep.includes(x));
    const moves = rest.map((x, i) => ({ id: x.id, title: x.title, to: addDays(todayISO(), i < 2 ? 1 : 3), label: i < 2 ? fmtDay(addDays(todayISO(), 1)) : fmtDay(addDays(todayISO(), 3), { relative: false }) }));
    const mins = open.reduce((a, x) => a + (x.estMin || 0), 0);
    if (!rest.length) return { text: `You have ${open.length} open task${open.length === 1 ? '' : 's'} today (${Math.round(mins / 60 * 10) / 10}h). They all look time-bound, so I’d keep them — try Focus mode and start with the highest priority.` };
    return { text: `You have **${open.length} open tasks (${Math.floor(mins / 60)}h ${mins % 60}m)** today. ${keep.length} ${keep.length === 1 ? 'is' : 'are'} time-bound; ${rest.length} can move without breaking anything.`, proposal: { kind: 'reschedule', title: `Keep today: ${keep.map((x) => x.title).join(' · ') || '—'}`, sub: moves.map((m) => `${m.title} → ${m.label}`).join(' · '), moves } };
  }
  // productivity summary
  if (/productive|how did (my|the) week|week go|summary|review my week|what did i (do|accomplish)/.test(l)) {
    const days = Array.from({ length: 7 }, (_, i) => addDays(todayISO(), -(6 - i))); const perDay = days.map((d) => s.tasks.filter((x) => x.completedAt === d).length); const done = perDay.reduce((a, b) => a + b, 0); const planned = done + openToday(s.tasks).length + s.tasks.filter(isOverdue).length; const focus = s.activity.filter((a) => a.text.startsWith('Focus')).length;
    return { text: `**${fmtDay(days[0], { relative: false })} – ${fmtDay(days[6], { relative: false })}:** you completed **${done} of ${planned}** planned tasks (${planned ? Math.round((done / planned) * 100) : 0}%)${focus ? ` and ran ${focus} focus session${focus > 1 ? 's' : ''}` : ''}. Habits were ${habitsWeekRate(s.habits)}% consistent${s.habits.filter((h) => (h.logs[todayISO()] || 0) < h.target).length ? `; “${s.habits.find((h) => (h.logs[todayISO()] || 0) < h.target)?.name}” is still open today` : ''}.`, chart: perDay };
  }
  // spend query
  if (/spend|spent|how much|expense/.test(l)) {
    const catSlug = CAT_WORDS.find(([re]) => re.test(l))?.[1]; const catId = catSlug ? cats.find((c) => c.slug === catSlug)?.id : undefined; const last = /last month/.test(l); const key = last ? monthKey(addDays(todayISO().slice(0, 8) + '01', -1)) : monthKey(todayISO()); const tx = thisMonth(s.transactions, key); const by = spentByCategory(tx);
    if (catId) { const c = cats.find((x) => x.id === catId)!; const v = by[catId] || 0; return { text: `You spent **${inr(v)}** on ${c.name.toLowerCase()} ${last ? 'last month' : 'this month'}${c.budget ? ` — ${Math.round((v / c.budget) * 100)}% of the ${inr(c.budget)} budget` : ''}. ${tx.filter((x) => x.categoryId === catId).length} transactions${tx.filter((x) => x.categoryId === catId)[0] ? `, largest ${tx.filter((x) => x.categoryId === catId).sort((a, b) => b.amount - a.amount)[0].merchant} ${inr(tx.filter((x) => x.categoryId === catId).sort((a, b) => b.amount - a.amount)[0].amount)}` : ''}.`, chart: Object.values(by).slice(0, 7) }; }
    const total = Object.values(by).reduce((a, b) => a + b, 0); const top = Object.entries(by).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([id, v]) => `${cats.find((c) => c.id === id)?.name} ${inr(v)}`).join(' · ');
    return { text: `Total spend ${last ? 'last month' : 'this month'}: **${inr(total)}**. Top categories: ${top}.`, chart: Object.values(by).slice(0, 7) };
  }
  // goals
  if (/goal/.test(l)) {
    const gs = s.goals.filter((g) => g.status === 'active').map((g) => ({ g, st: goalStatus(g, s.habits), p: goalProgress(g, s.habits) })); const bad = gs.filter((x) => x.st !== 'on-track');
    return { text: bad.length ? `${bad.length} goal${bad.length > 1 ? 's need' : ' needs'} attention: ${bad.map((x) => `**${x.g.name}** (${x.p}%, ${STATUS_LABEL[x.st].toLowerCase()})`).join(', ')}. ${gs.length - bad.length} on track. Want me to open the review?` : `All ${gs.length} goals are on track. Nice.` };
  }
  if (/habit|streak/.test(l)) { const best = [...s.habits].sort((a, b) => Object.keys(b.logs).length - Object.keys(a.logs).length)[0]; return { text: `This week your habits are ${habitsWeekRate(s.habits)}% consistent. Most consistent: **${best?.name}**. Today ${s.habits.filter((h) => (h.logs[todayISO()] || 0) >= h.target).length} of ${s.habits.length} are done.` }; }
  if (/today|what('s| is) (next|left)|agenda/.test(l)) { const open = openToday(s.tasks); return { text: open.length ? `Left for today: ${open.map((x) => `**${x.title}**${x.time ? ` (${fmtTime(x.time)})` : ''}`).join(', ')}. ${doneToday(s.tasks).length} done so far.` : 'Nothing left for today — you’re clear.' }; }
  if (/hello|hi\b|hey/.test(l)) return { text: 'Hi Aarav. Try “Spent ₹450 on dinner”, “Remind me to call John tomorrow at 6”, “How productive was I this week?” or “Plan my goal of saving ₹1 lakh in six months”.' };
  return { text: `I can capture expenses and tasks, answer questions about your money, tasks, habits and goals, and propose plans. Try “Spent ₹450 on dinner” or “What did I spend on food?”.` };
}
