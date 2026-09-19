'use client';
/* ---------- WELLNESS: daily check-in (saves), trends (computed), sleep detail, settings ---------- */
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Screen } from '@/components/shell/Screen';
import { Bars, Btn, Card, Chip, Heat, Icon, IconBox, Kpi, LRow, LineChart, List, MOODS, MoodRow, Section, Segmented, SetRow, ToggleRow } from '@/components/ui';
import { Stepper, TextInput, TimeInput } from '@/components/ui/controls';
import { addDays, diffDays, fmtDay, fmtShort, fmtTime, todayISO, DOW } from '@/lib/dates';
import { href } from '@/lib/routes';
import { useStore } from '@/store';
import { bmi, bmiBand, hm, sleepMinutes } from '@/store/selectors';

const streakOf = (w: Record<string, { savedAt?: string; mood?: number }>) => { let n = 0; for (let i = 0; i < 400; i++) { const d = addDays(todayISO(), -i); if (w[d]?.mood !== undefined) n++; else if (i > 0) break; } return n; };

export function CheckinScreen() {
  const s = useStore(); const router = useRouter(); const T = todayISO(); const e = s.wellness[T] || {}; const ws = s.wellnessSettings;
  const [mood, setMood] = useState<number>(-1); const [ss, setSs] = useState('23:30'); const [se, setSe] = useState('06:30'); const [steps, setSteps] = useState(''); const [water, setWater] = useState(0); const [weight, setWeight] = useState(''); const [height, setHeight] = useState(String(ws.height)); const [note, setNote] = useState('');
  useEffect(() => { setMood(e.mood ?? -1); setSs(e.sleepStart || '23:30'); setSe(e.sleepEnd || '06:30'); setSteps(e.steps ? String(e.steps) : ''); setWater(e.water || 0); setWeight(e.weight ? String(e.weight) : ''); setHeight(String(ws.height)); setNote(e.note || ''); }, [e.mood, e.sleepStart, e.sleepEnd, e.steps, e.water, e.weight, e.note, ws.height]);
  const sleep = sleepMinutes(ss, se); const b = bmi(+weight, +height); const last = Object.keys(s.wellness).filter((d) => d < T && s.wellness[d].weight).sort().pop(); const lastW = last ? s.wellness[last].weight! : undefined;
  const save = () => { s.saveWellness(T, { mood: mood >= 0 ? mood : undefined, sleepStart: ss, sleepEnd: se, steps: steps ? +steps : undefined, water, weight: weight ? +weight : undefined, note: note || undefined, savedAt: T }); if (+height !== ws.height) s.updateWellnessSettings({ height: +height }); s.addActivity(`Check-in · mood ${MOODS[mood] || '—'} · ${hm(sleep)} sleep`, 'heart', 'info', 'wellness'); s.toast('Check-in saved'); router.push(href('wellness-trends')); };
  const streak = streakOf(s.wellness);
  return (
    <Screen nav="wellness" top={{ title: 'Wellness', actions: [{ ic: 'chart', goto: 'wellness-trends' }, { ic: 'settings', goto: 'wellness-settings' }] }}>
      <div className="split main">
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Card cls="keep">
            <div className="row between"><div><div className="eyebrow">Daily check-in</div><h2 style={{ fontSize: 19, marginTop: 2 }}>{e.savedAt ? 'Saved for today — edit any time' : 'Takes about 20 seconds'}</h2></div>{streak > 0 && <Chip tone="success" cls="sm">Streak {streak} day{streak > 1 ? 's' : ''}</Chip>}</div>
            {ws.track.mood && <div className="field mt"><div className="lbl">Mood</div><MoodRow on={mood} onChange={setMood} /></div>}
            {ws.track.sleep && <div className="field mt"><div className="lbl">Sleep</div><div className="row" style={{ gap: 8 }}><TimeInput cls="grow" value={ss} onChange={setSs} ic="moon" /><span className="muted">→</span><TimeInput cls="grow" value={se} onChange={setSe} ic="sun" /><span className="num b" style={{ width: 70, textAlign: 'right' }}>{hm(sleep)}</span></div><div className="hint">Target {hm(ws.sleepTarget)}{sleep && sleep < ws.sleepTarget ? ` · ${hm(ws.sleepTarget - sleep)} under` : sleep ? ' · on target' : ''}</div></div>}
            <div className="split mt">{ws.track.steps && <TextInput label="Steps" value={steps} onChange={(v) => setSteps(v.replace(/\D/g, ''))} ic="activity" inputMode="numeric" sfx={`of ${ws.stepsTarget.toLocaleString('en-IN')}`} hint="Enter manually — wearable import comes later" />}{ws.track.water && <div className="field"><div className="lbl">Water</div><Stepper value={water} onChange={setWater} max={20} /><div className="hint" style={{ textAlign: 'center' }}>of {ws.waterTarget} glasses</div></div>}</div>
            {ws.track.bmi && <><div className="split mt"><TextInput label="Weight" value={weight} onChange={(v) => setWeight(v.replace(/[^\d.]/g, ''))} sfx="kg" ic="scale" inputMode="decimal" hint={lastW ? `Last ${lastW} kg · ${+weight && +weight - lastW !== 0 ? `${(+weight - lastW) > 0 ? '+' : ''}${(+weight - lastW).toFixed(1)} kg` : 'no change'}` : ''} /><TextInput label="Height" value={height} onChange={(v) => setHeight(v.replace(/\D/g, ''))} sfx="cm" inputMode="numeric" hint="Set once; edit any time" /></div>
              <div className="card accent mt"><div className="row between"><div><Kpi l="BMI · calculated" v={<><span>{b || '—'}</span> <span className="muted" style={{ fontSize: 13, fontWeight: 500 }}>{bmiBand(b)}</span></>} /></div><div className="xs muted" style={{ maxWidth: 220, textAlign: 'right' }}>weight ÷ height² {weight && height ? `= ${weight} ÷ ${(+height / 100).toFixed(2)}²` : ''} · Reference ranges 18.5–24.9 normal. Informational only, not medical advice.</div></div><div className="seg-track mt-s">{[18.5, 25, 30, 99].map((lim, i) => <i key={i} className={b > 0 && (i === 0 ? b < 18.5 : i === 1 ? b >= 18.5 && b < 25 : i === 2 ? b >= 25 && b < 30 : b >= 30) ? 'on' : ''} />)}</div><div className="row between xs faint mt-s"><span>&lt;18.5</span><span>18.5–24.9</span><span>25–29.9</span><span>30+</span></div></div></>}
            <TextInput label="Note" value={note} onChange={setNote} placeholder="Anything worth remembering about today?" ic="edit" cls="mt" />
            <div className="row mt" style={{ gap: 8 }}><Btn label="Skip today" kind="ghost" onClick={() => { s.toast('Skipped — no data recorded'); router.push(href('today')); }} /><span className="grow" /><Btn label="Save check-in" kind="primary" onClick={save} /></div>
          </Card>
        </div>
        <div className="col" style={{ gap: 'var(--gap)' }}>
          <Section title="Today"><Card cls="pad-0"><List>
            {ws.track.mood && <div className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic="smile" cls="sm" /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>Mood</div><div className="s">{e.savedAt ? `Logged ${fmtDay(e.savedAt)}` : 'Not logged yet'}</div></div><div className="r"><div className="v">{mood >= 0 ? MOODS[mood] : '—'}</div></div></div>}
            {ws.track.sleep && <div className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic="moon" tone={sleep < ws.sleepTarget ? 'info' : 'success'} cls="sm" /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>Sleep</div><div className="s">{sleep < ws.sleepTarget ? 'Under target' : 'On target'}</div></div><div className="r"><div className="v">{hm(sleep)}</div></div></div>}
            {ws.track.steps && <div className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic="activity" cls="sm" tone={+steps >= ws.stepsTarget ? 'success' : ''} /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>Steps</div><div className="s">of {ws.stepsTarget.toLocaleString('en-IN')}</div></div><div className="r"><div className="v">{steps ? (+steps).toLocaleString('en-IN') : '—'}</div></div></div>}
            {ws.track.water && <div className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic="droplet" cls="sm" tone={water >= ws.waterTarget ? 'success' : ''} /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>Water</div></div><div className="r"><div className="v">{water} / {ws.waterTarget}</div></div></div>}
            {ws.track.bmi && <div className="lrow" style={{ minHeight: 48, cursor: 'default' }}><IconBox ic="scale" cls="sm" /><div className="grow"><div className="t" style={{ fontSize: 13.5 }}>BMI</div><div className="s">{b ? `${bmiBand(b)} · ${weight} kg · ${height} cm` : 'Enter weight and height'}</div></div><div className="r"><div className="v">{b || '—'}</div></div></div>}
          </List></Card></Section>
          <Card cls="soft"><div className="eyebrow mb-s">Privacy</div><div className="sm"><Icon name="lock" /> Wellness data stays on this account and is <b>{ws.shareAI ? '' : 'not '}</b>shared with the AI assistant (change in Settings). No medical advice is given.</div></Card>
        </div>
      </div>
    </Screen>
  );
}

