'use client';
/* ---------- TASKS: inbox, today, upcoming, projects, project detail (list + kanban), task detail, focus mode ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Bar, Btn, Card, Chip, EmojiBox, Icon, Kpi, List, Section, Segmented, Sheet, TaskRow, Tabs, Empty } from '@/components/ui';
import { Confirm, DateInput, Dropdown, Modal, Select, TextArea, TextInput, TimeInput } from '@/components/ui/controls';
import { addDays, fmtDay, fmtTime, minsToHm, todayISO, diffDays } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import type { Pri, Task, TaskStatus } from '@/store/types';
import { children, doneMin, doneToday, groupUpcoming, isInbox, isOverdue, isToday, openToday, plannedMin, subProgress } from '@/store/selectors';
import { taskVM } from './vm';

/* ================= shared pieces ================= */
const useTaskCounts = () => { const tasks = useStore((s) => s.tasks); const projects = useStore((s) => s.projects); return { inbox: tasks.filter(isInbox).length, today: tasks.filter((t) => (isToday(t) || isOverdue(t))).length, upcoming: tasks.filter((t) => t.due && t.due > todayISO() && !t.done).length, projects: projects.length }; };
function TaskTabs({ on }: { on: 'Inbox' | 'Today' | 'Upcoming' | 'Projects' }) {
  const c = useTaskCounts(); const router = useRouter();
  const go: Record<string, string> = { Inbox: href('tasks-inbox'), Today: href('tasks-today'), Upcoming: href('tasks-upcoming'), Projects: href('projects') };
  return <Tabs opts={[['Inbox', c.inbox], ['Today', c.today], ['Upcoming', c.upcoming], ['Projects', c.projects]]} on={on} onChange={(v) => router.push(go[v])} />;
}

export type Filters = { project: string; pri: string; due: string };
const EMPTY_F: Filters = { project: '', pri: '', due: '' };
function TaskFilters({ f, setF, view, setView }: { f: Filters; setF: (f: Filters) => void; view?: string; setView?: (v: string) => void }) {
  const projects = useStore((s) => s.projects);
  return (
    <div className="row between" style={{ gap: 8, flexWrap: 'wrap' }}>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <Select value={f.project} onChange={(v) => setF({ ...f, project: v })} ic="folder" options={[{ value: '', label: 'All projects' }, ...projects.map((p) => ({ value: p.id, label: `${p.emoji} ${p.name}` }))]} />
        <Select value={f.pri} onChange={(v) => setF({ ...f, pri: v })} ic="flag" options={[{ value: '', label: 'Any priority' }, { value: 'high', label: 'High' }, { value: 'med', label: 'Medium' }, { value: 'low', label: 'Low' }]} />
        <Select value={f.due} onChange={(v) => setF({ ...f, due: v })} ic="clock" options={[{ value: '', label: 'Any due date' }, { value: 'overdue', label: 'Overdue' }, { value: 'today', label: 'Due today' }, { value: 'week', label: 'This week' }]} />
        {(f.project || f.pri || f.due) && <button type="button" className="chip outline" onClick={() => setF(EMPTY_F)}><Icon name="x" />Clear</button>}
      </div>
      {view && setView && <div className="row only-tablet-up" style={{ gap: 4 }}><Segmented opts={['List', 'Board']} on={view} onChange={setView} /></div>}
    </div>
  );
}
const applyFilters = (ts: Task[], f: Filters) => ts.filter((t) => (!f.project || t.projectId === f.project) && (!f.pri || t.pri === f.pri) && (!f.due || (f.due === 'overdue' ? isOverdue(t) : f.due === 'today' ? isToday(t) : !!t.due && t.due <= addDays(todayISO(), 7))));

/** Task row bound to the store: checkbox toggles, click opens detail */
export function TRow({ t, showProject = true }: { t: Task; showProject?: boolean }) {
  const s = useStore(); const vm = taskVM(t, s);
  return <TaskRow t={vm} showProject={showProject} href={`/tasks/${t.id}`} onToggle={() => { s.toggleTask(t.id); if (!t.done) s.toast(`Completed · ${t.title}`, { undo: () => s.toggleTask(t.id) }); }} />;
}

