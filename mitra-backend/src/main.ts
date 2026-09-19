import 'reflect-metadata';
import { randomUUID } from 'node:crypto';
import fastifyCookie from '@fastify/cookie';
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { FastifyAdapter, NestFastifyApplication } from '@nestjs/platform-fastify';
import { Logger } from 'nestjs-pino';
import { AppModule } from './app.module';
import { HttpExceptionFilter } from './common/http-exception.filter';
import type { Env } from './config/env';

async function bootstrap() {
  const app = await NestFactory.create<NestFastifyApplication>(AppModule, new FastifyAdapter({ trustProxy: true, genReqId: (req: { headers: Record<string, unknown> }) => (req.headers['x-request-id'] as string) || randomUUID() }), { bufferLogs: true });
  const logger = app.get(Logger); app.useLogger(logger);
  const config: ConfigService<Env, true> = app.get(ConfigService);

  await app.register(fastifyCookie, { secret: config.get('SESSION_SECRET', { infer: true }) as string });
  app.enableCors({ origin: config.get('CORS_ORIGINS', { infer: true }).split(',').map((s) => s.trim()), credentials: true });
  app.setGlobalPrefix('api');
  app.useGlobalFilters(new HttpExceptionFilter(logger));
  app.enableShutdownHooks();

  const port = config.get('PORT', { infer: true });
  await app.listen({ port, host: '0.0.0.0' });
  logger.log(`Mitra API listening on http://localhost:${port}/api`);
}
bootstrap();
