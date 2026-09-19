'use client';
/* =====================================================================
   COMPONENT LIBRARY — the design system, ported 1:1 from the prototype's
   template helpers (btn, chip, card, section, taskRow, …).
   Pure presentational components; all data comes in via props.
   ===================================================================== */
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { ICONS } from '@/lib/icons';
import { ROUTES, href, type ScreenId } from '@/lib/routes';
import { inr, type Task as TaskVM, type Habit, type Tx } from '@/lib/data';
import { useStore } from '@/store';
type Task = TaskVM & { ai?: boolean; parent?: string };

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

/* ---------- Icon ---------- */
export function Icon({ name, cls = '' }: { name: string; cls?: string }) {
  return (
    <svg className={cx('ic', cls)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: ICONS[name] || ICONS.circle }} />
  );
}

/* ---------- Go: link to a screen by id (or render children as-is) ---------- */
export function Go({ to, className, style, children, title, ...rest }: { to?: ScreenId | ''; className?: string; style?: CSSProperties; children: ReactNode; title?: string; onClick?: () => void }) {
  if (to) return <Link href={href(to)} className={className} style={style} title={title} {...rest}>{children}</Link>;
  return <div className={className} style={style} title={title} {...rest}>{children}</div>;
}

/* ---------- Button ---------- */
export type BtnProps = { label?: string; ic?: string; kind?: 'primary' | 'outline' | 'ghost' | 'soft' | 'danger'; size?: '' | 'lg'; cls?: string; goto?: ScreenId | ''; title?: string; onClick?: () => void; ariaLabel?: string };
export function Btn({ label = '', ic = '', kind = 'outline', size = '', cls = '', goto = '', title = '', onClick, ariaLabel }: BtnProps) {
  const className = cx('btn', kind, size, cls, !label && 'icon');
  const inner = <>{ic && <Icon name={ic} />}{label && <span>{label}</span>}</>;
  if (goto) return <Link href={href(goto)} className={className} title={title || undefined} aria-label={ariaLabel} onClick={onClick}>{inner}</Link>;
  return <button type="button" className={className} title={title || undefined} aria-label={ariaLabel} onClick={onClick}>{inner}</button>;
}

/* ---------- Small atoms ---------- */
export const Chip = ({ children, tone = '', ic = '', cls = '' }: { children: ReactNode; tone?: string; ic?: string; cls?: string }) => (
  <span className={cx('chip', tone, cls)}>{ic && <Icon name={ic} />}{children}</span>
);
export const Avatar = ({ i, cls = '' }: { i?: string; cls?: string }) => { const name = useStore((s) => s.user?.name); const ini = i ?? (name ? name.split(' ').map((x) => x[0]).join('').slice(0, 2).toUpperCase() : '·'); return <div className={cx('avatar', cls)}>{ini}</div>; };
export const IconBox = ({ ic, tone = '', cls = '' }: { ic: string; tone?: string; cls?: string }) => <div className={cx('iconbox', tone, cls)}><Icon name={ic} /></div>;
export const EmojiBox = ({ e, cls = '' }: { e: string; cls?: string }) => <div className={cx('iconbox emoji', cls)}>{e}</div>;
export const Bar = ({ pct, tone = '', cls = '' }: { pct: number; tone?: string; cls?: string }) => (
  <div className={cx('bar', tone, cls)}><i style={{ width: `${Math.min(100, pct)}%` }} /></div>
);
export function Ring({ pct, size = 44, stroke = 5, label = '', tone = '' }: { pct: number; size?: number; stroke?: number; label?: string; tone?: string }) {
  const r = (size - stroke) / 2, c = 2 * Math.PI * r;
  const col = tone === 'success' ? 'var(--success)' : tone === 'warning' ? 'var(--warning)' : tone === 'danger' ? 'var(--danger)' : 'var(--accent)';
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--track)" strokeWidth={stroke} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={col} strokeWidth={stroke} strokeLinecap="round" strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} />
      </svg>
      {label !== '' && <span className="lbl" style={{ fontSize: Math.round(size / 4) }}>{label}</span>}
    </div>
  );
}
export const Dot = ({ tone = '', style }: { tone?: string; style?: CSSProperties }) => <i className={cx('dot', tone)} style={style} />;

