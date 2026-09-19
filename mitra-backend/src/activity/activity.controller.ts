import { Controller, Get, Query } from '@nestjs/common';
import { z } from 'zod';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { ActivityService } from './activity.service';

const Q = z.object({ date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(), limit: z.coerce.number().int().positive().max(500).optional() });

@Controller('activity')
export class ActivityController {
  constructor(private readonly activity: ActivityService) {}
  @Get() list(@CurrentUser() userId: string, @Query(zod(Q)) q: z.infer<typeof Q>) { return this.activity.list(userId, q); }
}
