import { z } from 'zod';
import { amount, id, hhmm, isoDate } from '../money/schemas';

const status = z.enum(['todo', 'inprogress', 'blocked', 'done']);
const pri = z.enum(['high', 'med', 'low']);
const recurring = z.enum(['Daily', 'Weekly', 'Monthly', 'Quarterly']);

export const CreateProjectSchema = z.object({ id: id.optional(), name: z.string().trim().min(1, 'Name the project').max(80), emoji: z.string().trim().min(1).max(8).default('🚀'), goalId: z.string().max(64).nullable().optional() });
/* Update schemas are spelled out (not `.partial()` of the create ones): in Zod 4 `.partial()` keeps `.default()`s, which would reset untouched fields on PATCH. */
export const UpdateProjectSchema = z.object({ name: z.string().trim().min(1, 'Name the project').max(80).optional(), emoji: z.string().trim().min(1).max(8).optional(), goalId: z.string().max(64).nullable().optional(), archived: z.boolean().optional() });

export const CreateTaskSchema = z.object({
  id: id.optional(), title: z.string().trim().min(1, 'Give the task a title').max(300),
  projectId: id.nullable().optional(), goalId: z.string().max(64).nullable().optional(), habitId: z.string().max(64).nullable().optional(),
  due: isoDate.nullable().optional(), time: hhmm.nullable().optional(), estMin: z.number().int().min(0).max(24 * 60).nullable().optional(), pri: pri.nullable().optional(),
  status: status.default('todo'), done: z.boolean().default(false), completedAt: isoDate.nullable().optional(), parentId: id.nullable().optional(),
  notes: z.string().max(20_000).default(''), recurring: recurring.nullable().optional(), amount: amount.nullable().optional(), someday: z.boolean().default(false),
  source: z.enum(['user', 'import', 'ai', 'recurring']).default('user'),
});
export const UpdateTaskSchema = z.object({
  title: z.string().trim().min(1, 'Give the task a title').max(300).optional(),
  projectId: id.nullable().optional(), goalId: z.string().max(64).nullable().optional(), habitId: z.string().max(64).nullable().optional(),
  due: isoDate.nullable().optional(), time: hhmm.nullable().optional(), estMin: z.number().int().min(0).max(24 * 60).nullable().optional(), pri: pri.nullable().optional(),
  status: status.optional(), done: z.boolean().optional(), completedAt: isoDate.nullable().optional(), parentId: id.nullable().optional(),
  notes: z.string().max(20_000).optional(), recurring: recurring.nullable().optional(), amount: amount.nullable().optional(), someday: z.boolean().optional(),
});
export const ListTasksSchema = z.object({ projectId: id.optional(), includeDone: z.enum(['true', 'false']).optional(), doneSince: isoDate.optional() });

export type CreateProject = z.infer<typeof CreateProjectSchema>; export type UpdateProject = z.infer<typeof UpdateProjectSchema>;
export type CreateTask = z.infer<typeof CreateTaskSchema>; export type UpdateTask = z.infer<typeof UpdateTaskSchema>; export type ListTasks = z.infer<typeof ListTasksSchema>;
