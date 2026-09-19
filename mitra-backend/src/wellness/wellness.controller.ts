import { Body, Controller, Get, Param, Patch, Put, Query } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { isoDate } from '../money/schemas';
import { EntryInput, EntrySchema, Range, RangeSchema, SettingsInput, SettingsSchema, WellnessService } from './wellness.service';

@Controller('wellness')
export class WellnessController {
  constructor(private readonly wellness: WellnessService) {}
  @Get('bootstrap') async bootstrap(@CurrentUser() u: string) { const [entries, settings] = await Promise.all([this.wellness.entries(u, {}), this.wellness.settings(u)]); return { entries, settings }; }
  @Get('entries') entries(@CurrentUser() u: string, @Query(zod(RangeSchema)) q: Range) { return this.wellness.entries(u, q); }
  @Put('entries/:date') save(@CurrentUser() u: string, @Param('date', zod(isoDate)) date: string, @Body(zod(EntrySchema)) b: EntryInput) { return this.wellness.save(u, date, b); }
  @Get('settings') settings(@CurrentUser() u: string) { return this.wellness.settings(u); }
  @Patch('settings') updateSettings(@CurrentUser() u: string, @Body(zod(SettingsSchema)) b: SettingsInput) { return this.wellness.updateSettings(u, b); }
}
