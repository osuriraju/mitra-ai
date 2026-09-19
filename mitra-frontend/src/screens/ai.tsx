'use client';
/* ---------- AI: assistant (chat with previews), suggested actions (apply/dismiss), activity log (revert) ---------- */
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Screen } from '@/components/shell/Screen';
import { AiMsg, Bars, Btn, Card, Chip, Empty, EmojiBox, Icon, IconBox, LRow, List, Section } from '@/components/ui';
import { Dropdown } from '@/components/ui/controls';
import { ask, type Proposal } from '@/lib/ai';
import { addDays, fmtDay, inr, todayISO } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import type { AiMessage } from '@/store/types';

const md = (t: string) => t.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');

/** Apply a proposal to the store; returns an undo function */
export function applyProposal(p: Proposal, s: ReturnType<typeof useStore.getState>): () => void {
  if (p.kind === 'expense') { const tx = s.addTransaction({ kind: 'expense', amount: p.amount, categoryId: p.categoryId, accountId: p.accountId, merchant: p.merchant, source: 'ai' }); s.logAiAction({ action: 'Created expense', detail: `${p.merchant} · ${inr(p.amount)}`, state: 'Approved by you', tone: 'success', revertible: true }); return () => s.deleteTransaction(tx.id); }
  if (p.kind === 'task') { const t = s.addTask({ title: p.taskTitle, due: p.due, time: p.time, projectId: p.projectId, source: 'ai' }); s.logAiAction({ action: 'Created task', detail: `${t.title}${p.due ? ' · ' + fmtDay(p.due) : ''}`, state: 'Approved by you', tone: 'success', revertible: true }); return () => s.deleteTask(t.id); }
  if (p.kind === 'plan') { const g = s.addGoal({ name: p.goalName, type: 'numeric', unit: '₹', target: p.target, current: 0, due: addDays(todayISO(), p.months * 30), term: p.months <= 6 ? 'Short-term' : 'Medium-term', nextAction: `Transfer ${inr(p.monthly)} on the 2nd`, milestones: [1, 2, 3, 4].map((i) => ({ id: Math.random().toString(36).slice(2, 8), title: inr(Math.round((p.target * i) / 4)), target: Math.round((p.target * i) / 4), targetDate: addDays(todayISO(), Math.round((p.months * 30 * i) / 4)) })) }); const t = s.addTask({ title: `Transfer ${inr(p.monthly)} to savings`, due: addDays(todayISO(), 2), recurring: 'Monthly', goalId: g.id, amount: p.monthly, source: 'ai' }); s.logAiAction({ action: 'Proposed plan', detail: `${p.goalName}: 4 milestones, monthly transfer task`, state: 'Approved by you', tone: 'success', revertible: true }); return () => { s.deleteGoal(g.id); s.deleteTask(t.id); }; }
  const before = p.moves.map((m) => ({ id: m.id, due: s.tasks.find((t) => t.id === m.id)?.due })); p.moves.forEach((m) => s.updateTask(m.id, { due: m.to })); s.logAiAction({ action: `Rescheduled ${p.moves.length} tasks`, detail: p.moves.map((m) => `${m.title.split(' ').slice(0, 2).join(' ')} → ${m.label}`).join(' · '), state: 'Applied', tone: 'warning', revertible: true }); return () => before.forEach((b) => s.updateTask(b.id, { due: b.due }));
}

