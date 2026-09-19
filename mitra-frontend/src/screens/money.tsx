'use client';
/* ---------- MONEY: overview, transactions, expense entry, categories, budgets, recurring, subscriptions ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Bar, Bars, Btn, Card, Chip, Donut, Empty, EmojiBox, Icon, IconBox, Kpi, LRow, List, Section, Segmented, Sheet, Tabs } from '@/components/ui';
import { AmountField, Confirm, DateInput, Dropdown, Keypad, Modal, Select, TextInput, TimeInput } from '@/components/ui/controls';
import { addDays, daysInMonth, diffDays, fmtDay, fmtShort, fmtTime, inr, monthKey, todayISO, MON, MON_LONG } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import { errMsg } from '@/lib/api';
import type { Transaction, TxKind } from '@/store/types';
import { income, spent, spentByCategory, thisMonth, totalBudget, transferred } from '@/store/selectors';

const monthLabel = (m: string) => `${MON_LONG[+m.slice(5, 7) - 1]}`;
const useCatName = () => { const cats = useStore((s) => s.categories); return (id?: string) => cats.find((c) => c.id === id); };
const useAccName = () => { const a = useStore((s) => s.accounts); return (id?: string) => a.find((x) => x.id === id); };

/** Transaction row bound to store */
export function TxR({ t, onClick }: { t: Transaction; onClick?: () => void }) {
  const cat = useCatName()(t.categoryId); const acc = useAccName()(t.accountId);
  return (
    <div className="lrow" onClick={onClick} style={{ cursor: onClick ? 'pointer' : 'default' }}>
      <EmojiBox e={cat?.emoji || '💸'} />
      <div className="grow"><div className="t">{t.merchant || cat?.name} {t.source === 'recurring' && <Icon name="repeat" cls="faint" />} {t.source === 'ai' && <Chip tone="ai" ic="sparkles" cls="sm">AI</Chip>}</div><div className="s">{cat?.name} · {fmtDay(t.date)}{t.date === todayISO() || diffDays(todayISO(), t.date) === 1 ? ` · ${fmtTime(t.time)}` : ''}</div></div>
      <div className="r"><div className={`v amt ${t.kind === 'income' ? 'pos' : 'neg'}`}>{t.kind === 'income' ? '+' : '−'}{inr(t.amount)}</div><div className="s">{acc?.name} {acc?.mask}</div></div>
    </div>
  );
}

