import { ConflictException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PrismaService } from '../prisma/prisma.service';
import { AiService } from './ai.service';

/* A tiny in-memory stand-in for the Prisma calls the action log makes. `$transaction` snapshots the table and restores
 * it when the callback throws, like a rolled-back database transaction. */
type Row = Record<string, any>;
const matches = (row: Row, where: Row = {}) => Object.entries(where).every(([k, v]) => v === undefined || row[k] === v);
const p2002 = () => new Prisma.PrismaClientKnownRequestError('Unique constraint failed on the fields: (`id`)', { code: 'P2002', clientVersion: 'test' });
const DEFAULTS = { detail: '', state: 'Approved by you', tone: 'success', revertible: false, revert: null, revertedAt: null, revertOfId: null };

function fakePrisma(rows: Row[], hooks: { onCreate?: (data: Row) => void; beforeClaim?: () => void } = {}, external: (() => void)[] = []) {
  let n = 0;
  const aiAction = {
    findFirst: async ({ where }: Row) => { const r = rows.find((x) => matches(x, where)); return r ? { ...r } : null; },
    findUnique: async ({ where }: Row) => { const r = rows.find((x) => matches(x, where)); return r ? { ...r } : null; },
    create: async ({ data }: Row) => {
      hooks.onCreate?.(data);
      const row = { ...DEFAULTS, ...data, id: data.id ?? `gen-${++n}`, createdAt: new Date(Date.UTC(2026, 9, 14, 10, 0, n)) };
      if (rows.some((r) => r.id === row.id)) throw p2002();
      rows.push(row);
      return { ...row };
    },
    updateMany: async ({ where, data }: Row) => {
      hooks.beforeClaim?.();
      const hit = rows.filter((r) => matches(r, where));
      hit.forEach((r) => Object.assign(r, data));
      return { count: hit.length };
    },
  };
  const client: Row = { aiAction };
  client.$transaction = async (fn: (db: Row) => Promise<unknown>) => {
    const snapshot = rows.map((r) => ({ ...r }));
    try { return await fn(client); } catch (e) { rows.splice(0, rows.length, ...snapshot); external.splice(0).forEach((commit) => commit()); throw e; }
  };
  return client as unknown as PrismaService;
}

const USER = 'user-1';
const A_ID = '22222222-2222-4222-8222-222222222222';
const logger = { setContext: () => {} } as any;
const service = (rows: Row[], hooks?: Parameters<typeof fakePrisma>[1], external?: (() => void)[]) => new AiService({} as any, {} as any, fakePrisma(rows, hooks, external), {} as any, logger);
const input = (over: Row = {}) => ({ action: 'Created expense', detail: 'Swiggy · ₹450', state: 'Approved by you', tone: 'success', revertible: true, revert: [{ op: 'deleteTransaction', id: 'tx-1' }], ...over });

