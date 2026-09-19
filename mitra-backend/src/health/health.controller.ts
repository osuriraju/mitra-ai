import { Controller, Get } from '@nestjs/common';
import { Public } from '../common/decorators';
import { PrismaService } from '../prisma/prisma.service';
import { RedisService } from '../redis/redis.service';

@Controller('health')
export class HealthController {
  constructor(private readonly prisma: PrismaService, private readonly redis: RedisService) {}
  @Public() @Get()
  async check() {
    const [db, cache] = await Promise.all([this.prisma.$queryRaw`SELECT 1`.then(() => 'ok').catch(() => 'down'), this.redis.ping().then(() => 'ok').catch(() => 'down')]);
    return { status: db === 'ok' && cache === 'ok' ? 'ok' : 'degraded', db, redis: cache, time: new Date().toISOString() };
  }
}