/* ---------- Transaction form (route sheet at /money/add, or modal for edit) ---------- */
export function TxForm({ open, onClose, editId, asSheet = false }: { open: boolean; onClose: () => void; editId?: string; asSheet?: boolean }) {
  const s = useStore(); const ex = editId ? s.transactions.find((t) => t.id === editId) : undefined;
  const [kind, setKind] = useState<TxKind>('expense'); const [amount, setAmount] = useState(''); const [cat, setCat] = useState(''); const [acc, setAcc] = useState(s.accounts[0]?.id || ''); const [toAcc, setToAcc] = useState(s.accounts[1]?.id || ''); const [date, setDate] = useState(todayISO()); const [time, setTime] = useState(new Date().toTimeString().slice(0, 5)); const [merchant, setMerchant] = useState(''); const [note, setNote] = useState(''); const [newCat, setNewCat] = useState(false); const [ncName, setNcName] = useState(''); const [ncEmoji, setNcEmoji] = useState('🎁'); const [err, setErr] = useState(''); const [repeat, setRepeat] = useState(false); const [del, setDel] = useState(false);
  const cats = s.categories.filter((c) => c.kind === (kind === 'income' ? 'income' : 'expense') && (kind !== 'transfer' || c.slug === 'transfer'));
  useEffect(() => { if (!open) return; setKind(ex?.kind || 'expense'); setAmount(ex ? String(ex.amount) : ''); setCat(ex?.categoryId || ''); setAcc(ex?.accountId || s.accounts[0]?.id || ''); setToAcc(ex?.toAccountId || s.accounts.find((a) => a.id !== (ex?.accountId || s.accounts[0]?.id))?.id || ''); setDate(ex?.date || todayISO()); setTime(ex?.time || new Date().toTimeString().slice(0, 5)); setMerchant(ex?.merchant || ''); setNote(ex?.note || ''); setNewCat(false); setErr(''); setRepeat(false); }, [open, ex]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (!cat || !cats.find((c) => c.id === cat)) setCat(kind === 'transfer' ? s.categories.find((c) => c.slug === 'transfer')?.id || '' : cats[0]?.id || ''); }, [kind]); // eslint-disable-line react-hooks/exhaustive-deps
  // remember merchant per category
  const suggest = useMemo(() => { const m = s.transactions.filter((t) => t.categoryId === cat && t.merchant).map((t) => t.merchant); return [...new Set(m)].slice(0, 3); }, [cat, s.transactions]);
  const dup = s.transactions.find((t) => !ex && t.merchant.toLowerCase() === merchant.trim().toLowerCase() && t.amount === +amount && t.date === date && merchant);
  const save = (again = false) => {
    const a = +amount; if (!a || a <= 0) { setErr('Enter an amount'); return; } if (!cat && kind !== 'transfer') { setErr('Pick a category'); return; } if (!acc) { setErr('Pick an account'); return; } if (kind === 'transfer' && (!toAcc || toAcc === acc)) { setErr('Pick a different account to transfer to'); return; }
    const payload = { kind, amount: a, categoryId: cat || undefined, accountId: acc, toAccountId: kind === 'transfer' ? toAcc : undefined, date, time, merchant: merchant.trim(), note: note.trim() || undefined };
    if (ex) { s.updateTransaction(ex.id, payload); s.toast('Transaction updated'); onClose(); return; }
    const tx = s.addTransaction(payload); if (repeat) s.addRecurring({ name: merchant || cats.find((c) => c.id === cat)?.name || 'Recurring', amount: a, day: Math.min(28, +date.slice(8)), emoji: cats.find((c) => c.id === cat)?.emoji, kind: kind === 'income' ? 'income' : kind === 'transfer' ? 'transfer' : 'bill', categoryId: cat || undefined, accountId: acc, toAccountId: kind === 'transfer' ? toAcc : undefined });
    s.toast(`${kind === 'income' ? 'Income' : kind === 'transfer' ? 'Transfer' : 'Expense'} saved · ${inr(a)}${repeat ? ' · repeats monthly' : ''}`, { undo: () => s.deleteTransaction(tx.id) });
    if (again) { setAmount(''); setMerchant(''); setNote(''); } else onClose();
  };
  const createCat = () => { if (!ncName.trim()) return; const c = s.addCategory({ name: ncName.trim(), emoji: ncEmoji, kind: kind === 'income' ? 'income' : 'expense' }); setCat(c.id); setNewCat(false); setNcName(''); s.toast(`Category added · ${c.emoji} ${c.name}`); };
  const body = (
    <>
      <Segmented opts={['Expense', 'Income', 'Transfer']} on={kind[0].toUpperCase() + kind.slice(1)} cls="block" onChange={(v) => setKind(v.toLowerCase() as TxKind)} />
      <AmountField value={amount} onChange={setAmount} autoFocus={!ex} />
      {err && <div className="err sm" style={{ color: 'var(--danger)', textAlign: 'center' }}>{err}</div>}
      {dup && <div className="banner warning"><Icon name="alert" /><div className="grow">Looks like <b>{dup.merchant} {inr(dup.amount)}</b> was already added at {fmtTime(dup.time)}.</div></div>}
      {kind !== 'transfer' && <div className="field"><div className="row between"><div className="lbl">Category</div><Link className="link xs" href={href('categories')}>Manage</Link></div>
        <div className="chips scroll">{cats.map((c) => <button type="button" key={c.id} className={`chip ${c.id === cat ? 'accent' : 'outline'}`} onClick={() => setCat(c.id)}>{c.emoji} {c.name}</button>)}<button type="button" className="chip accent" onClick={() => setNewCat((v) => !v)}><Icon name="plus" />New category</button></div>
        {newCat && <div className="card soft tight mt-s"><div className="row" style={{ gap: 8 }}><input className="input" style={{ width: 64, justifyContent: 'center', flex: 'none', textAlign: 'center' }} value={ncEmoji} onChange={(e) => setNcEmoji(e.target.value.slice(-2))} /><div className="input grow"><input className="ph-in" value={ncName} onChange={(e) => setNcName(e.target.value)} placeholder="Category name" onKeyDown={(e) => { if (e.key === 'Enter') createCat(); }} /></div><Btn label="Add" kind="primary" cls="sm" onClick={createCat} /></div><div className="xs muted mt-s">New categories appear here and in Budgets. Pick an emoji and a name.</div></div>}
      </div>}
      <div className="split">
        <Select label={kind === 'transfer' ? 'From account' : 'Account'} ic="wallet" value={acc} onChange={setAcc} options={s.accounts.map((x) => ({ value: x.id, label: `${x.name} ${x.mask}${x.balance < 0 ? ' · −' : ' · '}${inr(x.balance)}` }))} hint={s.accounts.length === 0 ? 'No accounts yet — add one in Profile' : undefined} />
        {kind === 'transfer' ? <Select label="To account" ic="wallet" value={toAcc} onChange={setToAcc} options={s.accounts.filter((x) => x.id !== acc).map((x) => ({ value: x.id, label: `${x.name} ${x.mask}` }))} /> : <DateInput label="Date" value={date} onChange={setDate} />}
      </div>
      {kind === 'transfer' && <DateInput label="Date" value={date} onChange={setDate} />}
      <div className="split"><TimeInput label="Time" value={time} onChange={setTime} /><TextInput label={kind === 'income' ? 'Source' : 'Merchant'} value={merchant} onChange={setMerchant} ic="tag" placeholder={kind === 'income' ? 'e.g. Salary — Beyond Labs' : 'e.g. Swiggy'} /></div>
      {suggest.length > 0 && !merchant && <div className="row" style={{ gap: 6 }}><span className="xs muted">Recent:</span>{suggest.map((m) => <button type="button" key={m} className="chip outline sm" onClick={() => setMerchant(m)}>{m}</button>)}</div>}
      <TextInput label="Note" value={note} onChange={setNote} placeholder="Optional" ic="edit" />
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><Btn label="Receipt" ic="camera" kind="outline" cls="sm" onClick={() => s.toast('Receipt upload arrives with the backend (file storage)')} /><Btn label={repeat ? 'Repeats monthly ✓' : 'Repeat monthly'} ic="repeat" kind={repeat ? 'soft' : 'outline'} cls="sm" onClick={() => setRepeat((v) => !v)} />{ex && <Btn label="Delete" ic="trash" kind="danger" cls="sm" onClick={() => setDel(true)} />}</div>
      {!ex && <Keypad value={amount} onChange={setAmount} />}
      <Confirm open={del} onClose={() => setDel(false)} title="Delete this transaction?" body="The account balance will be adjusted back." onConfirm={() => { if (ex) { s.deleteTransaction(ex.id); s.toast('Transaction deleted'); onClose(); } }} />
    </>
  );
  const footer = <>{!ex && <Btn label="Save & add another" kind="outline" onClick={() => save(true)} />}<span className="grow" /><Btn label={ex ? 'Save changes' : `Save ${kind}`} kind="primary" onClick={() => save(false)} /></>;
  if (asSheet) return <Sheet title={ex ? 'Edit transaction' : 'Add expense'} onClose={onClose} footer={footer}>{body}</Sheet>;
  return <Modal open={open} onClose={onClose} title={ex ? 'Edit transaction' : 'Add expense'} footer={footer}>{body}</Modal>;
}

/* ================= OVERVIEW: Summary · Analytics ================= */
/** Fixed categorical order (validated for both themes, see globals.css --s1…--s8). Slot 9+ folds into "Other". */
const SERIES = ['var(--s1)', 'var(--s2)', 'var(--s3)', 'var(--s4)', 'var(--s5)', 'var(--s6)', 'var(--s7)', 'var(--s8)'];
const OTHER = 'var(--faint)';
const pct = (v: number, total: number) => (total ? (v / total) * 100 : 0);
const pct1 = (v: number, total: number) => `${pct(v, total).toFixed(1)}%`;
const monthTitle = (m: string) => `${monthLabel(m)} ${m.slice(0, 4)}`;

export function MoneyScreen({ overlay }: { overlay?: ReactNode }) {
  const s = useStore(); const [tab, setTab] = useState('Summary'); const [month, setMonth] = useState(monthKey(todayISO())); const [acc, setAcc] = useState('');
  const months = useMemo(() => [...new Set([monthKey(todayISO()), ...s.transactions.map((t) => monthKey(t.date))])].sort().reverse(), [s.transactions]);
  const txAll = acc ? s.transactions.filter((t) => t.accountId === acc || t.toAccountId === acc) : s.transactions;
  const tx = thisMonth(txAll, month);
  return (
    <Screen nav="money" top={{ title: 'Money', actions: [{ ic: 'plus', goto: 'expense-entry', kind: 'primary' }] }} overlay={overlay}>
      <div className="row between" style={{ gap: 10, flexWrap: 'wrap' }}>
        <Tabs opts={['Summary', 'Analytics']} on={tab} onChange={setTab} />
        <div className="row" style={{ gap: 8 }}>
          <Select value={month} onChange={setMonth} options={months.map((m) => ({ value: m, label: monthTitle(m) }))} ic="calendar" style={{ minWidth: 170 }} />
          <Select value={acc} onChange={setAcc} options={[{ value: '', label: 'All accounts' }, ...s.accounts.map((a) => ({ value: a.id, label: `${a.name} ${a.mask}`.trim() }))]} ic="wallet" style={{ minWidth: 160 }} />
        </div>
      </div>
      {tab === 'Summary' ? <MoneySummary month={month} tx={tx} /> : <MoneyAnalytics month={month} tx={tx} />}
    </Screen>
  );
}

