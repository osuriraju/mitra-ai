'use client';
/* ---------- GOALS: list, create/edit, detail, milestone detail, review ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, type ReactNode } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Bar, Btn, Card, Chip, Empty, Icon, Kpi, LRow, LineChart, List, Ring, Section, Segmented, Sheet } from '@/components/ui';
import { Confirm, DateInput, Dropdown, Modal, Select, TextArea, TextInput } from '@/components/ui/controls';
import { addDays, diffDays, fmtDay, fmtShort, todayISO, MON } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import type { Goal, GoalType, Milestone } from '@/store/types';
import { STATUS_LABEL, STATUS_TONE, goalProgress, goalStatus, goalTargetLabel, goalValueLabel, reviewDue, rateDays } from '@/store/selectors';
import { TRow, TaskForm } from './tasks';

const TYPE_LABEL: Record<GoalType, string> = { numeric: 'Numeric', milestone: 'Milestone', habit: 'Habit-supported', date: 'Date-based' };
const monthsLeft = (due: string) => { const d = diffDays(due, todayISO()); return d < 0 ? 'past due' : d < 31 ? `${d} days left` : `${Math.round(d / 30)} months left`; };

/* ---------- Goal form ---------- */
export function GoalForm({ open, onClose, editId }: { open: boolean; onClose: () => void; editId?: string }) {
  const s = useStore(); const router = useRouter(); const existing = editId ? s.goals.find((g) => g.id === editId) : undefined;
  const [name, setName] = useState(''); const [type, setType] = useState<GoalType>('numeric'); const [term, setTerm] = useState<Goal['term']>('Medium-term'); const [due, setDue] = useState(addDays(todayISO(), 180)); const [target, setTarget] = useState('100'); const [current, setCurrent] = useState('0'); const [unit, setUnit] = useState<'₹' | ''>(''); const [next, setNext] = useState(''); const [review, setReview] = useState<Goal['reviewEvery']>('Monthly'); const [ms, setMs] = useState(''); const [err, setErr] = useState('');
  useEffect(() => { if (!open) return; const g = existing; setName(g?.name || ''); setType(g?.type || 'numeric'); setTerm(g?.term || 'Medium-term'); setDue(g?.due || addDays(todayISO(), 180)); setTarget(String(g?.target ?? 100)); setCurrent(String(g?.current ?? 0)); setUnit(g?.unit || ''); setNext(g?.nextAction || ''); setReview(g?.reviewEvery || 'Monthly'); setMs(g ? '' : '25%\n50%\n75%\nDone'); setErr(''); }, [open, existing]);
  const save = () => {
    if (!name.trim()) { setErr('Give the goal a name'); return; }
    const base: Partial<Goal> = { name: name.trim(), type, term, due, unit, nextAction: next.trim(), reviewEvery: review, target: type === 'milestone' ? undefined : +target || 100, current: type === 'milestone' ? undefined : +current || 0 };
    if (existing) { s.updateGoal(existing.id, base); s.toast('Goal updated'); onClose(); return; }
    const milestones: Milestone[] = ms.split('\n').map((l) => l.trim()).filter(Boolean).map((title, i, arr) => ({ id: Math.random().toString(36).slice(2, 8), title, target: type === 'numeric' ? Math.round(((+target || 100) * (i + 1)) / arr.length) : undefined, targetDate: addDays(todayISO(), Math.round((diffDays(due, todayISO()) * (i + 1)) / arr.length)) }));
    const g = s.addGoal({ ...base, name: name.trim(), milestones, target: type === 'milestone' ? milestones.length : +target || 100, current: type === 'milestone' ? 0 : +current || 0, startedOn: todayISO(), reviewOn: addDays(todayISO(), review === 'Weekly' ? 7 : review === 'Monthly' ? 30 : 90) });
    s.toast(`Goal set · ${g.name}`); onClose(); router.push(`/goals/${g.id}`);
  };
  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit goal' : 'New goal'} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label={existing ? 'Save' : 'Create goal'} kind="primary" onClick={save} /></>}>
      <TextInput label="Goal" value={name} onChange={setName} placeholder="e.g. Build ₹1,00,000 emergency fund" autoFocus err={err} onEnter={save} />
      <div className="field"><div className="lbl">Type</div><Segmented opts={['Numeric', 'Milestone', 'Habit-supported', 'Date-based']} on={TYPE_LABEL[type]} cls="block" onChange={(v) => setType((Object.keys(TYPE_LABEL) as GoalType[]).find((k) => TYPE_LABEL[k] === v)!)} /><div className="hint">{type === 'numeric' ? 'Track a number toward a target (money, books, kg).' : type === 'milestone' ? 'Progress = milestones reached ÷ total.' : type === 'habit' ? 'Progress = 30-day consistency of the habits linked to this goal.' : 'Progress = time elapsed toward the date; use milestones for evidence.'}</div></div>
      {type !== 'milestone' && type !== 'habit' && <div className="split"><TextInput label="Current" value={current} onChange={(v) => setCurrent(v.replace(/[^\d.]/g, ''))} inputMode="decimal" sfx={unit || undefined} /><TextInput label="Target" value={target} onChange={(v) => setTarget(v.replace(/[^\d.]/g, ''))} inputMode="decimal" sfx={unit || undefined} /></div>}
      {type === 'numeric' && <div className="field"><div className="lbl">Unit</div><Segmented opts={['Number', '₹ Rupees']} on={unit === '₹' ? '₹ Rupees' : 'Number'} onChange={(v) => setUnit(v === '₹ Rupees' ? '₹' : '')} /></div>}
      <div className="split"><DateInput label="Due" value={due} onChange={setDue} /><Select label="Horizon" ic="flag" value={term} onChange={(v) => setTerm(v as Goal['term'])} options={['Short-term', 'Medium-term', 'Long-term']} /></div>
      <div className="split"><TextInput label="Next action" value={next} onChange={setNext} placeholder="The one thing to do next" ic="arrow-right" /><Select label="Review" ic="calendar" value={review} onChange={(v) => setReview(v as Goal['reviewEvery'])} options={['Weekly', 'Monthly', 'Quarterly']} /></div>
      {!existing && <TextArea label="Milestones (one per line)" value={ms} onChange={setMs} rows={4} hint="Targets and dates are spread evenly; edit them on the goal page." />}
    </Modal>
  );
}