/* ---------- Layout ---------- */
export const Card = ({ children, cls = '', style, goto }: { children: ReactNode; cls?: string; style?: CSSProperties; goto?: ScreenId }) => (
  <Go to={goto} className={cx('card', cls)} style={style}>{children}</Go>
);
export function Section({ title, sub = '', action = '', goto, children }: { title: ReactNode; sub?: string; action?: string; goto?: ScreenId | ''; children: ReactNode }) {
  return (
    <section className="section">
      <div className="head">
        <div><h3>{title}</h3>{sub && <div className="sm muted">{sub}</div>}</div>
        {action && (goto ? <Link href={href(goto)}>{action}</Link> : <a>{action}</a>)}
      </div>
      {children}
    </section>
  );
}
export const Row = ({ children, cls = '', style }: { children: ReactNode; cls?: string; style?: CSSProperties }) => <div className={cx('row', cls)} style={style}>{children}</div>;
export const Col = ({ children, cls = '', style }: { children: ReactNode; cls?: string; style?: CSSProperties }) => <div className={cx('col', cls)} style={style}>{children}</div>;
export const List = ({ children, cls = '' }: { children: ReactNode; cls?: string }) => <div className={cx('list', cls)}>{children}</div>;

/* ---------- KPI ---------- */
export function Kpi({ l, v, d = '', dir = '', sub = '' }: { l: string; v: ReactNode; d?: string; dir?: '' | 'up' | 'down'; sub?: string }) {
  return (
    <div className="kpi">
      <div className="l">{l}</div>
      <div className="v">{v}</div>
      {d && <div className={cx('d', dir)}>{dir === 'up' ? <Icon name="trending-up" /> : dir === 'down' ? <Icon name="trending-down" /> : null}{d}</div>}
      {sub && <div className="xs faint">{sub}</div>}
    </div>
  );
}

