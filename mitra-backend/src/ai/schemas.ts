import { z } from 'zod';
import { isoDate, hhmm } from '../money/schemas';

/* ---------- what the client sends ---------- */
export const AskSchema = z.object({
  text: z.string().trim().min(1, 'Say something').max(2000),
  history: z.array(z.object({ who: z.enum(['user', 'ai']), text: z.string().max(4000) })).max(12).default([]),
});
export const JournalDraftSchema = z.object({ date: isoDate });
export const LogActionSchema = z.object({ action: z.string().trim().min(1).max(80), detail: z.string().max(500).default(''), state: z.string().max(60).default('Approved by you'), tone: z.string().max(20).default('success'), revertible: z.boolean().default(false), revert: z.array(z.record(z.string(), z.unknown())).max(50).optional() });
export type Ask = z.infer<typeof AskSchema>; export type LogAction = z.infer<typeof LogActionSchema>;

/* ---------- what the model must return (validated, never trusted blindly) ---------- */
const money = z.coerce.number().positive().max(1e9);
export const ModelReplySchema = z.object({
  text: z.string().min(1),
  chart: z.array(z.coerce.number()).max(12).nullable().optional(),
  proposal: z.discriminatedUnion('kind', [
    z.object({ kind: z.literal('expense'), amount: money, categoryId: z.string(), accountId: z.string(), merchant: z.string().max(120).default(''), note: z.string().max(300).nullable().optional() }),
    z.object({ kind: z.literal('task'), taskTitle: z.string().min(1).max(300), due: isoDate.nullable().optional(), time: hhmm.nullable().optional(), projectId: z.string().nullable().optional() }),
    z.object({ kind: z.literal('plan'), goalName: z.string().min(1).max(160), target: money, months: z.coerce.number().int().min(1).max(120), monthly: money }),
    z.object({ kind: z.literal('reschedule'), moves: z.array(z.object({ id: z.string(), to: isoDate })).min(1).max(20) }),
  ]).nullable().optional(),
});
export type ModelReply = z.infer<typeof ModelReplySchema>;

export const SuggestionsReplySchema = z.object({ suggestions: z.array(z.object({
  title: z.string().min(1).max(120), why: z.string().max(300), source: z.string().max(60), icon: z.string().max(30).default('sparkles'), tone: z.string().max(20).default(''), preview: z.string().max(200),
  action: z.discriminatedUnion('kind', [
    z.object({ kind: z.literal('set_habit_reminder'), habitId: z.string(), time: hhmm }),
    z.object({ kind: z.literal('add_recurring'), name: z.string().max(80), amount: money, day: z.coerce.number().int().min(1).max(28), categoryId: z.string().nullable().optional() }),
    z.object({ kind: z.literal('add_subtasks'), taskId: z.string(), titles: z.array(z.string().max(120)).min(1).max(6) }),
    z.object({ kind: z.literal('triage_inbox'), moves: z.array(z.object({ taskId: z.string(), projectId: z.string().nullable().optional(), due: isoDate.nullable().optional() })).min(1).max(20) }),
    z.object({ kind: z.literal('pause_goal'), goalId: z.string() }),
    z.object({ kind: z.literal('set_budget'), categoryId: z.string(), amount: money }),
    z.object({ kind: z.literal('add_task'), title: z.string().max(200), due: isoDate.nullable().optional() }),
  ]),
})).max(6) });
export type ModelSuggestion = z.infer<typeof SuggestionsReplySchema>['suggestions'][number];

export const JournalReplySchema = z.object({ text: z.string().min(1).max(2000), sources: z.string().max(200).default('your day') });
