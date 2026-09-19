/* Amount helpers — the DB holds integer paise (BigInt); the API speaks rupees as plain numbers. */
export const toMinor = (rupees: number) => BigInt(Math.round(rupees * 100));
export const toMajor = (minor: bigint | null | undefined) => minor == null ? undefined : Number(minor) / 100;
/** DB `@db.Date` values come back as UTC-midnight Dates; we only ever care about the YYYY-MM-DD part. */
export const toISODate = (d: Date | null | undefined) => d ? d.toISOString().slice(0, 10) : undefined;
export const fromISODate = (s: string) => new Date(`${s}T00:00:00.000Z`);
export const todayISO = () => new Date().toISOString().slice(0, 10);
