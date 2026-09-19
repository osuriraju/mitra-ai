'use client';
/* ---------- TODAY: dashboard (live from store), daily detail, daily review ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState, type ReactNode } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Bar, Btn, Card, Chip, Empty, Icon, IconBox, Kpi, List, MOODS, MoodRow, Ring, Section } from '@/components/ui';
import { DateInput, TextArea } from '@/components/ui/controls';
import { addDays, diffDays, dowMon, fmtDay, fmtLong, inr, inrShort, minsToHm, nowTime, todayISO, daysInMonth, MON } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import { TxForm } from '@/screens/money';
import { STATUS_LABEL, STATUS_TONE, doneMin, doneToday, goalProgress, goalStatus, habitsDoneToday, habitsDueToday, habitsWeekRate, hm, isOverdue, openToday, plannedMin, priorityTasks, sleepMinutes, spent, spentByCategory, spentToday, thisMonth, totalBudget, bmi } from '@/store/selectors';
import { HRow } from './habits';
import { TRow, TaskForm } from './tasks';

const greet = () => { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'; };

/* Shared Today panels (also used by overlays) */
export function TodayPriority() {
  const s = useStore(); const pr = priorityTasks(s.tasks); const open = openToday(s.tasks).length + s.tasks.filter(isOverdue).length; const done = doneToday(s.tasks).length; const over = s.tasks.filter(isOverdue).length;
  return <Card cls="pad-0">{pr.length === 0 ? <Empty ic="check-square" title="Nothing urgent" p="No overdue or due-today tasks. Pull one from Upcoming or enjoy the calm." action={<Btn label="Plan the day" kind="outline" cls="sm" goto="tasks-upcoming" />} /> : <List>{pr.map((t) => <TRow key={t.id} t={t} />)}</List>}<div className="row between" style={{ padding: '8px 14px 10px' }}><Link className="link" href={href('tasks-today')}>All {open + done} tasks for today</Link><span className="xs muted">{done} done{over ? ` · ${over} overdue` : ''}</span></div></Card>;
}
export function TodayHabits() { const s = useStore(); const due = habitsDueToday(s.habits); return <Card cls="pad-0">{due.length ? <List>{due.map((h) => <HRow key={h.id} h={h} />)}</List> : <Empty ic="repeat" title="No habits today" p="" action={<Btn label="Add a habit" kind="outline" cls="sm" goto="habit-edit" />} />}</Card>; }
export function TodayWellness() {
  const s = useStore(); const e = s.wellness[todayISO()] || {}; const ws = s.wellnessSettings; const sleep = sleepMinutes(e.sleepStart, e.sleepEnd); const b = bmi(e.weight, ws.height);
  const stats: [string, string, string, string][] = [['moon', 'Sleep', sleep ? hm(sleep) : '—', sleep && sleep < ws.sleepTarget ? 'info' : ''], ['activity', 'Steps', e.steps ? e.steps.toLocaleString('en-IN') : '—', ''], ['droplet', 'Water', `${e.water || 0} / ${ws.waterTarget}`, (e.water || 0) < ws.waterTarget ? 'info' : 'success'], ['scale', 'BMI', b ? String(b) : '—', '']];
  return (
    <Card>
      <div className="field"><div className="lbl">How are you feeling today?</div><MoodRow on={e.mood ?? -1} onChange={(m) => { s.saveWellness(todayISO(), { mood: m, savedAt: todayISO() }); s.toast(`Mood logged ${MOODS[m]}`); }} /></div>
      <div className="row mt" style={{ justifyContent: 'space-between', gap: 6 }}>{stats.map(([ic, l, v, t]) => <div key={l} className="col" style={{ gap: 2, alignItems: 'center', textAlign: 'center', flex: 1 }}><IconBox ic={ic} tone={t} cls="sm" /><div className="num b" style={{ fontSize: 14 }}>{v}</div><div className="xs muted">{l}</div></div>)}</div>
      <div className="row between mt-s"><div className="row" style={{ gap: 6 }}><Btn label="+1 glass" ic="droplet" kind="outline" cls="sm" onClick={() => { const w = (e.water || 0) + 1; s.saveWellness(todayISO(), { water: w }); const hab = s.habits.find((h) => h.name.toLowerCase().includes('water')); if (hab) s.logHabit(hab.id, todayISO(), w); s.toast(`Water · ${w} of ${ws.waterTarget}`); }} /><Btn label="Add steps" ic="activity" kind="outline" cls="sm" goto="checkin" /></div><Link className="link sm" href={href('checkin')}>Full check-in</Link></div>
    </Card>
  );
}
export function TodayMoney() {
  const s = useStore(); const [add, setAdd] = useState(false); const tx = thisMonth(s.transactions); const sp = spent(tx); const budget = totalBudget(s); const by = spentByCategory(tx);
  const dim = daysInMonth(todayISO()); const day = +todayISO().slice(8); const left = dim - day + 1; // today counts
  const remaining = budget - sp; const safe = budget ? Math.max(0, remaining / left) : 0;
  const over = s.categories.filter((c) => c.budget && (by[c.id] || 0) > c.budget!).sort((a, b) => ((by[b.id] || 0) - b.budget!) - ((by[a.id] || 0) - a.budget!))[0];
  const near = !over ? s.categories.filter((c) => c.budget && (by[c.id] || 0) / c.budget! >= 0.9).sort((a, b) => (by[b.id] || 0) / b.budget! - (by[a.id] || 0) / a.budget!)[0] : undefined;
  // next bill / renewal — the same "Upcoming" logic as the Money screen, first item only
  const next = [...s.recurring.filter((r) => r.kind === 'bill' && !(r.lastPaidOn && r.lastPaidOn.slice(0, 7) === todayISO().slice(0, 7))).map((r) => ({ name: r.name, emoji: r.emoji, amount: r.amount, days: (r.day - day + dim) % dim })), ...s.subscriptions.map((x) => ({ name: x.name, emoji: '🔁', amount: x.amount, days: diffDays(x.next, todayISO()) }))].filter((u) => u.days >= 0).sort((a, b) => a.days - b.days)[0];
  return (
    <Card>
      <div className="row between"><div><Kpi l="Safe to spend" v={budget ? <>{inr(safe)} <span className="muted" style={{ fontSize: 14, fontWeight: 500 }}>/ day</span></> : '—'} d={budget ? `${left} day${left === 1 ? '' : 's'} left in ${MON[+todayISO().slice(5, 7) - 1]}` : 'Set budgets to see this'} /></div><div style={{ textAlign: 'right' }}><Kpi l={remaining >= 0 ? 'Left this month' : 'Over budget'} v={<span style={{ color: remaining < 0 ? 'var(--danger)' : undefined }}>{inr(Math.abs(remaining))}</span>} d={budget ? `of ${inrShort(budget)} budget` : ''} /></div></div>
      {next && <div className="row between sm mt-s"><span className="row" style={{ gap: 6 }}><span>{next.emoji}</span><span className="muted">Next up:</span><b>{next.name}</b></span><span className="num">{inr(next.amount)} <span className="muted">· {next.days === 0 ? 'today' : next.days === 1 ? 'tomorrow' : `in ${next.days} days`}</span></span></div>}
      {(over || near) && <div className="xs mt-s" style={{ color: over ? 'var(--danger)' : 'var(--warning)' }}>{over ? `${over.emoji} ${over.name} over by ${inr((by[over.id] || 0) - over.budget!)}` : `${near!.emoji} ${near!.name} at ${Math.round(((by[near!.id] || 0) / near!.budget!) * 100)}% of its budget`}</div>}
      <div className="row mt-s" style={{ gap: 6 }}><Btn label="Add expense" ic="plus" kind="soft" cls="sm" onClick={() => setAdd(true)} /><Btn label="Budgets" kind="ghost" cls="sm" goto="budgets" /></div>
      <TxForm open={add} onClose={() => setAdd(false)} />
    </Card>
  );
}
export function TodayGoals() {
  const s = useStore(); const gs = s.goals.filter((g) => g.status === 'active').map((g) => ({ g, st: goalStatus(g, s.habits), p: goalProgress(g, s.habits) })).sort((a, b) => (a.st === 'on-track' ? 1 : 0) - (b.st === 'on-track' ? 1 : 0)).slice(0, 2);
  return <Card cls="pad-0">{gs.length ? <List>{gs.map(({ g, st, p }) => <Link key={g.id} href={`/goals/${g.id}`} className="lrow"><Ring pct={p} size={40} stroke={4} label={p + '%'} tone={st === 'on-track' ? '' : st === 'slipping' ? 'danger' : 'warning'} /><div className="grow"><div className="t ellip">{g.name}</div><div className="s">Next: {g.nextAction || '—'}</div></div><Chip tone={STATUS_TONE[st]} cls="sm">{STATUS_LABEL[st]}</Chip></Link>)}</List> : <Empty ic="target" title="No active goals" p="" action={<Btn label="Set a goal" kind="outline" cls="sm" goto="goals" />} />}</Card>;
}

