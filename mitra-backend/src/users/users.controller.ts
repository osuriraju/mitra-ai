import { Body, Controller, Delete, Get, HttpCode, Patch, Post, Res } from '@nestjs/common';
import type { FastifyReply } from 'fastify';
import { SessionService } from '../auth/session.service';
import { CurrentUser, SessionUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { ChangePasswordSchema, DeleteAccountSchema, OnboardedSchema, UpdateProfileSchema, UpdateSettingsSchema, type UpdateProfileInput } from './schemas';
import { UsersService } from './users.service';

@Controller('users/me')
export class UsersController {
  constructor(private readonly users: UsersService, private readonly sessions: SessionService) {}

  @Get() async me(@CurrentUser() id: string) { return { user: await this.users.getPublic(id) }; }

  @Patch() async update(@CurrentUser() id: string, @Body(zod(UpdateProfileSchema)) body: UpdateProfileInput) { return { user: await this.users.updateProfile(id, body) }; }

  @Patch('settings') async settings(@CurrentUser() id: string, @Body(zod(UpdateSettingsSchema)) body: Record<string, unknown>) { return { user: await this.users.updateSettings(id, body) }; }

  @Post('onboarded') @HttpCode(200)
  async onboarded(@CurrentUser() id: string, @Body(zod(OnboardedSchema)) body: { settings?: Record<string, unknown> }) { return { user: await this.users.markOnboarded(id, body.settings) }; }

  @Patch('password')
  async password(@CurrentUser('all') u: SessionUser, @Body(zod(ChangePasswordSchema)) body: { currentPassword: string; newPassword: string }) {
    await this.users.changePassword(u.id, u.sessionId, body.currentPassword, body.newPassword); return { ok: true };
  }

  @Get('export') async export(@CurrentUser() id: string) { return this.users.exportData(id); }

  @Delete()
  async remove(@CurrentUser() id: string, @Body(zod(DeleteAccountSchema)) body: { password: string }, @Res({ passthrough: true }) res: FastifyReply) {
    await this.users.requestDeletion(id, body.password);
    res.clearCookie(this.sessions.cookieName(), { path: '/' });
    return { ok: true, gracePeriodDays: 30 };
  }
}
