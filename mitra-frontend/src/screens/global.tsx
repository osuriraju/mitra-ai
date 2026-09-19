'use client';
/* ---------- GLOBAL: auth, onboarding, profile, settings, ⌘K palette, notifications, quick add, UI states, design system ---------- */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Screen } from '@/components/shell/Screen';
import { Avatar, Bar, Bars, Btn, Card, Chip, Empty, Field, HabitRow, Icon, IconBox, Kpi, LRow, List, Ring, Section, Segmented, SetRow, Sheet, TaskRow, Toggle, ToggleRow, TxRow } from '@/components/ui';
import { Confirm, Modal, Select, TextInput } from '@/components/ui/controls';
import { ask } from '@/lib/ai';
import { errMsg } from '@/lib/api';
import { HABITS, TASKS, TX } from '@/lib/data';
import { fmtDay, fmtMonth, todayISO } from '@/lib/dates';
import { NAV, href, type ScreenId } from '@/lib/routes';
import { useStore } from '@/store';
import { noteSearch, streak, goalProgress } from '@/store/selectors';
import { ThemeSegment } from '@/screens/settings-theme';
import { TodayScreen } from '@/screens/today';
import { applyProposal } from '@/screens/ai';
import { useTheme } from '@/components/theme/ThemeProvider';

/* ---------- Login ---------- */
const DEV = process.env.NODE_ENV === 'development';
export function LoginScreen() {
  const router = useRouter(); const s = useStore(); const next = useSearchParams().get('next'); const [email, setEmail] = useState(DEV ? 'aarav@example.com' : ''); const [pw, setPw] = useState(DEV ? 'password123' : ''); const [show, setShow] = useState(false); const [keep, setKeep] = useState(true); const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (email.trim().length < 3) { setErr('Enter your email or username'); return; } if (!pw) { setErr('Enter your password'); return; }
    setErr(''); setBusy(true);
    try { const u = await s.login(email.trim(), pw, keep); s.toast(`Welcome back, ${u.name.split(' ')[0]}`); router.replace(!u.onboardedAt ? href('onboarding') : next && next.startsWith('/') ? next : href('today')); }
    catch (e) { setErr(errMsg(e)); setBusy(false); }
  };
  return (
    <div className="auth">
      <div className="side"><div style={{ fontSize: 44, fontWeight: 800, fontFamily: 'var(--font-display)' }}>M</div><h2>One calm place for your money, time, habits and goals.</h2><p>Mitra quietly collects what you do and surfaces what matters — so you never feel like you&apos;re maintaining a database.</p></div>
      <div className="main">
        <div className="hero"><div className="mark">M</div><h1 style={{ fontSize: 28 }}>Welcome back</h1><p className="muted">Sign in to continue to Mitra AI.</p></div>
        <div className="form">
          <TextInput label="Email or username" value={email} onChange={setEmail} ic="mail" onEnter={submit} autoFocus={!DEV} />
          <TextInput label="Password" value={pw} onChange={setPw} ic="lock" type={show ? 'text' : 'password'} sfx={<a className="link" onClick={() => setShow((v) => !v)}>{show ? 'Hide' : 'Show'}</a>} err={err} onEnter={submit} />
          <div className="row between"><label className="row sm" style={{ gap: 8, cursor: 'pointer' }} onClick={() => setKeep((v) => !v)}><Toggle on={keep} style={{ transform: 'scale(.8)', transformOrigin: 'left' }} />Keep me signed in</label><Link className="link" href="/forgot-password">Forgot password?</Link></div>
          <Btn label={busy ? 'Signing in…' : 'Sign in'} kind="primary" size="lg" cls="block" onClick={submit} />
          <p className="sm muted" style={{ textAlign: 'center' }}>New to Mitra? <Link className="link" href={href('signup')}>Create an account</Link></p>
          <p className="xs faint" style={{ textAlign: 'center' }}><Icon name="shield" /> Encrypted in transit · Your financial and wellness data are permission-separated.</p>
        </div>
      </div>
    </div>
  );
}

/* ---------- Forgot / reset password ---------- */
export function ForgotPasswordScreen() {
  const s = useStore(); const [email, setEmail] = useState(''); const [err, setErr] = useState(''); const [busy, setBusy] = useState(false); const [sent, setSent] = useState(false);
  const submit = async () => { if (!/^\S+@\S+\.\S+$/.test(email.trim())) { setErr('Enter a valid email'); return; } setErr(''); setBusy(true); try { await s.forgotPassword(email.trim()); setSent(true); } catch (e) { setErr(errMsg(e)); } finally { setBusy(false); } };
  return (
    <div className="auth"><div className="main"><div className="hero"><div className="mark">M</div><h1 style={{ fontSize: 28 }}>Reset your password</h1><p className="muted">We&apos;ll email you a link that works for one hour.</p></div>
      <div className="form">
        {sent ? <div className="banner info"><Icon name="mail" /><div className="grow">If <b>{email.trim()}</b> has an account, a reset link is on its way. Check spam too.</div></div> : <>
          <TextInput label="Email" value={email} onChange={setEmail} ic="mail" placeholder="you@example.com" err={err} onEnter={submit} autoFocus />
          <Btn label={busy ? 'Sending…' : 'Send reset link'} kind="primary" size="lg" cls="block" onClick={submit} />
        </>}
        <p className="sm muted" style={{ textAlign: 'center' }}><Link className="link" href={href('login')}>Back to sign in</Link></p>
      </div></div></div>
  );
}
export function ResetPasswordScreen() {
  const router = useRouter(); const s = useStore(); const params = useSearchParams(); const token = params.get('token') || '';
  const [pw, setPw] = useState(''); const [pw2, setPw2] = useState(''); const [err, setErr] = useState(''); const [busy, setBusy] = useState(false); const [done, setDone] = useState(false);
  const submit = async () => { if (pw.length < 8) { setErr('At least 8 characters'); return; } if (pw !== pw2) { setErr('Passwords do not match'); return; } setErr(''); setBusy(true); try { await s.resetPassword(token, pw); setDone(true); s.toast('Password updated — sign in with the new one'); setTimeout(() => router.replace(href('login')), 1200); } catch (e) { setErr(errMsg(e)); setBusy(false); } };
  return (
    <div className="auth"><div className="main"><div className="hero"><div className="mark">M</div><h1 style={{ fontSize: 28 }}>Choose a new password</h1><p className="muted">This signs you out of every other device.</p></div>
      <div className="form">
        {!token ? <div className="banner danger"><Icon name="alert" /><div className="grow">This link is missing its token. <Link className="link" href="/forgot-password">Request a new one</Link>.</div></div> : <>
          <TextInput label="New password" value={pw} onChange={setPw} ic="lock" type="password" placeholder="At least 8 characters" autoFocus />
          <TextInput label="Repeat password" value={pw2} onChange={setPw2} ic="lock" type="password" err={err} onEnter={submit} />
          <Btn label={done ? 'Done ✓' : busy ? 'Saving…' : 'Set new password'} kind="primary" size="lg" cls="block" onClick={submit} />
        </>}
        <p className="sm muted" style={{ textAlign: 'center' }}><Link className="link" href={href('login')}>Back to sign in</Link></p>
      </div></div></div>
  );
}

