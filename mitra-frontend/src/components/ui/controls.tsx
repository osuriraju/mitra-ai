'use client';
/* Interactive controls: real inputs, in-page modals, dropdown menus, confirm dialog, toast host, hydration gate. */
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useStore } from '@/store';
import { Btn, Icon } from './index';

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

/* ---------- Inputs ---------- */
type Base = { label?: string; hint?: string; err?: string; ic?: string; sfx?: ReactNode; cls?: string; style?: CSSProperties };
export function TextInput({ label, hint, err, ic, sfx, cls = '', style, value, onChange, placeholder, type = 'text', autoFocus, onEnter, inputMode, min, max, step }: Base & { value: string | number; onChange: (v: string) => void; placeholder?: string; type?: string; autoFocus?: boolean; onEnter?: () => void; inputMode?: 'text' | 'numeric' | 'decimal'; min?: number; max?: number; step?: number }) {
  const [focus, setFocus] = useState(false);
  return (
    <div className={cx('field', cls)} style={style}>
      {label && <div className="lbl">{label}</div>}
      <div className={cx('input', focus && 'focused', err && 'error')}>
        {ic && <Icon name={ic} />}
        <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} type={type} autoFocus={autoFocus} inputMode={inputMode} min={min} max={max} step={step}
          onFocus={() => setFocus(true)} onBlur={() => setFocus(false)} onKeyDown={(e) => { if (e.key === 'Enter' && onEnter) { e.preventDefault(); onEnter(); } }} />
        {sfx && <span className="sfx">{sfx}</span>}
      </div>
      {hint && <div className="hint">{hint}</div>}
      {err && <div className="err"><Icon name="alert" />{err}</div>}
    </div>
  );
}
export function TextArea({ label, hint, err, cls = '', style, value, onChange, placeholder, rows = 3, autoFocus }: Base & { value: string; onChange: (v: string) => void; placeholder?: string; rows?: number; autoFocus?: boolean }) {
  const [focus, setFocus] = useState(false);
  return (
    <div className={cx('field', cls)} style={style}>
      {label && <div className="lbl">{label}</div>}
      <div className={cx('input area', focus && 'focused', err && 'error')}><textarea rows={rows} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} autoFocus={autoFocus} onFocus={() => setFocus(true)} onBlur={() => setFocus(false)} /></div>
      {hint && <div className="hint">{hint}</div>}
      {err && <div className="err"><Icon name="alert" />{err}</div>}
    </div>
  );
}
export function Select({ label, hint, ic, cls = '', style, value, onChange, options }: Base & { value: string; onChange: (v: string) => void; options: ({ value: string; label: string } | string)[] }) {
  const opts = options.map((o) => typeof o === 'string' ? { value: o, label: o } : o);
  return (
    <div className={cx('field', cls)} style={style}>
      {label && <div className="lbl">{label}</div>}
      <div className="input">{ic && <Icon name={ic} />}<select value={value} onChange={(e) => onChange(e.target.value)}>{opts.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select><span className="sfx"><Icon name="chevron-down" /></span></div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
export const DateInput = (p: Base & { value: string; onChange: (v: string) => void }) => <TextInput {...p} type="date" ic={p.ic ?? 'calendar'} />;
export const TimeInput = (p: Base & { value: string; onChange: (v: string) => void }) => <TextInput {...p} type="time" ic={p.ic ?? 'clock'} />;

/** Big ₹ amount field with an editable input */
export function AmountField({ value, onChange, autoFocus }: { value: string; onChange: (v: string) => void; autoFocus?: boolean }) {
  return <div className="amount"><span className="cur">₹</span><input className="amt-in" inputMode="decimal" value={value} autoFocus={autoFocus} placeholder="0" onChange={(e) => onChange(e.target.value.replace(/[^\d.]/g, ''))} style={{ width: `${Math.max(1, value.length || 1)}ch` }} /></div>;
}
export const Keypad = ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
  <div className="keypad hide-tab-up">{['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫'].map((k) => <button type="button" key={k} onClick={() => onChange(k === '⌫' ? value.slice(0, -1) : k === '.' && value.includes('.') ? value : value + k)}>{k}</button>)}</div>
);

/** Chip that toggles */
export const ChipToggle = ({ on, children, onClick, ic, tone = 'outline' }: { on: boolean; children: ReactNode; onClick: () => void; ic?: string; tone?: string }) => (
  <button type="button" className={cx('chip', on ? 'accent' : tone)} onClick={onClick}>{ic && <Icon name={ic} />}{children}</button>
);
export const Stepper = ({ value, onChange, min = 0, max = 99, label }: { value: number; onChange: (v: number) => void; min?: number; max?: number; label?: string }) => (
  <div className="stepper"><Btn ic="minus" kind="outline" cls="round" onClick={() => onChange(Math.max(min, value - 1))} /><span className="v">{value}</span><Btn ic="plus" kind="outline" cls="round" onClick={() => onChange(Math.min(max, value + 1))} />{label && <span className="sm muted">{label}</span>}</div>
);

/* ---------- In-page modal (not route-backed) ---------- */
export function Modal({ open, onClose, title, sub, children, footer, cls = '' }: { open: boolean; onClose: () => void; title: ReactNode; sub?: string; children: ReactNode; footer?: ReactNode; cls?: string }) {
  useEffect(() => { if (!open) return; const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); }; document.addEventListener('keydown', k); return () => document.removeEventListener('keydown', k); }, [open, onClose]);
  if (!open) return null;
  const side = cls.includes('side');
  return (
    <div className={cx('overlay', cls)} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="sheet" role="dialog" aria-modal="true">
        <div className="grab" />
        <div className="sh">{side && <Btn ic="arrow-left" kind="ghost" cls="sm hide-tab-up" onClick={onClose} />}<div className="grow"><h2>{title}</h2>{sub && <div className="sm muted">{sub}</div>}</div><Btn ic="x" kind="ghost" cls={cx('sm', side && 'only-tablet-up')} onClick={onClose} title="Close (Esc)" /></div>
        <div className="sb">{children}</div>
        {footer && <div className="sf">{footer}</div>}
      </div>
    </div>
  );
}