/* ---------- Add / edit task modal ---------- */
export function TaskForm({ open, onClose, defaults = {}, editId }: { open: boolean; onClose: () => void; defaults?: Partial<Task>; editId?: string }) {
  const s = useStore(); const existing = editId ? s.tasks.find((t) => t.id === editId) : undefined;
  const [title, setTitle] = useState(''); const [projectId, setProjectId] = useState(''); const [due, setDue] = useState(''); const [time, setTime] = useState(''); const [pri, setPri] = useState<Pri | ''>(''); const [est, setEst] = useState(''); const [goalId, setGoalId] = useState(''); const [notes, setNotes] = useState(''); const [recurring, setRecurring] = useState(''); const [parentId, setParentId] = useState(''); const [err, setErr] = useState('');
  useEffect(() => { if (!open) return; const src = existing || defaults; setParentId(src.parentId || ''); setTitle(src.title || ''); setProjectId(src.projectId || ''); setDue(src.due || ''); setTime(src.time || ''); setPri(src.pri || ''); setEst(src.estMin ? String(src.estMin) : ''); setGoalId(src.goalId || ''); setNotes(src.notes || ''); setRecurring(src.recurring || ''); setErr(''); }, [open, editId]); // eslint-disable-line react-hooks/exhaustive-deps
  const save = () => {
    if (!title.trim()) { setErr('Give the task a title'); return; }
    const patch: Partial<Task> = { title: title.trim(), projectId: projectId || undefined, due: due || undefined, time: time || undefined, pri: pri || undefined, estMin: est ? +est : undefined, goalId: goalId || undefined, notes, recurring: recurring || undefined, parentId: parentId || undefined };
    if (existing) { s.updateTask(existing.id, patch); s.toast('Task updated'); } else { const t = s.addTask({ title: title.trim(), ...patch }); s.toast(`Task added · ${t.title}`, { undo: () => s.deleteTask(t.id) }); }
    onClose();
  };
  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit task' : parentId ? 'New subtask' : 'New task'} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label={existing ? 'Save' : 'Add task'} kind="primary" onClick={save} /></>}>
      <TextInput label="Title" value={title} onChange={setTitle} placeholder="What needs doing?" autoFocus err={err} onEnter={save} />
      <div className="split">
        <Select label="Project" ic="folder" value={projectId} onChange={setProjectId} options={[{ value: '', label: 'Inbox (no project)' }, ...s.projects.map((p) => ({ value: p.id, label: `${p.emoji} ${p.name}` }))]} />
        <Select label="Links to goal" ic="target" value={goalId} onChange={setGoalId} options={[{ value: '', label: 'None' }, ...s.goals.filter((g) => g.status === 'active').map((g) => ({ value: g.id, label: g.name }))]} />
      </div>
      <div className="split"><DateInput label="Due date" value={due} onChange={setDue} /><TimeInput label="Time" value={time} onChange={setTime} /></div>
      <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}><span className="sm muted" style={{ width: 70 }}>Quick date</span><button type="button" className="chip outline sm" onClick={() => setDue(todayISO())}>Today</button><button type="button" className="chip outline sm" onClick={() => setDue(addDays(todayISO(), 1))}>Tomorrow</button><button type="button" className="chip outline sm" onClick={() => setDue(addDays(todayISO(), 7))}>Next week</button><button type="button" className="chip outline sm" onClick={() => setDue('')}>No date</button></div>
      <div className="split">
        <div className="field"><div className="lbl">Priority</div><Segmented opts={['None', 'High', 'Medium', 'Low']} on={pri === 'high' ? 'High' : pri === 'med' ? 'Medium' : pri === 'low' ? 'Low' : 'None'} cls="block" onChange={(v) => setPri(v === 'High' ? 'high' : v === 'Medium' ? 'med' : v === 'Low' ? 'low' : '')} /></div>
        <TextInput label="Estimate" value={est} onChange={(v) => setEst(v.replace(/\D/g, ''))} sfx="minutes" ic="timer" inputMode="numeric" placeholder="30" />
      </div>
      <div className="split"><Select label="Repeat" ic="repeat" value={recurring} onChange={setRecurring} options={[{ value: '', label: 'Does not repeat' }, 'Daily', 'Weekly', 'Monthly', 'Quarterly']} /><Select label="Subtask of" ic="layers" value={parentId} onChange={setParentId} options={[{ value: '', label: 'None — top-level task' }, ...s.tasks.filter((t) => !t.parentId && !t.done && t.id !== editId).map((t) => ({ value: t.id, label: t.title }))]} hint={parentId ? 'Subtasks are full tasks: own status, priority and due date.' : undefined} /></div>
      <TextArea label="Notes" value={notes} onChange={setNotes} placeholder="Details, links, context…" />
    </Modal>
  );
}

/* ---------- Project form ---------- */
const EMOJIS = ['🚀', '💼', '🙂', '🏠', '📚', '👨‍👩‍👧', '🎯', '💡', '🏋️', '✈️', '🎨', '🧾', '🌱', '🛠️'];
export function ProjectForm({ open, onClose, editId }: { open: boolean; onClose: () => void; editId?: string }) {
  const s = useStore(); const router = useRouter(); const existing = editId ? s.projects.find((p) => p.id === editId) : undefined;
  const [name, setName] = useState(''); const [emoji, setEmoji] = useState('🚀'); const [goalId, setGoalId] = useState(''); const [err, setErr] = useState('');
  useEffect(() => { if (open) { setName(existing?.name || ''); setEmoji(existing?.emoji || '🚀'); setGoalId(existing?.goalId || ''); setErr(''); } }, [open, existing]);
  const save = () => { if (!name.trim()) { setErr('Name the project'); return; } if (existing) { s.updateProject(existing.id, { name: name.trim(), emoji, goalId: goalId || undefined }); s.toast('Project updated'); } else { const p = s.addProject({ name: name.trim(), emoji, goalId: goalId || undefined }); s.toast(`Project created · ${p.name}`); router.push(`/projects/${p.id}`); } onClose(); };
  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit project' : 'New project'} footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label={existing ? 'Save' : 'Create project'} kind="primary" onClick={save} /></>}>
      <div className="row" style={{ gap: 8 }}><div className="input" style={{ width: 64, justifyContent: 'center', flex: 'none', fontSize: 22 }}>{emoji}</div><TextInput cls="grow" label="" value={name} onChange={setName} placeholder="Project name" autoFocus err={err} onEnter={save} /></div>
      <div className="field"><div className="lbl">Icon</div><div className="chips">{EMOJIS.map((e) => <button type="button" key={e} className={`chip ${e === emoji ? 'accent' : 'outline'}`} style={{ fontSize: 16 }} onClick={() => setEmoji(e)}>{e}</button>)}</div></div>
      <Select label="Contributes to goal" ic="target" value={goalId} onChange={setGoalId} options={[{ value: '', label: 'None' }, ...s.goals.map((g) => ({ value: g.id, label: g.name }))]} hint="Completed tasks in this project show as evidence on the goal." />
    </Modal>
  );
}

