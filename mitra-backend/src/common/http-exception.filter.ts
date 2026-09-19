import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { Logger } from 'nestjs-pino';

/** Every error leaves the API as `{ error: { code, message, issues? } }` so the client has one shape to handle. */
@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  constructor(private readonly logger: Logger) {}
  catch(exception: unknown, host: ArgumentsHost) {
    const res = host.switchToHttp().getResponse<FastifyReply>();
    const req = host.switchToHttp().getRequest<FastifyRequest>();
    let status = HttpStatus.INTERNAL_SERVER_ERROR; let body: Record<string, unknown> = { code: 'INTERNAL', message: 'Something went wrong' };
    if (exception instanceof HttpException) {
      status = exception.getStatus(); const r = exception.getResponse();
      if (typeof r === 'string') body = { code: codeFor(status), message: r };
      else { const o = r as Record<string, unknown>; body = { code: (o.code as string) || codeFor(status), message: Array.isArray(o.message) ? o.message[0] : (o.message as string) || (o.error as string) || codeFor(status), ...(o.issues ? { issues: o.issues } : {}) }; }
    } else {
      this.logger.error({ err: exception, url: req.url, method: req.method }, 'Unhandled exception');
    }
    res.status(status).send({ error: body });
  }
}
const codeFor = (s: number) => ({ 400: 'BAD_REQUEST', 401: 'UNAUTHENTICATED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 409: 'CONFLICT', 429: 'RATE_LIMITED' } as Record<number, string>)[s] || 'ERROR';