/* ---------- Log progress ---------- */
function LogProgress({ g, open, onClose }: { g: Goal; open: boolean; onClose: () => void }) {
  const s = useStore(); const [v, setV] = useState(''); const [note, setNote] = useState('');
  useEffect(() => { if (open) { setV(''); setNote(''); } }, [open]);
  const numeric = g.type === 'numeric' || g.type === 'date';
  const save = () => { const n = +v; if (numeric && (!n || n <= 0)) return; s.logGoalProgress(g.id, numeric ? n : g.current, note || undefined); s.toast(`Progress logged${numeric ? ` · +${g.unit}${n.toLocaleString('en-IN')}` : ''}`); onClose(); };
  return (
    <Modal open={open} onClose={onClose} title="Log progress" sub={g.name} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label="Save" kind="primary" onClick={save} /></>}>
      {numeric ? <TextInput label={`Add to ${goalValueLabel(g)} (target ${goalTargetLabel(g)})`} value={v} onChange={(x) => setV(x.replace(/[^\d.]/g, ''))} inputMode="decimal" autoFocus placeholder={g.unit === '₹' ? '8000' : '1'} sfx={g.unit || undefined} onEnter={save} /> : <div className="card soft tight sm">{g.type === 'milestone' ? 'Tick milestones on the goal page — progress is milestones reached ÷ total.' : 'Progress for habit-supported goals comes from the linked habits’ consistency. Add a note below.'}</div>}
      <TextArea label="Note (optional)" value={note} onChange={setNote} placeholder="What moved the needle?" rows={2} />
    </Modal>
  );
}

