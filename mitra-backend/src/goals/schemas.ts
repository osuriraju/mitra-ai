import { z } from 'zod';
import { id, isoDate } from '../money/schemas';

const type = z.enum(['numeric', 'milestone', 'habit', 'date']);
const term = z.enum(['Short-term', 'Medium-term', 'Long-term']);
const reviewEvery = z.enum(['Weekly', 'Monthly', 'Quarterly']);
const status = z.enum(['active', 'paused', 'completed']);
const num = z.number().min(0).max(1e12);

export const MilestoneInputSchema = z.object({ id: id.optional(), title: z.string().trim().min(1, 'Name the milestone').max(120), target: num.nullable().optional(), targetDate: isoDate.nullable().optional(), reachedOn: isoDate.nullable().optional(), notes: z.string().max(2000).nullable().optional() });
export const UpdateMilestoneSchema = z.object({ title: z.string().trim().min(1).max(120).optional(), target: num.nullable().optional(), targetDate: isoDate.nullable().optional(), reachedOn: isoDate.nullable().optional(), notes: z.string().max(2000).nullable().optional() });

export const CreateGoalSchema = z.object({
  id: id.optional(), name: z.string().trim().min(1, 'Give the goal a name').max(160), type: type.default('numeric'), term: term.default('Medium-term'), due: isoDate, startedOn: isoDate.optional(),
  current: num.default(0), target: num.default(100), unit: z.enum(['₹', '']).default(''), formula: z.string().max(500).nullable().optional(), nextAction: z.string().trim().max(200).default(''),
  reviewEvery: reviewEvery.default('Monthly'), reviewOn: isoDate.optional(), status: status.default('active'), notes: z.string().max(20_000).default(''), milestones: z.array(MilestoneInputSchema).max(50).default([]),
});
export const UpdateGoalSchema = z.object({
  name: z.string().trim().min(1).max(160).optional(), type: type.optional(), term: term.optional(), due: isoDate.optional(), startedOn: isoDate.optional(), current: num.optional(), target: num.optional(), unit: z.enum(['₹', '']).optional(),
  formula: z.string().max(500).nullable().optional(), nextAction: z.string().trim().max(200).optional(), reviewEvery: reviewEvery.optional(), reviewOn: isoDate.optional(), status: status.optional(), notes: z.string().max(20_000).optional(),
});
export const LogProgressSchema = z.object({ value: num, note: z.string().trim().max(500).nullable().optional(), date: isoDate.optional() });

export type CreateGoal = z.infer<typeof CreateGoalSchema>; export type UpdateGoal = z.infer<typeof UpdateGoalSchema>; export type MilestoneInput = z.infer<typeof MilestoneInputSchema>; export type UpdateMilestone = z.infer<typeof UpdateMilestoneSchema>; export type LogProgress = z.infer<typeof LogProgressSchema>;