function Preview({ m }: { m: AiMessage }) {
  const s = useStore(); const router = useRouter(); const p = m.preview!; const prop = p.payload as Proposal; const cat = prop.kind === 'expense' ? s.categories.find((c) => c.id === prop.categoryId) : undefined;
  const apply = () => { const undo = applyProposal(prop, s); s.updateAiMessage(m.id, { preview: { ...p, applied: true } }); s.toast(`${prop.kind === 'expense' ? 'Expense saved' : prop.kind === 'task' ? 'Task added' : prop.kind === 'plan' ? 'Goal and plan created' : 'Tasks rescheduled'}`, { undo: () => { undo(); s.updateAiMessage(m.id, { preview: { ...p, applied: false } }); } }); };
  return (
    <>
      <div className="preview">{prop.kind === 'reschedule' ? <div className="kv"><b>Keep today</b><span>{prop.title.replace('Keep today: ', '')}</span>{prop.moves.map((mv) => <><b key={mv.id + 'k'}>Move to {mv.label}</b><span key={mv.id + 'v'}>{mv.title}</span></>)}</div> : <div className="row" style={{ gap: 8 }}><EmojiBox e={prop.kind === 'expense' ? cat?.emoji || '💸' : prop.kind === 'task' ? '✅' : '🎯'} cls="sm" /><div className="grow"><b>{p.title}</b><div className="xs muted">{p.sub}</div></div></div>}</div>
      {p.applied ? <div className="row mt-s" style={{ gap: 6 }}><Chip tone="success" ic="check" cls="sm">Applied · logged</Chip><a className="link xs" onClick={() => router.push(prop.kind === 'expense' ? href('transactions') : prop.kind === 'task' ? href('tasks-today') : prop.kind === 'plan' ? href('goals') : href('tasks-upcoming'))}>View</a></div>
        : <><div className="row mt-s" style={{ gap: 6 }}><Btn label={prop.kind === 'reschedule' ? 'Apply changes' : prop.kind === 'plan' ? 'Create goal + tasks' : 'Save'} kind="primary" cls="sm" onClick={apply} />{prop.kind === 'expense' && <Btn label="Edit" kind="outline" cls="sm" goto="expense-entry" />}{prop.kind === 'reschedule' && <Btn label="Adjust" kind="outline" cls="sm" goto="tasks-today" />}<Btn label="Cancel" kind="ghost" cls="sm" onClick={() => s.updateAiMessage(m.id, { preview: undefined, text: m.text + ' (cancelled)' })} /></div><div className="xs faint mt-s">Nothing changes until you confirm. Every change is logged and reversible.</div></>}
    </>
  );
}