/* ---------- Summary (the original overview, for the selected month) ---------- */
function MoneySummary({ month, tx }: { month: string; tx: Transaction[] }) {
  const s = useStore(); const [edit, setEdit] = useState<string | null>(null);
  const cur = month === monthKey(todayISO()); const first = `${month}-01`; const dim = daysInMonth(first); const day = cur ? +todayISO().slice(8) : dim; const left = cur ? dim - day : 0;
  const sp = spent(tx), inc = income(tx), tr = transferred(tx); const budget = totalBudget(s); const projected = Math.round((sp / Math.max(1, day)) * dim);
  const byCat = spentByCategory(tx);
  // real spend per day: last 16 days for the current month, every day for a past month
  const last = cur ? todayISO() : `${month}-${String(dim).padStart(2, '0')}`; const days = cur ? 16 : dim;
  const dailyDates = Array.from({ length: days }, (_, i) => addDays(last, -(days - 1 - i)));
  const daily = dailyDates.map((d) => spent(tx.filter((t) => t.date === d)));
  const dailyLabels = dailyDates.map((d, i) => i % (cur ? 3 : 5) === 0 || i === days - 1 ? String(+d.slice(8)) : '');
  const recent = [...tx].sort((a, b) => (b.date + b.time).localeCompare(a.date + a.time)).slice(0, 5);
  const today = +todayISO().slice(8), dimNow = daysInMonth(todayISO());
  const upcoming = [...s.recurring.filter((r) => r.kind === 'bill').map((r) => ({ id: r.id, emoji: r.emoji, name: r.name, when: r.day, amount: r.amount, goto: href('recurring') })), ...s.subscriptions.map((x) => ({ id: x.id, emoji: '🔁', name: x.name, when: +x.next.slice(8), amount: x.amount, goto: href('subscriptions'), next: x.next }))].map((u) => ({ ...u, days: 'next' in u && u.next ? diffDays(u.next as string, todayISO()) : ((u.when - today + dimNow) % dimNow) })).filter((u) => u.days >= 0).sort((a, b) => a.days - b.days).slice(0, 3);
  return (<>
    <Card><div className="kpi-3"><Kpi l="Spent" v={inr(sp)} d={budget ? `of ${inr(budget)} budget` : ''} /><Kpi l="Income" v={inr(inc)} d={inc ? `${tx.filter((t) => t.kind === 'income').length} credit${tx.filter((t) => t.kind === 'income').length > 1 ? 's' : ''}` : 'none yet'} /><Kpi l="Saved" v={`${inc - sp < 0 ? '−' : ''}${inr(inc - sp)}`} d={inc ? `${Math.round(((inc - sp) / inc) * 100)}% of income${tr ? ` · ${inr(tr)} moved between accounts` : ''}` : 'income − expenses'} dir={inc - sp > 0 ? 'up' : inc - sp < 0 ? 'down' : ''} /></div><div className="mt"><Bar pct={budget ? (sp / budget) * 100 : 0} tone={sp > budget ? 'danger' : sp > budget * .9 ? 'warning' : ''} /></div><div className="row between xs muted mt-s"><span>{budget ? Math.round((sp / budget) * 100) : 0}% of budget{cur ? ` · ${left} days left` : ` · ${monthTitle(month)}`}</span>{cur && <span>Projected month end: {inr(projected)}</span>}</div></Card>
    <div className="split main">
      <div className="col" style={{ gap: 'var(--gap)' }}>
        <Section title="Cash flow · daily spend" action={inr(sp)}><Card>{sp === 0 ? <div className="list-empty">Nothing spent in {monthTitle(month)} yet.</div> : <><Bars vals={daily} labels={dailyLabels} h={150} hi={cur ? days - 1 : -1} unit="₹" /><div className="xs muted mt-s">Spent per day · {cur ? 'last 16 days · today highlighted' : monthTitle(month)} · hover a bar for the amount</div></>}</Card></Section>
        <Section title="Recent transactions" action="All" goto="transactions"><Card cls="pad-0">{recent.length === 0 ? <Empty ic="wallet" title={cur ? 'No expenses yet this month' : `Nothing recorded in ${monthTitle(month)}`} p={cur ? 'Try typing “Spent ₹450 on dinner” in Quick Add — it takes two seconds.' : ''} action={cur ? <Btn label="Quick add" ic="sparkles" kind="soft" goto="quickadd" /> : undefined} /> : <List>{recent.map((t) => <TxR key={t.id} t={t} onClick={() => setEdit(t.id)} />)}</List>}</Card></Section>
      </div>
      <div className="col" style={{ gap: 'var(--gap)' }}>
        <Section title="Budgets" action="All budgets" goto="budgets"><Card><List>{s.categories.filter((c) => c.budget).sort((a, b) => (byCat[b.id] || 0) / b.budget! - (byCat[a.id] || 0) / a.budget!).slice(0, 4).map((c) => { const v = byCat[c.id] || 0; const p = Math.round((v / c.budget!) * 100); return <div key={c.id} style={{ padding: '9px 0' }}><div className="row between sm"><span className="b">{c.emoji} {c.name}</span><span className="num">{inr(v)} <span className="muted">/ {inr(c.budget!)}</span></span></div><div className="mt-s"><Bar pct={p} tone={p > 100 ? 'danger' : p > 90 ? 'warning' : ''} /></div></div>; })}</List></Card></Section>
        <Section title="Accounts" action="Manage" goto="profile"><Card cls="pad-0"><List>{s.accounts.map((a) => <LRow key={a.id} ic={a.kind === 'credit' ? 'columns' : 'wallet'} t={a.name} s={a.mask} v={<span style={{ color: a.balance < 0 ? 'var(--danger)' : undefined }}>{a.balance < 0 ? '−' : ''}{inr(a.balance)}</span>} goto="transactions" />)}</List></Card></Section>
        <Section title="Upcoming" action="Recurring" goto="recurring"><Card cls="pad-0"><List>{upcoming.map((u) => <Link key={u.id} href={u.goto} className="lrow"><EmojiBox e={u.emoji} /><div className="grow"><div className="t">{u.name}</div><div className="s">{u.days === 0 ? 'Today' : u.days === 1 ? 'Tomorrow' : `in ${u.days} days`}</div></div><div className="r"><div className="v">{inr(u.amount)}</div></div><Icon name="chevron-right" cls="chev" /></Link>)}</List></Card></Section>
      </div>
    </div>
    <TxForm open={!!edit} onClose={() => setEdit(null)} editId={edit || undefined} />
  </>);
}

