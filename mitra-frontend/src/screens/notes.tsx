'use client';
/* ---------- NOTES: all notes, folders/tags, editor (autosave), journal (AI draft), search ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Btn, Card, Chip, Empty, Icon, LRow, List, MOODS, MoodRow, Section, Segmented } from '@/components/ui';
import { Confirm, Dropdown, Modal, Select, TextArea, TextInput } from '@/components/ui/controls';
import { addDays, dowMon, fmtDay, fmtLong, todayISO } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import { errMsg } from '@/lib/api';
import type { Note } from '@/store/types';
import { noteSearch, spentToday } from '@/store/selectors';
import { habitsDoneToday, habitsDueToday, openToday, doneToday, sleepMinutes, hm } from '@/store/selectors';

const FOLDERS = ['Work', 'Ideas', 'Learning', 'Journal', 'Personal'];
const FOLDER_ICON: Record<string, string> = { Work: 'folder', Ideas: 'folder', Learning: 'book', Journal: 'edit', Personal: 'user' };
const excerpt = (b: string) => b.replace(/^#+\s*/gm, '').replace(/\[\[|\]\]/g, '').replace(/- \[[ x]\] /g, '• ').slice(0, 180);

/** Render note body: headings, checklists (toggleable), [[wikilinks]] */
function NoteBody({ note }: { note: Note }) {
  const s = useStore(); const lines = note.body.split('\n');
  const toggle = (idx: number) => { const ls = [...lines]; ls[idx] = ls[idx].startsWith('- [x]') ? ls[idx].replace('- [x]', '- [ ]') : ls[idx].replace('- [ ]', '- [x]'); s.updateNote(note.id, { body: ls.join('\n') }); };
  const wl = (t: string) => t.split(/(\[\[[^\]]+\]\])/g).map((p, i) => p.startsWith('[[') ? (() => { const name = p.slice(2, -2); const target = s.notes.find((n) => n.title === name) || s.goals.find((g) => g.name === name) || s.tasks.find((t) => t.title === name); const url = target ? ('body' in target ? `/notes/${target.id}` : 'milestones' in target ? `/goals/${target.id}` : `/tasks/${target.id}`) : undefined; return url ? <Link key={i} href={url} className="wl">{name}</Link> : <span key={i} className="wl" title="No matching note, goal or task">{name}</span>; })() : <span key={i}>{p}</span>);
  return (
    <div className="editor">
      {lines.map((l, i) => l.startsWith('## ') ? <h2 key={i}>{l.slice(3)}</h2> : l.startsWith('# ') ? <h1 key={i}>{l.slice(2)}</h1> : l.startsWith('- [') ? <div key={i} className="cbx" style={{ marginLeft: 0, marginBottom: 6 }}><i className={l.startsWith('- [x]') ? 'on' : ''} onClick={() => toggle(i)} style={{ cursor: 'pointer' }}>{l.startsWith('- [x]') && <Icon name="check" />}</i><span className={l.startsWith('- [x]') ? 'strike' : ''}>{wl(l.slice(6))}</span></div> : l.startsWith('- ') ? <ul key={i} style={{ marginBottom: 4 }}><li>{wl(l.slice(2))}</li></ul> : l.trim() === '' ? <div key={i} style={{ height: 8 }} /> : <p key={i}>{wl(l)}</p>)}
    </div>
  );
}