export function AiScreen() {
  const s = useStore(); const [text, setText] = useState(''); const [typing, setTyping] = useState(false); const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => { endRef.current?.scrollIntoView({ block: 'end' }); }, [s.aiMessages.length, typing]);
  const send = (q?: string) => { const input = (q ?? text).trim(); if (!input) return; setText(''); s.pushAiMessage({ who: 'user', text: input }); setTyping(true); setTimeout(() => { const r = ask(input, useStore.getState()); s.pushAiMessage({ who: 'ai', text: r.text, chart: r.chart, preview: r.proposal ? { kind: r.proposal.kind, title: r.proposal.title, sub: r.proposal.sub, payload: r.proposal } : undefined }); setTyping(false); }, 500 + Math.random() * 500); };
  const open = s.suggestions.filter((x) => x.state === 'open');
  const scope = ['tasks', 'habits', 'goals', 'money', 'notes', s.settings.shareWellness ? 'wellness' : null].filter(Boolean).join(', ');
  return (
    <Screen nav="ai" top={{ title: 'Mitra', actions: [{ ic: 'history', goto: 'ai-log', title: 'Activity log' }, { el: <Dropdown items={[{ label: 'Clear conversation', ic: 'trash', onClick: () => s.clearAi() }]} /> }] }}>
      <div className="split side">
        <div className="col" style={{ gap: 14, minHeight: '60vh' }}>
          <div className="xs muted" style={{ textAlign: 'center' }}>Today · Mitra can see {scope}. {s.settings.shareWellness ? '' : 'Wellness is off.'}</div>
          {s.aiMessages.length === 0 && <AiMsg who="ai">Hi Aarav. I can capture, answer and plan — and I always show a preview before changing anything. Try one of the suggestions below.</AiMsg>}
          {s.aiMessages.map((m) => <AiMsg key={m.id} who={m.who}><span dangerouslySetInnerHTML={{ __html: md(m.text) }} />{m.preview && <Preview m={m} />}{m.chart && <div className="mt-s"><Bars vals={m.chart} labels={m.chart.length === 7 ? ['M', 'T', 'W', 'T', 'F', 'S', 'S'] : []} h={80} /></div>}{m.who === 'ai' && (m.chart || /\*\*/.test(m.text)) && <div className="xs faint mt-s">Sources: your tasks, habits, money · computed locally.</div>}</AiMsg>)}
          {typing && <AiMsg who="ai"><span className="row" style={{ gap: 4 }}><i className="sk" style={{ width: 8, height: 8, borderRadius: 4 }} /><i className="sk" style={{ width: 8, height: 8, borderRadius: 4 }} /><i className="sk" style={{ width: 8, height: 8, borderRadius: 4 }} /></span></AiMsg>}
          <div ref={endRef} />
          <div className="suggest">{['Spent ₹450 on dinner', 'I have too much to do today', 'How productive was I this week?', 'Plan my goal of saving ₹1 lakh in six months', 'What did I spend on food?', 'What goals are slipping?', 'Remind me to call John tomorrow at 6'].map((x) => <button type="button" key={x} onClick={() => send(x)}>{x}</button>)}</div>
          <div className="composer"><Btn ic="plus" kind="ghost" cls="sm" goto="quickadd" title="Quick add" /><input className="ph-in" value={text} onChange={(e) => setText(e.target.value)} placeholder="Ask, capture or plan…" onKeyDown={(e) => { if (e.key === 'Enter') send(); }} /><Btn ic="mic" kind="ghost" cls="sm" onClick={() => s.toast('Voice input needs the backend', { tone: 'accent' })} /><Btn ic="send" kind="primary" cls="sm" onClick={() => send()} /></div>
        </div>
        <div className="col only-tablet-up" style={{ gap: 'var(--gap)' }}>
          <Section title="Suggested actions" action="All" goto="ai-suggestions"><Card cls="pad-0"><List>{open.slice(0, 3).map((x) => <LRow key={x.id} ic={x.ic} tone={x.tone} t={x.t} s={x.src} goto="ai-suggestions" />)}{open.length === 0 && <div className="list-empty">All caught up — nothing to suggest right now.</div>}</List></Card></Section>
          <Card cls="soft"><div className="eyebrow mb-s">Trust</div><div className="sm col" style={{ gap: 6 }}><div><Icon name="eye" /> Previews before consequential changes</div><div><Icon name="undo" /> Every AI action is reversible</div><div><Icon name="history" /> Full activity log · {s.aiActions.length} entries</div><div><Icon name="shield" /> Minimum context: wellness {s.settings.shareWellness ? 'included' : 'excluded'}</div></div></Card>
        </div>
      </div>
    </Screen>
  );
}