/* ---------- Analytics (the spreadsheet dashboard: KPIs · category breakdown · account utilisation · top outflows) ---------- */
function MoneyAnalytics({ month, tx }: { month: string; tx: Transaction[] }) {
  const s = useStore();
  const exp = tx.filter((t) => t.kind === 'expense'); const inc = income(tx); const sp = spent(tx); const net = inc - sp; const rate = inc ? (net / inc) * 100 : 0; const tr = transferred(tx);
  // category breakdown — sorted by amount, fixed colour per rank position (top 8 get a hue, the rest fold into Other)
  const byCat = useMemo(() => { const m = new Map<string, { id: string; name: string; emoji: string; v: number; n: number }>(); exp.forEach((t) => { const c = s.categories.find((x) => x.id === t.categoryId); const k = c?.id || 'none'; const row = m.get(k) || { id: k, name: c?.name || 'Uncategorised', emoji: c?.emoji || '💸', v: 0, n: 0 }; row.v += t.amount; row.n += 1; m.set(k, row); }); return [...m.values()].sort((a, b) => b.v - a.v); }, [exp, s.categories]);
  const maxCat = byCat[0]?.v || 1; const top = byCat.slice(0, 8); const rest = byCat.slice(8); const other = rest.reduce((a, c) => a + c.v, 0);
  const segs = [...top.map((c, i) => ({ v: c.v, c: SERIES[i], name: c.name })), ...(other > 0 ? [{ v: other, c: OTHER, name: `Other (${rest.length})` }] : [])];
  // account utilisation — money leaving each account (expenses + transfers out)
  const out = tx.filter((t) => t.kind !== 'income');
  const byAcc = s.accounts.map((a) => ({ ...a, v: out.filter((t) => t.accountId === a.id).reduce((x, t) => x + t.amount, 0), n: out.filter((t) => t.accountId === a.id).length })).filter((a) => a.n > 0).sort((a, b) => b.v - a.v);
  const outTotal = byAcc.reduce((a, x) => a + x.v, 0); const maxAcc = byAcc[0]?.v || 1;
  const topTx = [...exp].sort((a, b) => b.amount - a.amount).slice(0, 5);
  const catOf = (id?: string) => s.categories.find((c) => c.id === id); const accOf = (id: string) => s.accounts.find((a) => a.id === id);
  if (tx.length === 0) return <Card><Empty ic="chart" title={`No transactions in ${monthTitle(month)}`} p="Pick another month, or add an expense to see the breakdown." action={<Btn label="Add expense" ic="plus" kind="primary" goto="expense-entry" />} /></Card>;
  const tile = (l: string, v: string, d: string, tone: string) => <Card cls="stat-tile" ><div className="row" style={{ gap: 8, alignItems: 'center' }}><i className="dot" style={{ background: tone }} /><span className="eyebrow">{l}</span></div><div className="v num" title={v}>{v}</div><div className="xs muted">{d}</div></Card>;
  return (<>
    <div className="stat-row">
      {tile('Total income', inr(inc), 'Primary monthly inflow', 'var(--success)')}
      {tile('Total expenses', inr(sp), `${exp.length} debits & charges`, 'var(--danger)')}
      {tile('Net savings', `${net < 0 ? '−' : ''}${inr(net)}`, tr ? `Income − expenses · ${inr(tr)} moved between accounts` : 'Income − expenses', 'var(--accent)')}
      {tile('Savings rate', inc ? `${rate < 0 ? '−' : ''}${Math.abs(rate).toFixed(1)}%` : '—', '% of income retained', 'var(--info)')}
    </div>
    <div className="split main">
      <div className="col" style={{ gap: 'var(--gap)' }}>
        <Section title="Category spending breakdown"><Card cls="pad-0"><div style={{ overflowX: 'auto' }}><table className="table"><thead><tr><th>Category</th><th className="r">Amount</th><th className="r">% share</th><th className="r">Txns</th><th style={{ width: '28%' }}>Relative spend</th></tr></thead><tbody>
          {byCat.map((c, i) => <tr key={c.id}><td><span className="row" style={{ gap: 8 }}><i className="dot" style={{ background: i < 8 ? SERIES[i] : OTHER }} />{c.emoji} {c.name}</span></td><td className="r b">{inr(c.v)}</td><td className="r muted">{pct1(c.v, sp)}</td><td className="r muted">{c.n}</td><td><div className="hbar" title={`${c.name} · ${inr(c.v)}`}><i style={{ width: `${pct(c.v, maxCat)}%`, background: i < 8 ? SERIES[i] : OTHER }} /></div></td></tr>)}
          <tr className="total"><td className="b">Total expenses</td><td className="r b">{inr(sp)}</td><td className="r b">100%</td><td className="r b">{exp.length}</td><td /></tr>
        </tbody></table></div></Card></Section>
        <Section title="Account utilisation"><Card cls="pad-0"><div style={{ overflowX: 'auto' }}><table className="table"><thead><tr><th>Account channel</th><th className="r">Total spend</th><th className="r">% share</th><th className="r">Txns</th><th style={{ width: '28%' }}>Spend ratio</th></tr></thead><tbody>
          {byAcc.map((a) => <tr key={a.id}><td><span className="row" style={{ gap: 8 }}><Icon name={a.kind === 'credit' ? 'columns' : 'wallet'} cls="muted" />{a.name} <span className="muted">{a.mask}</span></span></td><td className="r b">{inr(a.v)}</td><td className="r muted">{pct1(a.v, outTotal)}</td><td className="r muted">{a.n}</td><td><div className="hbar" title={`${a.name} · ${inr(a.v)}`}><i style={{ width: `${pct(a.v, maxAcc)}%`, background: a.kind === 'credit' ? 'var(--warning)' : 'var(--accent)' }} /></div></td></tr>)}
          {byAcc.length === 0 && <tr><td colSpan={5} className="muted">No outflows this month.</td></tr>}
        </tbody></table></div></Card></Section>
        <Section title="Top individual outflows"><Card cls="pad-0"><div style={{ overflowX: 'auto' }}><table className="table"><thead><tr><th>Expense item</th><th>Category</th><th>Account</th><th className="r">Rank</th><th className="r">Amount</th></tr></thead><tbody>
          {topTx.length === 0 && <tr><td colSpan={5} className="muted">No expenses this month.</td></tr>}
          {topTx.map((t, i) => { const c = catOf(t.categoryId); const a = accOf(t.accountId); return <tr key={t.id}><td className="b">{t.merchant || c?.name || '—'}<div className="xs muted" style={{ fontWeight: 400 }}>{fmtDay(t.date, { relative: false })}</div></td><td>{c ? `${c.emoji} ${c.name}` : 'Uncategorised'}</td><td className="muted">{a?.name}</td><td className="r b">#{i + 1}</td><td className="r b">{inr(t.amount)}</td></tr>; })}
        </tbody></table></div></Card></Section>
      </div>
      <div className="col" style={{ gap: 'var(--gap)' }}>
        <Section title="Expense distribution by category"><Card><div className="row" style={{ gap: 18, alignItems: 'center', flexWrap: 'wrap' }}>
          {sp > 0 ? <Donut segs={segs} size={170} stroke={26} /> : <div className="ring" style={{ width: 170, height: 170 }}><span className="xs muted">No expenses</span></div>}
          <div className="grow list" style={{ minWidth: 150 }}>{segs.length === 0 && <div className="sm muted">Nothing spent in {monthTitle(month)} yet.</div>}{segs.map((g) => <div key={g.name} className="row between sm" style={{ padding: '5px 0' }}><span className="row" style={{ gap: 8 }}><i className="dot" style={{ background: g.c }} />{g.name}</span><span className="num muted">{pct1(g.v, sp)}</span></div>)}</div>
        </div></Card></Section>
        <Section title="Spending by category ranking"><Card>
          <div className="col" style={{ gap: 8 }}>{byCat.length === 0 && <div className="list-empty">No expenses to rank.</div>}{byCat.map((c, i) => <div key={c.id} className="rank-row" title={`${c.name} · ${inr(c.v)} · ${c.n} txns`}><span className="lbl sm">{c.name}</span><div className="hbar tall"><i style={{ width: `${pct(c.v, maxCat)}%`, background: i < 8 ? SERIES[i] : OTHER }} /></div><span className="num sm b" style={{ minWidth: 70, textAlign: 'right' }}>{inr(c.v)}</span></div>)}</div>
          <div className="xs muted mt-s">Total spend (₹) · {monthTitle(month)}</div>
        </Card></Section>
      </div>
    </div>
  </>);
}

export function ExpenseEntryScreen() { const router = useRouter(); return <MoneyScreen overlay={<TxForm open asSheet onClose={() => router.push(href('money'))} />} />; }

/* ================= TRANSACTIONS ================= */
export function TransactionsScreen() {
  const s = useStore(); const [month, setMonth] = useState(monthKey(todayISO())); const [cat, setCat] = useState(''); const [acc, setAcc] = useState(''); const [kind, setKind] = useState(''); const [q, setQ] = useState(''); const [edit, setEdit] = useState<string | null>(null); const [add, setAdd] = useState(false); const [sort, setSort] = useState<'date' | 'amount'>('date');
  const months = [...new Set(s.transactions.map((t) => monthKey(t.date)))].sort().reverse();
  const list = s.transactions.filter((t) => (month === 'all' || monthKey(t.date) === month) && (!cat || t.categoryId === cat) && (!acc || t.accountId === acc) && (!kind || t.kind === kind) && (!q || (t.merchant + ' ' + (t.note || '')).toLowerCase().includes(q.toLowerCase()))).sort((a, b) => sort === 'date' ? (b.date + b.time).localeCompare(a.date + a.time) : b.amount - a.amount);
  const groups: [string, Transaction[]][] = [['Today', list.filter((t) => t.date === todayISO())], ['Yesterday', list.filter((t) => t.date === addDays(todayISO(), -1))], ['Earlier', list.filter((t) => t.date < addDays(todayISO(), -1))], ['Upcoming', list.filter((t) => t.date > todayISO())]].filter(([, ts]) => ts.length) as [string, Transaction[]][];
  const sum = (ts: Transaction[]) => { const n = ts.reduce((a, t) => a + (t.kind === 'income' ? t.amount : t.kind === 'expense' ? -t.amount : 0), 0); return `${n < 0 ? '−' : '+'}${inr(n)}`; };
  return (
    <Screen nav="money" top={{ title: 'Transactions', back: 'money', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setAdd(true) }] }}>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <Select value={month} onChange={setMonth} ic="calendar" options={[{ value: 'all', label: 'All time' }, ...months.map((m) => ({ value: m, label: `${monthLabel(m)} ${m.slice(0, 4)}` }))]} />
          <Select value={cat} onChange={setCat} options={[{ value: '', label: 'All categories' }, ...s.categories.map((c) => ({ value: c.id, label: `${c.emoji} ${c.name}` }))]} />
          <Select value={acc} onChange={setAcc} ic="wallet" options={[{ value: '', label: 'All accounts' }, ...s.accounts.map((a) => ({ value: a.id, label: `${a.name} ${a.mask}`.trim() }))]} />
          <Select value={kind} onChange={setKind} options={[{ value: '', label: 'All types' }, { value: 'expense', label: 'Expense' }, { value: 'income', label: 'Income' }, { value: 'transfer', label: 'Transfer' }]} />
          {(cat || acc || kind || q) && <button type="button" className="chip outline" onClick={() => { setCat(''); setAcc(''); setKind(''); setQ(''); }}><Icon name="x" />Clear</button>}
        </div>
        <div className="input" style={{ maxWidth: 260, height: 36 }}><Icon name="search" /><input className="ph-in" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search merchant or note" /></div>
      </div>
      <div className="row between xs muted"><span>{list.length} transaction{list.length === 1 ? '' : 's'} · spent {inr(spent(list))} · income {inr(income(list))}</span><a className="link" onClick={() => setSort(sort === 'date' ? 'amount' : 'date')}>Sort by {sort === 'date' ? 'amount' : 'date'}</a></div>
      {list.length === 0 ? <Card><Empty ic="wallet" title="No transactions match these filters" p="Try a different month or clear the filters." action={<Btn label="Clear filters" kind="outline" onClick={() => { setMonth('all'); setCat(''); setAcc(''); setKind(''); setQ(''); }} />} /></Card> : (<>
        <div className="only-tablet-up card pad-0"><table className="table"><thead><tr><th>Merchant</th><th>Category</th><th>Account</th><th>Date</th><th className="r">Amount</th></tr></thead><tbody>
          {list.map((t) => { const c = s.categories.find((x) => x.id === t.categoryId); const a = s.accounts.find((x) => x.id === t.accountId); return <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => setEdit(t.id)}><td><div className="row" style={{ gap: 8 }}><EmojiBox e={c?.emoji || '💸'} cls="sm" /><span className="b">{t.merchant || c?.name}</span>{t.source === 'ai' && <Chip tone="ai" ic="sparkles" cls="sm">AI</Chip>}{t.source === 'recurring' && <Icon name="repeat" cls="faint" />}</div></td><td>{c?.name}</td><td className="muted">{a?.name} {a?.mask}</td><td className="muted">{fmtDay(t.date)} · {fmtTime(t.time)}</td><td className={`r b ${t.kind === 'income' ? 'amt pos' : ''}`}>{t.kind === 'income' ? '+' : '−'}{inr(t.amount)}</td></tr>; })}
        </tbody></table></div>
        <div className="hide-tab-up">{groups.map(([g, ts]) => <Section key={g} title={g} action={sum(ts)}><Card cls="pad-0"><List>{ts.map((t) => <TxR key={t.id} t={t} onClick={() => setEdit(t.id)} />)}</List></Card></Section>)}</div>
      </>)}
      <TxForm open={!!edit || add} onClose={() => { setEdit(null); setAdd(false); }} editId={edit || undefined} />
    </Screen>
  );
}