/* ================= INBOX ================= */
export function TasksInboxScreen() {
  const s = useStore(); const [form, setForm] = useState(false); const [text, setText] = useState('');
  const inbox = s.tasks.filter(isInbox);
  const quick = () => { if (!text.trim()) return; const t = s.addTask({ title: text.trim() }); s.toast(`Added to inbox · ${t.title}`, { undo: () => s.deleteTask(t.id) }); setText(''); };
  const triage = (t: Task, patch: Partial<Task>, label: string) => { s.updateTask(t.id, patch); s.toast(`${t.title} → ${label}`, { undo: () => s.updateTask(t.id, { projectId: undefined, due: undefined, someday: undefined }) }); };
  return (
    <Screen nav="tasks" top={{ title: 'Inbox', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }}>
      <TaskTabs on="Inbox" />
      {inbox.length > 0 && <div className="banner info"><Icon name="inbox" /><div className="grow">{inbox.length} unsorted capture{inbox.length > 1 ? 's' : ''}. Give each a project or a date — or let Mitra suggest.</div><Btn label="Suggest" ic="sparkles" kind="soft" cls="sm" goto="ai-suggestions" /></div>}
      <Card cls="pad-0">
        {inbox.length === 0 ? <Empty ic="inbox" title="Inbox zero — nice." p="New captures from Quick add, ⌘K and the AI land here until you file them." action={<Btn label="Add task" ic="plus" kind="primary" onClick={() => setForm(true)} />} /> : (
          <List>{inbox.map((t) => <div key={t.id}><TRow t={t} /><div className="row" style={{ gap: 6, padding: '0 0 10px 34px', flexWrap: 'wrap' }}>
            <button type="button" className="chip outline" onClick={() => triage(t, { due: todayISO() }, 'Today')}><Icon name="sun" />Today</button>
            <button type="button" className="chip outline" onClick={() => triage(t, { due: addDays(todayISO(), 1) }, 'Tomorrow')}><Icon name="clock" />Tomorrow</button>
            {s.projects.slice(0, 3).map((p) => <button type="button" key={p.id} className="chip outline" onClick={() => triage(t, { projectId: p.id }, p.name)}><Icon name="folder" />{p.name}</button>)}
            <button type="button" className="chip outline" onClick={() => triage(t, { someday: true }, 'Someday')}><Icon name="archive" />Someday</button>
          </div></div>)}</List>
        )}
      </Card>
      <div className="composer"><Icon name="plus" cls="muted" /><input className="ph-in" value={text} onChange={(e) => setText(e.target.value)} placeholder="Add to inbox… (press Enter)" onKeyDown={(e) => { if (e.key === 'Enter') quick(); }} />{text && <Btn ic="arrow-right" kind="primary" cls="sm" onClick={quick} />}</div>
      <TaskForm open={form} onClose={() => setForm(false)} />
    </Screen>
  );
}

/* ================= TODAY ================= */
export function TasksTodayScreen({ overlay }: { overlay?: ReactNode }) {
  const s = useStore(); const [form, setForm] = useState(false); const [f, setF] = useState<Filters>(EMPTY_F); const [view, setView] = useState('Board'); const [showDone, setShowDone] = useState(true);
  const overdue = applyFilters(s.tasks.filter((t) => isOverdue(t)), f);
  const today = applyFilters(s.tasks.filter((t) => isToday(t)), f).sort((a, b) => Number(a.done) - Number(b.done) || (a.time || '99').localeCompare(b.time || '99'));
  const open = openToday(s.tasks); const done = doneToday(s.tasks);
  const planned = plannedMin(s.tasks), dm = doneMin(s.tasks);
  const first = open.sort((a, b) => ({ high: 0, med: 1, low: 2, undefined: 3 } as Record<string, number>)[String(a.pri)] - ({ high: 0, med: 1, low: 2, undefined: 3 } as Record<string, number>)[String(b.pri)])[0];
  const rescheduleAll = () => { const ids = overdue.map((t) => t.id); ids.forEach((id) => s.updateTask(id, { due: todayISO() })); s.toast(`${ids.length} overdue task${ids.length > 1 ? 's' : ''} moved to today`, { undo: () => ids.forEach((id, i) => s.updateTask(id, { due: overdue[i].due })) }); };
  return (
    <Screen nav="tasks" top={{ title: 'Tasks', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }} overlay={overlay}>
      <TaskTabs on="Today" /><TaskFilters f={f} setF={setF} view={view} setView={setView} />
      {view === 'Board' ? <Board tasks={[...overdue, ...today]} /> : (
        <div className="split side">
          <div className="col" style={{ gap: 'var(--gap)' }}>
            {overdue.length > 0 && <Section title="Overdue" action="Reschedule all to today" goto="" ><Card cls="pad-0"><List>{overdue.map((t) => <TRow key={t.id} t={t} />)}</List><div style={{ padding: '6px 12px 10px' }}><Btn label="Move all to today" ic="clock" kind="soft" cls="sm" onClick={rescheduleAll} /></div></Card></Section>}
            <Section title="Today" action={`${open.length} open · ${done.length} done`}>
              <Card cls="pad-0">
                {today.length === 0 ? <Empty ic="check-square" title="No tasks for today" p="Add one, or pull something forward from Upcoming. Mitra will keep this screen calm." action={<Btn label="Add task" ic="plus" kind="primary" onClick={() => setForm(true)} />} /> :
                  <List>{today.filter((t) => showDone || !t.done).map((t) => <TRow key={t.id} t={t} />)}</List>}
                {done.length > 0 && <div style={{ padding: '6px 12px 10px' }}><a className="link sm" onClick={() => setShowDone((v) => !v)}>{showDone ? 'Hide' : 'Show'} {done.length} completed</a></div>}
              </Card>
            </Section>
          </div>
          <div className="col aside-desktop" style={{ gap: 'var(--gap)' }}>
            <Card cls="keep"><div className="row between"><div><Kpi l="Planned today" v={minsToHm(planned) || '0m'} /></div><div><Kpi l="Done" v={minsToHm(dm) || '0m'} /></div></div><div className="mt-s"><Bar pct={planned ? (dm / planned) * 100 : 0} /></div><div className="xs muted mt-s">Estimates come from your own task estimates.</div></Card>
            <Card cls="keep"><div className="eyebrow mb-s">Focus</div><div className="b">{first ? <>Start with {first.title.toLowerCase().startsWith('the') ? '' : 'the '}{first.title}{first.time ? ` — due ${fmtTime(first.time)}` : ''}{first.estMin ? `, ${minsToHm(first.estMin)}` : ''}.</> : 'All done for today. Enjoy the evening.'}</div><div className="row mt-s" style={{ gap: 6 }}><Btn label="Focus mode" ic="play" kind="primary" cls="sm" goto="focus" /><Btn label="Ask Mitra to plan" ic="sparkles" kind="outline" cls="sm" goto="ai" /></div></Card>
          </div>
        </div>
      )}
      <TaskForm open={form} onClose={() => setForm(false)} defaults={{ due: todayISO() }} />
    </Screen>
  );
}