/* ================= SUGGESTIONS ================= */
export function AiSuggestionsScreen() {
  const s = useStore(); const router = useRouter(); const [preview, setPreview] = useState<string | null>(null); const [tab, setTab] = useState<'open' | 'done'>('open');
  const list = s.suggestions.filter((x) => tab === 'open' ? x.state === 'open' : x.state !== 'open');
  const apply = (id: string) => {
    const x = s.suggestions.find((q) => q.id === id)!; let undo = () => {};
    if (id === 'sg1') { const h = s.habits.find((q) => q.name.toLowerCase().includes('sleep')); if (h) { const prev = h.reminder; s.updateHabit(h.id, { reminder: '22:30' }); undo = () => s.updateHabit(h.id, { reminder: prev }); } else { const nh = s.addHabit({ name: 'Wind down', type: 'binary', reminder: '22:30', timeOfDay: 'evening', icon: 'moon' }); undo = () => s.deleteHabit(nh.id); } }
    else if (id === 'sg2') { s.addRecurring({ name: 'Apollo Pharmacy', amount: 780, day: 11, emoji: '💊', kind: 'bill' }); undo = () => { const r = useStore.getState().recurring.find((q) => q.name === 'Apollo Pharmacy'); if (r) s.deleteRecurring(r.id); }; }
    else if (id === 'sg3') { const t = s.tasks.find((q) => q.title.startsWith('Prepare slides')); if (t) { const made = [['Outline', 30], ['Build slides', 60], ['Rehearse', 30]].map(([title, est]) => s.addSubtask(t.id, title as string, { estMin: est as number, due: t.due, source: 'ai' })); undo = () => made.forEach((m) => s.deleteTask(m.id)); } }
    else if (id === 'sg4') { const inbox = s.tasks.filter((q) => !q.projectId && !q.due && !q.done && !q.someday); const before = inbox.map((q) => ({ id: q.id, projectId: q.projectId, due: q.due })); inbox.forEach((q, i) => s.updateTask(q.id, { projectId: /gift|flight/i.test(q.title) ? 'p-personal' : /accountant|gst/i.test(q.title) ? 'p-freelance' : 'p-mitra', due: addDays(todayISO(), 2 + i) })); undo = () => before.forEach((b) => s.updateTask(b.id, { projectId: b.projectId, due: b.due })); }
    else if (id === 'sg5') { const g = s.goals.find((q) => q.name.includes('Spanish')); if (g) { s.updateGoal(g.id, { status: 'paused' }); undo = () => s.updateGoal(g.id, { status: 'active' }); } }
    s.setSuggestion(id, 'applied'); s.logAiAction({ action: 'Applied suggestion', detail: x.t, state: 'Approved by you', tone: 'success', revertible: true });
    s.toast('Applied', { undo: () => { undo(); s.setSuggestion(id, 'open'); } });
  };
  return (
    <Screen nav="ai" top={{ title: 'Suggested actions', back: 'ai', sub: 'Review, apply or dismiss — nothing is applied automatically' }} narrow>
      <div className="row between"><div className="segmented"><button type="button" className={tab === 'open' ? 'on' : ''} onClick={() => setTab('open')}>Open {s.suggestions.filter((x) => x.state === 'open').length}</button><button type="button" className={tab === 'done' ? 'on' : ''} onClick={() => setTab('done')}>Handled</button></div>{tab === 'done' && list.length > 0 && <a className="link sm" onClick={() => list.forEach((x) => s.setSuggestion(x.id, 'open'))}>Restore all</a>}</div>
      {list.length === 0 && <Card><Empty ic="sparkles" title={tab === 'open' ? 'Nothing to suggest right now' : 'No handled suggestions'} p={tab === 'open' ? 'Mitra suggests actions when it notices patterns in your data.' : ''} /></Card>}
      {list.map((x) => <Card key={x.id} style={{ opacity: x.state === 'dismissed' ? .6 : 1 }}><div className="row top" style={{ gap: 12 }}><IconBox ic={x.ic} tone={x.tone} /><div className="grow"><div className="row between"><div className="b">{x.t}</div>{x.state !== 'open' && <Chip tone={x.state === 'applied' ? 'success' : ''} cls="sm">{x.state}</Chip>}</div><div className="sm muted" style={{ marginTop: 3 }}>{x.p}</div><div className="xs faint mt-s">{x.src}</div>
        {preview === x.id && <div className="preview"><div className="kv"><b>Will change</b><span>{x.id === 'sg1' ? 'Habit “Sleep by 11 pm” → reminder 10:30 PM' : x.id === 'sg2' ? 'New recurring bill · Apollo Pharmacy ₹780 · 11th monthly' : x.id === 'sg3' ? 'Task “Prepare slides…” → 3 new subtasks (Outline · Build slides · Rehearse), each a real task' : x.id === 'sg4' ? `${s.tasks.filter((q) => !q.projectId && !q.due && !q.done && !q.someday).length} inbox tasks → projects + dates` : 'Goal “Learn Spanish to B1” → paused (history kept)'}</span><b>Reversible</b><span>Yes · from the activity log or the toast</span></div></div>}
        {x.state === 'open' ? <div className="row mt-s" style={{ gap: 6 }}><Btn label={preview === x.id ? 'Hide preview' : 'Preview'} ic="eye" kind="outline" cls="sm" onClick={() => setPreview(preview === x.id ? null : x.id)} /><Btn label="Apply" ic="check" kind="primary" cls="sm" onClick={() => apply(x.id)} /><Btn label="Dismiss" kind="ghost" cls="sm" onClick={() => { s.setSuggestion(x.id, 'dismissed'); s.toast('Dismissed · won’t resurface for 30 days', { undo: () => s.setSuggestion(x.id, 'open') }); }} /></div> : <div className="row mt-s" style={{ gap: 6 }}><Btn label="Restore" kind="ghost" cls="sm" onClick={() => s.setSuggestion(x.id, 'open')} />{x.state === 'applied' && <Btn label="View log" kind="ghost" cls="sm" onClick={() => router.push(href('ai-log'))} />}</div>}
      </div></div></Card>)}
    </Screen>
  );
}

