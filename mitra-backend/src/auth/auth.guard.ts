import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import type { FastifyRequest } from 'fastify';
import { PinoLogger } from 'nestjs-pino';
import { AuthedRequest, IS_PUBLIC } from '../common/decorators';
import { SessionService } from './session.service';

/** Global guard: every route needs a valid session cookie unless marked `@Public()`. */
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly reflector: Reflector, private readonly sessions: SessionService, private readonly logger: PinoLogger) {}
  async canActivate(ctx: ExecutionContext) {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC, [ctx.getHandler(), ctx.getClass()]);
    const req = ctx.switchToHttp().getRequest<AuthedRequest>();
    const sid = readSid(req, this.sessions.cookieName());
    if (sid) { const s = await this.sessions.touch(sid); if (s) { req.user = { id: s.userId, sessionId: sid }; this.logger.assign({ userId: s.userId }); } } // every later log line for this request carries the user id
    if (isPublic || req.user) return true;
    throw new UnauthorizedException({ code: 'UNAUTHENTICATED', message: 'Please sign in' });
  }
}

export function readSid(req: FastifyRequest, name: string): string | null {
  const raw = req.cookies?.[name]; if (!raw) return null;
  const r = req.unsignCookie(raw); return r.valid ? r.value : null;
}