/* ================= UPCOMING ================= */
export function TasksUpcomingScreen() {
  const s = useStore(); const [form, setForm] = useState(false); const [f, setF] = useState<Filters>(EMPTY_F);
  const groups = groupUpcoming(applyFilters(s.tasks, f)); const keys = Object.keys(groups).sort((a, b) => (a === 'later' ? 1 : b === 'later' ? -1 : a === 'next-week' ? 1 : b === 'next-week' ? -1 : a.localeCompare(b)));
  const label = (k: string) => k === 'next-week' ? 'Next week' : k === 'later' ? 'Later' : `${fmtDay(k)}${diffDays(k, todayISO()) === 1 ? ' · ' + fmtDay(k, { relative: false }) : ''}`;
  return (
    <Screen nav="tasks" top={{ title: 'Upcoming', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }}>
      <TaskTabs on="Upcoming" /><TaskFilters f={f} setF={setF} />
      {keys.length === 0 && <Card><Empty ic="calendar" title="Nothing scheduled — that is fine." p="Tasks with a future due date show up here grouped by day." action={<Btn label="Schedule a task" ic="plus" kind="primary" onClick={() => setForm(true)} />} /></Card>}
      {keys.map((k) => <Section key={k} title={label(k)}><Card cls="pad-0"><List>{groups[k].map((t) => <TRow key={t.id} t={t} />)}</List></Card></Section>)}
      <TaskForm open={form} onClose={() => setForm(false)} defaults={{ due: addDays(todayISO(), 1) }} />
    </Screen>
  );
}

/* ================= PROJECTS ================= */
export function ProjectsScreen() {
  const s = useStore(); const [form, setForm] = useState(false);
  const stats = (id: string) => { const ts = s.tasks.filter((t) => t.projectId === id); const done = ts.filter((t) => t.done).length; return { open: ts.length - done, pct: ts.length ? Math.round((done / ts.length) * 100) : 0 }; };
  return (
    <Screen nav="tasks" top={{ title: 'Projects', actions: [{ ic: 'plus', label: 'New project', kind: 'primary', onClick: () => setForm(true) }] }}>
      <TaskTabs on="Projects" />
      <div className="note-grid">
        {s.projects.map((p) => { const st = stats(p.id); const g = s.goals.find((x) => x.id === p.goalId); return <Link key={p.id} href={`/projects/${p.id}`} className="note-card"><div className="row between"><EmojiBox e={p.emoji} />{g && <Chip tone="accent" ic="target" cls="sm">{g.name.replace(/^Build |^Ship /, '').slice(0, 22)}</Chip>}</div><div className="t">{p.name}</div><div className="sm muted">{st.open} open task{st.open === 1 ? '' : 's'}</div><div className="f" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}><Bar pct={st.pct} cls="thin" /><span>{st.pct}% complete</span></div></Link>; })}
        <button type="button" className="note-card" style={{ alignItems: 'center', justifyContent: 'center', borderStyle: 'dashed', color: 'var(--muted)' }} onClick={() => setForm(true)}><Icon name="plus" /><div className="t" style={{ color: 'var(--muted)' }}>New project</div></button>
      </div>
      <ProjectForm open={form} onClose={() => setForm(false)} />
    </Screen>
  );
}