/* ---------- Rows ---------- */
export function TaskRow({ t, goto = 'task-detail', showProject = true, href: customHref, onToggle, onClick }: { t: Task; goto?: ScreenId | ''; showProject?: boolean; href?: string; onToggle?: () => void; onClick?: () => void }) {
  const cb = <div className="cb" role="checkbox" aria-checked={!!t.done} onClick={(e) => { if (onToggle) { e.preventDefault(); e.stopPropagation(); onToggle(); } }}><Icon name="check" /></div>;
  if (customHref || onClick) return (
    <Link href={customHref || '#'} className={cx('task', t.done && 'done')} onClick={onClick ? (e) => { e.preventDefault(); onClick(); } : undefined}>
      {cb}
      <div className="grow">
        <div className="t">{t.title}</div>
        <div className="meta">
          {t.due && <span className="row" style={{ gap: 3, ...(t.overdue ? { color: 'var(--danger)', fontWeight: 600 } : {}) }}><Icon name="clock" />{t.due}</span>}
          {showProject && t.project && t.project !== 'Inbox' && <><span>·</span><span>{t.project}</span></>}
          {t.est && <><span>·</span><span>{t.est}</span></>}
          {t.sub && <><span>·</span><span><Icon name="check-square" /> {t.sub}</span></>}
          {t.recurring && <><span>·</span><Icon name="repeat" /></>}
          {t.goal && <span className="chip sm accent"><Icon name="target" />{t.goal}</span>}
          {t.amount && <span className="chip sm">{t.amount}</span>}
          {t.ai && <span className="chip sm ai"><Icon name="sparkles" />AI</span>}
          {t.parent && <span className="chip sm outline parent-chip" title={`Subtask of ${t.parent}`}>↳ {t.parent.length > 26 ? t.parent.slice(0, 24) + '…' : t.parent}</span>}
        </div>
      </div>
      <div className={cx('pri', t.pri)} />
    </Link>
  );
  return (
    <Go to={goto} className={cx('task', t.done && 'done')}>
      {cb}
      <div className="grow">
        <div className="t">{t.title}</div>
        <div className="meta">
          {t.due && <span className="row" style={{ gap: 3, ...(t.overdue ? { color: 'var(--danger)', fontWeight: 600 } : {}) }}><Icon name="clock" />{t.due}</span>}
          {showProject && t.project && t.project !== 'Inbox' && <><span>·</span><span>{t.project}</span></>}
          {t.est && <><span>·</span><span>{t.est}</span></>}
          {t.sub && <><span>·</span><span><Icon name="check-square" /> {t.sub}</span></>}
          {t.recurring && <><span>·</span><Icon name="repeat" /></>}
          {t.goal && <span className="chip sm accent"><Icon name="target" />{t.goal}</span>}
          {t.amount && <span className="chip sm">{t.amount}</span>}
        </div>
      </div>
      <div className={cx('pri', t.pri)} />
    </Go>
  );
}
export function HabitRow({ h }: { h: Habit }) {
  const measurable = h.progress !== undefined;
  return (
    <Go to="habit-detail" className="habit">
      <IconBox ic={h.icon} tone={h.done ? 'success' : ''} />
      <div className="grow">
        <div className="t">{h.name}</div>
        <div className="s">{measurable ? `${h.progress}/${h.total} · ` : ''}{h.target} · {h.streak > 0 ? `🔥 ${h.streak}-day streak` : <span style={{ color: 'var(--accent)' }}>Recovering — start again today</span>}</div>
      </div>
      <div className={cx('check', h.done ? 'on' : measurable ? 'partial' : '')}>{h.done ? <Icon name="check" /> : measurable ? `${h.progress}/${h.total}` : <Icon name="plus" />}</div>
    </Go>
  );
}
export function TxRow({ t, goto = 'transactions' }: { t: Tx; goto?: ScreenId }) {
  return (
    <Go to={goto} className="lrow">
      <EmojiBox e={t.emoji} />
      <div className="grow">
        <div className="t">{t.m} {t.recurring && <Icon name="repeat" cls="faint" />} {t.src && <Chip tone="ai" ic="sparkles" cls="sm">AI</Chip>}</div>
        <div className="s">{t.cat} · {t.when}</div>
      </div>
      <div className="r"><div className={cx('v amt', t.amt > 0 ? 'pos' : 'neg')}>{t.amt > 0 ? '+' : '−'}{inr(t.amt)}</div><div className="s">{t.acc || ''}</div></div>
    </Go>
  );
}
export function LRow({ ic = '', emoji = '', tone = '', t, s = '', v = '', vs = '', goto = '', chev = true, extra, style }: { ic?: string; emoji?: string; tone?: string; t: ReactNode; s?: ReactNode; v?: ReactNode; vs?: string; goto?: ScreenId | ''; chev?: boolean; extra?: ReactNode; style?: CSSProperties }) {
  return (
    <Go to={goto} className="lrow" style={style}>
      {emoji ? <EmojiBox e={emoji} /> : ic ? <IconBox ic={ic} tone={tone} /> : null}
      <div className="grow"><div className="t">{t}</div>{s && <div className="s">{s}</div>}{extra}</div>
      {(v || vs) && <div className="r"><div className="v">{v}</div><div className="s">{vs}</div></div>}
      {chev && <Icon name="chevron-right" cls="chev" />}
    </Go>
  );
}

