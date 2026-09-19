import { BadRequestException, ConflictException, Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as argon2 from 'argon2';
import { createHash, randomBytes } from 'node:crypto';
import { PinoLogger } from 'nestjs-pino';
import type { Env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';
import { MoneyDefaultsService } from '../money/money-defaults.service';
import { UsersService } from '../users/users.service';
import { MailService } from './mail.service';
import { LoginInput, SignupInput } from './schemas';
import { SessionService } from './session.service';

const RESET_TTL_MS = 60 * 60 * 1000;
const hashToken = (t: string) => createHash('sha256').update(t).digest('hex');

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService, private readonly sessions: SessionService, private readonly users: UsersService,
    private readonly defaults: MoneyDefaultsService, private readonly mail: MailService, private readonly config: ConfigService<Env, true>, private readonly logger: PinoLogger,
  ) {}

  hash = (pw: string) => argon2.hash(pw, { type: argon2.argon2id });
  verify = (hash: string, pw: string) => argon2.verify(hash, pw).catch(() => false);

  async signup(input: SignupInput, ua?: string) {
    const taken = await this.prisma.user.findFirst({ where: { OR: [{ email: input.email }, ...(input.username ? [{ username: input.username }] : [])] }, select: { email: true } });
    if (taken) throw new ConflictException({ code: 'EMAIL_TAKEN', message: taken.email === input.email ? 'An account with this email already exists' : 'That username is taken' });
    const user = await this.prisma.$transaction(async (tx) => {
      const u = await tx.user.create({ data: { name: input.name, email: input.email, username: input.username, passwordHash: await this.hash(input.password) } });
      await this.defaults.createForUser(tx, u.id);
      return u;
    });
    this.logger.info({ userId: user.id }, 'User signed up');
    const sid = await this.sessions.create(user.id, true, ua);
    return { user: this.users.toPublic(user), sid, remember: true };
  }

  async login(input: LoginInput, ua?: string) {
    const id = input.identifier.toLowerCase();
    const user = await this.prisma.user.findFirst({ where: { OR: [{ email: id }, { username: id }] } });
    const ok = user ? await this.verify(user.passwordHash, input.password) : await argon2.hash(input.password).then(() => false); // constant-ish time
    if (!user || !ok) throw new UnauthorizedException({ code: 'BAD_CREDENTIALS', message: 'Email/username or password is incorrect' });
    // Signing in during the 30-day grace period cancels the pending deletion.
    if (user.deletedAt) { await this.prisma.user.update({ where: { id: user.id }, data: { deletedAt: null } }); this.logger.info({ userId: user.id }, 'Account deletion cancelled by sign-in'); }
    const sid = await this.sessions.create(user.id, input.remember, ua);
    this.logger.info({ userId: user.id }, 'User signed in');
    return { user: this.users.toPublic({ ...user, deletedAt: null }), sid, remember: input.remember };
  }

  logout = (sid: string) => this.sessions.destroy(sid);

  /** Always resolves the same way so the endpoint doesn't reveal whether an email is registered. */
  async forgotPassword(email: string) {
    const user = await this.prisma.user.findUnique({ where: { email } });
    if (!user) { this.logger.info({ email }, 'Password reset requested for unknown email'); return; }
    const token = randomBytes(32).toString('base64url');
    await this.prisma.passwordReset.create({ data: { userId: user.id, tokenHash: hashToken(token), expiresAt: new Date(Date.now() + RESET_TTL_MS) } });
    const link = `${this.config.get('APP_URL', { infer: true })}/reset-password?token=${token}`;
    await this.mail.sendPasswordReset(user.email, user.name.split(' ')[0], link);
    this.logger.info({ userId: user.id }, 'Password reset email sent');
  }

  async resetPassword(token: string, password: string) {
    const pr = await this.prisma.passwordReset.findUnique({ where: { tokenHash: hashToken(token) } });
    if (!pr || pr.usedAt || pr.expiresAt < new Date()) throw new BadRequestException({ code: 'BAD_TOKEN', message: 'This reset link is invalid or has expired. Request a new one.' });
    await this.prisma.$transaction([
      this.prisma.user.update({ where: { id: pr.userId }, data: { passwordHash: await this.hash(password) } }),
      this.prisma.passwordReset.update({ where: { id: pr.id }, data: { usedAt: new Date() } }),
    ]);
    await this.sessions.destroyAllForUser(pr.userId);
    this.logger.info({ userId: pr.userId }, 'Password reset completed');
  }
}