/* ---------- Confirm dialog ---------- */
export function Confirm({ open, onClose, onConfirm, title, body, confirmLabel = 'Delete', danger = true }: { open: boolean; onClose: () => void; onConfirm: () => void; title: string; body?: ReactNode; confirmLabel?: string; danger?: boolean }) {
  if (!open) return null;
  return (
    <div className="overlay center-modal" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="sheet confirm" role="alertdialog">
        <div className="sb"><div className="b" style={{ fontSize: 16 }}>{title}</div>{body && <div className="sm muted">{body}</div>}<div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}><Btn label="Cancel" kind="outline" cls="sm" onClick={onClose} /><Btn label={confirmLabel} kind={danger ? 'danger' : 'primary'} cls="sm" onClick={() => { onConfirm(); onClose(); }} /></div></div>
      </div>
    </div>
  );
}

/* ---------- Dropdown menu (for "more" buttons) ---------- */
export function Dropdown({ items, ic = 'more', label, kind = 'outline', cls = 'sm', align = 'right' }: { items: { label: string; ic?: string; onClick: () => void; danger?: boolean }[]; ic?: string; label?: string; kind?: 'primary' | 'outline' | 'ghost' | 'soft' | 'danger'; cls?: string; align?: 'left' | 'right' }) {
  const [open, setOpen] = useState(false); const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { if (!open) return; const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); }; const k = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); }; document.addEventListener('mousedown', h); document.addEventListener('keydown', k); return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k); }; }, [open]);
  return (
    <div className="dd" ref={ref}>
      <Btn ic={ic} label={label} kind={kind} cls={cls} onClick={() => setOpen((o) => !o)} />
      {open && <div className={cx('dd-menu', align)}>{items.map((it) => <button type="button" key={it.label} className={cx('dd-item', it.danger && 'danger')} onClick={() => { setOpen(false); it.onClick(); }}>{it.ic && <Icon name={it.ic} />}{it.label}</button>)}</div>}
    </div>
  );
}

/* ---------- Toasts ---------- */
export function ToastHost() {
  const toasts = useStore((s) => s.toasts); const dismiss = useStore((s) => s.dismissToast);
  return (
    <div className="toast-host">
      {toasts.map((t) => <div key={t.id} className={cx('toast', t.tone)} style={{ position: 'static' }}><Icon name={t.tone === 'danger' ? 'alert' : t.tone === 'accent' ? 'sparkles' : 'check-circle'} /><span>{t.msg}</span>{t.undo && <button type="button" className="u" onClick={() => { t.undo?.(); dismiss(t.id); }}>Undo</button>}<button type="button" className="x" onClick={() => dismiss(t.id)} aria-label="Dismiss"><Icon name="x" /></button></div>)}
    </div>
  );
}

/* ---------- Empty-with-action helper ---------- */
export const useToast = () => useStore((s) => s.toast);