/* ---------- Milestone form ---------- */
function MilestoneForm({ goal, open, onClose, editId }: { goal: Goal; open: boolean; onClose: () => void; editId?: string }) {
  const s = useStore(); const ex = editId ? goal.milestones.find((m) => m.id === editId) : undefined;
  const [title, setTitle] = useState(''); const [target, setTarget] = useState(''); const [date, setDate] = useState(''); const [notes, setNotes] = useState('');
  useEffect(() => { if (open) { setTitle(ex?.title || ''); setTarget(ex?.target ? String(ex.target) : ''); setDate(ex?.targetDate || ''); setNotes(ex?.notes || ''); } }, [open, ex]);
  const save = () => { if (!title.trim()) return; const patch = { title: title.trim(), target: target ? +target : undefined, targetDate: date || undefined, notes }; if (ex) s.updateMilestone(goal.id, ex.id, patch); else s.addMilestone(goal.id, patch); s.toast(ex ? 'Milestone updated' : 'Milestone added'); onClose(); };
  return (
    <Modal open={open} onClose={onClose} title={ex ? 'Edit milestone' : 'Add milestone'} sub={goal.name} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label={ex ? 'Save' : 'Add'} kind="primary" onClick={save} /></>}>
      <TextInput label="Milestone" value={title} onChange={setTitle} autoFocus placeholder="e.g. ₹75,000" onEnter={save} />
      <div className="split">{goal.type === 'numeric' && <TextInput label="Reached at value" value={target} onChange={(v) => setTarget(v.replace(/[^\d.]/g, ''))} inputMode="decimal" sfx={goal.unit || undefined} hint="Auto-ticks when progress passes this" />}<DateInput label="Target date" value={date} onChange={setDate} /></div>
      <TextArea label="Notes" value={notes} onChange={setNotes} rows={2} />
    </Modal>
  );
}

/* ================= LIST ================= */
const GoalCard = ({ g }: { g: Goal }) => {
  const habits = useStore((s) => s.habits); const p = goalProgress(g, habits); const st = goalStatus(g, habits);
  const links = useStore((s) => { const t = s.tasks.filter((x) => x.goalId === g.id).length; const h = s.habits.filter((x) => x.goalId === g.id).length; const pr = s.projects.filter((x) => x.goalId === g.id).length; return [pr && `${pr} project${pr > 1 ? 's' : ''}`, t && `${t} task${t > 1 ? 's' : ''}`, h && `${h} habit${h > 1 ? 's' : ''}`].filter(Boolean).join(' · ') || 'Nothing linked yet'; });
  return (
    <Link href={`/goals/${g.id}`} className="card" style={{ cursor: 'pointer', opacity: g.status === 'paused' ? .7 : 1 }}>
      <div className="row top" style={{ gap: 14 }}>
        <Ring pct={p} size={56} stroke={6} label={p + '%'} tone={st === 'on-track' || st === 'completed' ? (st === 'completed' ? 'success' : '') : st === 'slipping' ? 'danger' : 'warning'} />
        <div className="grow">
          <div className="row between top"><div className="b" style={{ fontSize: 15 }}>{g.name}</div><Chip tone={STATUS_TONE[st]} cls="sm">{STATUS_LABEL[st]}</Chip></div>
          <div className="sm muted" style={{ marginTop: 2 }}>{TYPE_LABEL[g.type]} · {g.term} · due {MON[+g.due.slice(5, 7) - 1]} {g.due.slice(0, 4)}</div>
          {g.nextAction && <div className="sm mt-s"><Icon name="arrow-right" /> <b>Next:</b> {g.nextAction}</div>}
          <div className="xs faint mt-s">{links}{reviewDue(g) ? ' · review due' : ''}</div>
        </div>
      </div>
    </Link>
  );
};
export function GoalsScreen() {
  const s = useStore(); const [tab, setTab] = useState('Active'); const [form, setForm] = useState(false);
  const active = s.goals.filter((g) => g.status === 'active'); const due = active.filter(reviewDue); const completed = s.goals.filter((g) => g.status !== 'active');
  const list = tab === 'Active' ? active : tab === 'Review due' ? due : completed;
  return (
    <Screen nav="goals" top={{ title: 'Goals', actions: [{ ic: 'plus', label: 'New goal', kind: 'primary', onClick: () => setForm(true) }] }}>
      <div className="row between"><Segmented opts={['Active', 'Review due', 'Completed']} on={tab} onChange={setTab} /><span className="sm muted">{active.length} active · {due.length} review due{due.length > 0 && <> · <Link className="link" href={href('goal-review')}>Start review</Link></>}</span></div>
      {list.length === 0 ? <Card><Empty ic="target" title={tab === 'Active' ? 'Set one goal — Mitra will link habits and tasks to it.' : tab === 'Review due' ? 'No reviews due' : 'Nothing completed or paused yet'} p={tab === 'Active' ? 'Goals give your tasks and habits a direction.' : tab === 'Review due' ? 'Goals show up here when their review date arrives.' : 'Completed and paused goals keep their history here.'} action={tab === 'Active' ? <Btn label="New goal" ic="plus" kind="primary" onClick={() => setForm(true)} /> : undefined} /></Card>
        : <div className="split">{list.map((g) => <GoalCard key={g.id} g={g} />)}</div>}
      <GoalForm open={form} onClose={() => setForm(false)} />
    </Screen>
  );
}