/* ---------- Sign up ---------- */
export function SignupScreen() {
  const router = useRouter(); const s = useStore(); const [name, setName] = useState(''); const [email, setEmail] = useState(''); const [pw, setPw] = useState(''); const [errs, setErrs] = useState<Record<string, string>>({}); const [busy, setBusy] = useState(false);
  const strength = pw.length >= 12 ? 'Strong' : pw.length >= 8 ? 'OK' : pw ? 'Too short' : '';
  const submit = async () => {
    const e: Record<string, string> = {}; if (!name.trim()) e.name = 'Enter your name'; if (!/^\S+@\S+\.\S+$/.test(email)) e.email = 'Enter a valid email'; if (pw.length < 8) e.pw = 'At least 8 characters'; setErrs(e); if (Object.keys(e).length) return;
    setBusy(true);
    try { await s.signup(name.trim(), email.trim(), pw); s.toast(`Account created for ${name.trim().split(' ')[0]}`); router.replace(href('onboarding')); }
    catch (err) { setErrs({ [errMsg(err).toLowerCase().includes('email') ? 'email' : 'pw']: errMsg(err) }); setBusy(false); }
  };
  return (
    <div className="auth">
      <div className="side"><div style={{ fontSize: 44, fontWeight: 800, fontFamily: 'var(--font-display)' }}>M</div><h2>Capture in seconds. Understand in minutes.</h2><p>Tasks, habits, goals, money, calendar, wellness and notes — connected, private, yours.</p></div>
      <div className="main">
        <div className="hero"><div className="mark">M</div><h1 style={{ fontSize: 28 }}>Create your account</h1><p className="muted">It takes under a minute.</p></div>
        <div className="form">
          <TextInput label="Full name" value={name} onChange={setName} ic="user" placeholder="Aarav Mehta" err={errs.name} autoFocus />
          <TextInput label="Email" value={email} onChange={setEmail} ic="mail" placeholder="you@example.com" err={errs.email} />
          <TextInput label="Password" value={pw} onChange={setPw} ic="lock" type="password" placeholder="At least 8 characters" hint={strength ? `Strength: ${strength} · use a passphrase you can remember. We never see it.` : 'Use a passphrase you can remember. We never see it.'} err={errs.pw} onEnter={submit} />
          <Field label="Currency & region" value="India · ₹ INR · Asia/Kolkata" ic="map" hint="INR only in this version" />
          <Btn label={busy ? 'Creating…' : 'Create account'} kind="primary" size="lg" cls="block" onClick={submit} />
          <p className="xs faint" style={{ textAlign: 'center' }}>By continuing you agree to the Terms and Privacy Policy. You can export or delete all your data at any time.</p>
          <p className="sm muted" style={{ textAlign: 'center' }}>Already have an account? <Link className="link" href={href('login')}>Sign in</Link></p>
        </div>
      </div>
    </div>
  );
}

/* ---------- Onboarding (4 steps) ---------- */
const ONB: [string, string, string, keyof ReturnType<typeof useStore.getState>['settings']['modules'] | ''][] = [['check-square', 'Tasks & projects', 'Inbox, today, upcoming, focus mode', ''], ['wallet', 'Money', 'Expenses, budgets, bills, subscriptions', 'money'], ['repeat', 'Habits', 'Daily and flexible habits with gentle streaks', ''], ['target', 'Goals', 'Milestones linked to habits and tasks', ''], ['heart', 'Wellness', 'Sleep, movement, water, mood — no medical claims', 'wellness'], ['note', 'Notes & journal', 'Quick notes, daily journal, second brain', '']];
export function OnboardingScreen() {
  const s = useStore(); const router = useRouter(); const [step, setStep] = useState(1); const [picked, setPicked] = useState<Set<number>>(new Set([0, 1, 2])); const [capture, setCapture] = useState('Spent ₹450 on dinner'); const [notif, setNotif] = useState({ tasks: true, habits: true, bills: true, digest: true });
  const toggle = (i: number) => setPicked((p) => { const n = new Set(p); if (n.has(i)) n.delete(i); else n.add(i); return n; });
  const first = s.user?.name.split(' ')[0] || 'there';
  const finish = () => { const settings = { modules: { money: picked.has(1), wellness: picked.has(4), ai: true }, digest: notif.digest }; s.updateSettings(settings); s.markOnboarded(settings).catch(() => {}); if (capture.trim()) { const r = ask(capture, useStore.getState()); if (r.proposal) applyProposal(r.proposal, useStore.getState()); } s.toast('You’re set. Today is built from your choices.'); router.replace(href('today')); };
  return (
    <div className="auth"><div className="main" style={{ justifyContent: 'flex-start' }}><div className="form" style={{ maxWidth: 520, paddingTop: 52, gap: 18 }}>
      <div className="row between"><span className="eyebrow">Step {step} of 4</span><a className="link" onClick={() => step < 4 ? setStep(step + 1) : finish()}>Skip</a></div>
      <div className="onb-dots">{[1, 2, 3, 4].map((i) => <i key={i} className={i === step ? 'on' : ''} />)}</div>
      {step === 1 && <><div><h1 style={{ fontSize: 26 }}>What do you want Mitra to help with first?</h1><p className="muted" style={{ marginTop: 6 }}>Pick a few. This sets up your Today screen and Quick Add — you can change it any time.</p></div><div className="col" style={{ gap: 10 }}>{ONB.map(([ic, t, sub], i) => <div key={t} className={`opt ${picked.has(i) ? 'on' : ''}`} onClick={() => toggle(i)}><IconBox ic={ic} tone={picked.has(i) ? 'accent' : ''} /><div><div className="t">{t}</div><div className="s">{sub}</div></div><div className="chk"><Icon name="check" /></div></div>)}</div></>}
      {step === 2 && <><div><h1 style={{ fontSize: 26 }}>Try a first capture</h1><p className="muted" style={{ marginTop: 6 }}>Type naturally. Mitra previews before saving anything consequential.</p></div><div className="composer"><Icon name="sparkles" /><input className="ph-in" value={capture} onChange={(e) => setCapture(e.target.value)} placeholder="Spent ₹450 on dinner · Call John tomorrow 6 pm" /></div>{capture.trim() && (() => { const r = ask(capture, useStore.getState()); return r.proposal ? <div className="card soft tight sm"><b>Preview:</b> {r.proposal.title} <span className="muted">· {r.proposal.sub}</span></div> : <div className="card soft tight sm muted">{r.text}</div>; })()}<div className="card soft tight row" style={{ gap: 10 }}><Icon name="sparkles" /><div className="sm">Try “Remind me to call John tomorrow at 6” or “Ran 5K”.</div></div></>}
      {step === 3 && <><div><h1 style={{ fontSize: 26 }}>Notifications</h1><p className="muted" style={{ marginTop: 6 }}>Only what’s actionable. Quiet hours 10:30 PM – 7:00 AM by default.</p></div><Card cls="pad-0"><List><ToggleRow t="Task reminders" s="Due and overdue" ic="check-square" on={notif.tasks} onChange={(v) => setNotif({ ...notif, tasks: v })} /><ToggleRow t="Habit reminders" s="At the time you set per habit" ic="repeat" on={notif.habits} onChange={(v) => setNotif({ ...notif, habits: v })} /><ToggleRow t="Bills & subscriptions" s="3 days before" ic="wallet" on={notif.bills} onChange={(v) => setNotif({ ...notif, bills: v })} /><ToggleRow t="Daily digest" s="8:00 AM summary instead of pings" ic="mail" on={notif.digest} onChange={(v) => setNotif({ ...notif, digest: v })} /></List></Card></>}
      {step === 4 && <><div><h1 style={{ fontSize: 26 }}>All set, {first}.</h1><p className="muted" style={{ marginTop: 6 }}>Today shows {[...picked].length} modules. Quick add is always one tap away.</p></div><Card cls="soft"><div className="sm col" style={{ gap: 6 }}><div><Icon name="check" /> {[...picked].map((i) => ONB[i][1]).join(', ')}</div><div><Icon name="sparkles" /> First capture: {capture || 'skipped'}</div><div><Icon name="bell" /> {Object.values(notif).filter(Boolean).length} notification types on</div></div></Card></>}
      <div className="row" style={{ gap: 8 }}><Btn label="Back" kind="outline" size="lg" onClick={() => step === 1 ? router.push(href('signup')) : setStep(step - 1)} /><Btn label={step < 4 ? 'Continue' : 'Open Today'} kind="primary" size="lg" cls="grow" onClick={() => step < 4 ? setStep(step + 1) : finish()} /></div>
    </div></div></div>
  );
}