export function WellnessTrendsScreen() {
  const s = useStore(); const ws = s.wellnessSettings; const [range, setRange] = useState('Month'); const days = range === 'Week' ? 7 : range === 'Month' ? 30 : 90;
  const series = Array.from({ length: days }, (_, i) => { const d = addDays(todayISO(), -(days - 1 - i)); return { d, e: s.wellness[d] }; });
  const sleeps = series.map((x) => x.e ? sleepMinutes(x.e.sleepStart, x.e.sleepEnd) / 60 : 0); const withSleep = sleeps.filter(Boolean);
  const stepsArr = series.slice(-14).map((x) => x.e?.steps || 0); const moods = series.map((x) => x.e?.mood); const moodVals = moods.filter((m): m is number => m !== undefined);
  const weights = series.filter((x) => x.e?.weight).map((x) => x.e!.weight!); const w0 = weights[0], w1 = weights[weights.length - 1];
  const avgSleep = withSleep.length ? withSleep.reduce((a, b) => a + b, 0) / withSleep.length : 0; const prevSleep = (() => { const p = Array.from({ length: days }, (_, i) => s.wellness[addDays(todayISO(), -(2 * days - 1 - i))]).filter(Boolean).map((e) => sleepMinutes(e.sleepStart, e.sleepEnd) / 60).filter(Boolean); return p.length ? p.reduce((a, b) => a + b, 0) / p.length : 0; })();
  const avgSteps = stepsArr.filter(Boolean).length ? Math.round(stepsArr.reduce((a, b) => a + b, 0) / stepsArr.filter(Boolean).length) : 0;
  // sleep vs tasks completed: bucket by sleep hours using tasks completed that day
  const buckets = [[0, 6.5], [6.5, 7.5], [7.5, 24]].map(([lo, hi]) => { const ds = series.filter((x) => { const h = x.e ? sleepMinutes(x.e.sleepStart, x.e.sleepEnd) / 60 : 0; return h >= lo && h < hi && h > 0; }); const done = ds.map((x) => s.tasks.filter((t) => t.completedAt === x.d).length + Math.round(((x.e?.steps || 0) / 3000))); return ds.length ? +(done.reduce((a, b) => a + b, 0) / ds.length).toFixed(1) : 0; });
  const lbl = (i: number, n: number, every: number) => i === 0 || i === n - 1 || i % every === 0 ? fmtShort(addDays(todayISO(), -(n - 1 - i))) : '';
  return (
    <Screen nav="wellness" top={{ title: 'Trends', back: 'checkin', actions: [{ ic: 'settings', goto: 'wellness-settings' }] }}>
      <Segmented opts={['Week', 'Month', '3 months']} on={range} onChange={setRange} />
      <div className="kpi-grid"><Kpi l="Avg sleep" v={hm(Math.round(avgSleep * 60))} d={prevSleep ? `${avgSleep - prevSleep >= 0 ? '+' : '−'}${Math.round(Math.abs(avgSleep - prevSleep) * 60)}m vs previous` : ''} dir={avgSleep >= prevSleep ? 'up' : 'down'} /><Kpi l="Avg steps" v={avgSteps.toLocaleString('en-IN')} d={`target ${ws.stepsTarget.toLocaleString('en-IN')}`} dir={avgSteps >= ws.stepsTarget ? 'up' : ''} /><Kpi l="Mood avg" v={moodVals.length ? `${(moodVals.reduce((a, b) => a + b + 1, 0) / moodVals.length).toFixed(1)} / 5` : '—'} d={`${moodVals.length} check-ins`} /><Kpi l="BMI" v={w1 ? String(bmi(w1, ws.height)) : '—'} d={w0 && w1 ? `${w1} kg · ${(w1 - w0) >= 0 ? '+' : ''}${(w1 - w0).toFixed(1)} kg in ${days}d` : ''} dir={w0 && w1 && w1 < w0 ? 'up' : ''} /></div>
      <div className="split">
        <Section title={`Sleep · last ${days} nights`} action="Detail" goto="sleep-detail"><Card>{withSleep.length > 1 ? <><LineChart vals={sleeps.map((v) => +((v || avgSleep).toFixed(1)))} labels={sleeps.map((_, i) => lbl(i, days, Math.ceil(days / 4)))} h={140} goal={ws.sleepTarget / 60} /><div className="legend mt-s"><span><i />Hours</span><span><i className="s" style={{ height: 2 }} />Target {hm(ws.sleepTarget)}</span></div></> : <div className="list-empty">Log {Math.max(0, 7 - withSleep.length)} more check-ins to see this chart.</div>}</Card></Section>
        <Section title="Steps · last 2 weeks"><Card><Bars vals={stepsArr} labels={stepsArr.map((_, i) => i % 2 === 0 ? String(+addDays(todayISO(), -(13 - i)).slice(8)) : '')} h={140} hi={13} max={Math.max(12000, ...stepsArr) * 1.05} /><div className="legend mt-s"><span><i />Steps · today highlighted · target {ws.stepsTarget.toLocaleString('en-IN')}</span></div></Card></Section>
        <Section title={`Weight & BMI · ${range.toLowerCase()}`}><Card>{weights.length > 1 ? <><LineChart vals={weights} labels={weights.map((_, i) => i === 0 ? 'Start' : i === weights.length - 1 ? 'Now' : '')} h={130} /><div className="legend mt-s"><span><i />Weight (kg) · BMI {bmi(w0, ws.height)} → {bmi(w1, ws.height)} · height {ws.height} cm</span></div></> : <div className="list-empty">Log weight in a few check-ins to see the trend.</div>}</Card></Section>
        <Section title={`Mood · last ${days} days`}><Card><Heat cells={moods.map((m) => m === undefined ? 0 : m + 1 > 4 ? 4 : m + 1)} cols={days >= 30 ? 10 : 7} /><div className="row between xs muted mt-s"><span>{fmtShort(addDays(todayISO(), -(days - 1)))}</span><span>Darker = better mood</span><span>Today</span></div></Card></Section>
        <Section title="Sleep vs productivity"><Card><div className="row" style={{ gap: 14, alignItems: 'flex-start' }}><div className="grow"><Bars vals={buckets} labels={['< 6.5h', '6.5–7.5h', '> 7.5h']} h={120} showVals /></div><div className="sm" style={{ maxWidth: 180 }}><b>{buckets[2] > buckets[0] ? `After 7.5h+ of sleep you did ${buckets[2]} things on average vs ${buckets[0]} after short nights.` : 'Not enough sleep variation yet to see a pattern.'}</b><div className="xs faint mt-s">{days} days · {withSleep.length} check-ins · correlation, not causation.</div></div></div></Card></Section>
      </div>
    </Screen>
  );
}

