import { Body, Controller, Get, HttpCode, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { AiService } from './ai.service';
import { Ask, AskSchema, JournalDraftSchema, LogAction, LogActionSchema } from './schemas';

@Controller('ai')
export class AiController {
  constructor(private readonly ai: AiService) {}
  @Get('status') status() { return { enabled: this.ai.enabled() }; }
  @Throttle({ default: { limit: 30, ttl: 60_000 } })
  @Post('ask') @HttpCode(200) ask(@CurrentUser() u: string, @Body(zod(AskSchema)) b: Ask) { return this.ai.ask(u, b); }
  @Throttle({ default: { limit: 6, ttl: 60_000 } })
  @Get('suggestions') suggestions(@CurrentUser() u: string, @Query('refresh') refresh?: string) { return this.ai.suggestions(u, refresh === '1'); }
  @Throttle({ default: { limit: 10, ttl: 60_000 } })
  @Post('journal-draft') @HttpCode(200) journal(@CurrentUser() u: string, @Body(zod(JournalDraftSchema)) b: { date: string }) { return this.ai.journalDraft(u, b.date); }
  @Get('actions') actions(@CurrentUser() u: string) { return this.ai.actions(u); }
  @Post('actions') logAction(@CurrentUser() u: string, @Body(zod(LogActionSchema)) b: LogAction) { return this.ai.logAction(u, b); }
  @Post('actions/:id/revert') @HttpCode(200) revert(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.ai.revert(u, id); }
}
