'use client';
/* ---------- HABITS: list (check-off), detail, history (editable calendar), create/edit ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, type ReactNode } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Btn, Card, Empty, Heat, Icon, IconBox, Kpi, LineChart, List, Ring, Section, Segmented, ToggleRow, WeekDots } from '@/components/ui';
import { Confirm, Dropdown, Modal, Select, TextInput } from '@/components/ui/controls';
import { addDays, daysInMonth, dowMon, fmtDay, fmtMonth, fromISO, todayISO, toISO, MON } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import type { Habit, HabitType } from '@/store/types';
import { bestStreak, habitDone, habitDue, habitValue, habitsDoneToday, habitsDueToday, habitsWeekRate, rateDays, streak, weekDots, weekRate, goalProgress } from '@/store/selectors';

const ICONS_PICK = ['activity', 'droplet', 'book', 'sun', 'moon', 'x', 'heart', 'zap', 'star', 'timer', 'edit', 'smile', 'scale', 'bolt', 'map'];
const TYPE_L: Record<HabitType, string> = { binary: 'Yes / No', count: 'Count', duration: 'Duration', quantity: 'Quantity' };
const streakLabel = (h: Habit) => { const n = streak(h); return n > 0 ? `🔥 ${n}-day streak` : null; };

/** Habit row bound to the store — tap the check to log today */
export function HRow({ h }: { h: Habit }) {
  const s = useStore(); const done = habitDone(h); const v = habitValue(h); const measurable = h.type !== 'binary';
  const tap = () => { if (done) { s.logHabit(h.id, todayISO(), null); s.toast(`Unchecked · ${h.name}`); return; } if (!measurable) { s.logHabit(h.id, todayISO(), 1); s.toast(`${h.name} ✓${streak(h) > 0 ? ` · streak ${streak(h) + 1}` : ''}`, { undo: () => s.logHabit(h.id, todayISO(), null) }); return; } const step = h.type === 'count' ? 1 : Math.max(1, Math.round(h.target / 2)); const nv = Math.min(h.target, v + step); s.logHabit(h.id, todayISO(), nv); if (nv >= h.target) s.toast(`${h.name} ✓ · target reached`); };
  return (
    <div className="habit">
      <Link href={`/habits/${h.id}`} className="row grow" style={{ gap: 12 }}>
        <IconBox ic={h.icon} tone={done ? 'success' : ''} />
        <div className="grow"><div className="t">{h.name}</div><div className="s">{measurable ? `${v}/${h.target} ${h.unit} · ` : ''}{h.type === 'binary' ? (h.days.every(Boolean) ? 'daily' : h.days.slice(0, 5).every(Boolean) && !h.days[5] ? 'Mon–Fri' : `${h.days.filter(Boolean).length}× a week`) : `${h.target} ${h.unit} · ${h.days.every(Boolean) ? 'daily' : h.days.filter(Boolean).length + '× a week'}`} · {streakLabel(h) || <span style={{ color: 'var(--accent)' }}>Recovering — start again today</span>}</div></div>
      </Link>
      <button type="button" className={`check ${done ? 'on' : measurable && v > 0 ? 'partial' : ''}`} onClick={tap} title={done ? 'Undo' : measurable ? `Log +${h.type === 'count' ? 1 : Math.max(1, Math.round(h.target / 2))} ${h.unit}` : 'Mark done'}>{done ? <Icon name="check" /> : measurable && v > 0 ? `${v}/${h.target}` : <Icon name="plus" />}</button>
    </div>
  );
}