/* ================= ACTIVITY LOG ================= */
export function AiLogScreen() {
  const s = useStore(); const [type, setType] = useState('All types'); const [range, setRange] = useState('Last 30 days');
  const types = ['All types', ...new Set(s.aiActions.map((a) => a.action.split(' ')[0]))];
  const list = s.aiActions.filter((a) => type === 'All types' || a.action.startsWith(type)).filter((a) => range === 'All time' || !/ago|Aug/.test(a.when));
  const reverted = s.aiActions.filter((a) => a.reverted).length;
  const exportLog = () => { const csv = 'when,action,detail,state\n' + s.aiActions.map((a) => `"${a.when}","${a.action}","${a.detail.replace(/"/g, "'")}","${a.state}"`).join('\n'); const el = document.createElement('a'); el.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })); el.download = 'mitra-ai-activity.csv'; el.click(); s.toast('Activity log exported'); };
  return (
    <Screen nav="ai" top={{ title: 'AI activity', back: 'ai', actions: [{ ic: 'download', title: 'Export CSV', onClick: exportLog }] }}>
      <div className="row between"><div className="chips scroll"><button type="button" className={`pill-select ${type !== 'All types' ? 'on' : ''}`} onClick={() => setType(types[(types.indexOf(type) + 1) % types.length])}>{type}<Icon name="chevron-down" /></button><button type="button" className="pill-select" onClick={() => setRange(range === 'Last 30 days' ? 'All time' : 'Last 30 days')}>{range}<Icon name="chevron-down" /></button></div><span className="sm muted">{s.aiActions.length} actions · {reverted} reverted</span></div>
      <Card cls="pad-0"><List>{list.map((a) => <div key={a.id} className="lrow" style={{ cursor: 'default', opacity: a.reverted ? .6 : 1 }}><IconBox ic={a.action === 'Reverted' ? 'undo' : 'sparkles'} tone={a.tone} cls="sm" /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>{a.action} <span className="muted" style={{ fontWeight: 500 }}>· {a.detail}</span></div><div className="s">{a.when} · {a.state}</div></div>{a.revertible && !a.reverted && <Btn label="Revert" ic="undo" kind="ghost" cls="sm" onClick={() => { s.revertAiAction(a.id); s.toast('Reverted · original entry kept'); }} />}</div>)}{list.length === 0 && <div className="list-empty">Mitra has not changed anything yet.</div>}</List></Card>
      <div className="card soft sm"><Icon name="lock" /> This log is immutable. Reverting creates a new entry rather than deleting the original. Financial and health records are never modified silently.</div>
    </Screen>
  );
}