/* ---------- Kanban board with drag & drop ---------- */
const COLS: { key: TaskStatus; label: string }[] = [{ key: 'todo', label: 'To do' }, { key: 'inprogress', label: 'In progress' }, { key: 'blocked', label: 'Blocked' }, { key: 'done', label: 'Done' }];
function Board({ tasks, onAdd }: { tasks: Task[]; onAdd?: (status: TaskStatus) => void }) {
  const s = useStore(); const [showSubs, setShowSubs] = useState(true); const subCount = tasks.filter((t) => t.parentId).length; tasks = showSubs ? tasks : tasks.filter((t) => !t.parentId); const [over, setOver] = useState<TaskStatus | null>(null); const [drag, setDrag] = useState<string | null>(null);
  const move = (id: string, status: TaskStatus) => { const t = s.tasks.find((x) => x.id === id); if (!t || t.status === status) return; s.updateTask(id, { status, done: status === 'done', completedAt: status === 'done' ? todayISO() : undefined }); if (status === 'done') s.addActivity(`${t.title} ✓`, 'check-square', 'success', 'tasks'); };
  return (<>
    {subCount > 0 && <div className="row" style={{ gap: 8 }}><span className="xs muted">{subCount} subtask{subCount > 1 ? 's' : ''} on the board</span><a className="link xs" onClick={() => setShowSubs((v) => !v)}>{showSubs ? 'Hide subtasks' : 'Show subtasks'}</a></div>}
    <div className="kanban">
      {COLS.map((c) => { const ts = tasks.filter((t) => (t.status || 'todo') === c.key); return (
        <div key={c.key} className={`col ${over === c.key ? 'over' : ''}`} onDragOver={(e) => { e.preventDefault(); setOver(c.key); }} onDragLeave={() => setOver(null)} onDrop={(e) => { e.preventDefault(); const id = e.dataTransfer.getData('text/plain') || drag; if (id) move(id, c.key); setOver(null); setDrag(null); }}>
          <h4>{c.label}<span className="num">{ts.length}</span></h4>
          {ts.map((t) => <Link key={t.id} href={`/tasks/${t.id}`} className={`kc ${drag === t.id ? 'dragging' : ''}`} draggable onDragStart={(e) => { e.dataTransfer.setData('text/plain', t.id); setDrag(t.id); }} onDragEnd={() => setDrag(null)}>
            <div className={t.done ? 'strike' : ''}>{t.title}</div>
            <div className="meta">{t.pri === 'high' && <Chip tone="danger" cls="sm">High</Chip>}{t.pri === 'med' && <Chip tone="warning" cls="sm">Med</Chip>}{t.estMin && <span><Icon name="clock" /> {minsToHm(t.estMin)}</span>}{subProgress(s.tasks, t.id).total > 0 && <span><Icon name="check-square" /> {subProgress(s.tasks, t.id).done}/{subProgress(s.tasks, t.id).total}</span>}{t.due && <span>{fmtDay(t.due)}</span>}</div>
            {t.parentId && <div className="xs muted" style={{ marginTop: 2 }}>↳ {s.tasks.find((p) => p.id === t.parentId)?.title}</div>}
            <div className="row" style={{ gap: 4, marginTop: 2 }}>{c.key !== 'todo' && <button type="button" className="chip sm outline" onClick={(e) => { e.preventDefault(); move(t.id, COLS[COLS.findIndex((x) => x.key === c.key) - 1].key); }}>←</button>}{c.key !== 'done' && <button type="button" className="chip sm outline" onClick={(e) => { e.preventDefault(); move(t.id, COLS[COLS.findIndex((x) => x.key === c.key) + 1].key); }}>→</button>}</div>
          </Link>)}
          {ts.length === 0 && <div className="list-empty" style={{ border: '1px dashed var(--border)', borderRadius: 8 }}>Drop here</div>}
          {onAdd && <button type="button" className="chip outline" style={{ alignSelf: 'flex-start' }} onClick={() => onAdd(c.key)}><Icon name="plus" />Add</button>}
        </div>
      ); })}
    </div>
  </>);
}