const SECTION_META: Record<string, { title: string; action: string; goto: Parameters<typeof href>[0] }> = { priorities: { title: 'Now · priorities', action: 'Plan', goto: 'tasks-today' }, activity: { title: 'Activity', action: 'Detail', goto: 'today-detail' }, habits: { title: 'Habits', action: 'All', goto: 'habits' }, checkin: { title: 'Check-in', action: 'Trends', goto: 'wellness-trends' }, money: { title: 'Money', action: 'Overview', goto: 'money' }, goals: { title: 'Goals', action: 'All', goto: 'goals' } };

export function TodayScreen({ overlay }: { overlay?: ReactNode }) {
  const s = useStore(); const router = useRouter(); const [form, setForm] = useState(false); const [refreshing, setRefreshing] = useState(false);
  const open = openToday(s.tasks), done = doneToday(s.tasks); const due = habitsDueToday(s.habits), hd = habitsDoneToday(s.habits); const e = s.wellness[todayISO()] || {}; const sleep = sleepMinutes(e.sleepStart, e.sleepEnd); const b = bmi(e.weight, s.wellnessSettings.height);
  const sp = spent(thisMonth(s.transactions)); const budget = totalBudget(s); const activity = s.activity.filter((a) => a.date === todayISO()).slice(0, 4);
  const sections = s.settings.todaySections.filter((k) => (k !== 'money' || s.settings.modules.money) && (k !== 'checkin' || s.settings.modules.wellness));
  const render = (k: string) => { const m = SECTION_META[k]; const body = k === 'priorities' ? <TodayPriority /> : k === 'activity' ? <Card><List>{activity.length ? activity.map((a) => <div key={a.id} className="row sm" style={{ padding: '7px 0' }}><span className="num muted" style={{ width: 64 }}>{a.when}</span><span className="grow">{a.text}</span></div>) : <div className="list-empty">Nothing logged yet today.</div>}</List></Card> : k === 'habits' ? <TodayHabits /> : k === 'checkin' ? <TodayWellness /> : k === 'money' ? <TodayMoney /> : <TodayGoals />; return <Section key={k} title={k === 'habits' ? `Habits · ${hd.length}/${due.length}` : m.title} action={m.action} goto={m.goto}>{body}</Section>; };
  const cols = [sections.filter((_, i) => i % 3 === 0), sections.filter((_, i) => i % 3 === 1), sections.filter((_, i) => i % 3 === 2)];
  return (
    <Screen nav="today" overlay={overlay}>
      <div className="row between top"><div><div className="eyebrow">{fmtLong(todayISO())} · {nowTime()}</div><h1 style={{ fontSize: 20 }}>{greet()}{s.user ? `, ${s.user.name.split(' ')[0]}` : ''}</h1></div><div className="row" style={{ gap: 6 }}><Btn ic="plus" kind="primary" cls="sm" onClick={() => setForm(true)} title="Add task" /><Btn ic="drag" kind="outline" cls="sm" onClick={() => router.push(href('settings') + '#today')} title="Customise sections" /><Btn ic="refresh" kind="outline" cls={`sm ${refreshing ? 'spin' : ''}`} title="Refresh" onClick={() => { setRefreshing(true); setTimeout(() => { setRefreshing(false); s.toast('Up to date'); }, 700); }} /></div></div>
      <div className="statstrip">
        <Link href={href('tasks-today')} className="st"><span className="l">Tasks</span><span className="v">{done.length}<span className="muted" style={{ fontSize: 14 }}>/{open.length + done.length}</span></span><span className="s">{s.tasks.filter(isOverdue).length ? `${s.tasks.filter(isOverdue).length} overdue · ` : ''}{minsToHm(plannedMin(s.tasks)) || '0m'} planned · {minsToHm(doneMin(s.tasks)) || '0m'} done</span></Link>
        <Link href={href('habits')} className="st"><span className="l">Habits</span><span className="v">{hd.length}<span className="muted" style={{ fontSize: 14 }}>/{due.length}</span></span><span className="s">{habitsWeekRate(s.habits)}% this week</span></Link>
        <Link href={href('money')} className="st"><span className="l">Spent today</span><span className="v">{inr(spentToday(s.transactions))}</span><span className="s">{budget ? Math.round((sp / budget) * 100) : 0}% of month budget used</span></Link>
        <Link href={href('checkin')} className="st"><span className="l">Sleep · BMI</span><span className="v">{sleep ? hm(sleep) : '—'}</span><span className="s">{b ? `BMI ${b} · ${b < 25 && b >= 18.5 ? 'normal' : 'check'}` : 'No check-in yet'}</span></Link>
      </div>
      <div className="cockpit">{cols.map((c, i) => <div key={i}>{c.map(render)}</div>)}</div>
      <TaskForm open={form} onClose={() => setForm(false)} defaults={{ due: todayISO() }} />
    </Screen>
  );
}