/* ================= CATEGORIES ================= */
export function CategoriesScreen() {
  const s = useStore(); const [kind, setKind] = useState<'expense' | 'income'>('expense'); const [name, setName] = useState(''); const [emoji, setEmoji] = useState('🎁'); const [budget, setBudget] = useState(''); const [edit, setEdit] = useState<string | null>(null); const [eName, setEName] = useState(''); const [eBudget, setEBudget] = useState(''); const [del, setDel] = useState<string | null>(null);
  const tx = thisMonth(s.transactions); const byCat = spentByCategory(tx); const incByCat = (() => { const m: Record<string, number> = {}; tx.filter((t) => t.kind === 'income' && t.categoryId).forEach((t) => { m[t.categoryId!] = (m[t.categoryId!] || 0) + t.amount; }); return m; })();
  const cats = s.categories.filter((c) => c.kind === kind).map((c) => ({ ...c, v: (kind === 'expense' ? byCat : incByCat)[c.id] || 0, n: tx.filter((t) => t.categoryId === c.id).length })).sort((a, b) => b.v - a.v);
  const total = cats.reduce((a, c) => a + c.v, 0); const top = cats.slice(0, 5); const other = total - top.reduce((a, c) => a + c.v, 0);
  const create = () => { if (!name.trim()) return; const c = s.addCategory({ name: name.trim(), emoji, kind, budget: budget ? +budget : undefined }); s.toast(`Category created · ${c.emoji} ${c.name}`); setName(''); setBudget(''); };
  const shade = (i: number) => i < 4 ? `color-mix(in srgb,var(--accent) ${100 - i * 22}%,var(--surface))` : 'var(--track)';
  return (
    <Screen nav="money" top={{ title: 'Categories', back: 'money', actions: [{ ic: 'plus', label: 'New category', kind: 'primary', onClick: () => document.getElementById('cat-name')?.focus() }] }}>
      <div className="row between"><Segmented opts={['Expense', 'Income']} on={kind === 'expense' ? 'Expense' : 'Income'} onChange={(v) => setKind(v.toLowerCase() as 'expense' | 'income')} /><span className="sm muted">{monthLabel(monthKey(todayISO()))}</span></div>
      <Card cls="keep"><div className="eyebrow mb-s">Create a category</div><div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><input className="input" style={{ width: 64, justifyContent: 'center', flex: 'none', textAlign: 'center' }} value={emoji} onChange={(e) => setEmoji(e.target.value.slice(-2))} /><div className="input grow" style={{ minWidth: 160 }}><input id="cat-name" className="ph-in" value={name} onChange={(e) => setName(e.target.value)} placeholder="Category name" onKeyDown={(e) => { if (e.key === 'Enter') create(); }} /></div>{kind === 'expense' && <div className="input" style={{ width: 170, flex: 'none' }}><span className="muted sm">Monthly budget</span><input className="ph-in" style={{ textAlign: 'right' }} value={budget} onChange={(e) => setBudget(e.target.value.replace(/\D/g, ''))} placeholder="₹ 2,000" inputMode="numeric" /></div>}<Btn label="Create" kind="primary" onClick={create} /></div><div className="xs muted mt-s">Custom categories work everywhere: Quick Add, AI capture (“spent ₹800 on gifts”), budgets and reports. Rename or merge later without losing history.</div></Card>
      <div className="split main">
        <Card><div className="row" style={{ gap: 18, alignItems: 'center' }}>{total > 0 ? <Donut segs={[...top.map((c, i) => ({ v: c.v, c: shade(i) })), ...(other > 0 ? [{ v: other, c: 'var(--track)' }] : [])]} size={130} stroke={18} /> : <div className="ring" style={{ width: 130, height: 130 }}><span className="xs muted">No data</span></div>}<div className="grow list">{[...top.map((c, i) => ({ n: c.name, v: c.v, i })), ...(other > 0 ? [{ n: 'Other', v: other, i: 9 }] : [])].map((r) => <div key={r.n} className="row between sm" style={{ padding: '6px 0' }}><span className="row" style={{ gap: 8 }}><i className="dot" style={{ background: shade(r.i) }} />{r.n}</span><span className="num"><b>{inr(r.v)}</b> <span className="muted">{total ? Math.round((r.v / total) * 100) : 0}%</span></span></div>)}</div></div></Card>
        <Card cls="pad-0"><List>{cats.map((c) => edit === c.id ? <div key={c.id} className="row" style={{ gap: 8, padding: '8px 12px' }}><EmojiBox e={c.emoji} /><div className="input grow"><input className="ph-in" value={eName} onChange={(e) => setEName(e.target.value)} autoFocus /></div>{kind === 'expense' && <div className="input" style={{ width: 120 }}><input className="ph-in" value={eBudget} onChange={(e) => setEBudget(e.target.value.replace(/\D/g, ''))} placeholder="Budget" /></div>}<Btn label="Save" kind="primary" cls="sm" onClick={() => { s.updateCategory(c.id, { name: eName.trim() || c.name, budget: eBudget ? +eBudget : undefined }); setEdit(null); s.toast('Category updated'); }} /><Btn ic="x" kind="ghost" cls="sm" onClick={() => setEdit(null)} /></div>
          : <div key={c.id} className="lrow" style={{ cursor: 'default' }}><EmojiBox e={c.emoji} /><div className="grow"><div className="t">{c.name}</div><div className="s">{c.n} tx{c.budget ? ` · budget ${inr(c.budget)}` : ''}</div></div><div className="r"><div className="v">{inr(c.v)}</div></div><Dropdown kind="ghost" items={[{ label: 'Transactions', ic: 'list', onClick: () => {} }, { label: 'Rename / budget', ic: 'edit', onClick: () => { setEdit(c.id); setEName(c.name); setEBudget(c.budget ? String(c.budget) : ''); } }, { label: 'Delete', ic: 'trash', danger: true, onClick: () => setDel(c.id) }]} /></div>)}</List></Card>
      </div>
      <Confirm open={!!del} onClose={() => setDel(null)} title="Delete this category?" body="Existing transactions keep their history but show as uncategorised." onConfirm={() => { if (del) { s.deleteCategory(del); s.toast('Category deleted'); } }} />
    </Screen>
  );
}

