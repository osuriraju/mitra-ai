import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { randomBytes } from 'node:crypto';
import type { Env } from '../config/env';
import { RedisService } from '../redis/redis.service';

export type Session = { userId: string; createdAt: string; remember: boolean; ua?: string };

const DAY = 60 * 60 * 24;
export const SESSION_TTL = { short: DAY, long: 30 * DAY } as const;

/** Server-side sessions in Redis. The cookie only carries an unguessable id; revoking is one DEL. */
@Injectable()
export class SessionService {
  constructor(private readonly redis: RedisService, private readonly config: ConfigService<Env, true>) {}

  private key = (sid: string) => `sess:${sid}`;
  private userKey = (userId: string) => `user:${userId}:sessions`;
  ttl = (remember: boolean) => (remember ? SESSION_TTL.long : SESSION_TTL.short);

  async create(userId: string, remember: boolean, ua?: string): Promise<string> {
    const sid = randomBytes(32).toString('base64url');
    const s: Session = { userId, createdAt: new Date().toISOString(), remember, ua };
    await this.redis.multi().set(this.key(sid), JSON.stringify(s), 'EX', this.ttl(remember)).sadd(this.userKey(userId), sid).exec();
    return sid;
  }

  /** Returns the session and slides its expiry (sliding window keeps active users signed in). */
  async touch(sid: string): Promise<Session | null> {
    const raw = await this.redis.get(this.key(sid)); if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    await this.redis.expire(this.key(sid), this.ttl(s.remember));
    return s;
  }

  async destroy(sid: string) {
    const raw = await this.redis.get(this.key(sid));
    const m = this.redis.multi().del(this.key(sid));
    if (raw) m.srem(this.userKey((JSON.parse(raw) as Session).userId), sid);
    await m.exec();
  }

  /** Sign out everywhere — used after a password change or account deletion. */
  async destroyAllForUser(userId: string, except?: string) {
    const sids = await this.redis.smembers(this.userKey(userId));
    const m = this.redis.multi();
    for (const sid of sids) if (sid !== except) { m.del(this.key(sid)); m.srem(this.userKey(userId), sid); }
    await m.exec();
  }

  cookieName() { return this.config.get('SESSION_COOKIE', { infer: true }); }
  cookieOptions(remember: boolean) {
    const prod = this.config.get('NODE_ENV', { infer: true }) === 'production';
    return { path: '/', httpOnly: true, sameSite: 'lax' as const, secure: prod, signed: true, maxAge: remember ? SESSION_TTL.long : undefined };
  }
}