export function ProjectDetailScreen({ id }: { id: string }) {
  const s = useStore(); const router = useRouter(); const p = s.projects.find((x) => x.id === id);
  const [view, setView] = useState('Board'); const [form, setForm] = useState<false | Partial<Task>>(false); const [edit, setEdit] = useState(false); const [del, setDel] = useState(false);
  if (!p) return <Screen nav="tasks" top={{ title: 'Project not found', back: 'projects' }}><Card><Empty ic="folder" title="This project doesn't exist" p="It may have been deleted." action={<Btn label="All projects" goto="projects" />} /></Card></Screen>;
  const ts = s.tasks.filter((t) => t.projectId === id); const done = ts.filter((t) => t.done).length; const pct = ts.length ? Math.round((done / ts.length) * 100) : 0; const g = s.goals.find((x) => x.id === p.goalId);
  const notes = s.notes.filter((n) => n.tags.includes(p.name.toLowerCase().replace(/\s+/g, '-')) || n.body.includes(p.name)).length;
  return (
    <Screen nav="tasks" top={{ title: p.name, back: 'projects', eyebrow: 'Project', actions: [{ el: <Dropdown items={[{ label: 'Edit project', ic: 'edit', onClick: () => setEdit(true) }, { label: 'Add task', ic: 'plus', onClick: () => setForm({ projectId: id }) }, { label: 'Delete project', ic: 'trash', danger: true, onClick: () => setDel(true) }]} /> }] }}>
      <div className="row wrap" style={{ gap: 8 }}>{g && <Link href={`/goals/${g.id}`} className="chip accent"><Icon name="target" />{g.name}</Link>}{g && <Chip ic="flag">Due {fmtDay(g.due, { relative: false })}</Chip>}<Chip>{ts.length - done} open · {done} done</Chip>{notes > 0 && <Chip ic="note">Notes {notes}</Chip>}</div>
      <Bar pct={pct} />
      <div className="row between"><Segmented opts={['List', 'Board']} on={view} onChange={setView} /><Btn label="Add task" ic="plus" kind="primary" cls="sm" onClick={() => setForm({ projectId: id })} /></div>
      {view === 'Board' ? <Board tasks={ts} onAdd={(status) => setForm({ projectId: id, status })} /> : (
        <Card cls="pad-0">{ts.length === 0 ? <Empty ic="check-square" title="No tasks yet" p="Add the first task to this project." action={<Btn label="Add task" ic="plus" kind="primary" onClick={() => setForm({ projectId: id })} />} /> : <List>{[...ts].sort((a, b) => Number(a.done) - Number(b.done)).map((t) => <TRow key={t.id} t={t} showProject={false} />)}</List>}</Card>
      )}
      <TaskForm open={!!form} onClose={() => setForm(false)} defaults={form || {}} />
      <ProjectForm open={edit} onClose={() => setEdit(false)} editId={id} />
      <Confirm open={del} onClose={() => setDel(false)} title={`Delete “${p.name}”?`} body={`${ts.length} task${ts.length === 1 ? '' : 's'} will be moved to Inbox, not deleted.`} onConfirm={() => { s.deleteProject(id); s.toast('Project deleted'); router.push(href('projects')); }} />
    </Screen>
  );
}

