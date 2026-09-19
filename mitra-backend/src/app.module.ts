import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { APP_GUARD } from '@nestjs/core';
import { ScheduleModule } from '@nestjs/schedule';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { LoggerModule } from 'nestjs-pino';
import { ActivityModule } from './activity/activity.module';
import { AiModule } from './ai/ai.module';
import { AuthModule } from './auth/auth.module';
import { SessionModule } from './auth/session.module';
import { Env, validateEnv } from './config/env';
import { HealthController } from './health/health.controller';
import { MoneyModule } from './money/money.module';
import { PrismaModule } from './prisma/prisma.module';
import { RedisModule } from './redis/redis.module';
import { GoalsModule } from './goals/goals.module';
import { HabitsModule } from './habits/habits.module';
import { NotesModule } from './notes/notes.module';
import { WellnessModule } from './wellness/wellness.module';
import { TasksModule } from './tasks/tasks.module';
import { UsersModule } from './users/users.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, validate: validateEnv }),
    /* File logs only (spec: no cloud logging). logs/api.<date>.<n>.log = info+, logs/dev.<date>.<n>.log = debug (dev only); daily rotation, 14 days kept. */
    LoggerModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService<Env, true>) => {
        const dev = config.get('NODE_ENV', { infer: true }) !== 'production'; const dir = config.get('LOG_DIR', { infer: true });
        const roll = (name: string) => ({ file: `${dir}/${name}`, frequency: 'daily', extension: '.log', mkdir: true, dateFormat: 'yyyy-MM-dd', limit: { count: 14 } });
        return {
          assignResponse: true, // PinoLogger.assign({ userId }) in the auth guard also tags the 'request completed' line
          pinoHttp: {
            level: dev ? 'debug' : 'info',
            redact: ['req.headers.cookie', 'req.headers.authorization', 'res.headers["set-cookie"]'],
            autoLogging: { ignore: (req) => req.url === '/api/health' },
            transport: { targets: [
              { target: 'pino-roll', level: 'info', options: roll('api') },
              ...(dev ? [{ target: 'pino-roll', level: 'debug', options: roll('dev') }, { target: 'pino-pretty', level: 'debug', options: { colorize: true, translateTime: 'HH:MM:ss', ignore: 'pid,hostname,req,res,responseTime' } }] : []),
            ] },
          },
        };
      },
    }),
    ScheduleModule.forRoot(),
    ThrottlerModule.forRoot([{ ttl: 60_000, limit: 300 }]),
    PrismaModule, RedisModule, SessionModule, ActivityModule, UsersModule, MoneyModule, TasksModule, HabitsModule, GoalsModule, NotesModule, WellnessModule, AiModule, AuthModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: ThrottlerGuard }],
})
export class AppModule {}