/* ================= DETAIL ================= */
export function GoalDetailScreen({ id, overlay }: { id: string; overlay?: ReactNode }) {
  const s = useStore(); const router = useRouter(); const g = s.goals.find((x) => x.id === id);
  const [log, setLog] = useState(false); const [edit, setEdit] = useState(false); const [del, setDel] = useState(false); const [msForm, setMsForm] = useState<false | string>(false); const [taskForm, setTaskForm] = useState(false); const [formula, setFormula] = useState(false);
  if (!g) return <Screen nav="goals" top={{ title: 'Goal not found', back: 'goals' }}><Card><Empty ic="target" title="This goal doesn't exist" p="It may have been deleted." action={<Btn label="All goals" goto="goals" />} /></Card></Screen>;
  const p = goalProgress(g, s.habits); const st = goalStatus(g, s.habits);
  const tasks = s.tasks.filter((t) => t.goalId === g.id); const habits = s.habits.filter((h) => h.goalId === g.id); const projects = s.projects.filter((x) => x.goalId === g.id); const notes = s.notes.filter((n) => n.body.includes(g.name) || n.title.toLowerCase().includes(g.name.toLowerCase().split(' ').slice(-2).join(' ')));
  const nextTask = tasks.filter((t) => !t.done).sort((a, b) => (a.due || '9').localeCompare(b.due || '9'))[0];
  const series = g.progressLog.length > 1 ? g.progressLog.map((x) => x.value) : [0, g.current]; const labels = g.progressLog.length > 1 ? g.progressLog.map((x, i) => i % Math.ceil(g.progressLog.length / 5) === 0 ? MON[+x.date.slice(5, 7) - 1] : '') : ['Start', 'Now'];
  const blockers = st === 'slipping' ? 'No progress logged in 3+ weeks — the goal is drifting from its target line.' : st === 'behind' ? 'Progress is below the pace needed to finish by the due date.' : g.unit === '₹' && st !== 'on-track' ? 'Over-budget categories this month reduce the surplus available to transfer.' : '';
  const tickMs = (m: Milestone) => { s.updateMilestone(g.id, m.id, { reachedOn: m.reachedOn ? undefined : todayISO() }); if (!m.reachedOn) { s.addActivity(`Milestone reached · ${m.title}`, 'target', 'success', 'goals'); s.toast(`Milestone reached · ${m.title}`); } };
  return (
    <Screen nav="goals" top={{ title: g.name, back: 'goals', eyebrow: `${TYPE_LABEL[g.type]} goal · ${g.term} · Review ${g.reviewEvery.toLowerCase()}`, actions: [{ ic: 'edit', onClick: () => setEdit(true), title: 'Edit' }, { el: <Dropdown items={[{ label: 'Log progress', ic: 'plus', onClick: () => setLog(true) }, { label: 'Add milestone', ic: 'flag', onClick: () => setMsForm('new') }, { label: 'Link a task', ic: 'check-square', onClick: () => setTaskForm(true) }, { label: g.status === 'paused' ? 'Resume goal' : 'Pause goal', ic: g.status === 'paused' ? 'play' : 'pause', onClick: () => { s.updateGoal(g.id, { status: g.status === 'paused' ? 'active' : 'paused' }); s.toast(g.status === 'paused' ? 'Goal resumed' : 'Goal paused · history kept'); } }, { label: 'Mark completed', ic: 'check-circle', onClick: () => { s.updateGoal(g.id, { status: 'completed' }); s.toast('🎉 Goal completed'); } }, { label: 'Delete goal', ic: 'trash', danger: true, onClick: () => setDel(true) }]} /> }] }} overlay={overlay}>
      {g.status !== 'active' && <div className={`banner ${g.status === 'completed' ? 'success' : 'info'}`}><Icon name={g.status === 'completed' ? 'check-circle' : 'pause'} /><div className="grow">{g.status === 'completed' ? 'Completed — well done.' : 'Paused — hidden from Today, history kept.'}</div><Btn label={g.status === 'completed' ? 'Reopen' : 'Resume'} kind="soft" cls="sm" onClick={() => s.updateGoal(g.id, { status: 'active' })} /></div>}
      <div className="split main">
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Card>
            <div className="row between top"><div><Kpi l="Progress" v={g.type === 'milestone' ? <>{g.milestones.filter((m) => m.reachedOn).length} <span className="muted" style={{ fontSize: 14, fontWeight: 500 }}>of {g.milestones.length} milestones</span></> : g.type === 'habit' ? <>{p}% <span className="muted" style={{ fontSize: 14, fontWeight: 500 }}>habit consistency · 30d</span></> : <>{goalValueLabel(g)} <span className="muted" style={{ fontSize: 14, fontWeight: 500 }}>of {goalTargetLabel(g)}</span></>} /></div><Chip tone={STATUS_TONE[st]} ic={st === 'on-track' ? 'check' : 'alert'}>{STATUS_LABEL[st]}</Chip></div>
            <div className="mt-s"><Bar pct={p} tone={st === 'slipping' ? 'danger' : st === 'on-track' || st === 'completed' ? '' : 'warning'} cls="thick" /></div>
            <div className="row between xs muted mt-s"><span>{p}% · started {fmtShort(g.startedOn)} {g.startedOn.slice(0, 4)}</span><span>Due {fmtShort(g.due)} {g.due.slice(0, 4)} · {monthsLeft(g.due)}</span></div>
            <div className="card soft tight mt sm"><b>How this is calculated:</b> {formula ? <TextArea value={g.formula || ''} onChange={(v) => s.updateGoal(g.id, { formula: v })} rows={2} placeholder="Describe how progress is measured" /> : (g.formula || (g.type === 'milestone' ? 'milestones reached ÷ total milestones' : g.type === 'habit' ? 'average 30-day consistency of linked habits' : g.type === 'date' ? 'time elapsed ÷ total time to due date' : `${goalValueLabel(g)} ÷ ${goalTargetLabel(g)}`))} <a className="link" onClick={() => setFormula((v) => !v)}>{formula ? 'Done' : 'Edit formula'}</a></div>
          </Card>
          <Section title="Milestones" action="Add" goto=""><Card>
            {g.milestones.length === 0 ? <div className="list-empty">No milestones yet. <a className="link" onClick={() => setMsForm('new')}>Add one</a></div> : (
              <div className="milestones">{g.milestones.map((m, i) => { const now = !m.reachedOn && g.milestones.slice(0, i).every((x) => x.reachedOn); return <div key={m.id} className={`ms ${m.reachedOn ? 'done' : now ? 'now' : ''}`}><div className="row between"><Link href={`/goals/${g.id}/milestones/${m.id}`} className="grow"><div className="t">{m.title}</div><div className="s">{m.reachedOn ? `Reached ${fmtDay(m.reachedOn, { relative: false })}` : `${m.targetDate ? 'Target ' + fmtShort(m.targetDate) + ' ' + m.targetDate.slice(0, 4) : 'No date'}${m.target && g.type === 'numeric' ? ` · ${g.unit}${Math.max(0, m.target - g.current).toLocaleString('en-IN')} to go` : ''}`}</div></Link><Btn ic={m.reachedOn ? 'undo' : 'check'} kind={m.reachedOn ? 'ghost' : 'outline'} cls="sm" title={m.reachedOn ? 'Un-reach' : 'Mark reached'} onClick={() => tickMs(m)} /></div></div>; })}</div>
            )}
            <div className="row mt-s"><Btn label="Add milestone" ic="plus" kind="ghost" cls="sm" onClick={() => setMsForm('new')} /></div>
          </Card></Section>
          <Section title="Progress over time"><Card><LineChart vals={series} labels={labels} h={140} goal={g.type === 'milestone' ? g.milestones.length : g.target} /><div className="legend mt-s"><span><i />{g.unit === '₹' ? 'Saved (₹)' : 'Progress'}</span><span><i className="s" style={{ height: 2 }} />Target</span></div>{g.progressLog.length > 0 && <div className="xs faint mt-s">Last logged {fmtDay(g.progressLog[g.progressLog.length - 1].date)}{g.progressLog[g.progressLog.length - 1].note ? ` · “${g.progressLog[g.progressLog.length - 1].note}”` : ''}</div>}</Card></Section>
        </div>
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Card><div className="eyebrow mb-s">Next action</div>{nextTask ? <TRow t={nextTask} /> : <div className="task" style={{ padding: 0, cursor: 'default' }}><div className="grow"><div className="t">{g.nextAction || 'Define the next step'}</div><div className="meta">Set in Edit · or link a task</div></div></div>}<div className="row mt-s" style={{ gap: 6 }}><Btn label="Add task" ic="plus" kind="outline" cls="sm" onClick={() => setTaskForm(true)} /></div></Card>
          <Section title={`Linked · ${projects.length + tasks.length + habits.length + notes.length}`}><Card cls="pad-0"><List>
            {projects.map((pr) => <LRow key={pr.id} emoji={pr.emoji} t={pr.name} s={`Project · ${s.tasks.filter((t) => t.projectId === pr.id && !t.done).length} open`} goto="" extra={<Link href={`/projects/${pr.id}`} className="link xs">Open</Link>} />)}
            {tasks.slice(0, 4).map((t) => <LRow key={t.id} ic="check-square" tone={t.done ? 'success' : ''} t={<span className={t.done ? 'strike' : ''}>{t.title}</span>} s={`Task · ${t.done ? 'done' : t.due ? fmtDay(t.due) : 'no date'}`} extra={<Link href={`/tasks/${t.id}`} className="link xs">Open</Link>} />)}
            {habits.map((h) => <LRow key={h.id} ic={h.icon} t={h.name} s={`Habit · ${rateDays(h, 30).pct}% last 30 days`} extra={<Link href={`/habits/${h.id}`} className="link xs">Open</Link>} />)}
            {notes.slice(0, 2).map((n) => <LRow key={n.id} ic="note" t={n.title} s={`Note · ${n.ai ? 'AI-generated, edited' : n.folder}`} extra={<Link href={`/notes/${n.id}`} className="link xs">Open</Link>} />)}
            {projects.length + tasks.length + habits.length + notes.length === 0 && <div className="list-empty">Link tasks (Edit task → goal), habits (Edit habit → goal) or projects to show evidence here.</div>}
          </List></Card></Section>
          {blockers && <Section title="Blockers"><Card cls="soft"><div className="sm"><Icon name="alert" /> {blockers}</div></Card></Section>}
          <Btn label="Log progress" ic="plus" kind="primary" cls="block" onClick={() => setLog(true)} />
        </div>
      </div>
      <LogProgress g={g} open={log} onClose={() => setLog(false)} />
      <GoalForm open={edit} onClose={() => setEdit(false)} editId={g.id} />
      <MilestoneForm goal={g} open={!!msForm} onClose={() => setMsForm(false)} editId={msForm && msForm !== 'new' ? msForm : undefined} />
      <TaskForm open={taskForm} onClose={() => setTaskForm(false)} defaults={{ goalId: g.id }} />
      <Confirm open={del} onClose={() => setDel(false)} title={`Delete “${g.name}”?`} body="Linked tasks and habits are kept; only the goal is removed." onConfirm={() => { s.deleteGoal(g.id); s.toast('Goal deleted'); router.push(href('goals')); }} />
    </Screen>
  );
}