/* ---------- Inputs ---------- */
export function Field({ label = '', value = '', ph = '', ic = '', hint = '', err = '', sfx = '', area = false, focus = false, cls = '' }: { label?: string; value?: ReactNode; ph?: string; ic?: string; hint?: string; err?: string; sfx?: ReactNode; area?: boolean; focus?: boolean; cls?: string }) {
  return (
    <div className={cx('field', cls)}>
      {label && <div className="lbl">{label}</div>}
      <div className={cx('input', area && 'area', focus && 'focused', err && 'error')}>
        {ic && <Icon name={ic} />}
        {value ? <span>{value}</span> : <span className="ph">{ph}</span>}
        {sfx && <span className="sfx">{sfx}</span>}
      </div>
      {hint && <div className="hint">{hint}</div>}
      {err && <div className="err"><Icon name="alert" />{err}</div>}
    </div>
  );
}
export const Toggle = ({ on = true, style, onChange }: { on?: boolean; style?: CSSProperties; onChange?: (v: boolean) => void }) => <button type="button" role="switch" aria-checked={on} className={cx('toggle', on && 'on')} style={style} onClick={() => onChange?.(!on)} />;
export const ToggleRow = ({ t, s = '', on = true, ic = '', onChange }: { t: string; s?: string; on?: boolean; ic?: string; onChange?: (v: boolean) => void }) => (
  <div className="setrow" onClick={() => onChange?.(!on)}>{ic && <IconBox ic={ic} />}<div className="grow"><div className="t">{t}</div>{s && <div className="s">{s}</div>}</div><Toggle on={on} onChange={onChange} /></div>
);
export const SetRow = ({ t, s = '', r = '', ic = '', goto = '', tone = '', onClick }: { t: string; s?: string; r?: ReactNode; ic?: string; goto?: ScreenId | ''; tone?: string; onClick?: () => void }) => (
  <Go to={goto} className="setrow" onClick={onClick}>{ic && <IconBox ic={ic} tone={tone} />}<div className="grow"><div className="t">{t}</div>{s && <div className="s">{s}</div>}</div><div className="r">{r}<Icon name="chevron-right" /></div></Go>
);
export const Segmented = ({ opts, on, cls = '', onChange }: { opts: string[]; on: string; cls?: string; onChange?: (v: string) => void }) => (
  <div className={cx('segmented', cls)}>{opts.map((o) => <button type="button" key={o} className={o === on ? 'on' : ''} onClick={() => onChange?.(o)}>{o}</button>)}</div>
);
export const Tabs = ({ opts, on, onChange }: { opts: (string | [string, number | string])[]; on: string; onChange?: (v: string) => void }) => (
  <div className="tabs">{opts.map((o) => { const [l, n] = Array.isArray(o) ? o : [o, '']; return <button type="button" key={l} className={l === on ? 'on' : ''} onClick={() => onChange?.(l)}>{l}{n !== '' && <span className="n">{n}</span>}</button>; })}</div>
);
export const Pills = ({ opts, on = '', onChange }: { opts: string[]; on?: string | string[]; onChange?: (v: string) => void }) => (
  <div className="chips scroll">{opts.map((o) => <button type="button" key={o} className={cx('pill-select', (Array.isArray(on) ? on.includes(o) : o === on) && 'on')} onClick={() => onChange?.(o)}>{o}<Icon name="chevron-down" /></button>)}</div>
);
export const Empty = ({ ic, title, p, action }: { ic: string; title: string; p: string; action?: ReactNode }) => (
  <div className="empty"><IconBox ic={ic} tone="accent" /><h4>{title}</h4><p>{p}</p>{action && <div className="mt-s">{action}</div>}</div>
);
export const WeekDots = ({ w, todayIdx = 2 }: { w: number[]; todayIdx?: number }) => (
  <div className="week">{w.map((v, i) => <i key={i} className={cx(!!v && 'on', i === todayIdx && 'today')} />)}</div>
);
export const MOODS = ['😞', '😕', '😐', '🙂', '😄'];
export const MoodRow = ({ on = -1, onChange, small = false }: { on?: number; onChange?: (i: number) => void; small?: boolean }) => (
  <div className="mood">{MOODS.map((m, i) => <button type="button" key={i} className={i === on ? 'on' : ''} aria-label={`mood ${i + 1}`} style={small ? { maxWidth: 48, borderRadius: 10, fontSize: 20 } : undefined} onClick={() => onChange?.(i)}>{m}</button>)}</div>
);
export const Banner = ({ tone, ic, children }: { tone: 'warning' | 'danger' | 'info' | 'success'; ic: string; children: ReactNode }) => (
  <div className={cx('banner', tone)}><Icon name={ic} />{children}</div>
);
export const Eyebrow = ({ children, cls = '' }: { children: ReactNode; cls?: string }) => <div className={cx('eyebrow', cls)}>{children}</div>;