export function SleepDetailScreen() {
  const s = useStore(); const ws = s.wellnessSettings; const [day, setDay] = useState(addDays(todayISO(), -1)); const e = s.wellness[day] || {}; const [edit, setEdit] = useState(false); const [ss, setSs] = useState(e.sleepStart || '23:30'); const [se, setSe] = useState(e.sleepEnd || '06:30'); const [noteOpen, setNoteOpen] = useState(false); const [noteV, setNoteV] = useState('');
  useEffect(() => { setSs(e.sleepStart || '23:30'); setSe(e.sleepEnd || '06:30'); }, [e.sleepStart, e.sleepEnd]);
  const mins = sleepMinutes(e.sleepStart, e.sleepEnd); const week = Array.from({ length: 7 }, (_, i) => { const d = addDays(day, -(6 - i)); const x = s.wellness[d]; return +(x ? sleepMinutes(x.sleepStart, x.sleepEnd) / 60 : 0).toFixed(1); }); const avg = week.filter(Boolean).length ? (week.reduce((a, b) => a + b, 0) / week.filter(Boolean).length).toFixed(1) : '0';
  const sleepHabit = s.habits.find((h) => h.name.toLowerCase().includes('sleep')); const journal = s.notes.find((n) => n.journalDate === day);
  return (
    <Screen nav="wellness" top={{ title: 'Sleep', back: 'wellness-trends', eyebrow: `${diffDays(todayISO(), day) === 1 ? 'Last night' : fmtDay(day)} · ${fmtDay(day, { relative: false })}`, actions: [{ ic: 'chevron-left', onClick: () => setDay(addDays(day, -1)) }, { ic: 'chevron-right', onClick: () => { if (day < todayISO()) setDay(addDays(day, 1)); } }] }}>
      <div className="split">
        <Card cls="keep"><div className="row between top"><div><Kpi l="Duration" v={mins ? hm(mins) : 'Not logged'} d={mins ? `${mins < ws.sleepTarget ? hm(ws.sleepTarget - mins) + ' under target' : 'on target'}` : ''} dir={mins && mins < ws.sleepTarget ? 'down' : ''} /></div><Chip ic="edit" cls="sm">Manual entry</Chip></div>
          {mins > 0 && <div className="mt" style={{ height: 36, borderRadius: 8, background: 'linear-gradient(90deg,var(--accent-soft) 0 12%,var(--accent) 12% 38%,var(--accent-soft) 38% 46%,var(--accent) 46% 78%,var(--accent-soft) 78% 90%,var(--accent) 90% 100%)' }} />}
          <div className="row between xs muted mt-s"><span>{fmtTime(e.sleepStart) || '—'}</span><span>Bed → wake</span><span>{fmtTime(e.sleepEnd) || '—'}</span></div>
          {edit ? <div className="row mt" style={{ gap: 8 }}><TimeInput value={ss} onChange={setSs} ic="moon" cls="grow" /><TimeInput value={se} onChange={setSe} ic="sun" cls="grow" /><Btn label="Save" kind="primary" cls="sm" onClick={() => { s.saveWellness(day, { sleepStart: ss, sleepEnd: se }); setEdit(false); s.toast('Sleep updated'); }} /></div> : <div className="row mt" style={{ gap: 8 }}><Btn label="Edit times" kind="outline" cls="sm" onClick={() => setEdit(true)} /><Btn label={e.note ? 'Edit note' : 'Add note'} kind="ghost" cls="sm" onClick={() => { setNoteV(e.note || ''); setNoteOpen((v) => !v); }} /></div>}
          {noteOpen && <div className="row mt-s" style={{ gap: 6 }}><TextInput value={noteV} onChange={setNoteV} placeholder="Note for this night" cls="grow" autoFocus onEnter={() => { s.saveWellness(day, { note: noteV }); setNoteOpen(false); s.toast('Note saved'); }} /><Btn label="Save" kind="primary" cls="sm" onClick={() => { s.saveWellness(day, { note: noteV }); setNoteOpen(false); s.toast('Note saved'); }} /></div>}
        </Card>
        <Card cls="keep"><div className="eyebrow mb-s">This week</div><Bars vals={week} labels={week.map((_, i) => DOW[new Date(addDays(day, -(6 - i))).getDay()][0])} h={120} showVals hi={6} /><div className="xs muted mt-s">Hours slept · target {hm(ws.sleepTarget)} · avg {avg}h</div></Card>
        <Section title="Related"><Card cls="pad-0"><List>{sleepHabit && <LRow ic="repeat" t={sleepHabit.name} s={`Habit · ${Object.keys(sleepHabit.logs).filter((d) => diffDays(todayISO(), d) <= 3 && sleepHabit.logs[d]).length} of last 3 done`} extra={<a className="link xs" href={`/habits/${sleepHabit.id}`}>Open</a>} />}{journal && <LRow ic="edit" t={`Journal: ${journal.body.slice(0, 40)}…`} s={fmtDay(day)} extra={<a className="link xs" href={`/notes/${journal.id}`}>Open</a>} />}<LRow ic="smile" t={`Mood ${e.mood !== undefined ? MOODS[e.mood] : '—'}`} s="From check-in" goto="checkin" />{e.note && <LRow ic="note" t={e.note} s="Sleep note" chev={false} />}</List></Card></Section>
      </div>
    </Screen>
  );
}

