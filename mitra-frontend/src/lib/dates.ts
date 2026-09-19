/* Date helpers — everything in the app is keyed by ISO day strings ('2026-09-17'). */
const pad = (n: number) => String(n).padStart(2, '0');
export const toISO = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const fromISO = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
export const todayISO = () => toISO(new Date());
export const addDays = (iso: string, n: number) => { const d = fromISO(iso); d.setDate(d.getDate() + n); return toISO(d); };
export const diffDays = (a: string, b: string) => Math.round((fromISO(a).getTime() - fromISO(b).getTime()) / 86400000);
export const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
export const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
export const dow = (iso: string) => fromISO(iso).getDay();
/** Monday-based index 0..6 */
export const dowMon = (iso: string) => (dow(iso) + 6) % 7;
export const monthKey = (iso: string) => iso.slice(0, 7);
export const daysInMonth = (iso: string) => { const d = fromISO(iso); return new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate(); };
export const startOfWeek = (iso: string) => addDays(iso, -dowMon(iso));

/** 'Today' · 'Tomorrow' · 'Yesterday' · 'Mon 21 Sep' */
export function fmtDay(iso: string, opts: { relative?: boolean } = { relative: true }) {
  const t = todayISO(); const n = diffDays(iso, t);
  if (opts.relative !== false) { if (n === 0) return 'Today'; if (n === 1) return 'Tomorrow'; if (n === -1) return 'Yesterday'; }
  const d = fromISO(iso);
  return `${DOW[d.getDay()]} ${d.getDate()} ${MON[d.getMonth()]}`;
}
export const DOW_LONG = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
export const fmtLong = (iso: string) => { const d = fromISO(iso); return `${DOW_LONG[d.getDay()]}, ${d.getDate()} ${MON_LONG[d.getMonth()]} ${d.getFullYear()}`; };
export const fmtShort = (iso: string) => { const d = fromISO(iso); return `${d.getDate()} ${MON[d.getMonth()]}`; };
export const fmtMonth = (iso: string) => { const d = fromISO(iso); return `${MON_LONG[d.getMonth()]} ${d.getFullYear()}`; };
export const isPast = (iso: string) => iso < todayISO();
export const nowTime = () => { const d = new Date(); const h = d.getHours(); return `${h % 12 || 12}:${pad(d.getMinutes())} ${h < 12 ? 'AM' : 'PM'}`; };
/** '17:00' → '5:00 PM' */
export const fmtTime = (t?: string) => { if (!t) return ''; const [h, m] = t.split(':').map(Number); return `${h % 12 || 12}:${pad(m)} ${h < 12 ? 'AM' : 'PM'}`; };
export const minsToHm = (m: number) => m >= 60 ? `${Math.floor(m / 60)}h ${m % 60 ? pad(m % 60) + 'm' : ''}`.trim() : `${m}m`;
export const uid = () => Math.random().toString(36).slice(2, 10);
/** Server-accepted ids for records we create optimistically (the API validates them as UUIDs). */
export const uuid = () => (typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => { const r = (Math.random() * 16) | 0; return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16); }));
export const inr = (n: number) => '₹' + Math.abs(Math.round(n)).toLocaleString('en-IN');
export const inrShort = (n: number) => Math.abs(n) >= 100000 ? `₹${(n / 100000).toFixed(2)}L` : Math.abs(n) >= 1000 ? `₹${(n / 1000).toFixed(1)}k` : inr(n);