describe('AiService.logAction', () => {
  let rows: Row[];
  beforeEach(() => { rows = []; });

  it('logs the action under the client id', async () => {
    const res = await service(rows).logAction(USER, input({ id: A_ID }));
    expect(res).toMatchObject({ id: A_ID, action: 'Created expense', detail: 'Swiggy · ₹450', revertible: true, reverted: false, revert: [{ op: 'deleteTransaction', id: 'tx-1' }] });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ id: A_ID, userId: USER });
  });

  it('a retry with the same id returns the original row and logs once', async () => {
    const svc = service(rows);
    const first = await svc.logAction(USER, input({ id: A_ID }));
    const retry = await svc.logAction(USER, input({ id: A_ID, detail: 'changed' }));
    expect(retry).toEqual(first);
    expect(rows).toHaveLength(1);
  });

  it('a concurrent duplicate that loses the insert race (P2002) returns the winner\'s row', async () => {
    const external: (() => void)[] = [];
    let raced = false;
    const svc = service(rows, { onCreate: (data) => {
      if (raced) return;
      raced = true;
      external.push(() => rows.push({ ...DEFAULTS, ...data, id: A_ID, createdAt: new Date(0) }));
      throw p2002();
    } }, external);
    const res = await svc.logAction(USER, input({ id: A_ID }));
    expect(res).toMatchObject({ id: A_ID, action: 'Created expense' });
    expect(rows.filter((r) => r.id === A_ID)).toHaveLength(1);
  });

  it('an id used by another user\'s action is a 409 and changes nothing', async () => {
    rows.push({ ...DEFAULTS, id: A_ID, userId: 'user-2', action: 'Theirs', createdAt: new Date(0) });
    const before = rows.map((r) => ({ ...r }));
    const err = await service(rows).logAction(USER, input({ id: A_ID })).catch((e) => e);
    expect(err).toBeInstanceOf(ConflictException);
    expect((err as ConflictException).getStatus()).toBe(409);
    expect((err as ConflictException).getResponse()).toMatchObject({ code: 'ID_CONFLICT' });
    expect(rows).toEqual(before);
  });

  it('a P2002 race won by another user is a 409', async () => {
    const external: (() => void)[] = [];
    const svc = service(rows, { onCreate: (data) => { if (external.length || rows.length) return; external.push(() => rows.push({ ...DEFAULTS, ...data, userId: 'user-2', id: A_ID, createdAt: new Date(0) })); throw p2002(); } }, external);
    await expect(svc.logAction(USER, input({ id: A_ID }))).rejects.toBeInstanceOf(ConflictException);
  });

  it('without an id (web) every call logs a new row, as before', async () => {
    const svc = service(rows);
    const a = await svc.logAction(USER, input());
    const b = await svc.logAction(USER, input());
    expect(a.id).not.toBe(b.id);
    expect(rows).toHaveLength(2);
  });
});

describe('AiService.revert', () => {
  let rows: Row[];
  beforeEach(() => { rows = [{ ...DEFAULTS, id: A_ID, userId: USER, action: 'Created expense', detail: 'Swiggy · ₹450', revertible: true, revert: [{ op: 'deleteTransaction', id: 'tx-1' }], createdAt: new Date(0) }]; });

  it('marks the original, adds one "Reverted" row and returns the undo ops', async () => {
    const res = await service(rows).revert(USER, A_ID);
    expect(res.original).toMatchObject({ id: A_ID, reverted: true, state: 'Approved by you · reverted' });
    expect(res.reverted).toMatchObject({ action: 'Reverted', detail: 'Swiggy · ₹450', tone: 'warning' });
    expect(res.revert).toEqual([{ op: 'deleteTransaction', id: 'tx-1' }]);
    expect(rows).toHaveLength(2);
  });

  it('a retry returns the same result and writes nothing more', async () => {
    const svc = service(rows);
    const first = await svc.revert(USER, A_ID);
    const retry = await svc.revert(USER, A_ID);
    expect(retry).toEqual(first);
    expect(rows).toHaveLength(2);
    expect(rows[0].state).toBe('Approved by you · reverted');
  });

  it('a concurrent revert that loses the claim returns the winner\'s result without a second "Reverted" row', async () => {
    let raced = false;
    const svc = service(rows, { beforeClaim: () => {
      if (raced) return;
      raced = true;
      Object.assign(rows[0], { revertedAt: new Date(1), state: 'Approved by you · reverted' });
      rows.push({ ...DEFAULTS, id: 'rev-winner', userId: USER, action: 'Reverted', detail: 'Swiggy · ₹450', state: 'By you', tone: 'warning', revertOfId: A_ID, createdAt: new Date(2) });
    } });
    const res = await svc.revert(USER, A_ID);
    expect(res.reverted).toMatchObject({ id: 'rev-winner' });
    expect(res.revert).toEqual([{ op: 'deleteTransaction', id: 'tx-1' }]);
    expect(rows.filter((r) => r.revertOfId === A_ID)).toHaveLength(1);
  });

  it('another user\'s action is a 404', async () => {
    await expect(service(rows).revert('user-2', A_ID)).rejects.toMatchObject({ status: 404 });
    expect(rows).toHaveLength(1);
  });
});
