/* Thin fetch wrapper for the Mitra API. Every error becomes an ApiError with the server's code + message,
   so screens can `toast(err.message)` without inspecting responses. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public issues?: { path: string; message: string }[]) { super(message); this.name = 'ApiError'; }
  get unauthenticated() { return this.status === 401; }
}

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
const BASE = '/api';

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, { method, credentials: 'include', headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new ApiError(0, 'OFFLINE', 'You appear to be offline');
  }
  const text = await res.text(); const data = text ? JSON.parse(text) : null;
  if (!res.ok) { const e = data?.error || {}; throw new ApiError(res.status, e.code || 'ERROR', e.message || `Request failed (${res.status})`, e.issues); }
  return data as T;
}

const qs = (q?: Record<string, string | number | undefined>) => { if (!q) return ''; const p = Object.entries(q).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`); return p.length ? `?${p.join('&')}` : ''; };

export const api = {
  get: <T>(path: string, q?: Record<string, string | number | undefined>) => request<T>('GET', path + qs(q)),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string, body?: unknown) => request<T>('DELETE', path, body),
};
export const errMsg = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong');
