import { createParamDecorator, ExecutionContext, SetMetadata } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';

export const IS_PUBLIC = 'isPublic';
/** Routes that don't need a session (login, signup, health…). Everything else is guarded. */
export const Public = () => SetMetadata(IS_PUBLIC, true);

export type SessionUser = { id: string; sessionId: string };
export type AuthedRequest = FastifyRequest & { user?: SessionUser };

/** Injects the authenticated user's id (or the whole session user with `@CurrentUser('all')`). */
export const CurrentUser = createParamDecorator((data: 'all' | undefined, ctx: ExecutionContext) => {
  const req = ctx.switchToHttp().getRequest<AuthedRequest>();
  return data === 'all' ? req.user : req.user?.id;
});
