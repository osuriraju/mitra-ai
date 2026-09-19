import { z } from 'zod';
import { id, isoDate, hhmm } from '../money/schemas';

const type = z.enum(['binary', 'count', 'duration', 'quantity']);
const tod = z.enum(['morning', 'anytime', 'evening']);
const days = z.array(z.boolean()).length(7).refine((d) => d.some(Boolean), 'Pick at least one day');

export const CreateHabitSchema = z.object({
  id: id.optional(), name: z.string().trim().min(1, 'Name the habit').max(80), type: type.default('binary'), target: z.number().int().min(1).max(100_000).default(1), unit: z.string().trim().max(20).default(''),
  icon: z.string().trim().max(30).default('repeat'), timeOfDay: tod.default('anytime'), days: days.default([true, true, true, true, true, true, true]), reminder: hhmm.nullable().optional(), goalId: z.string().max(64).nullable().optional(), gentle: z.boolean().default(true),
});
/* Spelled out (no `.partial()` of the create schema) so PATCH never re-applies defaults. */
export const UpdateHabitSchema = z.object({
  name: z.string().trim().min(1).max(80).optional(), type: type.optional(), target: z.number().int().min(1).max(100_000).optional(), unit: z.string().trim().max(20).optional(), icon: z.string().trim().max(30).optional(),
  timeOfDay: tod.optional(), days: days.optional(), reminder: hhmm.nullable().optional(), goalId: z.string().max(64).nullable().optional(), gentle: z.boolean().optional(), archived: z.boolean().optional(),
});
export const LogSchema = z.object({ date: isoDate, value: z.number().int().min(0).max(100_000).nullable() });

export type CreateHabit = z.infer<typeof CreateHabitSchema>; export type UpdateHabit = z.infer<typeof UpdateHabitSchema>; export type LogInput = z.infer<typeof LogSchema>;