/* ================= BUDGETS ================= */
export function BudgetsScreen() {
  const s = useStore(); const [edit, setEdit] = useState<string | null>(null); const [val, setVal] = useState(''); const [add, setAdd] = useState(false); const [addCat, setAddCat] = useState('');
  const byCat = spentByCategory(thisMonth(s.transactions)); const cats = s.categories.filter((c) => c.kind === 'expense' && c.budget); const total = totalBudget(s); const sp = cats.reduce((a, c) => a + (byCat[c.id] || 0), 0);
  const dim = daysInMonth(todayISO()), left = dim - +todayISO().slice(8) + 1; const unbudgeted = s.categories.filter((c) => c.kind === 'expense' && !c.budget && c.slug !== 'transfer');
  return (
    <Screen nav="money" top={{ title: 'Budgets', back: 'money', actions: [{ ic: 'plus', label: 'New budget', kind: 'primary', onClick: () => setAdd(true) }] }}>
      <Card><div className="row between"><Kpi l="Total budget" v={inr(total)} /><Kpi l="Spent" v={inr(sp)} /><Kpi l="Left" v={inr(Math.max(0, total - sp))} d={`${left} days`} /></div><div className="mt-s"><Bar pct={total ? (sp / total) * 100 : 0} tone={sp > total ? 'danger' : ''} /></div><div className="xs muted mt-s">Safe to spend: <b>{inr(Math.max(0, (total - sp) / left))} / day</b> until {fmtShort(`${todayISO().slice(0, 8)}${dim}`)}</div></Card>
      {add && <Card cls="soft"><div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><Select value={addCat} onChange={setAddCat} options={[{ value: '', label: 'Choose a category…' }, ...unbudgeted.map((c) => ({ value: c.id, label: `${c.emoji} ${c.name}` }))]} cls="grow" /><TextInput value={val} onChange={(v) => setVal(v.replace(/\D/g, ''))} placeholder="Monthly limit" inputMode="numeric" sfx="₹" style={{ width: 170 }} /><Btn label="Set budget" kind="primary" onClick={() => { if (addCat && val) { s.updateCategory(addCat, { budget: +val }); s.toast('Budget set'); setAdd(false); setVal(''); setAddCat(''); } }} /><Btn ic="x" kind="ghost" onClick={() => setAdd(false)} /></div>{unbudgeted.length === 0 && <div className="xs muted mt-s">Every category has a budget — create a new category first.</div>}</Card>}
      <div className="split">{cats.map((c) => { const v = byCat[c.id] || 0; const p = Math.round((v / c.budget!) * 100); const over = v > c.budget!; return (
        <Card key={c.id}><div className="row between"><div className="row" style={{ gap: 10 }}><EmojiBox e={c.emoji} /><div><div className="b">{c.name}</div><div className="xs muted">Monthly · resets 1 {MON[(+todayISO().slice(5, 7)) % 12]}</div></div></div>{over ? <Chip tone="danger" ic="alert" cls="sm">Over by {inr(v - c.budget!)}</Chip> : p > 90 ? <Chip tone="warning" cls="sm">Near limit</Chip> : <Chip tone="success" cls="sm">On track</Chip>}</div><div className="mt"><Bar pct={p} tone={over ? 'danger' : p > 90 ? 'warning' : ''} cls="thick" /></div>
          {edit === c.id ? <div className="row mt-s" style={{ gap: 6 }}><TextInput value={val} onChange={(x) => setVal(x.replace(/\D/g, ''))} inputMode="numeric" sfx="₹" cls="grow" autoFocus onEnter={() => { s.updateCategory(c.id, { budget: +val || c.budget }); setEdit(null); s.toast('Budget updated'); }} /><Btn label="Save" kind="primary" cls="sm" onClick={() => { s.updateCategory(c.id, { budget: +val || c.budget }); setEdit(null); s.toast('Budget updated'); }} /><Btn label="Remove" kind="ghost" cls="sm" onClick={() => { s.updateCategory(c.id, { budget: undefined }); setEdit(null); s.toast('Budget removed'); }} /></div>
            : <div className="row between sm mt-s"><span className="num"><b>{inr(v)}</b> <span className="muted">of {inr(c.budget!)}</span></span><span className="row" style={{ gap: 8 }}><span className="muted">{over ? '—' : inr(c.budget! - v) + ' left'}</span><a className="link xs" onClick={() => { setEdit(c.id); setVal(String(c.budget)); }}>Edit</a></span></div>}
        </Card>); })}</div>
    </Screen>
  );
}

