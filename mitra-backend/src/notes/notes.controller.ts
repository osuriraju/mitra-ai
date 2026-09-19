import { Body, Controller, Delete, Get, Param, ParseUUIDPipe, Patch, Post } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { CreateNote, CreateNoteSchema, NotesService, UpdateNote, UpdateNoteSchema } from './notes.service';

@Controller('notes')
export class NotesController {
  constructor(private readonly notes: NotesService) {}
  @Get() list(@CurrentUser() u: string) { return this.notes.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(CreateNoteSchema)) b: CreateNote) { return this.notes.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(UpdateNoteSchema)) b: UpdateNote) { return this.notes.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.notes.remove(u, id); }
}