const NoteCard = ({ n }: { n: Note }) => (
  <Link href={`/notes/${n.id}`} className="note-card"><div className="row between"><span className="xs muted">{n.folder}</span>{n.ai && <Chip tone="ai" ic="sparkles" cls="sm">AI draft</Chip>}</div><div className="t">{n.title}</div><div className="p">{excerpt(n.body)}</div><div className="f">{n.tags.slice(0, 3).map((t) => <span key={t}>#{t}</span>)}<span style={{ marginLeft: 'auto' }}>{fmtDay(n.updatedAt)}</span></div></Link>
);

/* ---------- New note modal ---------- */
export function NoteForm({ open, onClose, folder = 'Personal' }: { open: boolean; onClose: () => void; folder?: string }) {
  const s = useStore(); const router = useRouter(); const [title, setTitle] = useState(''); const [f, setF] = useState(folder); const [tags, setTags] = useState('');
  useEffect(() => { if (open) { setTitle(''); setF(folder); setTags(''); } }, [open, folder]);
  const save = () => { if (!title.trim()) return; const n = s.addNote({ title: title.trim(), folder: f, tags: tags.split(/[,\s]+/).map((t) => t.replace(/^#/, '').trim()).filter(Boolean), body: '' }); s.toast('Note created'); onClose(); router.push(`/notes/${n.id}`); };
  return <Modal open={open} onClose={onClose} title="New note" footer={<><Btn label="Cancel" kind="outline" onClick={onClose} /><span className="grow" /><Btn label="Create" kind="primary" onClick={save} /></>}><TextInput label="Title" value={title} onChange={setTitle} autoFocus placeholder="Note title" onEnter={save} /><div className="split"><Select label="Folder" ic="folder" value={f} onChange={setF} options={FOLDERS} /><TextInput label="Tags" value={tags} onChange={setTags} placeholder="ideas, money" ic="hash" /></div></Modal>;
}

/* ================= ALL NOTES ================= */
export function NotesScreen() {
  const s = useStore(); const [filter, setFilter] = useState('All'); const [view, setView] = useState('Grid'); const [form, setForm] = useState(false);
  const list = s.notes.filter((n) => filter === 'All' || n.folder === filter).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  return (
    <Screen nav="notes" top={{ title: 'Notes', actions: [{ ic: 'search', goto: 'notes-search' }, { ic: 'folder', goto: 'notes-folders', title: 'Folders & tags' }, { ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }}>
      <div className="row between"><div className="chips scroll">{['All', ...FOLDERS].map((f) => <button type="button" key={f} className={`pill-select ${filter === f ? 'on' : ''}`} onClick={() => setFilter(f)}>{f}{f !== 'All' && <span className="muted"> {s.notes.filter((n) => n.folder === f).length}</span>}</button>)}</div><Segmented opts={['Grid', 'List']} on={view} cls="only-tablet-up" onChange={setView} /></div>
      {list.length === 0 ? <Card><Empty ic="note" title="Write your first note" p="Or let Mitra draft today's journal." action={<div className="row" style={{ gap: 6 }}><Btn label="New note" ic="plus" kind="primary" onClick={() => setForm(true)} /><Btn label="Journal" ic="edit" kind="outline" goto="journal" /></div>} /></Card>
        : view === 'Grid' ? <div className="note-grid">{list.map((n) => <NoteCard key={n.id} n={n} />)}</div> : <Card cls="pad-0"><List>{list.map((n) => <LRow key={n.id} ic={FOLDER_ICON[n.folder] || 'note'} t={n.title} s={`${n.folder} · ${fmtDay(n.updatedAt)} · ${n.tags.map((t) => '#' + t).join(' ')}`} extra={<Link href={`/notes/${n.id}`} className="link xs">Open</Link>} />)}</List></Card>}
      <NoteForm open={form} onClose={() => setForm(false)} folder={filter === 'All' ? 'Personal' : filter} />
    </Screen>
  );
}

/* ================= FOLDERS & TAGS ================= */
export function NotesFoldersScreen() {
  const s = useStore(); const [sel, setSel] = useState<{ kind: 'folder' | 'tag'; v: string }>({ kind: 'folder', v: 'Work' }); const [form, setForm] = useState(false);
  const tags = useMemo(() => { const m: Record<string, number> = {}; s.notes.forEach((n) => n.tags.forEach((t) => { m[t] = (m[t] || 0) + 1; })); return Object.entries(m).sort((a, b) => b[1] - a[1]); }, [s.notes]);
  const list = s.notes.filter((n) => sel.kind === 'folder' ? n.folder === sel.v : n.tags.includes(sel.v));
  return (
    <Screen nav="notes" top={{ title: 'Folders & tags', back: 'notes', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setForm(true) }] }}>
      <div className="split side">
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Section title="Folders"><Card cls="pad-0"><List>{FOLDERS.map((f) => <div key={f} className={`lrow ${sel.kind === 'folder' && sel.v === f ? 'sel' : ''}`} style={{ padding: '11px 12px' }} onClick={() => setSel({ kind: 'folder', v: f })}><div className="iconbox"><Icon name={FOLDER_ICON[f]} /></div><div className="grow"><div className="t">{f}</div></div><div className="r"><div className="v">{s.notes.filter((n) => n.folder === f).length}</div></div><Icon name="chevron-right" cls="chev" /></div>)}</List></Card></Section>
          <Section title="Tags"><Card><div className="chips">{tags.map(([t, c]) => <button type="button" key={t} className={`chip ${sel.kind === 'tag' && sel.v === t ? 'accent' : 'outline'}`} onClick={() => setSel({ kind: 'tag', v: t })}>#{t} {c}</button>)}</div></Card></Section>
        </div>
        <div className="col" style={{ gap: 'var(--gap)' }}><Section title={sel.kind === 'folder' ? `In “${sel.v}”` : `Tagged #${sel.v}`} action="New note" goto=""><Card cls="pad-0">{list.length === 0 ? <div className="list-empty">Nothing here yet.</div> : <List>{list.map((n) => <LRow key={n.id} ic="note" t={n.title} s={`${fmtDay(n.updatedAt)} · ${n.tags.map((t) => '#' + t).join(' ')}`} extra={<Link href={`/notes/${n.id}`} className="link xs">Open</Link>} />)}</List>}<div style={{ padding: '6px 12px 10px' }}><Btn label="New note here" ic="plus" kind="ghost" cls="sm" onClick={() => setForm(true)} /></div></Card></Section></div>
      </div>
      <NoteForm open={form} onClose={() => setForm(false)} folder={sel.kind === 'folder' ? sel.v : 'Personal'} />
    </Screen>
  );
}

/* ================= EDITOR ================= */
export function NoteEditorScreen({ id }: { id: string }) {
  const s = useStore(); const router = useRouter(); const n = s.notes.find((x) => x.id === id); const [mode, setMode] = useState<'read' | 'write'>('read'); const [del, setDel] = useState(false); const [tag, setTag] = useState(''); const [saved, setSaved] = useState<'idle' | 'saving' | 'saved'>('idle'); const [suggest, setSuggest] = useState(true);
  useEffect(() => { if (saved === 'saving') { const t = setTimeout(() => setSaved('saved'), 500); return () => clearTimeout(t); } }, [saved, n?.body, n?.title]);
  if (!n) return <Screen nav="notes" top={{ title: 'Note not found', back: 'notes' }}><Card><Empty ic="note" title="This note doesn't exist" p="" action={<Btn label="All notes" goto="notes" />} /></Card></Screen>;
  const upd = (patch: Partial<Note>) => { s.updateNote(n.id, patch); setSaved('saving'); };
  const linked = { goals: s.goals.filter((g) => n.body.includes(`[[${g.name}]]`) || n.title.includes(g.name.split(' ').slice(-2).join(' '))), tasks: s.tasks.filter((t) => n.body.includes(`[[${t.title}]]`)), projects: s.projects.filter((p) => n.tags.includes(p.name.toLowerCase().replace(/\s+/g, '-')) || n.body.includes(p.name)) };
  const backlinks = s.notes.filter((x) => x.id !== n.id && x.body.includes(`[[${n.title}]]`));
  const words = n.body.split(/\s+/).filter(Boolean).length; const unchecked = n.body.split('\n').find((l) => l.startsWith('- [ ] '));
  const insert = (tok: string) => upd({ body: n.body + (n.body.endsWith('\n') || !n.body ? '' : '\n') + tok });
  return (
    <Screen nav="notes" top={{ title: 'Note', back: 'notes', actions: [{ ic: mode === 'read' ? 'edit' : 'eye', label: mode === 'read' ? 'Edit' : 'Preview', onClick: () => setMode(mode === 'read' ? 'write' : 'read') }, { el: <Dropdown items={[{ label: 'Move to folder…', ic: 'folder', onClick: () => { const i = FOLDERS.indexOf(n.folder); upd({ folder: FOLDERS[(i + 1) % FOLDERS.length] }); s.toast(`Moved to ${FOLDERS[(i + 1) % FOLDERS.length]}`); } }, { label: 'Duplicate', ic: 'columns', onClick: () => { const c = s.addNote({ ...n, id: undefined as unknown as string, title: n.title + ' (copy)' }); router.push(`/notes/${c.id}`); } }, { label: 'Copy link', ic: 'link', onClick: () => { navigator.clipboard?.writeText(`[[${n.title}]]`); s.toast(`Copied [[${n.title}]]`); } }, { label: 'Delete', ic: 'trash', danger: true, onClick: () => setDel(true) }]} /> }] }}>
      <div className="split side">
        <div className="col" style={{ gap: 12 }}>
          <div className="row between" style={{ flexWrap: 'wrap', gap: 6 }}><div className="row" style={{ gap: 6, flexWrap: 'wrap' }}><Chip tone="outline" ic={FOLDER_ICON[n.folder] || 'folder'}>{n.folder}</Chip>{n.tags.map((t) => <button type="button" key={t} className="chip outline" onClick={() => upd({ tags: n.tags.filter((x) => x !== t) })} title="Remove tag">#{t} <Icon name="x" /></button>)}<span className="chip outline" style={{ padding: '0 6px' }}><Icon name="plus" /><input className="ph-in" style={{ width: 70, fontSize: 11.5 }} value={tag} onChange={(e) => setTag(e.target.value)} placeholder="tag" onKeyDown={(e) => { if (e.key === 'Enter' && tag.trim()) { upd({ tags: [...new Set([...n.tags, tag.trim().replace(/^#/, '')])] }); setTag(''); } }} /></span>{n.ai && <Chip tone="ai" ic="sparkles" cls="sm">AI draft</Chip>}</div><span className="xs faint">{saved === 'saving' ? 'Saving…' : saved === 'saved' ? 'Saved' : `Edited ${fmtDay(n.updatedAt)}`}</span></div>
          {mode === 'write' && <div className="toolbar">{[['H1', '# '], ['H2', '## '], ['•', '- '], ['☐', '- [ ] '], ['🔗', '[[']].map(([b, tok]) => <button type="button" key={b} onClick={() => insert(tok)} title={`Insert ${b}`}>{b}</button>)}<button type="button" onClick={() => setMode('read')} title="Preview"><Icon name="eye" /></button></div>}
          <Card>
            {mode === 'write' ? <><input className="ph-in" style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-.03em', marginBottom: 10, width: '100%' }} value={n.title} onChange={(e) => upd({ title: e.target.value })} /><textarea className="editor-ta" value={n.body} onChange={(e) => upd({ body: e.target.value })} placeholder={'Write in Markdown-ish:\n# Heading · - bullet · - [ ] checklist · [[Link to a note, goal or task]]'} autoFocus /></>
              : <div onDoubleClick={() => setMode('write')} title="Double-click to edit"><div className="editor"><h1>{n.title}</h1></div>{n.body ? <NoteBody note={n} /> : <div className="list-empty">Empty note — <a className="link" onClick={() => setMode('write')}>start writing</a></div>}</div>}
            {suggest && unchecked && mode === 'read' && <div className="ai-block"><div className="row xs b" style={{ color: 'var(--accent)', gap: 6 }}><Icon name="sparkles" /> Suggested by Mitra · not saved yet</div><div className="sm mt-s">Turn “{unchecked.slice(6)}” into a task due {fmtDay(addDays(todayISO(), 2), { relative: false })}{linked.projects[0] ? ` in project ${linked.projects[0].name}` : ''}?</div><div className="row mt-s" style={{ gap: 6 }}><Btn label="Create task" kind="soft" cls="sm" onClick={() => { const t = s.addTask({ title: unchecked.slice(6), due: addDays(todayISO(), 2), projectId: linked.projects[0]?.id, source: 'ai' }); s.logAiAction({ action: 'Created task', detail: `${t.title} · from note “${n.title}”`, state: 'Approved by you', tone: 'success', revertible: true }); upd({ body: n.body.replace(unchecked, `${unchecked} → [[${t.title}]]`) }); s.toast(`Task created · ${t.title}`, { undo: () => s.deleteTask(t.id) }); setSuggest(false); }} /><Btn label="Dismiss" kind="ghost" cls="sm" onClick={() => setSuggest(false)} /></div></div>}
          </Card>
        </div>
        <div className="col aside-desktop" style={{ gap: 'var(--gap)' }}>
          <Section title="Linked"><Card cls="pad-0"><List>{linked.goals.map((g) => <LRow key={g.id} ic="target" tone="accent" t={g.name} s="Goal" extra={<Link href={`/goals/${g.id}`} className="link xs">Open</Link>} />)}{linked.projects.map((p) => <LRow key={p.id} ic="layers" t={p.name} s={`Project · ${s.tasks.filter((t) => t.projectId === p.id && !t.done).length} open`} extra={<Link href={`/projects/${p.id}`} className="link xs">Open</Link>} />)}{linked.tasks.map((t) => <LRow key={t.id} ic="check-square" t={t.title} s={`Task · ${t.due ? fmtDay(t.due) : 'no date'}`} extra={<Link href={`/tasks/${t.id}`} className="link xs">Open</Link>} />)}{linked.goals.length + linked.tasks.length + linked.projects.length === 0 && <div className="list-empty">Use [[Name]] to link a goal, task or note.</div>}</List></Card></Section>
          <Section title={`Backlinks · ${backlinks.length}`}><Card cls="pad-0"><List>{backlinks.map((b) => <LRow key={b.id} ic="note" t={b.title} s="mentions this note" extra={<Link href={`/notes/${b.id}`} className="link xs">Open</Link>} />)}{backlinks.length === 0 && <div className="list-empty">No other note links here yet.</div>}</List></Card></Section>
          <div className="xs faint">Created {fmtDay(n.createdAt)} · edited {fmtDay(n.updatedAt)} · {words} words</div>
        </div>
      </div>
      <Confirm open={del} onClose={() => setDel(false)} title={`Delete “${n.title}”?`} body="You can undo for a few seconds." onConfirm={() => { const copy = { ...n }; s.deleteNote(n.id); s.toast('Note deleted', { undo: () => s.addNote(copy) }); router.push(href('notes')); }} />
    </Screen>
  );
}

/* ================= JOURNAL ================= */
function draftFor(day: string, s: ReturnType<typeof useStore.getState>) {
  const e = s.wellness[day]; const sleep = e ? sleepMinutes(e.sleepStart, e.sleepEnd) : 0; const done = s.tasks.filter((t) => t.completedAt === day); const open = s.tasks.filter((t) => t.due === day && !t.done); const habits = s.habits.filter((h) => (h.logs[day] || 0) >= h.target); const sp = spentToday(s.transactions.filter((t) => t.date === day).length ? s.transactions.filter((t) => t.date === day) : []);
  const txs = s.transactions.filter((t) => t.date === day && t.kind === 'expense');
  const p1 = `**Morning.** ${sleep ? `Slept ${hm(sleep)}${sleep < s.wellnessSettings.sleepTarget ? ' — under target' : ''}. ` : ''}${habits.length ? `${habits.map((h) => h.name.toLowerCase()).join(', ')} done. ` : 'No habits ticked yet. '}${done.length ? `Finished ${done.length} task${done.length > 1 ? 's' : ''}${done[0] ? ` including “${done[0].title}”` : ''}.` : ''}`;
  const p2 = `**Money.** ${txs.length ? `₹${sp.toLocaleString('en-IN')} so far: ${txs.map((t) => t.merchant || 'expense').join(', ')}.` : 'Nothing spent yet.'}`;
  const p3 = `**Still open.** ${open.length ? open.map((t) => `${t.title}${t.time ? ` (${t.time})` : ''}`).join(', ') + '.' : 'Nothing left for today.'}`;
  return { text: `${p1}\n\n${p2}\n\n${p3}`, sources: `${s.activity.filter((a) => a.date === day).length} activities, ${txs.length} expense${txs.length === 1 ? '' : 's'}, ${e ? 1 : 0} check-in` };
}
export function JournalScreen() {
  const s = useStore(); const router = useRouter(); const [day, setDay] = useState(todayISO()); const entry = s.notes.find((n) => n.journalDate === day);
  const [text, setText] = useState(''); const [mood, setMood] = useState(-1); const [draft, setDraft] = useState<null | { text: string; sources: string }>(null); const [kept, setKept] = useState(false);
  useEffect(() => { setText(entry?.body || ''); setMood(entry?.mood ?? (s.wellness[day]?.mood ?? -1)); setDraft(null); setKept(false); }, [day, entry?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const weekStart = addDays(day, -dowMon(day)); const week = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const [drafting, setDrafting] = useState(false);
  const gen = async () => { setDrafting(true); try { setDraft(s.aiEnabled ? await s.journalDraft(day) : draftFor(day, s)); s.logAiAction({ action: 'Drafted journal', detail: `Journal — ${fmtDay(day, { relative: false })} (draft, not saved)`, state: 'Pending', tone: 'accent', revertible: false }); } catch (e) { s.toast(errMsg(e), { tone: 'danger' }); setDraft(draftFor(day, s)); } finally { setDrafting(false); } };
  const save = () => { const body = kept && draft ? `${draft.text}\n\n${text}`.trim() : text.trim(); if (!body) { s.toast('Write something first', { tone: 'danger' }); return; } if (entry) s.updateNote(entry.id, { body, mood: mood >= 0 ? mood : undefined, ai: kept }); else s.addNote({ title: `Journal — ${fmtDay(day, { relative: false })}`, body, folder: 'Journal', tags: ['journal'], journalDate: day, mood: mood >= 0 ? mood : undefined, ai: kept }); if (mood >= 0) s.saveWellness(day, { mood }); s.addActivity('Journal entry saved', 'edit'); s.toast('Journal saved'); setDraft(null); setKept(false); };
  const earlier = s.notes.filter((n) => n.journalDate && n.journalDate !== day).sort((a, b) => b.journalDate!.localeCompare(a.journalDate!)).slice(0, 5);
  return (
    <Screen nav="notes" top={{ title: 'Journal', back: 'notes', actions: [{ ic: 'calendar', title: 'Today', onClick: () => setDay(todayISO()) }] }} narrow>
      <div className="weekstrip">{week.map((d) => <button type="button" key={d} className={d === day ? 'on' : ''} onClick={() => setDay(d)} disabled={d > todayISO()} style={d > todayISO() ? { opacity: .4 } : undefined}>{fmtDay(d, { relative: false }).slice(0, 3)}<b>{+d.slice(8)}</b><i style={{ opacity: s.notes.some((n) => n.journalDate === d) ? 1 : 0 }} /></button>)}</div>
      <div className="row between xs muted"><a className="link" onClick={() => setDay(addDays(day, -7))}>← Previous week</a><span>{fmtLong(day)}</span><a className="link" onClick={() => { if (addDays(day, 7) <= todayISO()) setDay(addDays(day, 7)); }}>Next week →</a></div>
      <Card>
        <div className="row between"><div><div className="eyebrow">{fmtLong(day)}</div><h2 style={{ fontSize: 19 }}>{day === todayISO() ? 'Today' : fmtDay(day)}</h2></div>{entry ? <Chip tone="success" ic="check" cls="sm">Saved{entry.ai ? ' · AI-assisted' : ''}</Chip> : draft ? <Chip tone="ai" ic="sparkles" cls="sm">Draft · AI-generated</Chip> : <Btn label={drafting ? "Drafting…" : "Draft with AI"} ic="sparkles" kind="soft" cls="sm" onClick={gen} />}</div>
        {draft && !kept && <div className="ai-block mt"><div className="editor" style={{ fontSize: 14 }}>{draft.text.split('\n\n').map((p, i) => <p key={i} dangerouslySetInnerHTML={{ __html: p.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>') }} />)}</div><div className="row mt-s" style={{ gap: 6 }}><Btn label="Keep & edit" kind="soft" cls="sm" onClick={() => { setText((t) => (draft.text + '\n\n' + t).trim()); setKept(true); setDraft(null); }} /><Btn label="Regenerate" ic="refresh" kind="ghost" cls="sm" onClick={gen} /><Btn label="Discard" kind="ghost" cls="sm" onClick={() => setDraft(null)} /></div><div className="xs faint mt-s">Generated from the day&apos;s timeline: {draft.sources}. Edit freely — the draft is never saved without you.</div></div>}
        <div className="mt"><TextArea label={entry ? 'Your entry' : 'Your words'} value={text} onChange={setText} placeholder="What stood out today?" rows={6} /></div>
        <div className="row mt-s" style={{ gap: 8, alignItems: 'center' }}><div className="mood" style={{ flex: 1 }}><MoodRow on={mood} onChange={setMood} small /></div><Btn label={entry ? 'Update entry' : 'Save entry'} kind="primary" cls="grow" onClick={save} /></div>
      </Card>
      <Section title="Earlier"><Card cls="pad-0"><List>{earlier.length === 0 && <div className="list-empty">No earlier entries.</div>}{earlier.map((n) => <div key={n.id} className="lrow" onClick={() => setDay(n.journalDate!)}><div className="iconbox"><Icon name="edit" /></div><div className="grow"><div className="t">{fmtDay(n.journalDate!, { relative: false })}{n.mood !== undefined ? ` · ${MOODS[n.mood]}` : ''}</div><div className="s ellip">{excerpt(n.body)}</div></div><Link href={`/notes/${n.id}`} className="link xs" onClick={(e) => e.stopPropagation()}>Open</Link></div>)}</List></Card></Section>
    </Screen>
  );
}

/* ================= SEARCH ================= */
export function NotesSearchScreen() {
  const s = useStore(); const [q, setQ] = useState(''); const [scope, setScope] = useState('All'); const r = noteSearch(s, q);
  const total = r.notes.length + r.tasks.length + r.goals.length + r.habits.length; const mark = (t: string) => q ? t.split(new RegExp(`(${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'ig')).map((p, i) => p.toLowerCase() === q.toLowerCase() ? <mark key={i}>{p}</mark> : <span key={i}>{p}</span>) : t;
  const snippet = (b: string) => { const i = b.toLowerCase().indexOf(q.toLowerCase()); return i < 0 ? excerpt(b).slice(0, 80) : '…' + b.slice(Math.max(0, i - 40), i + 60).replace(/\n/g, ' ') + '…'; };
  const back = s.notes.find((n) => q && n.title.toLowerCase().includes(q.toLowerCase())); const backlinks = back ? s.notes.filter((x) => x.id !== back.id && x.body.includes(`[[${back.title}]]`)) : [];
  return (
    <Screen nav="notes" top={{ title: 'Search notes', back: 'notes' }}>
      <TextInput value={q} onChange={setQ} ic="search" autoFocus placeholder="Search notes, tasks, goals, habits…" sfx={q ? `${total} result${total === 1 ? '' : 's'}` : undefined} />
      <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>{[['All', total], ['Notes', r.notes.length], ['Tasks', r.tasks.length], ['Goals', r.goals.length], ['Habits', r.habits.length]].map(([l, n]) => <button type="button" key={l} className={`chip ${scope === l ? 'accent' : 'outline'}`} onClick={() => setScope(l as string)}>{l} {q ? n : ''}</button>)}</div>
      {!q ? <Card><Empty ic="search" title="Search everything" p="Notes first, then tasks, goals and habits — one timeline, not silos." /></Card> : total === 0 ? <Card><Empty ic="note" title={`No results for “${q}”`} p="" action={<Btn label={`Create note “${q}”`} ic="plus" kind="primary" onClick={() => { const n = s.addNote({ title: q }); location.assign(`/notes/${n.id}`); }} />} /></Card> : (
        <Card cls="pad-0"><List>
          {(scope === 'All' || scope === 'Notes') && r.notes.map((n) => <LRow key={n.id} ic={n.journalDate ? 'edit' : 'note'} t={mark(n.title)} s={<>{n.journalDate ? 'Journal' : 'Note'} · {n.folder} · {mark(snippet(n.body))}</>} extra={<Link href={`/notes/${n.id}`} className="link xs">Open</Link>} />)}
          {(scope === 'All' || scope === 'Tasks') && r.tasks.map((t) => <LRow key={t.id} ic="check-square" t={mark(t.title)} s={`Task · ${t.done ? 'done' : t.due ? fmtDay(t.due) : 'Inbox'}`} extra={<Link href={`/tasks/${t.id}`} className="link xs">Open</Link>} />)}
          {(scope === 'All' || scope === 'Goals') && r.goals.map((g) => <LRow key={g.id} ic="target" t={mark(g.name)} s={`Goal · ${g.status}`} extra={<Link href={`/goals/${g.id}`} className="link xs">Open</Link>} />)}
          {(scope === 'All' || scope === 'Habits') && r.habits.map((h) => <LRow key={h.id} ic="repeat" t={mark(h.name)} s="Habit" extra={<Link href={`/habits/${h.id}`} className="link xs">Open</Link>} />)}
        </List></Card>
      )}
      {back && backlinks.length > 0 && <Section title={`Backlinks for “${back.title}”`}><Card cls="pad-0"><List>{backlinks.map((b) => <LRow key={b.id} ic="note" t={b.title} s={`${b.folder} · mentions this note`} extra={<Link href={`/notes/${b.id}`} className="link xs">Open</Link>} />)}</List></Card></Section>}
    </Screen>
  );
}
