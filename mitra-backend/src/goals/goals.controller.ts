import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Patch, Post } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { GoalsService } from './goals.service';
import * as S from './schemas';

@Controller('goals')
export class GoalsController {
  constructor(private readonly goals: GoalsService) {}
  @Get() list(@CurrentUser() u: string) { return this.goals.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateGoalSchema)) b: S.CreateGoal) { return this.goals.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateGoalSchema)) b: S.UpdateGoal) { return this.goals.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.goals.remove(u, id); }
  @Post(':id/progress') @HttpCode(200) progress(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.LogProgressSchema)) b: S.LogProgress) { return this.goals.logProgress(u, id, b); }
  @Post(':id/milestones') addMs(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.MilestoneInputSchema)) b: S.MilestoneInput) { return this.goals.addMilestone(u, id, b); }
  @Patch(':id/milestones/:mid') updMs(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Param('mid', ParseUUIDPipe) mid: string, @Body(zod(S.UpdateMilestoneSchema)) b: S.UpdateMilestone) { return this.goals.updateMilestone(u, id, mid, b); }
  @Delete(':id/milestones/:mid') delMs(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Param('mid', ParseUUIDPipe) mid: string) { return this.goals.deleteMilestone(u, id, mid); }
}
