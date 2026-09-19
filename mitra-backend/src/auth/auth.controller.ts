import { Body, Controller, Get, HttpCode, Post, Req, Res } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { AuthedRequest, CurrentUser, Public, SessionUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { UsersService } from '../users/users.service';
import { AuthService } from './auth.service';
import { ForgotSchema, LoginSchema, ResetSchema, SignupSchema, type LoginInput, type SignupInput } from './schemas';
import { SessionService } from './session.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly auth: AuthService, private readonly sessions: SessionService, private readonly users: UsersService) {}

  private setCookie(res: FastifyReply, sid: string, remember: boolean) { res.setCookie(this.sessions.cookieName(), sid, this.sessions.cookieOptions(remember)); }
  private clearCookie(res: FastifyReply) { res.clearCookie(this.sessions.cookieName(), { path: '/' }); }

  @Public() @Throttle({ default: { limit: 5, ttl: 60_000 } })
  @Post('signup')
  async signup(@Body(zod(SignupSchema)) body: SignupInput, @Req() req: FastifyRequest, @Res({ passthrough: true }) res: FastifyReply) {
    const { user, sid, remember } = await this.auth.signup(body, req.headers['user-agent']);
    this.setCookie(res, sid, remember);
    return { user };
  }

  @Public() @Throttle({ default: { limit: 10, ttl: 60_000 } })
  @Post('login') @HttpCode(200)
  async login(@Body(zod(LoginSchema)) body: LoginInput, @Req() req: FastifyRequest, @Res({ passthrough: true }) res: FastifyReply) {
    const { user, sid, remember } = await this.auth.login(body, req.headers['user-agent']);
    this.setCookie(res, sid, remember);
    return { user };
  }

  @Public()
  @Post('logout') @HttpCode(200)
  async logout(@Req() req: AuthedRequest, @Res({ passthrough: true }) res: FastifyReply) {
    if (req.user) await this.auth.logout(req.user.sessionId);
    this.clearCookie(res);
    return { ok: true };
  }

  /** Who am I — the client calls this on boot to decide between the app shell and the login screen. */
  @Get('me')
  async me(@CurrentUser('all') u: SessionUser) { return { user: await this.users.getPublic(u.id) }; }

  @Public() @Throttle({ default: { limit: 3, ttl: 60_000 } })
  @Post('forgot-password') @HttpCode(200)
  async forgot(@Body(zod(ForgotSchema)) body: { email: string }) { await this.auth.forgotPassword(body.email); return { ok: true }; }

  @Public() @Throttle({ default: { limit: 5, ttl: 60_000 } })
  @Post('reset-password') @HttpCode(200)
  async reset(@Body(zod(ResetSchema)) body: { token: string; password: string }) { await this.auth.resetPassword(body.token, body.password); return { ok: true }; }
}