/* ================= TASK DETAIL (side panel over Tasks · Today) ================= */
export function TaskDetailScreen({ id }: { id: string }) {
  const s = useStore(); const router = useRouter(); const t = s.tasks.find((x) => x.id === id);
  const [sub, setSub] = useState(''); const [edit, setEdit] = useState(false); const [del, setDel] = useState(false); const [resched, setResched] = useState(false);
  const back = () => router.push(href('tasks-today'));
  if (!t) return <TasksTodayScreen overlay={<Sheet title="Task" cls="side" back="tasks-today"><Empty ic="check-square" title="Task not found" p="It may have been deleted." /></Sheet>} />;
  const project = s.projects.find((p) => p.id === t.projectId); const goal = s.goals.find((g) => g.id === t.goalId); const kids = children(s.tasks, t.id); const subDone = kids.filter((x) => x.done).length; const parent = t.parentId ? s.tasks.find((x) => x.id === t.parentId) : undefined; const [subForm, setSubForm] = useState(false);
  const addSub = () => { if (!sub.trim()) return; const c = s.addSubtask(t.id, sub.trim(), { due: t.due }); s.toast(`Subtask added · ${c.title}`); setSub(''); };
  const complete = () => { s.toggleTask(t.id); s.toast(t.done ? 'Marked as open' : `Completed · ${t.title}`, { undo: () => s.toggleTask(t.id) }); back(); };
  const linked = s.notes.filter((n) => n.body.includes(t.title) || n.title.includes(t.title.split(' ')[0]) && n.tags.length).slice(0, 2);
  return (
    <TasksTodayScreen overlay={
      <Sheet title="Task" cls="side" back="tasks-today" head={<Dropdown items={[{ label: 'Edit', ic: 'edit', onClick: () => setEdit(true) }, { label: 'Duplicate', ic: 'columns', onClick: () => { const { id: _id, createdAt: _c, completedAt: _ca, ...rest } = t; const c = s.addTask({ ...rest, title: t.title + ' (copy)', done: false, status: 'todo' }); s.toast('Duplicated'); router.push(`/tasks/${c.id}`); } }, { label: 'Delete', ic: 'trash', danger: true, onClick: () => setDel(true) }]} kind="ghost" />}
        footer={<><Btn label="Focus" ic="play" kind="outline" onClick={() => router.push(`/focus?task=${t.id}`)} /><Btn label="Reschedule" ic="clock" kind="outline" onClick={() => setResched((v) => !v)} /><span className="grow" /><Btn label={t.done ? 'Reopen' : 'Complete'} ic="check" kind="primary" onClick={complete} /></>}>
        <div className="row top" style={{ gap: 12 }}><div className={`task ${t.done ? 'done' : ''}`} style={{ padding: 0 }}><div className="cb" onClick={() => s.toggleTask(t.id)}><Icon name="check" /></div></div><div className="grow"><h2 style={{ fontSize: 19 }} className={t.done ? 'strike' : ''}>{t.title}</h2><div className="row wrap mt-s" style={{ gap: 6 }}>{t.pri && <Chip tone={t.pri === 'high' ? 'danger' : t.pri === 'med' ? 'warning' : 'info'} ic="flag">{{ high: 'High', med: 'Medium', low: 'Low' }[t.pri]} priority</Chip>}{project && <Link href={`/projects/${project.id}`} className="chip"><Icon name="folder" />{project.name}</Link>}{goal && <Link href={`/goals/${goal.id}`} className="chip accent"><Icon name="target" />{goal.name}</Link>}{t.source === 'ai' && <Chip tone="ai" ic="sparkles">Created by AI</Chip>}</div></div></div>
        {resched && <div className="card soft tight"><div className="sm b mb-s">Reschedule to</div><div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>{[['Today', todayISO()], ['Tomorrow', addDays(todayISO(), 1)], ['Next Monday', addDays(todayISO(), (8 - new Date().getDay()) % 7 || 7)], ['Next week', addDays(todayISO(), 7)], ['No date', '']].map(([l, d]) => <button type="button" key={l} className="chip outline" onClick={() => { s.updateTask(t.id, { due: d || undefined }); s.toast(`Rescheduled · ${l}`); setResched(false); }}>{l}</button>)}<DateInput cls="grow" value={t.due || ''} onChange={(v) => { s.updateTask(t.id, { due: v || undefined }); setResched(false); }} /></div></div>}
        <div className="card pad-0 keep"><List>
          <div className="setrow" onClick={() => setResched(true)}><div className="iconbox"><Icon name="clock" /></div><div className="grow"><div className="t">Due</div><div className="s">{t.due ? `${fmtDay(t.due)}${t.time ? ' · ' + fmtTime(t.time) : ''}` : 'No date'}{isOverdue(t) && <span style={{ color: 'var(--danger)' }}> · overdue</span>}</div></div><div className="r"><Icon name="chevron-right" /></div></div>
          <div className="setrow" onClick={() => setEdit(true)}><div className="iconbox"><Icon name="timer" /></div><div className="grow"><div className="t">Estimate</div><div className="s">{t.estMin ? `${minsToHm(t.estMin)} · effort: ${t.estMin > 90 ? 'high' : t.estMin > 30 ? 'medium' : 'low'}` : 'Not set'}</div></div><div className="r"><Icon name="chevron-right" /></div></div>
          <div className="setrow" onClick={() => setEdit(true)}><div className="iconbox"><Icon name="repeat" /></div><div className="grow"><div className="t">Repeat</div><div className="s">{t.recurring || 'Does not repeat'}</div></div><div className="r"><Icon name="chevron-right" /></div></div>
          <div className="setrow"><div className="iconbox"><Icon name="layers" /></div><div className="grow"><div className="t">Status</div></div><Segmented opts={['To do', 'Doing', 'Blocked', 'Done']} on={{ todo: 'To do', inprogress: 'Doing', blocked: 'Blocked', done: 'Done' }[t.status || 'todo']} onChange={(v) => { const st = ({ 'To do': 'todo', Doing: 'inprogress', Blocked: 'blocked', Done: 'done' } as Record<string, TaskStatus>)[v]; s.updateTask(t.id, { status: st, done: st === 'done', completedAt: st === 'done' ? todayISO() : undefined }); }} /></div>
        </List></div>
        {parent && <Link href={`/tasks/${parent.id}`} className="card soft tight row" style={{ gap: 8 }}><Icon name="layers" /><div className="sm grow">Subtask of <b>{parent.title}</b> · {subProgress(s.tasks, parent.id).done}/{subProgress(s.tasks, parent.id).total} done</div><Icon name="chevron-right" cls="chev" /></Link>}
        <div className="field"><div className="row between"><div className="lbl">Subtasks · {subDone} of {kids.length}{kids.length > 0 && kids.every((k) => k.done) && !t.done ? ' · all done' : ''}</div><a className="link xs" onClick={() => setSubForm(true)}>Detailed subtask</a></div>
          {kids.length > 0 && <div className="mb-s"><Bar pct={(subDone / kids.length) * 100} cls="thin" tone={subDone === kids.length ? 'success' : ''} /></div>}
          <div className="card pad-0 keep"><List>
          {kids.map((k) => <TRow key={k.id} t={k} showProject={false} />)}
          <div className="row" style={{ padding: '6px 12px' }}><Icon name="plus" cls="muted" /><input className="ph-in" value={sub} onChange={(e) => setSub(e.target.value)} placeholder="Add a subtask… (Enter) — it becomes a real task you can open, prioritise and move on the board" onKeyDown={(e) => { if (e.key === 'Enter') addSub(); }} /></div>
        </List></div></div>
        <TextArea label="Notes" value={t.notes} onChange={(v) => s.updateTask(t.id, { notes: v })} placeholder="Details, links, context…" />
        <div className="field"><div className="lbl">Linked</div><div className="chips">{goal && <Link href={`/goals/${goal.id}`} className="chip outline"><Icon name="target" />Goal: {goal.name.slice(0, 30)}</Link>}{t.amount && <Chip tone="outline" ic="wallet">₹{t.amount.toLocaleString('en-IN')}</Chip>}{linked.map((n) => <Link key={n.id} href={`/notes/${n.id}`} className="chip outline"><Icon name="note" />{n.title}</Link>)}{!goal && !t.amount && linked.length === 0 && <span className="xs muted">Nothing linked yet — set a goal in Edit.</span>}</div></div>
        <div className="xs faint">Created {fmtDay(t.createdAt.slice(0, 10))} by {t.source === 'ai' ? 'Mitra (approved by you)' : 'you'}{t.completedAt ? ` · completed ${fmtDay(t.completedAt)}` : ''}</div>
        <TaskForm open={edit} onClose={() => setEdit(false)} editId={t.id} />
        <TaskForm open={subForm} onClose={() => setSubForm(false)} defaults={{ parentId: t.id, projectId: t.projectId, goalId: t.goalId, due: t.due }} />
        <Confirm open={del} onClose={() => setDel(false)} title={`Delete “${t.title}”?`} body={kids.length ? `${kids.length} subtask${kids.length > 1 ? 's' : ''} will be deleted too. You can undo for a few seconds.` : 'You can undo for a few seconds.'} onConfirm={() => { const copy = { ...t }; s.deleteTask(t.id); s.toast('Task deleted', { undo: () => s.addTask(copy) }); back(); }} />
      </Sheet>
    } />
  );
}

