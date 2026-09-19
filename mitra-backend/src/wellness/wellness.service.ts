import { Injectable } from '@nestjs/common';
import { Prisma, WellnessEntry, WellnessSettings } from '@prisma/client';
import { z } from 'zod';
import { ActivityService } from '../activity/activity.service';
import { fromISODate, toISODate, todayISO } from '../common/money';
import { hhmm, isoDate } from '../money/schemas';
import { PrismaService } from '../prisma/prisma.service';

export const EntrySchema = z.object({
  mood: z.number().int().min(0).max(4).nullable().optional(), sleepStart: hhmm.nullable().optional(), sleepEnd: hhmm.nullable().optional(),
  steps: z.number().int().min(0).max(200_000).nullable().optional(), water: z.number().int().min(0).max(100).nullable().optional(), weight: z.number().min(1).max(500).nullable().optional(), note: z.string().max(2000).nullable().optional(),
});
export const SettingsSchema = z.object({
  height: z.number().int().min(50).max(272).optional(), sleepTarget: z.number().int().min(60).max(900).optional(), stepsTarget: z.number().int().min(100).max(100_000).optional(), waterTarget: z.number().int().min(1).max(30).optional(),
  track: z.object({ mood: z.boolean(), sleep: z.boolean(), steps: z.boolean(), water: z.boolean(), bmi: z.boolean() }).partial().optional(), shareAI: z.boolean().optional(), showOnToday: z.boolean().optional(), morningReminder: hhmm.optional(), eveningReminder: hhmm.optional(),
});
export const RangeSchema = z.object({ from: isoDate.optional(), to: isoDate.optional() });
export type EntryInput = z.infer<typeof EntrySchema>; export type SettingsInput = z.infer<typeof SettingsSchema>; export type Range = z.infer<typeof RangeSchema>;

const entryOut = (e: WellnessEntry) => ({ mood: e.mood ?? undefined, sleepStart: e.sleepStart ?? undefined, sleepEnd: e.sleepEnd ?? undefined, steps: e.steps ?? undefined, water: e.water ?? undefined, weight: e.weight ?? undefined, note: e.note ?? undefined, savedAt: toISODate(e.savedAt) });
const settingsOut = (s: WellnessSettings) => ({ height: s.height, sleepTarget: s.sleepTarget, stepsTarget: s.stepsTarget, waterTarget: s.waterTarget, track: { mood: true, sleep: true, steps: true, water: true, bmi: true, ...(s.track as object) }, shareAI: s.shareAI, showOnToday: s.showOnToday, morningReminder: s.morningReminder, eveningReminder: s.eveningReminder });

@Injectable()
export class WellnessService {
  constructor(private readonly prisma: PrismaService, private readonly activity: ActivityService) {}

  /** Entries keyed by day — default window is the last 120 days (trends look back 90). */
  async entries(userId: string, r: Range) {
    const from = fromISODate(r.from ?? new Date(Date.now() - 120 * 86_400_000).toISOString().slice(0, 10));
    const rows = await this.prisma.wellnessEntry.findMany({ where: { userId, date: { gte: from, ...(r.to ? { lte: fromISODate(r.to) } : {}) } }, orderBy: { date: 'asc' } });
    return Object.fromEntries(rows.map((e) => [toISODate(e.date)!, entryOut(e)]));
  }

  /** Merge-save one day (only the fields sent change; null clears a field). */
  async save(userId: string, date: string, patch: EntryInput) {
    const d = fromISODate(date); const isNew = !(await this.prisma.wellnessEntry.findUnique({ where: { userId_date: { userId, date: d } } }));
    const e = await this.prisma.wellnessEntry.upsert({ where: { userId_date: { userId, date: d } }, create: { userId, date: d, ...patch }, update: { ...patch } });
    if (isNew && date === todayISO() && patch.mood != null) await this.activity.log(userId, { module: 'wellness', icon: 'heart', tone: 'info', text: `Check-in · mood ${['😞', '😕', '😐', '🙂', '😄'][patch.mood] || ''}${patch.sleepStart && patch.sleepEnd ? ` · slept ${patch.sleepStart}–${patch.sleepEnd}` : ''}`.trim() });
    return { date, entry: entryOut(e) };
  }

  async settings(userId: string) { return settingsOut(await this.prisma.wellnessSettings.upsert({ where: { userId }, create: { userId }, update: {} })); }
  async updateSettings(userId: string, patch: SettingsInput) {
    const cur = await this.prisma.wellnessSettings.upsert({ where: { userId }, create: { userId }, update: {} });
    const { track, ...rest } = patch;
    return settingsOut(await this.prisma.wellnessSettings.update({ where: { userId }, data: { ...rest, ...(track ? { track: { ...(cur.track as object), ...track } as Prisma.InputJsonObject } : {}) } }));
  }
}