export function WellnessSettingsScreen() {
  const s = useStore(); const ws = s.wellnessSettings; const set = (p: Partial<typeof ws>) => s.updateWellnessSettings(p); const track = (k: keyof typeof ws.track) => (v: boolean) => set({ track: { ...ws.track, [k]: v } });
  const [editT, setEditT] = useState<string | null>(null); const [val, setVal] = useState('');
  const row = (key: string, t: string, current: string, ic: string, apply: (v: string) => void, sfx: string) => editT === key ? <div className="setrow" style={{ cursor: 'default' }}><IconBox ic={ic} /><div className="grow"><div className="t">{t}</div></div><TextInput value={val} onChange={setVal} sfx={sfx} style={{ width: 160 }} autoFocus onEnter={() => { apply(val); setEditT(null); }} /><Btn label="Save" kind="primary" cls="sm" onClick={() => { apply(val); setEditT(null); }} /></div> : <SetRow t={t} s={current} ic={ic} onClick={() => { setEditT(key); setVal(current.replace(/[^\d:]/g, '')); }} />;
  return (
    <Screen nav="wellness" top={{ title: 'Wellness settings', back: 'checkin' }} narrow>
      <Section title="What to track"><Card cls="pad-0"><List><ToggleRow t="Mood" s="Five-point scale, also on Today" ic="smile" on={ws.track.mood} onChange={track('mood')} /><ToggleRow t="Sleep" s="Duration and bed/wake times" ic="moon" on={ws.track.sleep} onChange={track('sleep')} /><ToggleRow t="Steps" s="Manual entry now, wearable import later" ic="activity" on={ws.track.steps} onChange={track('steps')} /><ToggleRow t="Water" s="Glasses per day" ic="droplet" on={ws.track.water} onChange={track('water')} /><ToggleRow t="Weight & height → BMI" s="BMI shown on Today and Trends" ic="scale" on={ws.track.bmi} onChange={track('bmi')} /></List></Card></Section>
      <Section title="Targets"><Card cls="pad-0"><List>{row('sleep', 'Sleep target', hm(ws.sleepTarget), 'moon', (v) => { const [h, m] = v.includes(':') ? v.split(':').map(Number) : [+v, 0]; set({ sleepTarget: h * 60 + (m || 0) }); }, 'h:mm')}{row('steps', 'Steps target', ws.stepsTarget.toLocaleString('en-IN'), 'activity', (v) => set({ stepsTarget: +v.replace(/\D/g, '') || ws.stepsTarget }), 'steps')}{row('water', 'Water target', `${ws.waterTarget} glasses`, 'droplet', (v) => set({ waterTarget: +v || ws.waterTarget }), 'glasses')}{row('height', 'Height', `${ws.height} cm`, 'scale', (v) => set({ height: +v || ws.height }), 'cm')}</List></Card></Section>
      <Section title="Reminders"><Card cls="pad-0"><List>{row('morning', 'Morning check-in', ws.morningReminder, 'bell', (v) => set({ morningReminder: v }), 'HH:MM')}{row('evening', 'Evening check-in', ws.eveningReminder, 'bell', (v) => set({ eveningReminder: v }), 'HH:MM')}</List></Card></Section>
      <Section title="Privacy & integrations"><Card cls="pad-0"><List><ToggleRow t="Share with AI assistant" s="Off keeps wellness out of AI context" on={ws.shareAI} ic="shield" onChange={(v) => { set({ shareAI: v }); s.updateSettings({ shareWellness: v }); }} /><ToggleRow t="Show on Today" s="Mood picker and compact stats" on={ws.showOnToday} ic="sun" onChange={(v) => set({ showOnToday: v })} /><SetRow t="Apple Health / Health Connect" s="Coming in a later phase" r="Soon" ic="link" onClick={() => s.toast('Health integrations arrive in phase 7')} /></List></Card></Section>
    </Screen>
  );
}