/* ================= FOCUS MODE ================= */
export function FocusScreen({ taskId }: { taskId?: string }) {
  const s = useStore(); const router = useRouter();
  const queue = useMemo(() => { const open = openToday(s.tasks).sort((a, b) => ({ high: 0, med: 1, low: 2, undefined: 3 } as Record<string, number>)[String(a.pri)] - ({ high: 0, med: 1, low: 2, undefined: 3 } as Record<string, number>)[String(b.pri)]); const first = taskId ? s.tasks.find((t) => t.id === taskId) : undefined; return first ? [first, ...open.filter((t) => t.id !== first.id)] : open; }, [s.tasks, taskId]);
  const [idx, setIdx] = useState(0); const cur = queue[idx];
  const total = (cur?.estMin || s.settings.focusMinutes) * 60;
  const [left, setLeft] = useState(total); const [running, setRunning] = useState(true); const [sessions, setSessions] = useState(0);
  useEffect(() => { setLeft((cur?.estMin || s.settings.focusMinutes) * 60); setRunning(true); }, [cur?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (!running) return; const i = setInterval(() => setLeft((l) => { if (l <= 1) { setRunning(false); setSessions((n) => n + 1); s.toast('Session complete — take a short break', { tone: 'accent' }); return 0; } return l - 1; }), 1000); return () => clearInterval(i); }, [running]); // eslint-disable-line react-hooks/exhaustive-deps
  const mm = String(Math.floor(left / 60)).padStart(2, '0'), ss = String(left % 60).padStart(2, '0');
  const done = () => { if (!cur) return; const spent = Math.round((total - left) / 60); s.toggleTask(cur.id); s.addActivity(`Focus · ${cur.title} · ${spent} min`, 'timer', 'accent'); s.toast(`Completed · ${cur.title} · ${spent} min logged`); if (idx + 1 < queue.length) setIdx((i) => i); else router.push(href('tasks-today')); };
  const skip = () => { if (idx + 1 < queue.length) setIdx((i) => i + 1); else { s.toast('That was the last task for today'); router.push(href('tasks-today')); } };
  const pct = total ? ((total - left) / total) * 100 : 0;
  if (!cur) return <div className="focus"><div className="top"><Btn ic="x" kind="ghost" goto="tasks-today" /><span /><span /></div><div className="eyebrow">Focus</div><div className="task-title">Nothing left for today</div><div className="sm muted">Add a task or pull one from Upcoming to start a session.</div><Btn label="Back to tasks" kind="primary" size="lg" cls="round" goto="tasks-today" /></div>;
  return (
    <div className="focus">
      <div className="top"><Btn ic="x" kind="ghost" goto="tasks-today" title="Exit (Esc)" /><Chip>{idx + 1} of {queue.length} · Today{sessions ? ` · ${sessions} session${sessions > 1 ? 's' : ''}` : ''}</Chip><Dropdown kind="ghost" cls="" items={[{ label: 'Reset timer', ic: 'refresh', onClick: () => { setLeft(total); setRunning(true); } }, { label: '+5 minutes', ic: 'plus', onClick: () => setLeft((l) => l + 300) }, { label: 'Open task', ic: 'check-square', onClick: () => router.push(`/tasks/${cur.id}`) }]} /></div>
      <div className="eyebrow">Now</div><div className="task-title">{cur.title}</div>
      <div className="timer num" style={{ color: left === 0 ? 'var(--success)' : undefined }}>{mm}:{ss}</div>
      <div className="seg-track" style={{ width: 200 }}>{[0, 1, 2, 3].map((i) => <i key={i} className={pct >= (i + 1) * 25 ? 'on' : ''} />)}</div>
      <div className="row" style={{ gap: 10 }}><Btn ic={running ? 'pause' : 'play'} kind="outline" size="lg" cls="round" onClick={() => setRunning((r) => !r)} title={running ? 'Pause (Space)' : 'Resume'} /><Btn label="Done" ic="check" kind="primary" size="lg" cls="round" onClick={done} /><Btn ic="arrow-right" kind="outline" size="lg" cls="round" title="Skip" onClick={skip} /></div>
      <div className="sm muted">{queue[idx + 1] ? <>Next: {queue[idx + 1].title}{queue[idx + 1].estMin ? ` · ${minsToHm(queue[idx + 1].estMin!)}` : ''} · </> : ''}Time is logged when you finish.</div>
      <FocusKeys onSpace={() => setRunning((r) => !r)} onEsc={() => router.push(href('tasks-today'))} />
    </div>
  );
}
function FocusKeys({ onSpace, onEsc }: { onSpace: () => void; onEsc: () => void }) {
  useEffect(() => { const k = (e: KeyboardEvent) => { if (e.key === ' ') { e.preventDefault(); onSpace(); } if (e.key === 'Escape') onEsc(); }; document.addEventListener('keydown', k); return () => document.removeEventListener('keydown', k); }, [onSpace, onEsc]);
  return null;
}