/* ================= MILESTONE (side panel over goal) ================= */
export function MilestoneDetailScreen({ goalId, id }: { goalId: string; id: string }) {
  const s = useStore(); const router = useRouter(); const g = s.goals.find((x) => x.id === goalId); const m = g?.milestones.find((x) => x.id === id);
  const [edit, setEdit] = useState(false); const [taskForm, setTaskForm] = useState(false); const back = () => router.push(`/goals/${goalId}`);
  if (!g || !m) return <GoalDetailScreen id={goalId} overlay={<Sheet title="Milestone" cls="side" onClose={back}><Empty ic="flag" title="Milestone not found" p="" /></Sheet>} />;
  const remaining = m.target ? Math.max(0, m.target - g.current) : 0; const pct = m.target ? Math.min(100, Math.round((g.current / m.target) * 100)) : m.reachedOn ? 100 : 0;
  const tasks = s.tasks.filter((t) => t.goalId === g.id && !t.done);
  const pace = g.progressLog.length > 1 ? (g.progressLog[g.progressLog.length - 1].value - g.progressLog[0].value) / Math.max(1, diffDays(g.progressLog[g.progressLog.length - 1].date, g.progressLog[0].date)) * 30 : 0;
  return (
    <GoalDetailScreen id={goalId} overlay={
      <Sheet title={`Milestone · ${m.title}`} cls="side" onClose={back} sub={g.name} footer={<><Btn label="Edit" ic="edit" kind="outline" onClick={() => setEdit(true)} /><span className="grow" /><Btn label={m.reachedOn ? 'Mark not reached' : 'Mark reached'} ic={m.reachedOn ? 'undo' : 'check'} kind="primary" onClick={() => { s.updateMilestone(g.id, m.id, { reachedOn: m.reachedOn ? undefined : todayISO() }); s.toast(m.reachedOn ? 'Milestone reopened' : `Milestone reached · ${m.title}`); back(); }} /></>}>
        <Card><div className="row between">{m.target ? <Kpi l="Remaining" v={`${g.unit}${remaining.toLocaleString('en-IN')}`} /> : <Kpi l="Status" v={m.reachedOn ? 'Reached' : 'Open'} />}<Kpi l="Target date" v={m.targetDate ? `${fmtShort(m.targetDate)} ${m.targetDate.slice(0, 4)}` : '—'} /></div><div className="mt-s"><Bar pct={pct} tone={m.reachedOn ? 'success' : ''} /></div><div className="xs muted mt-s">{m.reachedOn ? `Reached ${fmtDay(m.reachedOn, { relative: false })}` : m.target ? `${goalValueLabel(g)} of ${g.unit}${m.target.toLocaleString('en-IN')}${pace > 0 ? ` · on pace at ${g.unit}${Math.round(pace).toLocaleString('en-IN')}/month (≈ ${Math.ceil(remaining / pace)} months)` : ''}` : 'Tick it when the milestone is done.'}</div></Card>
        <div className="field"><div className="lbl">Tasks to reach it</div><div className="card pad-0"><List>{tasks.length === 0 ? <div className="list-empty">No open tasks linked to this goal.</div> : tasks.map((t) => <TRow key={t.id} t={t} />)}</List></div><div className="row mt-s"><Btn label="Add task" ic="plus" kind="ghost" cls="sm" onClick={() => setTaskForm(true)} /></div></div>
        <TextArea label="Notes" value={m.notes || ''} onChange={(v) => s.updateMilestone(g.id, m.id, { notes: v })} placeholder="Anything that affects this milestone" />
        <MilestoneForm goal={g} open={edit} onClose={() => setEdit(false)} editId={m.id} />
        <TaskForm open={taskForm} onClose={() => setTaskForm(false)} defaults={{ goalId: g.id }} />
      </Sheet>
    } />
  );
}