/* ---------- Profile ---------- */
export function ProfileScreen() {
  const s = useStore(); const router = useRouter(); const u = s.user;
  const [edit, setEdit] = useState(false); const [name, setName] = useState(''); const [email, setEmail] = useState(''); const [phone, setPhone] = useState(''); const [saveErr, setSaveErr] = useState('');
  const [del, setDel] = useState(false); const [confirmText, setConfirmText] = useState(''); const [delPw, setDelPw] = useState(''); const [delErr, setDelErr] = useState('');
  const [addAcc, setAddAcc] = useState(false); const [accName, setAccName] = useState(''); const [accBal, setAccBal] = useState(''); const [accKind, setAccKind] = useState('bank'); const [reset, setReset] = useState(false);
  const [pwOpen, setPwOpen] = useState(false); const [curPw, setCurPw] = useState(''); const [newPw, setNewPw] = useState(''); const [pwErr, setPwErr] = useState(''); const [busy, setBusy] = useState(false);
  useEffect(() => { if (u) { setName(u.name); setEmail(u.email); setPhone(u.phone || ''); } }, [u]);
  const doneAll = s.tasks.filter((t) => t.done).length; const best = Math.max(0, ...s.habits.map(streak)); const bestH = s.habits.find((h) => streak(h) === best);
  const displayName = u?.name || name || '—'; const initials = displayName.split(' ').map((x) => x[0]).join('').slice(0, 2).toUpperCase();
  const exportData = async () => { try { const data = await s.exportData(); const blob = new Blob([JSON.stringify({ ...(data as object), local: { tasks: s.tasks, projects: s.projects, habits: s.habits, goals: s.goals, notes: s.notes, wellness: s.wellness } }, null, 2)], { type: 'application/json' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `mitra-export-${todayISO()}.json`; a.click(); s.toast('Export downloaded (JSON)'); } catch (e) { s.toast(errMsg(e), { tone: 'danger' }); } };
  const saveProfile = async () => { setBusy(true); setSaveErr(''); try { await s.updateProfile({ name: name.trim(), email: email.trim(), phone: phone.trim() || null }); setEdit(false); s.toast('Profile saved'); } catch (e) { setSaveErr(errMsg(e)); } finally { setBusy(false); } };
  const savePw = async () => { if (newPw.length < 8) { setPwErr('New password needs at least 8 characters'); return; } setBusy(true); setPwErr(''); try { await s.changePassword(curPw, newPw); setPwOpen(false); setCurPw(''); setNewPw(''); s.toast('Password changed · other devices signed out'); } catch (e) { setPwErr(errMsg(e)); } finally { setBusy(false); } };
  const deleteAcc = async () => { if (confirmText !== email || !delPw) return; setBusy(true); setDelErr(''); try { await s.deleteAccount(delPw); s.toast('Account scheduled for deletion · sign in within 30 days to cancel'); router.replace(href('login')); } catch (e) { setDelErr(errMsg(e)); setBusy(false); } };
  return (
    <Screen nav="settings" top={{ title: 'Profile', back: 'today', actions: [{ ic: 'edit', label: 'Edit', kind: 'outline', onClick: () => setEdit(true) }] }} narrow>
      <Card><div className="row" style={{ gap: 14 }}><Avatar i={initials} cls="lg" /><div className="grow"><h2 style={{ fontSize: 20 }}>{displayName}</h2><div className="muted sm">{u?.email || email}</div><div className="row mt-s" style={{ gap: 6 }}><Chip>Free plan</Chip><Chip>Member since {u ? fmtMonth(u.memberSince.slice(0, 10)) : '—'}</Chip></div></div></div></Card>
      <div className="kpi-grid"><Kpi l="Tasks done" v={String(doneAll)} d="all time" /><Kpi l="Best streak" v={`${best} days`} d={bestH?.name || ''} /><Kpi l="Goals active" v={String(s.goals.filter((g) => g.status === 'active').length)} d={`${s.goals.filter((g) => g.status === 'active' && goalProgress(g, s.habits) > 50).length} past halfway`} /><Kpi l="Transactions" v={String(s.transactions.length)} d="last 12 months" /></div>
      <Card cls="pad-0"><List>
        <SetRow t="Personal details" s={`${displayName}${u?.phone ? ' · ' + u.phone : ''}`} ic="user" onClick={() => setEdit(true)} />
        <SetRow t="Password" s="Change your password" ic="lock" onClick={() => setPwOpen(true)} />
        <SetRow t="Region & currency" s="India · ₹ INR · Asia/Kolkata" ic="map" onClick={() => s.toast('INR-only in this version')} />
        <SetRow t="Accounts & cards" s={s.accounts.map((a) => a.name).join(', ') || 'No accounts yet'} r={String(s.accounts.length)} ic="wallet" onClick={() => setAddAcc((v) => !v)} />
        {addAcc && <div style={{ padding: '4px 14px 10px' }}>
          <List>{s.accounts.map((a) => <div key={a.id} className="row" style={{ padding: '8px 0', gap: 8 }}><IconBox ic={a.kind === 'credit' ? 'columns' : 'wallet'} cls="sm" /><div className="grow"><div className="sm b">{a.name} <span className="muted" style={{ fontWeight: 500 }}>{a.mask}</span></div><div className="xs muted">{a.kind}</div></div><span className="sm num">{a.balance < 0 ? '−' : ''}₹{Math.abs(a.balance).toLocaleString('en-IN')}</span><Btn ic="trash" kind="ghost" cls="sm" title="Remove" onClick={() => { s.removeAccount(a.id); s.toast(`${a.name} removed`); }} /></div>)}</List>
          <div className="row mt-s" style={{ gap: 8, flexWrap: 'wrap' }}><TextInput value={accName} onChange={setAccName} placeholder="Account name" cls="grow" autoFocus /><Select value={accKind} onChange={setAccKind} options={['bank', 'credit', 'cash']} /><TextInput value={accBal} onChange={(v) => setAccBal(v.replace(/[^\d-]/g, ''))} placeholder="Balance" sfx="₹" style={{ width: 140 }} /><Btn label="Add" kind="primary" onClick={() => { if (!accName.trim()) return; s.addAccount({ name: accName.trim(), balance: +accBal || 0, kind: accKind as 'bank' | 'credit' | 'cash', mask: '••' + String(Math.floor(1000 + Math.random() * 9000)) }); s.toast('Account added'); setAccName(''); setAccBal(''); }} /></div>
        </div>}
        <SetRow t="Connected services" s="Apple Health / Health Connect (later phase)" ic="link" onClick={() => s.toast('Integrations arrive in phase 7')} />
        <SetRow t="Export my data" s="JSON, includes all modules" ic="download" onClick={exportData} />
        <SetRow t="Reset sample data" s="Restore the sample tasks, habits, goals and notes (money is kept)" ic="refresh" onClick={() => setReset(true)} />
        <SetRow t="Delete account" s="Permanent. 30-day grace period." ic="trash" tone="danger" onClick={() => setDel(true)} />
      </List></Card>
      <Btn label="Sign out" ic="logout" kind="outline" cls="block" onClick={async () => { await s.logout(); s.toast('Signed out'); router.replace(href('login')); }} />
      <Modal open={edit} onClose={() => setEdit(false)} title="Personal details" footer={<><Btn label="Cancel" kind="outline" onClick={() => setEdit(false)} /><span className="grow" /><Btn label={busy ? 'Saving…' : 'Save'} kind="primary" onClick={saveProfile} /></>}><TextInput label="Full name" value={name} onChange={setName} ic="user" /><TextInput label="Email" value={email} onChange={setEmail} ic="mail" err={saveErr} /><TextInput label="Phone" value={phone} onChange={setPhone} ic="phone" placeholder="Optional" /></Modal>
      <Modal open={pwOpen} onClose={() => setPwOpen(false)} title="Change password" footer={<><Btn label="Cancel" kind="outline" onClick={() => setPwOpen(false)} /><span className="grow" /><Btn label={busy ? 'Saving…' : 'Change password'} kind="primary" onClick={savePw} /></>}><TextInput label="Current password" value={curPw} onChange={setCurPw} ic="lock" type="password" autoFocus /><TextInput label="New password" value={newPw} onChange={setNewPw} ic="lock" type="password" placeholder="At least 8 characters" err={pwErr} onEnter={savePw} /><div className="xs muted">Changing it signs you out everywhere else.</div></Modal>
      <Modal open={del} onClose={() => setDel(false)} title="Delete account" footer={<><Btn label="Cancel" kind="outline" onClick={() => setDel(false)} /><span className="grow" /><Btn label={busy ? 'Deleting…' : 'Delete permanently'} kind="danger" onClick={deleteAcc} /></>}><div className="banner danger"><Icon name="alert" /><div className="grow">This removes everything after a 30-day grace period. Export first if you want a copy. Signing in again within 30 days cancels it.</div></div><TextInput label={`Type your email (${email}) to confirm`} value={confirmText} onChange={setConfirmText} err={confirmText && confirmText !== email ? 'Does not match' : ''} /><TextInput label="Your password" value={delPw} onChange={setDelPw} ic="lock" type="password" err={delErr} /></Modal>
      <Confirm open={reset} onClose={() => setReset(false)} title="Reset sample data?" body="Tasks, habits, goals, notes and wellness on this device go back to the sample data. Your account and Money data are not affected." confirmLabel="Reset" onConfirm={() => { s.resetAll(); s.toast('Sample data restored'); }} />
    </Screen>
  );
}

/* ---------- Settings ---------- */
const ACCENTS = ['#2DD4BF', '#4F46E5', '#C2410C', '#7C3AED', '#E11D48'];
export function SettingsScreen() {
  const s = useStore(); const st = s.settings; const set = (p: Partial<typeof st>) => s.updateSettings(p); const [accent, setAccent] = useState(''); const [sections, setSections] = useState(false);
  useEffect(() => { try { setAccent(localStorage.getItem('mitra.accent') || ''); } catch {} }, []);
  const pickAccent = (c: string) => { setAccent(c); try { localStorage.setItem('mitra.accent', c); } catch {} const f = document.querySelector<HTMLElement>('.frame'); if (f) { if (c === ACCENTS[0]) f.style.removeProperty('--accent'); else f.style.setProperty('--accent', c); } s.toast('Accent updated'); };
  const ALL_SECTIONS = ['priorities', 'activity', 'habits', 'checkin', 'money', 'goals']; const LABEL: Record<string, string> = { priorities: 'Priorities', activity: 'Activity', habits: 'Habits', checkin: 'Check-in', money: 'Money', goals: 'Goals' };
  const move = (k: string, dir: number) => { const a = [...st.todaySections]; const i = a.indexOf(k); const j = i + dir; if (i < 0 || j < 0 || j >= a.length) return; [a[i], a[j]] = [a[j], a[i]]; set({ todaySections: a }); };
  return (
    <Screen nav="settings" top={{ title: 'Settings', back: 'today' }} narrow>
      <Section title="Appearance"><Card cls="pad-0"><List>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="sun" /><div className="grow"><div className="t">Theme</div><div className="s">Follows system by default</div></div><ThemeSegment /></div>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="star" /><div className="grow"><div className="t">Accent colour</div><div className="s">One accent, used for primary actions</div></div><div className="row" style={{ gap: 6 }}>{ACCENTS.map((c) => <button type="button" key={c} onClick={() => pickAccent(c)} style={{ width: 22, height: 22, borderRadius: '50%', background: c, ...((accent || ACCENTS[0]) === c ? { outline: '2px solid var(--text)', outlineOffset: 2 } : {}) }} aria-label={c} />)}</div></div>
        <ToggleRow t="Reduce motion" s="Fewer animations and transitions" on={st.reduceMotion} ic="zap" onChange={(v) => { set({ reduceMotion: v }); document.querySelector('.frame')?.classList.toggle('reduce-motion', v); }} />
        <ToggleRow t="Compact density" s="Tighter lists on tablet and desktop" on={st.compact} ic="list" onChange={(v) => { set({ compact: v }); document.querySelector('.frame')?.classList.toggle('compact', v); }} />
      </List></Card></Section>
      <Section title="Today screen"><Card cls="pad-0"><List>
        <SetRow t="Sections & order" s={st.todaySections.map((k) => LABEL[k]).join(', ')} r={`${st.todaySections.length} shown`} ic="grid" onClick={() => setSections((v) => !v)} />
        {sections && <div style={{ padding: '4px 14px 10px' }}><List>{ALL_SECTIONS.map((k) => { const on = st.todaySections.includes(k); return <div key={k} className="row" style={{ padding: '8px 0', gap: 8 }}><Toggle on={on} onChange={(v) => set({ todaySections: v ? [...st.todaySections, k] : st.todaySections.filter((x) => x !== k) })} /><span className="grow sm b">{LABEL[k]}</span>{on && <><Btn ic="chevron-up" kind="ghost" cls="sm" onClick={() => move(k, -1)} /><Btn ic="chevron-down" kind="ghost" cls="sm" onClick={() => move(k, 1)} /></>}</div>; })}</List></div>}
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="calendar" /><div className="grow"><div className="t">Start of week</div></div><Segmented opts={['Monday', 'Sunday']} on={st.weekStart} onChange={(v) => set({ weekStart: v as 'Monday' | 'Sunday' })} /></div>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="clock" /><div className="grow"><div className="t">Day starts at</div></div><input type="time" className="ph-in" style={{ width: 110, textAlign: 'right' }} value={st.dayStart} onChange={(e) => set({ dayStart: e.target.value })} /></div>
      </List></Card></Section>
      <Section title="Modules"><Card cls="pad-0"><List><ToggleRow t="Money" s="Expenses, budgets, subscriptions" ic="wallet" on={st.modules.money} onChange={(v) => set({ modules: { ...st.modules, money: v } })} /><ToggleRow t="Wellness" s="Daily check-in and trends" ic="heart" on={st.modules.wellness} onChange={(v) => set({ modules: { ...st.modules, wellness: v } })} /><ToggleRow t="AI assistant" s="Natural-language capture, questions and planning" ic="sparkles" on={st.modules.ai} onChange={(v) => set({ modules: { ...st.modules, ai: v } })} /></List></Card></Section>
      <Section title="Notifications"><Card cls="pad-0"><List><SetRow t="Reminders by module" s="Tasks, habits, bills, check-ins" ic="bell" goto="notifications" /><ToggleRow t="Quiet hours" s="10:30 PM – 7:00 AM" ic="moon" on={st.quietHours} onChange={(v) => set({ quietHours: v })} /><ToggleRow t="Daily digest" s="8:00 AM · summary instead of individual pings" ic="mail" on={st.digest} onChange={(v) => set({ digest: v })} /></List></Card></Section>
      <Section title="AI & privacy"><Card cls="pad-0"><List>
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="sparkles" tone="accent" /><div className="grow"><div className="t">Auto-save AI captures</div><div className="s">Small expenses and tasks under ₹500 can be saved without preview</div></div><Segmented opts={['Always ask', 'Small only', 'Never']} on={st.autoSave} onChange={(v) => set({ autoSave: v as typeof st.autoSave })} /></div>
        <ToggleRow t="Share wellness data with AI" s="Off keeps mood, sleep and health out of AI context" on={st.shareWellness} ic="shield" onChange={(v) => { set({ shareWellness: v }); s.updateWellnessSettings({ shareAI: v }); }} />
        <div className="setrow" style={{ cursor: 'default' }}><IconBox ic="timer" /><div className="grow"><div className="t">Focus session length</div><div className="s">Used when a task has no estimate</div></div><Segmented opts={['15', '25', '45']} on={String(st.focusMinutes)} onChange={(v) => set({ focusMinutes: +v })} /><span className="xs muted">min</span></div>
        <SetRow t="AI activity log" s={`${s.aiActions.length} entries`} ic="history" goto="ai-log" />
        <SetRow t="Data & privacy" s="Export, delete, audit trail" ic="lock" goto="profile" />
      </List></Card></Section>
    </Screen>
  );
}

/* ---------- Command palette (⌘K) — live search over the store ---------- */
export function CommandScreen() {
  const s = useStore(); const router = useRouter(); const [q, setQ] = useState(''); const [idx, setIdx] = useState(0);
  const r = useMemo(() => noteSearch(s, q), [q, s]);
  const nav = NAV.filter((n) => !q || n.l.toLowerCase().includes(q.toLowerCase())).map((n) => ({ ic: n.ic, t: n.l, sub: 'Go to', href: href(n.goto) }));
  const goTo = [{ ic: 'sun', t: 'Daily review', sub: 'Go to', href: href('daily-review'), k: 'G R' }, { ic: 'repeat', t: 'Recurring payments', sub: 'Go to', href: href('recurring') }, { ic: 'settings', t: 'Settings', sub: 'Go to', href: href('settings') }, { ic: 'history', t: 'AI activity', sub: 'Go to', href: href('ai-log') }].filter((x) => !q || x.t.toLowerCase().includes(q.toLowerCase()));
  const ai = q ? ask(q, s) : null;
  const items = [
    ...(q ? [{ ic: 'plus', tone: 'accent', t: `Add task “${q}”`, sub: '', k: '↵', run: () => { const t = s.addTask({ title: q }); s.toast(`Task added · ${t.title}`); router.push(href('tasks-inbox')); } }, { ic: 'sparkles', tone: 'accent', t: ai?.proposal ? `${ai.proposal.title}` : `Ask Mitra: “${q}”`, sub: ai?.proposal ? `${ai.proposal.sub} · confirm in chat` : 'Natural language capture or question', k: '⇧↵', run: () => { s.pushAiMessage({ who: 'user', text: q }); const rr = ask(q, useStore.getState()); s.pushAiMessage({ who: 'ai', text: rr.text, chart: rr.chart, preview: rr.proposal ? { kind: rr.proposal.kind, title: rr.proposal.title, sub: rr.proposal.sub, payload: rr.proposal } : undefined }); router.push(href('ai')); } }] : []),
    ...r.tasks.slice(0, 4).map((t) => ({ ic: 'check-square', t: t.title, sub: `Task · ${t.done ? 'done' : t.due ? fmtDay(t.due) : 'Inbox'}`, run: () => router.push(`/tasks/${t.id}`) })),
    ...r.notes.slice(0, 3).map((n) => ({ ic: 'note', t: n.title, sub: `Note · ${n.folder} · ${fmtDay(n.updatedAt)}`, run: () => router.push(`/notes/${n.id}`) })),
    ...(q ? s.transactions.filter((t) => t.merchant.toLowerCase().includes(q.toLowerCase())).slice(0, 2).map((t) => ({ ic: 'wallet', t: `${t.merchant} · ₹${t.amount.toLocaleString('en-IN')}`, sub: `Expense · ${fmtDay(t.date)}`, run: () => router.push(href('transactions')) })) : []),
    ...r.goals.slice(0, 2).map((g) => ({ ic: 'target', t: g.name, sub: `Goal · ${goalProgress(g, s.habits)}%`, run: () => router.push(`/goals/${g.id}`) })),
    ...r.habits.slice(0, 2).map((h) => ({ ic: 'repeat', t: h.name, sub: 'Habit', run: () => router.push(`/habits/${h.id}`) })),
    ...(q ? [] : nav).map((n) => ({ ic: n.ic, t: n.t, sub: n.sub, run: () => router.push(n.href) })),
    ...goTo.map((n) => ({ ic: n.ic, t: n.t, sub: n.sub, k: 'k' in n ? n.k : undefined, run: () => router.push(n.href) })),
  ];
  useEffect(() => setIdx(0), [q]);
  const close = () => router.push(href('today'));
  return (
    <TodayScreen overlay={
      <div className="overlay top" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}><div className="sheet"><div className="cmdp">
        <div className="in"><Icon name="search" /><input className="ph-in" style={{ fontSize: 16 }} autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search or ask Mitra…" onKeyDown={(e) => { if (e.key === 'Escape') close(); if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(items.length - 1, i + 1)); } if (e.key === 'ArrowUp') { e.preventDefault(); setIdx((i) => Math.max(0, i - 1)); } if (e.key === 'Enter') { e.preventDefault(); (e.shiftKey && items[1] ? items[1] : items[idx])?.run(); } }} /><button type="button" className="chip outline sm" onClick={close}>Esc</button></div>
        {q && <div className="grp">Actions</div>}
        {items.map((it, i) => <div key={i} className={`it ${i === idx ? 'on' : ''}`} onMouseEnter={() => setIdx(i)} onClick={it.run}>{i === 2 && q && <></>}<IconBox ic={it.ic} tone={'tone' in it ? (it as { tone?: string }).tone || '' : ''} cls="sm" /><div><div className="b">{it.t}</div>{it.sub && <div className="xs muted">{it.sub}</div>}</div>{'k' in it && it.k && <span className="k">{it.k}</span>}</div>)}
        {q && items.length <= 2 && <div className="grp">No matching records — add it as a task or ask Mitra.</div>}
        {!q && <div className="grp">Recent</div>}{!q && s.activity.slice(0, 3).map((a) => <div key={a.id} className="it" onClick={() => router.push(href('today-detail'))}><IconBox ic={a.icon} tone={a.tone} cls="sm" /><div><div className="b">{a.text}</div><div className="xs muted">{a.when}</div></div></div>)}
        <div style={{ padding: '10px var(--pad)', borderTop: '1px solid var(--border)' }} className="row xs muted"><span>↑↓ navigate</span><span>↵ open</span><span>⇧↵ ask Mitra</span><span className="grow" /><span>Search covers tasks, notes, money, goals, habits</span></div>
      </div></div></div>
    } />
  );
}

/* ---------- Notifications (side panel) ---------- */
export function NotificationsScreen() {
  const s = useStore(); const router = useRouter(); const [tab, setTab] = useState('All');
  const list = s.notifications.filter((n) => tab === 'All' || (tab === 'Unread' ? !n.read : tab === 'Tasks' ? n.icon === 'check-square' : n.icon === 'wallet' || n.icon === 'repeat')); const unread = s.notifications.filter((n) => !n.read).length;
  return (
    <TodayScreen overlay={
      <Sheet title="Notifications" cls="side" sub={unread ? `${unread} unread` : 'All caught up'} back="today" footer={<Btn label="Notification settings" ic="settings" kind="outline" cls="block" goto="settings" />}>
        <div className="row between"><Segmented opts={['All', 'Unread', 'Tasks', 'Money']} on={tab} onChange={setTab} /><a className="link" onClick={() => { s.markRead('all'); s.toast('All marked read'); }}>Mark all read</a></div>
        <List>{list.length === 0 ? <Empty ic="bell" title="You are all caught up" p="" /> : list.map((n) => <div key={n.id} className={`notif ${n.read ? '' : 'unread'}`} style={{ cursor: 'pointer' }} onClick={() => { s.markRead(n.id); if (n.goto) router.push(n.goto); }}><IconBox ic={n.icon} tone={n.tone} /><div className="grow"><div className="t">{n.t}</div><div className="s">{n.s}</div></div><div className="when">{n.when}</div></div>)}</List>
        <div className="card soft tight sm muted row"><Icon name="moon" /> Quiet hours {s.settings.quietHours ? 'on · 10:30 PM – 7:00 AM' : 'off'}. {s.settings.digest ? 'Digest at 8:00 AM.' : ''}</div>
      </Sheet>
    } />
  );
}

/* ---------- Quick add (sheet) — natural language → preview → save ---------- */
export function QuickAddScreen() {
  const s = useStore(); const router = useRouter(); const [text, setText] = useState(''); const r = text.trim() ? ask(text, s) : null;
  const types: [string, string, ScreenId][] = [['wallet', 'Expense', 'expense-entry'], ['check-square', 'Task', 'tasks-inbox'], ['repeat', 'Habit', 'habit-edit'], ['note', 'Note', 'notes'], ['heart', 'Check-in', 'checkin']];
  const recent = [...s.transactions].slice(0, 3).map((t) => ({ l: `${s.categories.find((c) => c.id === t.categoryId)?.emoji} ${t.merchant} ₹${t.amount}`, run: () => { s.addTransaction({ kind: 'expense', amount: t.amount, categoryId: t.categoryId, accountId: t.accountId, merchant: t.merchant }); s.toast(`Added · ${t.merchant} ₹${t.amount}`); router.push(href('money')); } }));
  const save = () => { if (!r?.proposal) { if (text.trim()) { const t = s.addTask({ title: text.trim() }); s.toast(`Added to inbox · ${t.title}`); router.push(href('tasks-inbox')); } return; } const p = r.proposal; if (p.kind === 'expense' && s.settings.autoSave === 'Never' || p.kind === 'plan' || p.kind === 'reschedule' || (p.kind === 'expense' && s.settings.autoSave === 'Small only' && p.amount > 500)) { s.pushAiMessage({ who: 'user', text }); s.pushAiMessage({ who: 'ai', text: r.text, preview: { kind: p.kind, title: p.title, sub: p.sub, payload: p } }); router.push(href('ai')); return; } applyProposal(p, s); s.toast(`${p.kind === 'expense' ? 'Expense' : 'Task'} saved · ${p.title}`); router.push(p.kind === 'expense' ? href('money') : href('tasks-today')); };
  return (
    <TodayScreen overlay={
      <Sheet title="Quick add" back="today">
        <div className="composer"><Icon name="sparkles" /><input className="ph-in" autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder="Spent ₹450 on dinner · Call John tomorrow 6 pm · Ran 5K" onKeyDown={(e) => { if (e.key === 'Enter') save(); }} /><Btn ic="mic" kind="ghost" cls="sm" onClick={() => s.toast('Voice input needs the backend', { tone: 'accent' })} /><Btn ic="arrow-right" kind="primary" cls="sm" onClick={save} /></div>
        {r ? <div className="card soft tight sm">{r.proposal ? <><b>Preview:</b> {r.proposal.title} <span className="muted">· {r.proposal.sub}</span><div className="xs faint mt-s">{s.settings.autoSave === 'Never' || (r.proposal.kind === 'expense' && r.proposal.amount > 500 && s.settings.autoSave === 'Small only') ? 'Press ↵ to review and confirm in chat.' : 'Press ↵ to save. Auto-save is on for small items (Settings → AI).'}</div></> : <span className="muted">{r.text}</span>}</div> : <div className="xs muted" style={{ marginTop: -6 }}>Type naturally — Mitra previews before saving anything consequential.</div>}
        <div className="eyebrow">Or pick a type</div>
        <div className="habit-grid" style={{ gridTemplateColumns: 'repeat(3,1fr)' }}>{types.map(([ic, l, g]) => <Link key={l} href={href(g)} className="habit-cell" style={{ minHeight: 84, alignItems: 'center', justifyContent: 'center', gap: 6 }}><IconBox ic={ic} tone="accent" /><div className="name">{l}</div></Link>)}</div>
        <div className="eyebrow">Recent</div>
        <div className="chips">{recent.map((x) => <button type="button" key={x.l} className="chip outline" onClick={x.run}>{x.l}</button>)}<button type="button" className="chip outline" onClick={() => { const w = s.habits.find((h) => h.name.toLowerCase().includes('water')); if (w) { s.logHabit(w.id, todayISO(), (w.logs[todayISO()] || 0) + 1); s.toast('+1 glass'); } }}>💧 +1 glass</button><Link href={href('journal')} className="chip outline">📝 Journal today</Link></div>
      </Sheet>
    } />
  );
}

/* ---------- UI states reference ---------- */
export function StatesScreen() {
  const s = useStore(); const [offline, setOffline] = useState(true); const [confirm, setConfirm] = useState(false);
  return (
    <Screen nav="today" top={{ title: 'UI states', back: 'today' }}>
      {offline && <div className="banner warning"><Icon name="wifi-off" /><div className="grow">You are offline. Changes are saved on this device and will sync when you reconnect.</div><Chip tone="warning" cls="sm">3 queued</Chip><Btn ic="x" kind="ghost" cls="sm" onClick={() => setOffline(false)} /></div>}
      <div className="banner danger"><Icon name="alert" /><div className="grow">We couldn&apos;t load your transactions. <a className="link" onClick={() => s.toast('Retried · loaded')}>Retry</a> or check your connection.</div></div>
      <div className="split three">
        <Card><div className="eyebrow mb-s">Loading (skeleton)</div><div className="col" style={{ gap: 14 }}>{[1, 2, 3].map((i) => <div key={i} className="row"><div className="sk c" /><div className="grow col" style={{ gap: 6 }}><div className="sk t" /><div className="sk s" /></div></div>)}<div className="sk" style={{ height: 80, width: '100%' }} /></div></Card>
        <Card><Empty ic="check-square" title="No tasks for today" p="Add one, or pull something forward from Upcoming. Mitra will keep this screen calm." action={<Btn label="Add task" ic="plus" kind="primary" goto="tasks-today" />} /></Card>
        <Card><Empty ic="wallet" title="No expenses yet this month" p="Try typing “Spent ₹450 on dinner” in Quick Add — it takes two seconds." action={<Btn label="Quick add" ic="sparkles" kind="soft" goto="quickadd" />} /></Card>
      </div>
      <div className="split three">
        <Card><div className="eyebrow mb-s">First-run example</div><div className="task"><div className="cb"><Icon name="check" /></div><div className="grow"><div className="t">Example: Plan the week</div><div className="meta"><Chip tone="accent" cls="sm">Sample — tap to keep or dismiss</Chip></div></div></div><div className="row"><a className="link" onClick={() => s.toast('Examples kept')}>Keep examples</a><a className="link muted" onClick={() => s.toast('Examples cleared', { undo: () => {} })}>Clear all</a></div></Card>
        <Card><div className="eyebrow mb-s">Confirmation dialog</div><div className="col"><div className="b">Delete “Renew car insurance”?</div><div className="sm muted">This task is linked to 1 reminder. You can undo for 10 seconds.</div><div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}><Btn label="Cancel" kind="outline" cls="sm" /><Btn label="Delete" kind="danger" cls="sm" onClick={() => setConfirm(true)} /></div></div></Card>
        <Card><div className="eyebrow mb-s">Toasts</div><div className="col"><Btn label="Success toast" kind="outline" cls="sm" onClick={() => s.toast('Expense saved', { undo: () => s.toast('Undone') })} /><Btn label="Error toast" kind="outline" cls="sm" onClick={() => s.toast("Couldn't sync · will retry", { tone: 'danger' })} /><Btn label="AI toast" kind="outline" cls="sm" onClick={() => s.toast('AI moved 3 tasks · review', { tone: 'accent' })} /></div></Card>
      </div>
      <Confirm open={confirm} onClose={() => setConfirm(false)} title="Delete “Renew car insurance”?" body="Demo only — nothing is deleted." onConfirm={() => s.toast('Deleted (demo)', { undo: () => {} })} />
    </Screen>
  );
}