/* ---------- Daily detail ---------- */
export function TodayDetailScreen() {
  const s = useStore(); const [day, setDay] = useState(todayISO()); const weekStart = addDays(day, -dowMon(day)); const week = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const tl = s.activity.filter((a) => a.date === day); const done = s.tasks.filter((t) => t.completedAt === day); const remaining = s.tasks.filter((t) => t.due === day && !t.done); const e = s.wellness[day] || {}; const sleep = sleepMinutes(e.sleepStart, e.sleepEnd); const habitsDone = s.habits.filter((h) => (h.logs[day] || 0) >= h.target); const sp = spent(s.transactions.filter((t) => t.date === day)); const journal = s.notes.find((n) => n.journalDate === day);
  const focusMin = s.activity.filter((a) => a.date === day && a.text.startsWith('Focus')).reduce((a, x) => a + (+(x.text.match(/(\d+) min/)?.[1] || 0)), 0) + (day === todayISO() ? 115 : 0);
  return (
    <Screen nav="today" top={{ title: fmtDay(day, { relative: false }), back: 'today', sub: day === todayISO() ? 'Today' : fmtDay(day) }}>
      <div className="weekstrip">{week.map((d) => <button type="button" key={d} className={d === day ? 'on' : ''} onClick={() => setDay(d)} disabled={d > todayISO()} style={d > todayISO() ? { opacity: .4 } : undefined}>{fmtDay(d, { relative: false }).slice(0, 3)}<b>{+d.slice(8)}</b><i style={{ opacity: s.activity.some((a) => a.date === d) || s.tasks.some((t) => t.completedAt === d) ? 1 : 0 }} /></button>)}</div>
      <div className="stat-inline card tight keep"><div><span className="v">{done.length} / {done.length + remaining.length}</span><span className="l">Tasks done</span></div><div><span className="v">{habitsDone.length} / {s.habits.filter((h) => h.days[dowMon(day)]).length}</span><span className="l">Habits</span></div><div><span className="v">{inr(sp)}</span><span className="l">Spent</span></div><div><span className="v">{focusMin ? minsToHm(focusMin) : '—'}</span><span className="l">Focus time</span></div><div><span className="v">{sleep ? hm(sleep) : '—'}</span><span className="l">Sleep</span></div><div><span className="v">{e.steps ? e.steps.toLocaleString('en-IN') : '—'}</span><span className="l">Steps</span></div></div>
      <div className="split">
        <Section title="Activity timeline"><Card cls="pad-0"><List>{tl.length === 0 && done.length === 0 ? <div className="list-empty">{day === todayISO() ? 'Nothing recorded yet today.' : 'Nothing recorded on this day.'}</div> : [...tl.map((a) => ({ key: a.id, when: a.when, text: a.text, icon: a.icon, tone: a.tone })), ...done.filter((t) => !tl.some((a) => a.text.includes(t.title))).map((t) => ({ key: t.id, when: '', text: `${t.title} ✓`, icon: 'check-square', tone: 'success' }))].map((a) => <div key={a.key} className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic={a.icon} tone={a.tone} cls="sm" /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>{a.text}</div></div><div className="r"><div className="s num">{a.when}</div></div></div>)}</List></Card></Section>
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Section title={day === todayISO() ? 'Remaining today' : 'Was due'}><Card cls="pad-0">{remaining.length ? <List>{remaining.map((t) => <TRow key={t.id} t={t} />)}</List> : <div className="list-empty">Nothing left — all clear.</div>}</Card></Section>
          <Section title="Journal"><Card>{journal ? <><div className="sm" style={{ whiteSpace: 'pre-wrap' }}>{journal.body.slice(0, 220)}{journal.body.length > 220 ? '…' : ''}</div><div className="row mt-s"><Link className="link sm" href={`/notes/${journal.id}`}>Open entry</Link></div></> : <><div className="sm muted">No entry yet. Mitra can draft one from the day&apos;s timeline.</div><div className="row mt-s" style={{ gap: 6 }}><Btn label="Write" ic="edit" kind="outline" cls="sm" goto="journal" /><Btn label="Draft with AI" ic="sparkles" kind="soft" cls="sm" goto="journal" /></div></>}</Card></Section>
        </div>
      </div>
    </Screen>
  );
}