/* ================= RECURRING ================= */
export function RecurringScreen() {
  const s = useStore(); const [tab, setTab] = useState('Bills'); const [add, setAdd] = useState(false); const [name, setName] = useState(''); const [amt, setAmt] = useState(''); const [day, setDay] = useState('1'); const [emoji, setEmoji] = useState('💡'); const [del, setDel] = useState<string | null>(null); const [dismissed, setDismissed] = useState(false);
  const kind = tab === 'Bills' ? 'bill' : tab === 'Income' ? 'income' : 'transfer'; const list = s.recurring.filter((r) => r.kind === kind);
  const today = +todayISO().slice(8), dim = daysInMonth(todayISO()); const daysTo = (d: number) => (d - today + dim) % dim;
  const monthTotal = s.recurring.filter((r) => r.kind !== 'income').reduce((a, r) => a + r.amount, 0) + s.subscriptions.filter((x) => x.cycle === 'Monthly').reduce((a, x) => a + x.amount, 0);
  const soon = [...s.recurring.filter((r) => r.kind === 'bill' && daysTo(r.day) <= 7)].sort((a, b) => daysTo(a.day) - daysTo(b.day)); const paidThisMonth = (r: { lastPaidOn?: string }) => !!r.lastPaidOn && monthKey(r.lastPaidOn) === monthKey(todayISO());
  const detected = s.transactions.filter((t) => t.merchant === 'Apollo Pharmacy').length > 0 && !s.recurring.some((r) => r.name.includes('Apollo')) && !dismissed;
  const create = () => { if (!name.trim() || !amt) return; s.addRecurring({ name: name.trim(), amount: +amt, day: Math.min(28, Math.max(1, +day || 1)), emoji, kind: kind as 'bill' | 'income' | 'transfer', reminder: true, accountId: s.accounts[0]?.id, toAccountId: kind === 'transfer' ? s.accounts[1]?.id : undefined }); s.toast(`Recurring ${kind} added`); setAdd(false); setName(''); setAmt(''); };
  return (
    <Screen nav="money" top={{ title: 'Recurring', back: 'money', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setAdd(true) }] }}>
      <Tabs opts={['Bills', 'Income', 'Transfers']} on={tab} onChange={setTab} />
      <Card><div className="row between"><Kpi l="This month" v={inr(monthTotal)} d={`${s.recurring.length + s.subscriptions.length} items`} /><Kpi l="Next 7 days" v={inr(soon.filter((r) => !paidThisMonth(r)).reduce((a, r) => a + r.amount, 0))} d={`${soon.filter((r) => !paidThisMonth(r)).length} due`} /></div></Card>
      {add && <Card cls="soft"><div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><input className="input" style={{ width: 56, textAlign: 'center', flex: 'none' }} value={emoji} onChange={(e) => setEmoji(e.target.value.slice(-2))} /><TextInput value={name} onChange={setName} placeholder={kind === 'income' ? 'e.g. Salary' : 'e.g. Electricity'} cls="grow" autoFocus /><TextInput value={amt} onChange={(v) => setAmt(v.replace(/\D/g, ''))} placeholder="Amount" inputMode="numeric" sfx="₹" style={{ width: 140 }} /><TextInput value={day} onChange={(v) => setDay(v.replace(/\D/g, ''))} placeholder="Day" inputMode="numeric" sfx="of month" style={{ width: 140 }} /><Btn label="Add" kind="primary" onClick={create} /><Btn ic="x" kind="ghost" onClick={() => setAdd(false)} /></div></Card>}
      {tab === 'Bills' && soon.length > 0 && <Section title="Due soon"><Card cls="pad-0"><List>{soon.map((r) => <LRow key={r.id} emoji={r.emoji} t={r.name} s={`${daysTo(r.day) === 0 ? 'Today' : daysTo(r.day) === 1 ? 'Tomorrow' : `in ${daysTo(r.day)} days`} · ${r.method}`} v={inr(r.amount)} vs={r.reminder ? 'Pay reminder set' : 'Auto'} chev={false} extra={<div className="row mt-s" style={{ gap: 6 }}>{r.lastPaidOn && monthKey(r.lastPaidOn) === monthKey(todayISO()) ? <Chip tone="success" ic="check" cls="sm">Paid {fmtDay(r.lastPaidOn)}</Chip> : <Btn label="Mark paid" kind="soft" cls="sm" onClick={() => s.payRecurring(r.id).then(() => s.toast(`Paid · ${r.name} · ${inr(r.amount)}`)).catch((e) => s.toast(errMsg(e), { tone: 'danger' }))} />}</div>} />)}</List></Card></Section>}
      <Section title={`All ${tab.toLowerCase()}`}><Card cls="pad-0">{list.length === 0 ? <Empty ic="repeat" title={`No recurring ${tab.toLowerCase()} yet`} p="" action={<Btn label="Add one" ic="plus" kind="primary" onClick={() => setAdd(true)} />} /> : <List>{list.sort((a, b) => a.day - b.day).map((r) => <div key={r.id} className="lrow" style={{ cursor: 'default' }}><EmojiBox e={r.emoji} /><div className="grow"><div className="t">{r.name}</div><div className="s">{r.day}{r.day === 1 ? 'st' : r.day === 2 ? 'nd' : r.day === 3 ? 'rd' : 'th'} · {r.method}</div></div><div className="r"><div className="v">{inr(r.amount)}</div><div className="s">{r.reminder ? 'reminder on' : ''}</div></div><Dropdown kind="ghost" items={[{ label: r.reminder ? 'Turn reminder off' : 'Turn reminder on', ic: 'bell', onClick: () => s.updateRecurring(r.id, { reminder: !r.reminder }) }, { label: 'Delete', ic: 'trash', danger: true, onClick: () => setDel(r.id) }]} /></div>)}</List>}</Card></Section>
      {detected && <div className="banner info"><Icon name="sparkles" /><div className="grow">Mitra noticed <b>Apollo Pharmacy ₹780</b> on the 11th for three months in a row. Make it recurring?</div><Btn label="Yes" kind="soft" cls="sm" onClick={() => { s.addRecurring({ name: 'Apollo Pharmacy', amount: 780, day: 11, emoji: '💊', kind: 'bill' }); s.logAiAction({ action: 'Created recurring', detail: 'Apollo Pharmacy ₹780 · monthly', state: 'Approved by you', tone: 'success', revertible: true }); s.toast('Recurring bill added'); }} /><Btn label="No" kind="ghost" cls="sm" onClick={() => setDismissed(true)} /></div>}
      <Confirm open={!!del} onClose={() => setDel(null)} title="Remove this recurring item?" body="Past transactions are kept." onConfirm={() => { if (del) { s.deleteRecurring(del); s.toast('Removed'); } }} />
    </Screen>
  );
}