/* ================= REVIEW ================= */
export function GoalReviewScreen() {
  const s = useStore(); const router = useRouter();
  const active = s.goals.filter((g) => g.status === 'active'); const [decided, setDecided] = useState<Record<string, string>>({}); const [adjust, setAdjust] = useState<string | null>(null); const [newTarget, setNewTarget] = useState('');
  const counts = { on: active.filter((g) => goalStatus(g, s.habits) === 'on-track').length, behind: active.filter((g) => ['attention', 'behind'].includes(goalStatus(g, s.habits))).length, slip: active.filter((g) => goalStatus(g, s.habits) === 'slipping').length };
  const delta = (g: Goal) => { const logs = g.progressLog.filter((x) => diffDays(todayISO(), x.date) <= 31); const p = goalProgress(g, s.habits); if (logs.length >= 1 && g.type === 'numeric') { const before = g.progressLog.filter((x) => diffDays(todayISO(), x.date) > 31).slice(-1)[0]?.value ?? logs[0].value; return `${Math.round(((g.current - before) / Math.max(1, g.target)) * 100) >= 0 ? '+' : ''}${Math.round(((g.current - before) / Math.max(1, g.target)) * 100)} pts this month`; } return `${p}% · ${g.progressLog.length ? 'last logged ' + fmtDay(g.progressLog[g.progressLog.length - 1].date) : 'nothing logged yet'}`; };
  const decide = (g: Goal, d: string) => { setDecided((x) => ({ ...x, [g.id]: d })); if (d === 'Pause') s.updateGoal(g.id, { status: 'paused' }); };
  const finish = () => { active.forEach((g) => s.updateGoal(g.id, { reviewOn: addDays(todayISO(), g.reviewEvery === 'Weekly' ? 7 : g.reviewEvery === 'Monthly' ? 30 : 90) })); s.addActivity('Goal review completed', 'target', 'accent'); s.toast(`Review done · ${Object.keys(decided).length} decision${Object.keys(decided).length === 1 ? '' : 's'} recorded`); router.push(href('goals')); };
  return (
    <Screen nav="goals" top={{ title: 'Goal review', back: 'goals', sub: `${s.goals[0]?.reviewEvery || 'Monthly'} · ${fmtDay(todayISO(), { relative: false })} · ${active.length} goals` }} narrow>
      <Card><div className="eyebrow mb-s">Trajectory</div><div className="stat-inline"><div><span className="v" style={{ color: 'var(--success)' }}>{counts.on}</span><span className="l">on track</span></div><div><span className="v" style={{ color: 'var(--warning)' }}>{counts.behind}</span><span className="l">behind</span></div><div><span className="v" style={{ color: 'var(--danger)' }}>{counts.slip}</span><span className="l">slipping</span></div></div><div className="xs faint mt-s">Compared with each goal&apos;s own target line since it started.</div></Card>
      {active.map((g) => { const st = goalStatus(g, s.habits); const d = decided[g.id]; return (
        <Card key={g.id} style={{ opacity: d ? .75 : 1 }}><div className="row top" style={{ gap: 12 }}><Ring pct={goalProgress(g, s.habits)} size={48} stroke={5} label={goalProgress(g, s.habits) + '%'} tone={st === 'on-track' ? '' : st === 'slipping' ? 'danger' : 'warning'} /><div className="grow"><div className="row between"><div className="b">{g.name}</div>{d && <Chip tone={d === 'Keep' ? 'success' : d === 'Pause' ? '' : 'accent'} cls="sm">{d}</Chip>}</div><div className="sm muted">{delta(g)} · {STATUS_LABEL[st].toLowerCase()}</div>
          {adjust === g.id ? <div className="row mt-s" style={{ gap: 6 }}><TextInput value={newTarget} onChange={(v) => setNewTarget(v.replace(/[^\d.]/g, ''))} placeholder={String(g.target)} inputMode="decimal" sfx={g.unit || 'target'} cls="grow" /><DateInput value={g.due} onChange={(v) => s.updateGoal(g.id, { due: v })} /><Btn label="Save" kind="primary" cls="sm" onClick={() => { if (newTarget) s.updateGoal(g.id, { target: +newTarget }); decide(g, 'Adjusted'); setAdjust(null); }} /></div>
            : <div className="row mt-s" style={{ gap: 6, flexWrap: 'wrap' }}><Btn label="Keep" kind={d === 'Keep' ? 'primary' : 'soft'} cls="sm" onClick={() => decide(g, 'Keep')} /><Btn label="Adjust target" kind="outline" cls="sm" onClick={() => { setAdjust(g.id); setNewTarget(''); }} /><Btn label="Pause" kind="ghost" cls="sm" onClick={() => decide(g, 'Pause')} /></div>}
        </div></div></Card>); })}
      {active.length === 0 && <Card><Empty ic="target" title="No active goals to review" p="" /></Card>}
      <Btn label="Finish review" kind="primary" size="lg" cls="block" onClick={finish} />
    </Screen>
  );
}