/* ---------- Daily review (4 steps) ---------- */
export function DailyReviewScreen() {
  const s = useStore(); const router = useRouter(); const [step, setStep] = useState(0); const [mood, setMood] = useState(s.wellness[todayISO()]?.mood ?? -1); const [line, setLine] = useState(''); const [decisions, setDecisions] = useState<Record<string, string>>({}); const [pick, setPick] = useState<string | null>(null);
  const done = doneToday(s.tasks); const unfinished = openToday(s.tasks).concat(s.tasks.filter(isOverdue)); const hd = habitsDoneToday(s.habits), due = habitsDueToday(s.habits); const e = s.wellness[todayISO()] || {};
  const tomorrow = s.tasks.filter((t) => t.due === addDays(todayISO(), 1) && !t.done);
  const decide = (id: string, d: string) => { setDecisions((x) => ({ ...x, [id]: d })); if (d === 'Tomorrow') s.updateTask(id, { due: addDays(todayISO(), 1) }); if (d === 'Drop') s.updateTask(id, { due: undefined, someday: true }); };
  const finish = () => { if (mood >= 0) s.saveWellness(todayISO(), { mood, savedAt: todayISO() }); if (line.trim()) { const j = s.notes.find((n) => n.journalDate === todayISO()); if (j) s.updateNote(j.id, { body: j.body + '\n\n' + line.trim() }); else s.addNote({ title: `Journal — ${fmtDay(todayISO(), { relative: false })}`, body: line.trim(), folder: 'Journal', tags: ['journal'], journalDate: todayISO(), mood: mood >= 0 ? mood : undefined }); } s.addActivity('Daily review completed', 'sun', 'accent'); s.toast('Review saved — see you tomorrow'); router.push(href('tasks-upcoming')); };
  return (
    <Screen nav="today" top={{ title: 'Daily review', back: 'today', sub: `${fmtDay(todayISO(), { relative: false })} · takes about 2 minutes` }} narrow>
      <div className="onb-dots">{[0, 1, 2, 3].map((i) => <i key={i} className={i === step ? 'on' : ''} onClick={() => setStep(i)} style={{ cursor: 'pointer' }} />)}</div>
      {step === 0 && <Card cls="keep"><div className="eyebrow">1 · What happened</div><h2 style={{ fontSize: 20, margin: '6px 0 10px' }}>You completed {done.length} of {done.length + unfinished.length} tasks and {hd.length} of {due.length} habits.</h2><div className="stat-inline"><div><span className="v">{done.length}/{done.length + unfinished.length}</span><span className="l">tasks</span></div><div><span className="v">{hd.length}/{due.length}</span><span className="l">habits</span></div><div><span className="v">{inr(spentToday(s.transactions))}</span><span className="l">spent</span></div><div><span className="v">{e.steps ? e.steps.toLocaleString('en-IN') : '—'}</span><span className="l">steps</span></div></div><div className="xs faint mt-s">Sources: tasks, habits, expenses, check-ins · today {s.settings.dayStart} – now</div></Card>}
      {step === 1 && <Section title={`2 · Unfinished — what should happen to these? (${unfinished.length})`}><Card cls="pad-0">{unfinished.length === 0 ? <div className="list-empty">Nothing unfinished. 🎉</div> : <List>{unfinished.map((t) => <div key={t.id} style={{ opacity: decisions[t.id] ? .6 : 1 }}><div className="task" style={{ cursor: 'default' }}><div className="cb" onClick={() => { s.toggleTask(t.id); setDecisions((x) => ({ ...x, [t.id]: 'Done' })); }}><Icon name="check" /></div><div className="grow"><div className="t">{t.title}</div><div className="meta">{t.due ? fmtDay(t.due) : ''}{decisions[t.id] ? ` · ${decisions[t.id]}` : ''}</div></div></div>{!decisions[t.id] && <div className="row" style={{ gap: 6, padding: '0 0 8px 34px', flexWrap: 'wrap' }}><Btn label="Tomorrow" kind="outline" cls="sm" onClick={() => decide(t.id, 'Tomorrow')} /><Btn label="Pick date" kind="outline" cls="sm" onClick={() => setPick(pick === t.id ? null : t.id)} /><Btn label="Drop" kind="ghost" cls="sm" onClick={() => decide(t.id, 'Drop')} />{pick === t.id && <DateInput value={t.due || todayISO()} onChange={(v) => { s.updateTask(t.id, { due: v }); setDecisions((x) => ({ ...x, [t.id]: fmtDay(v) })); setPick(null); }} />}</div>}</div>)}</List>}</Card></Section>}
      {step === 2 && <Section title="3 · Evening check-in"><Card><div className="field"><div className="lbl">How was your day?</div><MoodRow on={mood} onChange={setMood} /></div><div className="field mt-s"><TextArea label="One line for the journal" value={line} onChange={setLine} placeholder="Good focus block in the morning…" rows={3} /></div></Card></Section>}
      {step === 3 && <Section title="4 · Tomorrow's priorities"><Card cls="pad-0">{tomorrow.length ? <List>{tomorrow.map((t) => <TRow key={t.id} t={t} />)}</List> : <div className="list-empty">Nothing scheduled for tomorrow yet — the Upcoming page is next.</div>}</Card></Section>}
      <div className="row" style={{ gap: 8 }}><Btn label={step === 0 ? 'Later' : 'Back'} kind="outline" size="lg" onClick={() => step === 0 ? router.push(href('today')) : setStep(step - 1)} /><Btn label={step < 3 ? `Next: ${['unfinished', 'check-in', "tomorrow's priorities"][step]}` : 'Finish review'} kind="primary" size="lg" cls="grow" onClick={() => step < 3 ? setStep(step + 1) : finish()} /></div>
    </Screen>
  );
}