/* ================= SUBSCRIPTIONS ================= */
export function SubscriptionsScreen() {
  const s = useStore(); const [add, setAdd] = useState(false); const [name, setName] = useState(''); const [amt, setAmt] = useState(''); const [cycle, setCycle] = useState<'Monthly' | 'Yearly'>('Monthly'); const [del, setDel] = useState<string | null>(null);
  const monthly = s.subscriptions.reduce((a, x) => a + (x.cycle === 'Monthly' ? x.amount : x.amount / 12), 0); const unused = s.subscriptions.filter((x) => x.lastUsed && diffDays(todayISO(), x.lastUsed) > 30);
  const soon = [...s.subscriptions].sort((a, b) => a.next.localeCompare(b.next)).filter((x) => diffDays(x.next, todayISO()) <= 30);
  const create = () => { if (!name.trim() || !amt) return; s.addSubscription({ name: name.trim(), amount: +amt, cycle }); s.toast(`Subscription added · ${name}`); setAdd(false); setName(''); setAmt(''); };
  return (
    <Screen nav="money" top={{ title: 'Subscriptions', back: 'money', actions: [{ ic: 'plus', kind: 'primary', onClick: () => setAdd(true) }] }}>
      <Card><div className="row between"><Kpi l="Monthly" v={inr(monthly)} d={`${s.subscriptions.length} active`} /><Kpi l="Yearly equivalent" v={inr(monthly * 12)} /><Kpi l="Unused 30d" v={String(unused.length)} d={unused.map((x) => x.name.split(' ')[0]).join(', ') || 'none'} /></div></Card>
      {add && <Card cls="soft"><div className="row" style={{ gap: 8, flexWrap: 'wrap' }}><TextInput value={name} onChange={setName} placeholder="Service name" cls="grow" autoFocus /><TextInput value={amt} onChange={(v) => setAmt(v.replace(/\D/g, ''))} placeholder="Amount" inputMode="numeric" sfx="₹" style={{ width: 140 }} /><Segmented opts={['Monthly', 'Yearly']} on={cycle} onChange={(v) => setCycle(v as 'Monthly' | 'Yearly')} /><Btn label="Add" kind="primary" onClick={create} /><Btn ic="x" kind="ghost" onClick={() => setAdd(false)} /></div></Card>}
      <Card cls="pad-0"><List>{s.subscriptions.map((x) => <div key={x.id} className="sub-row" style={{ padding: '10px 12px' }}><div className="logo" style={{ background: x.color }}>{x.letter}</div><div className="grow"><div className="b sm">{x.name}</div><div className="xs muted">{x.cycle} · next {fmtDay(x.next)}{x.lastUsed ? ` · last used ${fmtDay(x.lastUsed)}` : ''}</div></div><div style={{ textAlign: 'right' }}><div className="b num">{inr(x.amount)}</div>{x.lastUsed && diffDays(todayISO(), x.lastUsed) > 30 && <Chip tone="warning" cls="sm">Not used {diffDays(todayISO(), x.lastUsed)}d</Chip>}</div><Dropdown kind="ghost" items={[{ label: 'Mark as used today', ic: 'check', onClick: () => { s.updateSubscription(x.id, { lastUsed: todayISO() }); s.toast(`${x.name} · used today`); } }, { label: 'Cancel guidance', ic: 'external', onClick: () => s.toast('Mitra never cancels anything itself — open the service to cancel', { tone: 'accent' }) }, { label: 'Remove', ic: 'trash', danger: true, onClick: () => setDel(x.id) }]} /></div>)}</List></Card>
      <Section title="Renewals · next 30 days"><Card>{soon.length ? <Bars vals={soon.map((x) => x.amount)} labels={soon.map((x) => fmtShort(x.next))} h={110} showVals unit="₹" /> : <div className="list-empty">Nothing renews in the next 30 days.</div>}</Card></Section>
      <Confirm open={!!del} onClose={() => setDel(null)} title="Remove this subscription?" body="This only removes it from tracking." onConfirm={() => { if (del) { s.deleteSubscription(del); s.toast('Removed'); } }} />
    </Screen>
  );
}
