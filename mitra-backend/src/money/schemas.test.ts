import { describe, expect, it } from 'vitest';
import { amount } from './schemas';
import { toMinor } from '../common/money';

describe('amount schema', () => {
  it.each([1.1, 4.35, 1234.1, 0.01, 999999.99, 100])('accepts %s', (n) => {
    expect(amount.safeParse(n).success).toBe(true);
  });
  it.each([1.234, 0.001, 0, -5, 1e9 + 1])('rejects %s', (n) => {
    expect(amount.safeParse(n).success).toBe(false);
  });
  it('keeps the 3-decimal message', () => {
    const r = amount.safeParse(1.234);
    expect(r.success ? '' : r.error.issues[0].message).toBe('Amounts can have at most 2 decimals');
  });
  it('accepts every 2-decimal value up to 5000.00', () => {
    const rejected: number[] = [];
    for (let p = 1; p <= 500000; p++) if (!amount.safeParse(p / 100).success) rejected.push(p);
    expect(rejected).toEqual([]);
  }, 30_000);
});

describe('toMinor', () => {
  it('converts to exact paise', () => {
    expect(toMinor(1.1)).toBe(110n);
    expect(toMinor(4.35)).toBe(435n);
    expect(toMinor(1234.1)).toBe(123410n);
  });
});
