import { Body, Controller, Delete, Get, Param, ParseUUIDPipe, Patch, Post, Put } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { HabitsService } from './habits.service';
import * as S from './schemas';

@Controller('habits')
export class HabitsController {
  constructor(private readonly habits: HabitsService) {}
  @Get() list(@CurrentUser() u: string) { return this.habits.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateHabitSchema)) b: S.CreateHabit) { return this.habits.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateHabitSchema)) b: S.UpdateHabit) { return this.habits.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.habits.remove(u, id); }
  /** PUT /habits/:id/logs { date, value | null } — idempotent per day. */
  @Put(':id/logs') log(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.LogSchema)) b: S.LogInput) { return this.habits.log(u, id, b); }
}
