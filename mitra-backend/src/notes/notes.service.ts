import { Injectable, NotFoundException } from '@nestjs/common';
import { Note } from '@prisma/client';
import { z } from 'zod';
import { fromISODate, toISODate } from '../common/money';
import { id, isoDate } from '../money/schemas';
import { PrismaService } from '../prisma/prisma.service';

const tags = z.array(z.string().trim().min(1).max(40)).max(30);
export const CreateNoteSchema = z.object({ id: id.optional(), title: z.string().trim().min(1, 'Give the note a title').max(200), body: z.string().max(200_000).default(''), folder: z.string().trim().min(1).max(40).default('Personal'), tags: tags.default([]), journalDate: isoDate.nullable().optional(), ai: z.boolean().default(false), mood: z.number().int().min(0).max(4).nullable().optional() });
/* Spelled out so PATCH never re-applies defaults (Zod 4 keeps them under .partial()). */
export const UpdateNoteSchema = z.object({ title: z.string().trim().min(1).max(200).optional(), body: z.string().max(200_000).optional(), folder: z.string().trim().min(1).max(40).optional(), tags: tags.optional(), journalDate: isoDate.nullable().optional(), ai: z.boolean().optional(), mood: z.number().int().min(0).max(4).nullable().optional() });
export type CreateNote = z.infer<typeof CreateNoteSchema>; export type UpdateNote = z.infer<typeof UpdateNoteSchema>;

/** The client keys everything by ISO day, so created/updated go out as YYYY-MM-DD. */
export const noteOut = (n: Note) => ({ id: n.id, title: n.title, body: n.body, folder: n.folder, tags: n.tags, journalDate: toISODate(n.journalDate), ai: n.ai || undefined, mood: n.mood ?? undefined, createdAt: n.createdAt.toISOString().slice(0, 10), updatedAt: n.updatedAt.toISOString().slice(0, 10) });

@Injectable()
export class NotesService {
  constructor(private readonly prisma: PrismaService) {}
  async list(userId: string) { return (await this.prisma.note.findMany({ where: { userId }, orderBy: { updatedAt: 'desc' } })).map(noteOut); }
  async create(userId: string, input: CreateNote) {
    const { journalDate, ...rest } = input;
    return noteOut(await this.prisma.note.create({ data: { ...rest, userId, journalDate: journalDate ? fromISODate(journalDate) : null } }));
  }
  async update(userId: string, id: string, patch: UpdateNote) {
    await this.own(userId, id); const { journalDate, ...rest } = patch;
    return noteOut(await this.prisma.note.update({ where: { id }, data: { ...rest, ...(journalDate !== undefined ? { journalDate: journalDate ? fromISODate(journalDate) : null } : {}), version: { increment: 1 } } }));
  }
  async remove(userId: string, id: string) { await this.own(userId, id); await this.prisma.note.delete({ where: { id } }); return { ok: true }; }
  private async own(userId: string, id: string) { const n = await this.prisma.note.findFirst({ where: { id, userId } }); if (!n) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Note not found' }); return n; }
}