/* ---------- Habit form ---------- */
export function HabitForm({ open, onClose, editId }: { open: boolean; onClose: () => void; editId?: string }) {
  const s = useStore(); const router = useRouter(); const ex = editId ? s.habits.find((h) => h.id === editId) : undefined;
  const [name, setName] = useState(''); const [type, setType] = useState<HabitType>('binary'); const [target, setTarget] = useState('10'); const [unit, setUnit] = useState('minutes'); const [tod, setTod] = useState<Habit['timeOfDay']>('anytime'); const [days, setDays] = useState([true, true, true, true, true, true, true]); const [remOn, setRemOn] = useState(false); const [rem, setRem] = useState('21:30'); const [goalId, setGoalId] = useState(''); const [gentle, setGentle] = useState(true); const [icon, setIcon] = useState('repeat'); const [err, setErr] = useState('');
  useEffect(() => { if (!open) return; setName(ex?.name || ''); setType(ex?.type || 'binary'); setTarget(String(ex?.target ?? 10)); setUnit(ex?.unit || 'minutes'); setTod(ex?.timeOfDay || 'anytime'); setDays(ex?.days || [true, true, true, true, true, true, true]); setRemOn(!!ex?.reminder); setRem(ex?.reminder || '21:30'); setGoalId(ex?.goalId || ''); setGentle(ex?.gentle ?? true); setIcon(ex?.icon || 'repeat'); setErr(''); }, [open, ex]);
  const schedule = (k: string) => setDays(k === 'Every day' ? [true, true, true, true, true, true, true] : k === 'Weekdays' ? [true, true, true, true, true, false, false] : [true, false, true, false, true, false, false]);
  const schedLabel = days.every(Boolean) ? 'Every day' : JSON.stringify(days) === JSON.stringify([true, true, true, true, true, false, false]) ? 'Weekdays' : JSON.stringify(days) === JSON.stringify([true, false, true, false, true, false, false]) ? '3× a week' : 'Custom';
  const save = () => { if (!name.trim()) { setErr('Name the habit'); return; } if (!days.some(Boolean)) { setErr('Pick at least one day'); return; } const patch = { name: name.trim(), type, target: type === 'binary' ? 1 : +target || 1, unit: type === 'binary' ? '' : unit, timeOfDay: tod, days, reminder: remOn ? rem : undefined, goalId: goalId || undefined, gentle, icon }; if (ex) { s.updateHabit(ex.id, patch); s.toast('Habit updated'); onClose(); } else { const h = s.addHabit(patch); s.toast(`Habit created · ${h.name}`); onClose(); router.push(href('habits')); } };
  return (
    <Modal open={open} onClose={onClose} title={ex ? 'Edit habit' : 'New habit'} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label={ex ? 'Save' : 'Create habit'} kind="primary" onClick={save} /></>}>
      <TextInput label="Name" value={name} onChange={setName} placeholder="e.g. Evening stretch" autoFocus err={err} onEnter={save} />
      <div className="field"><div className="lbl">Icon</div><div className="chips">{ICONS_PICK.map((i) => <button type="button" key={i} className={`chip ${i === icon ? 'accent' : 'outline'}`} onClick={() => setIcon(i)}><Icon name={i} /></button>)}</div></div>
      <div className="field"><div className="lbl">Type</div><Segmented opts={Object.values(TYPE_L)} on={TYPE_L[type]} cls="block" onChange={(v) => { const t = (Object.keys(TYPE_L) as HabitType[]).find((k) => TYPE_L[k] === v)!; setType(t); setUnit(t === 'duration' ? 'minutes' : t === 'count' ? 'times' : t === 'quantity' ? 'glasses' : ''); }} /></div>
      <div className="split">{type !== 'binary' && <TextInput label="Target" value={target} onChange={(v) => setTarget(v.replace(/\D/g, ''))} inputMode="numeric" sfx={<input className="ph-in" style={{ width: 70, textAlign: 'right' }} value={unit} onChange={(e) => setUnit(e.target.value)} />} />}<div className="field"><div className="lbl">Time of day</div><Segmented opts={['Morning', 'Anytime', 'Evening']} on={tod[0].toUpperCase() + tod.slice(1)} cls="block" onChange={(v) => setTod(v.toLowerCase() as Habit['timeOfDay'])} /></div></div>
      <div className="field"><div className="lbl">Schedule</div><div className="chips">{['Every day', 'Weekdays', '3× a week'].map((k) => <button type="button" key={k} className={`chip ${schedLabel === k ? 'accent' : 'outline'}`} onClick={() => schedule(k)}>{k}</button>)}<span className={`chip ${schedLabel === 'Custom' ? 'accent' : 'outline'}`}>Custom</span></div></div>
      <div className="field"><div className="lbl">Days</div><div className="scale">{['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => <button type="button" key={i} className={days[i] ? 'on' : ''} onClick={() => setDays(days.map((x, j) => j === i ? !x : x))}>{d}</button>)}</div></div>
      <div className="card pad-0"><List>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="bell" /><div className="grow"><div className="t">Reminder</div><div className="s">{remOn ? <input type="time" className="ph-in" value={rem} onChange={(e) => setRem(e.target.value)} style={{ width: 110 }} /> : 'Off'}</div></div><button type="button" role="switch" aria-checked={remOn} className={`toggle ${remOn ? 'on' : ''}`} onClick={() => setRemOn((v) => !v)} /></div>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="target" /><div className="grow"><div className="t">Supports goal</div><Select value={goalId} onChange={setGoalId} options={[{ value: '', label: 'None — optional' }, ...s.goals.filter((g) => g.status === 'active').map((g) => ({ value: g.id, label: g.name }))]} cls="mt-s" /></div></div>
        <ToggleRow t="Gentle mode" s="Missed days show as recovering, never as failure" on={gentle} ic="heart" onChange={setGentle} />
      </List></div>
    </Modal>
  );
}

/* ================= LIST ================= */
export function HabitsScreen({ overlay }: { overlay?: ReactNode }) {
  const s = useStore(); const [view, setView] = useState('Today'); const [form, setForm] = useState(false);
  const habits = s.habits.filter((h) => !h.archived); const due = habitsDueToday(habits); const doneN = habitsDoneToday(habits).length; const longest = Math.max(0, ...habits.map(streak));
  const group = (k: Habit['timeOfDay']) => (view === 'Today' ? due : habits).filter((h) => h.timeOfDay === k);
  const Grp = ({ k, title }: { k: Habit['timeOfDay']; title: string }) => group(k).length ? <Section title={title}><Card cls="pad-0"><List>{group(k).map((h) => <HRow key={h.id} h={h} />)}</List></Card></Section> : null;
  return (
    <Screen nav="habits" top={{ title: 'Habits', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }} overlay={overlay}>
      <div className="row between"><div className="stat-inline"><div><span className="v">{doneN} / {due.length}</span><span className="l">done today</span></div><div><span className="v">{habitsWeekRate(habits)}%</span><span className="l">this week</span></div><div><span className="v">{longest}</span><span className="l">longest active streak</span></div></div><Segmented opts={['Today', 'Week']} on={view} onChange={setView} /></div>
      {habits.length === 0 ? <Card><Empty ic="repeat" title="No habits yet" p="Start with one small daily habit — Mitra keeps streaks gentle." action={<Btn label="New habit" ic="plus" kind="primary" onClick={() => setForm(true)} />} /></Card> : (
        <div className="split">
          <Grp k="morning" title="Morning" /><Grp k="anytime" title="Anytime" /><Grp k="evening" title="Evening" />
          {view === 'Today' && due.length === 0 && <Card><Empty ic="sun" title="Nothing scheduled today" p="Switch to Week to see all habits." /></Card>}
          <Section title="This week"><Card><List>{habits.map((h) => <div key={h.id} className="row" style={{ padding: '9px 0' }}><Link href={`/habits/${h.id}`} className="grow sm b ellip">{h.name}</Link><WeekDots w={weekDots(h)} todayIdx={dowMon(todayISO())} /><span className="xs muted num" style={{ width: 34, textAlign: 'right' }}>{weekRate(h)}%</span></div>)}</List><div className="xs faint mt-s">Mon → Sun · today outlined</div></Card></Section>
        </div>
      )}
      <HabitForm open={form} onClose={() => setForm(false)} />
    </Screen>
  );
}

/* ================= DETAIL ================= */
export function HabitDetailScreen({ id }: { id: string }) {
  const s = useStore(); const router = useRouter(); const h = s.habits.find((x) => x.id === id); const [edit, setEdit] = useState(false); const [del, setDel] = useState(false); const [logV, setLogV] = useState('');
  if (!h) return <Screen nav="habits" top={{ title: 'Habit not found', back: 'habits' }}><Card><Empty ic="repeat" title="This habit doesn't exist" p="" action={<Btn label="All habits" goto="habits" />} /></Card></Screen>;
  const r30 = rateDays(h, 30); const done = habitDone(h); const goal = s.goals.find((g) => g.id === h.goalId); const measurable = h.type !== 'binary';
  const heat = Array.from({ length: 84 }, (_, i) => { const d = addDays(todayISO(), -(83 - i)); const v = h.logs[d] || 0; return !habitDue(h, d) && !v ? 0 : v >= h.target ? 4 : v > 0 ? Math.max(1, Math.round((v / h.target) * 3)) : 0; });
  const weekly = Array.from({ length: 12 }, (_, w) => { const vals = Array.from({ length: 7 }, (_, i) => h.logs[addDays(todayISO(), -((11 - w) * 7 + (6 - i)))] || 0).filter(Boolean); return vals.length ? +(vals.reduce((a, b) => a + b, 0) / vals.length).toFixed(1) : 0; });
  const recent = Array.from({ length: 5 }, (_, i) => addDays(todayISO(), -i));
  const avg = (() => { const v = Object.values(h.logs).filter(Boolean); return v.length ? Math.round(v.reduce((a, b) => a + b, 0) / v.length) : 0; })();
  const monthDone = Object.keys(h.logs).filter((d) => d.slice(0, 7) === todayISO().slice(0, 7) && h.logs[d] >= h.target).length; const monthDue = Array.from({ length: +todayISO().slice(8) }, (_, i) => `${todayISO().slice(0, 8)}${String(i + 1).padStart(2, '0')}`).filter((d) => habitDue(h, d)).length;
  return (
    <Screen nav="habits" top={{ title: h.name, back: 'habits', eyebrow: `Habit · ${TYPE_L[h.type].toLowerCase()} · ${h.days.every(Boolean) ? 'daily' : h.days.filter(Boolean).length + '× a week'}`, actions: [{ ic: 'edit', onClick: () => setEdit(true), title: 'Edit' }, { el: <Dropdown items={[{ label: 'View history', ic: 'calendar', onClick: () => router.push(`/habits/${h.id}/history`) }, { label: h.archived ? 'Unarchive' : 'Archive', ic: 'archive', onClick: () => { s.updateHabit(h.id, { archived: !h.archived }); s.toast(h.archived ? 'Habit restored' : 'Habit archived · history kept'); } }, { label: 'Delete', ic: 'trash', danger: true, onClick: () => setDel(true) }]} /> }] }}>
      <div className="split">
        <Card><div className="row" style={{ gap: 16 }}><Ring pct={r30.pct} size={84} stroke={8} label={r30.pct + '%'} tone={r30.pct >= 70 ? 'success' : r30.pct >= 40 ? 'warning' : 'danger'} /><div className="grow"><div className="kpi-grid"><Kpi l="Current streak" v={`${streak(h)} days`} /><Kpi l="Best" v={`${bestStreak(h)} days`} /><Kpi l="This month" v={`${monthDone} / ${monthDue}`} /><Kpi l={measurable ? `Avg ${h.unit}` : 'Last 30 days'} v={measurable ? `${avg}` : `${r30.done}/${r30.due}`} /></div></div></div>
          <div className="row mt" style={{ gap: 8, flexWrap: 'wrap' }}><Btn label={done ? 'Done today ✓' : 'Done today'} ic="check" kind={done ? 'soft' : 'primary'} cls="grow" onClick={() => { s.logHabit(h.id, todayISO(), done ? null : h.target); s.toast(done ? 'Unchecked' : `${h.name} ✓`); }} />{measurable && <div className="row" style={{ gap: 6 }}><TextInput value={logV} onChange={(v) => setLogV(v.replace(/\D/g, ''))} placeholder={String(h.target)} inputMode="numeric" sfx={h.unit} style={{ width: 150 }} onEnter={() => { if (logV) { s.logHabit(h.id, todayISO(), +logV); s.toast(`Logged ${logV} ${h.unit}`); setLogV(''); } }} /><Btn label="Log" ic="timer" kind="outline" onClick={() => { if (logV) { s.logHabit(h.id, todayISO(), +logV); s.toast(`Logged ${logV} ${h.unit}`); setLogV(''); } }} /></div>}</div>
        </Card>
        <Card><div className="eyebrow mb-s">Consistency · last 12 weeks</div><Heat cells={heat} cols={7} /><div className="row between xs muted mt-s"><span>12 weeks ago</span><span>{measurable ? `Darker = more ${h.unit}` : 'Filled = done'}</span><span>This week</span></div></Card>
      </div>
      {measurable && <Section title="Trend"><Card><LineChart vals={weekly} labels={weekly.map((_, i) => i % 4 === 0 ? MON[fromISO(addDays(todayISO(), -(11 - i) * 7)).getMonth()] : '')} h={130} goal={h.target} /><div className="legend mt-s"><span><i />{h.unit} per session (weekly avg)</span><span><i className="s" style={{ height: 2 }} />Target {h.target} {h.unit}</span></div></Card></Section>}
      <div className="split">
        <Section title="Supports goal"><Card cls="pad-0">{goal ? <Link href={`/goals/${goal.id}`} className="lrow"><Ring pct={goalProgress(goal, s.habits)} size={40} stroke={4} label={goalProgress(goal, s.habits) + '%'} /><div className="grow"><div className="t">{goal.name}</div><div className="s">Evidence: {r30.done} sessions in 30 days</div></div><Icon name="chevron-right" cls="chev" /></Link> : <div className="list-empty">Not linked to a goal. <a className="link" onClick={() => setEdit(true)}>Link one</a></div>}</Card></Section>
        <Section title="Recent" action="History" goto=""><Card cls="pad-0"><List>{recent.map((d) => { const v = h.logs[d] || 0; const due = habitDue(h, d); return <div key={d} className="row" style={{ padding: '9px 12px' }}><span className="sm b" style={{ width: 90 }}>{fmtDay(d)}</span><span className="sm muted grow">{v >= h.target ? (measurable ? `${v} ${h.unit} ✓` : 'Done ✓') : v > 0 ? `${v} ${h.unit} · partial` : due ? 'Missed' : 'Rest day'}</span>{d !== todayISO() && <button type="button" className="chip sm outline" onClick={() => s.logHabit(h.id, d, v >= h.target ? null : h.target)}>{v >= h.target ? 'Undo' : 'Mark done'}</button>}</div>; })}</List><div style={{ padding: '6px 12px 10px' }}><Link className="link sm" href={`/habits/${h.id}/history`}>Full history</Link></div></Card></Section>
      </div>
      <HabitForm open={edit} onClose={() => setEdit(false)} editId={h.id} />
      <Confirm open={del} onClose={() => setDel(false)} title={`Delete “${h.name}”?`} body="All logs will be removed. Archiving keeps history." onConfirm={() => { s.deleteHabit(h.id); s.toast('Habit deleted'); router.push(href('habits')); }} />
    </Screen>
  );
}

/* ================= HISTORY (editable calendar) ================= */
export function HabitHistoryScreen({ id }: { id: string }) {
  const s = useStore(); const h = s.habits.find((x) => x.id === id); const [month, setMonth] = useState(todayISO().slice(0, 7) + '-01');
  if (!h) return <Screen nav="habits" top={{ title: 'Habit not found', back: 'habits' }}><Card><Empty ic="repeat" title="This habit doesn't exist" p="" /></Card></Screen>;
  const n = daysInMonth(month); const first = dowMon(month); const days = Array.from({ length: n }, (_, i) => `${month.slice(0, 8)}${String(i + 1).padStart(2, '0')}`);
  const doneDays = days.filter((d) => (h.logs[d] || 0) >= h.target).length; const dueDays = days.filter((d) => habitDue(h, d) && d <= todayISO()).length;
  const totalAll = Object.values(h.logs).filter((v) => v >= h.target).length; const totalTime = h.type === 'duration' ? Object.values(h.logs).reduce((a, b) => a + b, 0) : 0;
  const best = (() => { const m: Record<string, { done: number; due: number }> = {}; Object.keys(h.logs).forEach((d) => { const k = d.slice(0, 7); (m[k] ||= { done: 0, due: 0 }); if (h.logs[d] >= h.target) m[k].done++; }); Object.keys(m).forEach((k) => { m[k].due = Array.from({ length: daysInMonth(k + '-01') }, (_, i) => `${k}-${String(i + 1).padStart(2, '0')}`).filter((d) => habitDue(h, d) && d <= todayISO()).length; }); return Object.entries(m).map(([k, v]) => ({ k, pct: v.due ? Math.round((v.done / v.due) * 100) : 0 })).sort((a, b) => b.pct - a.pct)[0]; })();
  const shift = (dir: number) => { const d = fromISO(month); d.setMonth(d.getMonth() + dir); setMonth(toISO(d).slice(0, 7) + '-01'); };
  const tap = (d: string) => { if (d > todayISO()) return; const v = h.logs[d] || 0; s.logHabit(h.id, d, v >= h.target ? null : h.target); };
  return (
    <Screen nav="habits" top={{ title: `History · ${h.name}`, back: 'habits', actions: [{ ic: 'download', title: 'Export CSV', onClick: () => { const csv = 'date,value\n' + Object.entries(h.logs).sort().map(([d, v]) => `${d},${v}`).join('\n'); const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })); a.download = `${h.name.replace(/\s+/g, '-').toLowerCase()}.csv`; a.click(); s.toast('CSV downloaded'); } }] }}>
      <div className="row between"><Btn ic="chevron-left" kind="ghost" cls="sm" onClick={() => shift(-1)} /><h2 style={{ fontSize: 17 }}>{fmtMonth(month)}</h2><Btn ic="chevron-right" kind="ghost" cls="sm" onClick={() => shift(1)} /></div>
      <div className="split">
        <Card>
          <div className="calendar-grid">
            {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => <div key={i} className="dow">{d}</div>)}
            {Array.from({ length: first }, (_, i) => <div key={'pad' + i} className="d out">{daysInMonth(addDays(month, -1)) - first + i + 1}</div>)}
            {days.map((d) => { const v = h.logs[d] || 0; const hit = v >= h.target; const future = d > todayISO(); const due = habitDue(h, d); return <div key={d} className={`d ${hit ? 'hit' : ''} ${!hit && !future && due && v === 0 ? 'miss' : ''} ${d === todayISO() ? 'today' : ''} ${future ? 'out' : ''}`} onClick={() => tap(d)} title={future ? '' : hit ? 'Tap to unmark' : 'Tap to mark done'}>{+d.slice(8)}{v > 0 && !hit && <span className="m"><i /></span>}</div>; })}
          </div>
          <div className="legend mt-s"><span><i className="s" />Done</span><span><i className="n" />Missed / rest</span><span><i style={{ background: 'transparent', outline: '2px solid var(--accent)' }} />Today</span></div>
          <div className="xs faint mt-s">Tap any past day to correct it.</div>
        </Card>
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Card><div className="kpi-grid"><Kpi l="Completion" v={`${dueDays ? Math.round((doneDays / dueDays) * 100) : 0}%`} d={MON[+month.slice(5, 7) - 1]} /><Kpi l="Best month" v={best ? `${MON[+best.k.slice(5, 7) - 1]} · ${best.pct}%` : '—'} /><Kpi l="Total" v={`${totalAll} ${h.type === 'binary' ? 'days' : 'sessions'}`} />{h.type === 'duration' ? <Kpi l="Total time" v={`${Math.floor(totalTime / 60)}h ${totalTime % 60}m`} /> : <Kpi l="Best streak" v={`${bestStreak(h)} days`} />}</div></Card>
          <Section title="Log"><Card cls="pad-0"><List>{days.filter((d) => d <= todayISO()).reverse().slice(0, 10).map((d) => { const v = h.logs[d] || 0; return <div key={d} className="row" style={{ padding: '9px 12px' }}><span className="sm b" style={{ width: 64 }}>{fmtDay(d, { relative: false }).slice(4)}</span><span className="sm num grow">{v ? `${v}${h.unit ? ' ' + h.unit : ''}` : '—'}</span><span className="xs muted">{v >= h.target ? 'Done' : v > 0 ? 'Partial' : habitDue(h, d) ? 'Missed' : 'Rest day'}</span></div>; })}</List></Card></Section>
        </div>
      </div>
    </Screen>
  );
}

/* ================= CREATE (route: /habits/new) ================= */
export function HabitEditScreen() {
  const router = useRouter(); const [open, setOpen] = useState(true);
  return <><HabitsScreen /><HabitForm open={open} onClose={() => { setOpen(false); router.push(href('habits')); }} /></>;
}