/* ---------- Charts (inline SVG, single-hue emphasis, neutral for context, labelled) ---------- */
/** Measure the container so the SVG viewBox matches its rendered width (no stretched text). */
function useChartWidth(fallback = 320) {
  const ref = useRef<SVGSVGElement>(null); const [w, setW] = useState(fallback);
  useLayoutEffect(() => { const el = ref.current?.parentElement; if (!el) return; const m = () => { const cw = el.clientWidth; if (cw > 40) setW(Math.round(cw)); }; m(); const ro = new ResizeObserver(m); ro.observe(el); return () => ro.disconnect(); }, []);
  return { ref, w };
}
export function Bars({ vals, labels = [], h = 120, hi = -1, tone = 'm', max = null, unit = '', w: w0 = 320, showVals = false }: { vals: number[]; labels?: string[]; h?: number; hi?: number; tone?: string; max?: number | null; unit?: string; w?: number; showVals?: boolean }) {
  const { ref, w } = useChartWidth(w0);
  const mx = max || Math.max(1, ...vals) * 1.15; const n = Math.max(1, vals.length); const gap = 6; const bw = (w - gap * (n - 1)) / n; const ph = h - 22;
  return (
    <svg ref={ref} className="chart" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" style={{ height: h }}>
      {[0.5, 1].map((g) => <line key={g} className="grid" x1={0} x2={w} y1={ph - ph * g} y2={ph - ph * g} strokeDasharray="2 3" />)}
      {vals.map((v, i) => {
        // zero stays empty (no placeholder stub), anything > 0 is at least 3px so it stays visible
        const bh = v > 0 ? Math.min(ph, Math.max(3, (ph * v) / mx)) : 0; const x = i * (bw + gap); const cls = hi === i ? 'm' : hi >= 0 ? 'n' : tone;
        const label = `${unit}${v.toLocaleString('en-IN')}`;
        return (
          <g key={i}>
            <title>{labels[i] ? `${labels[i]} · ` : ''}{label}</title>
            {bh > 0 && <rect className={cls} x={x} y={ph - bh} width={bw} height={bh} rx={3} ry={3} />}
            {bh > 0 && <rect className={cls} x={x} y={ph - Math.min(bh, 3)} width={bw} height={Math.min(bh, 3)} />}
            {(showVals || hi === i) && v > 0 && <text className="lbl" x={x + bw / 2} y={ph - bh - 5} textAnchor="middle">{label}</text>}
            {labels[i] !== undefined && <text x={x + bw / 2} y={h - 6} textAnchor="middle">{labels[i]}</text>}
          </g>
        );
      })}
    </svg>
  );
}
export function LineChart({ vals, labels = [], h = 120, w: w0 = 320, area = true, tone = '', pts = true, vals2 = null, goal = null }: { vals: number[]; labels?: string[]; h?: number; w?: number; area?: boolean; tone?: string; pts?: boolean; vals2?: number[] | null; goal?: number | null }) {
  const { ref, w } = useChartWidth(w0);
  const all = (vals2 ? vals.concat(vals2) : vals).concat(goal !== null ? [goal] : []); const mx = Math.max(...all) * 1.08 || 1, mn = Math.min(...vals, ...(vals2 || [])) * 0.9; const ph = h - 22; const n = Math.max(2, vals.length);
  const X = (i: number) => i * (w / (n - 1)); const Y = (v: number) => ph - ((v - mn) / (mx - mn || 1)) * ph;
  const path = (arr: number[]) => arr.map((v, i) => `${i ? 'L' : 'M'}${X(i).toFixed(1)},${Y(v).toFixed(1)}`).join(' ');
  return (
    <svg ref={ref} className="chart" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" style={{ height: h }}>
      {[0, 0.5, 1].map((g) => <line key={g} className="grid" x1={0} x2={w} y1={ph - ph * g} y2={ph - ph * g} strokeDasharray="2 3" />)}
      {goal !== null && <line x1={0} x2={w} y1={Y(goal)} y2={Y(goal)} stroke="var(--success)" strokeWidth={1.5} strokeDasharray="4 4" />}
      {vals2 && <path className="line n" d={path(vals2)} />}
      {area && <path className="area" d={`${path(vals)} L${X(n - 1)},${ph} L0,${ph}Z`} />}
      <path className={cx('line', tone)} d={path(vals)} />
      {pts && vals.length > 0 && <><circle className="pt" cx={X(vals.length - 1)} cy={Y(vals[vals.length - 1])} r={4} /><text className="lbl" x={X(vals.length - 1)} y={Y(vals[vals.length - 1]) - 9} textAnchor="end">{Number.isInteger(vals[vals.length - 1]) ? vals[vals.length - 1].toLocaleString('en-IN') : vals[vals.length - 1]}</text></>}
      {labels.map((l, i) => l ? <text key={i} x={X(i)} y={h - 6} textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'}>{l}</text> : null)}
    </svg>
  );
}
export function Donut({ segs, size = 120, stroke = 16 }: { segs: { v: number; c: string }[]; size?: number; stroke?: number }) {
  const r = (size - stroke) / 2, c = 2 * Math.PI * r; let off = 0; const total = segs.reduce((a, b) => a + b.v, 0);
  return (
    <svg width={size} height={size} style={{ transform: 'rotate(-90deg)', flex: 'none' }}>
      {segs.map((g, i) => { const len = (c * g.v) / total; const o = off; off += len; return <circle key={i} cx={size / 2} cy={size / 2} r={r} fill="none" stroke={g.c} strokeWidth={stroke} strokeDasharray={`${Math.max(0, len - 2)} ${c - len + 2}`} strokeDashoffset={-o} />; })}
    </svg>
  );
}
export const Heat = ({ cells, cols = 12 }: { cells: number[]; cols?: number }) => (
  <div className="heat" style={{ ['--cols' as string]: cols }}>{cells.map((v, i) => <i key={i} className={v ? `l${v}` : ''} />)}</div>
);
export function Sparkline({ vals, w = 80, h = 24 }: { vals: number[]; w?: number; h?: number }) {
  const mx = Math.max(...vals), mn = Math.min(...vals); const n = vals.length;
  const p = vals.map((v, i) => `${i ? 'L' : 'M'}${((i * w) / (n - 1)).toFixed(1)},${(h - 2 - ((v - mn) / (mx - mn || 1)) * (h - 4)).toFixed(1)}`).join(' ');
  return <svg className="chart" viewBox={`0 0 ${w} ${h}`} style={{ width: w, height: h, flex: 'none' }}><path className="line" d={p} /></svg>;
}

/* ---------- Overlays ---------- */
/** Route-backed sheet / side panel / modal. Closes on ✕, Esc, or clicking the scrim → navigates back to `back`. */
export function Sheet({ title, sub = '', cls = '', back, children, footer, onClose, head }: { title: ReactNode; sub?: string; cls?: string; back?: ScreenId | string; children: ReactNode; footer?: ReactNode; onClose?: () => void; head?: ReactNode }) {
  const router = useRouter();
  const close = onClose || (() => { if (back) router.push(back in ROUTES_ALL ? href(back as ScreenId) : back); else router.back(); });
  useEffect(() => { const k = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); }; document.addEventListener('keydown', k); return () => document.removeEventListener('keydown', k); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const side = cls.includes('side');
  return (
    <div className={cx('overlay', cls)} onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="sheet" role="dialog" aria-modal="true">
        <div className="grab" />
        <div className="sh">
          {side && <Btn ic="arrow-left" kind="ghost" cls="sm hide-tab-up" onClick={close} />}
          <div className="grow"><h2>{title}</h2>{sub && <div className="sm muted">{sub}</div>}</div>
          {head}
          <Btn ic="x" kind="ghost" cls={cx('sm', side && 'only-tablet-up')} onClick={close} title="Close (Esc)" />
        </div>
        <div className="sb">{children}</div>
        {footer && <div className="sf">{footer}</div>}
      </div>
    </div>
  );
}
const ROUTES_ALL: Record<string, string> = ROUTES;
export const Toast = ({ msg, undo = true, style }: { msg: string; undo?: boolean; style?: CSSProperties }) => (
  <div className="toast" style={style}><Icon name="check-circle" /><span>{msg}</span>{undo && <span className="u">Undo</span>}</div>
);
export const AiMsg = ({ who, children }: { who: 'user' | 'ai'; children: ReactNode }) => (
  <div className={cx('msg', who)}>{who === 'ai' && <div className="who"><Icon name="sparkles" /></div>}<div className="bubble">{children}</div></div>
);