/* ---------- Design system reference ---------- */
export function DesignSystemScreen() {
  const { resolved, setMode } = useTheme(); const s = useStore();
  const swatches: [string, string][] = [['--bg', 'Background'], ['--surface', 'Surface'], ['--surface2', 'Surface 2'], ['--border', 'Border'], ['--text', 'Text'], ['--muted', 'Muted'], ['--accent', 'Accent'], ['--accent-soft', 'Accent soft'], ['--success', 'Success'], ['--warning', 'Warning'], ['--danger', 'Danger'], ['--info', 'Info']];
  const type: [string, React.ReactNode][] = [['Display 34/1.05', <span key="a" className="big">₹58,420</span>], ['H1 26/1.2', <h1 key="b" style={{ fontSize: 26 }}>Good morning, Aarav</h1>], ['H2 20/1.2', <h2 key="c" style={{ fontSize: 20 }}>Top priority</h2>], ['H3 15/1.3', <h3 key="d" style={{ fontSize: 15 }}>Section heading</h3>], ['Body 14/1.45', <span key="e">Body text for rows, descriptions and forms.</span>], ['Small 12.5', <span key="f" className="sm muted">Secondary metadata, timestamps</span>], ['Eyebrow 11 caps', <span key="g" className="eyebrow">Step 2 of 4</span>], ['Numeric', <span key="h" className="num b">1,284 · 86% · 6h 40m</span>]];
  const [seg, setSeg] = useState('Week'); const [tog, setTog] = useState(true); const [txt, setTxt] = useState('Typing…');
  return (
    <Screen nav="today" top={{ title: 'Design system', sub: 'Tokens and reusable components', actions: [{ ic: resolved === 'dark' ? 'sun' : 'moon', label: resolved === 'dark' ? 'Light' : 'Dark', onClick: () => setMode(resolved === 'dark' ? 'light' : 'dark') }] }}>
      <Section title="Colour tokens"><div className="ds-grid">{swatches.map(([v, l]) => <div key={v} className="ds-swatch" onClick={() => { navigator.clipboard?.writeText(`var(${v})`); s.toast(`Copied var(${v})`); }} style={{ cursor: 'pointer' }}><i style={{ background: `var(${v})` }} />{l}<span className="xs faint num">{v}</span></div>)}</div></Section>
      <Section title="Typography scale"><Card>{type.map(([m, e]) => <div key={m} className="type-row"><span className="m">{m}</span><div>{e}</div></div>)}</Card></Section>
      <Section title="Buttons"><div className="card row wrap" style={{ gap: 8 }}><Btn label="Primary" kind="primary" onClick={() => s.toast('Primary')} /><Btn label="Outline" onClick={() => s.toast('Outline')} /><Btn label="Ghost" kind="ghost" onClick={() => s.toast('Ghost')} /><Btn label="Soft" kind="soft" onClick={() => s.toast('Soft')} /><Btn label="Danger" kind="danger" onClick={() => s.toast('Danger', { tone: 'danger' })} /><Btn label="With icon" ic="plus" kind="primary" onClick={() => s.toast('With icon')} /><Btn ic="more" onClick={() => s.toast('Icon button')} /><Btn label="Small" kind="outline" cls="sm" /><Btn label="Large" kind="primary" size="lg" /></div></Section>
      <Section title="Inputs"><div className="card split"><TextInput label="Default" value="" onChange={() => {}} placeholder="Placeholder" ic="search" /><TextInput label="Focused / typed" value={txt} onChange={setTxt} /><TextInput label="Error" value="abc" onChange={() => {}} err="Enter a valid amount" /><TextInput label="With suffix" value="30" onChange={() => {}} sfx="minutes" /><div className="field"><div className="lbl">Amount field</div><div className="amount"><span className="cur">₹</span>450</div></div><div className="field"><div className="lbl">Segmented</div><Segmented opts={['Day', 'Week', 'Month']} on={seg} onChange={setSeg} /></div><div className="field"><div className="lbl">Toggle</div><Toggle on={tog} onChange={setTog} /></div><div className="field"><div className="lbl">Chips</div><div className="chips"><Chip>Neutral</Chip><Chip tone="accent">Accent</Chip><Chip tone="success" ic="check">Success</Chip><Chip tone="warning" ic="alert">Warning</Chip><Chip tone="danger">Danger</Chip><Chip tone="ai" ic="sparkles">AI</Chip></div></div></div></Section>
      <Section title="Rows"><Card cls="pad-0"><List><TaskRow t={TASKS[0]} goto="" /><TaskRow t={{ ...TASKS[2], done: true }} goto="" /><HabitRow h={HABITS[0]} /><HabitRow h={HABITS[1]} /><TxRow t={TX[0]} /><LRow ic="target" tone="accent" t="Generic list row" s="Subtitle" v="62%" vs="on track" /></List></Card></Section>
      <Section title="Progress & charts"><div className="card split three"><div className="col"><div className="sm b">Bars</div><Bar pct={62} /><Bar pct={96} tone="warning" /><Bar pct={108} tone="danger" /><div className="seg-track"><i className="on" /><i className="on" /><i className="on" /><i /><i /></div></div><div className="row" style={{ gap: 12 }}><Ring pct={62} size={64} stroke={7} label="62%" /><Ring pct={86} size={64} stroke={7} label="86%" tone="success" /><Ring pct={35} size={64} stroke={7} label="35%" tone="warning" /></div><div><Bars vals={[12, 18, 9, 22, 17, 25, 14]} labels={['M', 'T', 'W', 'T', 'F', 'S', 'S']} h={90} hi={2} /></div></div></Section>
      <Section title="Navigation & overlays"><div className="card sm muted keep">Cockpit: mobile bottom tab bar (Today · Tasks · Quick Add · Money · More sheet) → single-row top navigation whose tabs collapse into a “More ▾” menu when they don’t fit (no horizontal scrolling); Settings/Profile live under the avatar menu. Bottom sheet ↔ modal, side panel, command palette, toasts with Undo, banners. All overlays close on Esc or clicking outside.</div></Section>
      <Section title="Spacing & radius"><div className="card sm"><div className="row wrap" style={{ gap: 16 }}><span>Spacing: 4 · 8 · 12 · 16 · 20 · 24 · 32</span><span>Radius: sm var(--r-sm) · md var(--r) · lg var(--r-lg)</span><span>Touch target ≥ 44px on mobile</span><span>Breakpoints: 320–767 · 768–1199 · 1200+</span></div></div></Section>
    </Screen>
  );
}
