import { ConflictException, Injectable, NotFoundException, UnauthorizedException } from '@nestjs/common';
import { Cron } from '@nestjs/schedule';
import { Prisma, User } from '@prisma/client';
import * as argon2 from 'argon2';
import { PinoLogger } from 'nestjs-pino';
import { SessionService } from '../auth/session.service';
import { toISODate, toMajor } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { UpdateProfileInput } from './schemas';

export type PublicUser = ReturnType<UsersService['toPublic']>;
const GRACE_DAYS = 30;

@Injectable()
export class UsersService {
  constructor(private readonly prisma: PrismaService, private readonly sessions: SessionService, private readonly logger: PinoLogger) {}

  /** The shape the client sees — never the password hash. */
  toPublic(u: User) {
    return {
      id: u.id, name: u.name, email: u.email, username: u.username, phone: u.phone,
      settings: (u.settings ?? {}) as Record<string, unknown>,
      onboardedAt: u.onboardedAt?.toISOString() ?? null, deletedAt: u.deletedAt?.toISOString() ?? null,
      memberSince: u.createdAt.toISOString(),
    };
  }

  async get(id: string) { const u = await this.prisma.user.findUnique({ where: { id } }); if (!u) throw new NotFoundException({ code: 'NOT_FOUND', message: 'User not found' }); return u; }
  async getPublic(id: string) { return this.toPublic(await this.get(id)); }

  async updateProfile(id: string, patch: UpdateProfileInput) {
    if (patch.email || patch.username) {
      const clash = await this.prisma.user.findFirst({ where: { id: { not: id }, OR: [...(patch.email ? [{ email: patch.email }] : []), ...(patch.username ? [{ username: patch.username }] : [])] }, select: { email: true } });
      if (clash) throw new ConflictException({ code: 'EMAIL_TAKEN', message: clash.email === patch.email ? 'That email is already in use' : 'That username is taken' });
    }
    return this.toPublic(await this.prisma.user.update({ where: { id }, data: patch }));
  }

  /** Shallow-merges into the JSONB settings document. */
  async updateSettings(id: string, patch: Record<string, unknown>) {
    const u = await this.get(id);
    const settings = { ...(u.settings as Record<string, unknown>), ...patch } as Prisma.InputJsonObject;
    return this.toPublic(await this.prisma.user.update({ where: { id }, data: { settings } }));
  }

  async markOnboarded(id: string, settings?: Record<string, unknown>) {
    const u = await this.get(id);
    const merged = { ...(u.settings as Record<string, unknown>), ...(settings || {}) } as Prisma.InputJsonObject;
    return this.toPublic(await this.prisma.user.update({ where: { id }, data: { onboardedAt: u.onboardedAt ?? new Date(), settings: merged } }));
  }

  async changePassword(id: string, sessionId: string, currentPassword: string, newPassword: string) {
    const u = await this.get(id);
    if (!(await argon2.verify(u.passwordHash, currentPassword).catch(() => false))) throw new UnauthorizedException({ code: 'BAD_PASSWORD', message: 'Current password is incorrect' });
    await this.prisma.user.update({ where: { id }, data: { passwordHash: await argon2.hash(newPassword, { type: argon2.argon2id }) } });
    await this.sessions.destroyAllForUser(id, sessionId); // keep this device signed in, drop the rest
    this.logger.info({ userId: id }, 'Password changed');
  }

  /** "Export all my data" — one JSON document with every module we have so far. */
  async exportData(id: string) {
    const [user, accounts, categories, transactions, recurring, subscriptions, activity] = await Promise.all([
      this.get(id),
      this.prisma.account.findMany({ where: { userId: id } }), this.prisma.category.findMany({ where: { userId: id } }),
      this.prisma.transaction.findMany({ where: { userId: id }, orderBy: { date: 'asc' } }), this.prisma.recurring.findMany({ where: { userId: id } }),
      this.prisma.subscription.findMany({ where: { userId: id } }), this.prisma.activity.findMany({ where: { userId: id }, orderBy: { createdAt: 'asc' } }),
    ]);
    const [notes, wellness, wellnessSettings] = await Promise.all([this.prisma.note.findMany({ where: { userId: id } }), this.prisma.wellnessEntry.findMany({ where: { userId: id }, orderBy: { date: 'asc' } }), this.prisma.wellnessSettings.findUnique({ where: { userId: id } })]);
    const [projects, tasks, habits, goals] = await Promise.all([this.prisma.project.findMany({ where: { userId: id } }), this.prisma.task.findMany({ where: { userId: id } }), this.prisma.habit.findMany({ where: { userId: id }, include: { logs: true } }), this.prisma.goal.findMany({ where: { userId: id }, include: { milestones: true, progress: true } })]);
    return {
      exportedAt: new Date().toISOString(), profile: this.toPublic(user),
      money: {
        accounts: accounts.map((a) => ({ ...a, balance: toMajor(a.balanceMinor), balanceMinor: undefined })),
        categories: categories.map((c) => ({ ...c, budget: toMajor(c.budgetMinor), budgetMinor: undefined })),
        transactions: transactions.map((t) => ({ ...t, amount: toMajor(t.amountMinor), amountMinor: undefined, date: toISODate(t.date) })),
        recurring: recurring.map((r) => ({ ...r, amount: toMajor(r.amountMinor), amountMinor: undefined, lastPaidOn: toISODate(r.lastPaidOn) })),
        subscriptions: subscriptions.map((s) => ({ ...s, amount: toMajor(s.amountMinor), amountMinor: undefined, next: toISODate(s.next), lastUsed: toISODate(s.lastUsed) })),
      },
      tasks: { projects, tasks: tasks.map((t) => ({ ...t, amount: toMajor(t.amountMinor), amountMinor: undefined, due: toISODate(t.due), completedAt: toISODate(t.completedAt) })) },
      habits: habits.map((h) => ({ ...h, logs: h.logs.map((l) => ({ date: toISODate(l.date), value: l.value })) })),
      goals: goals.map((g) => ({ ...g, due: toISODate(g.due), startedOn: toISODate(g.startedOn), reviewOn: toISODate(g.reviewOn), milestones: g.milestones.map((m) => ({ ...m, targetDate: toISODate(m.targetDate), reachedOn: toISODate(m.reachedOn) })), progress: g.progress.map((p) => ({ ...p, date: toISODate(p.date) })) })),
      notes: notes.map((n) => ({ ...n, journalDate: toISODate(n.journalDate) })),
      wellness: { settings: wellnessSettings, entries: wellness.map((w) => ({ ...w, date: toISODate(w.date) })) },
      activity,
    };
  }

  /** Marks the account for deletion and signs the user out everywhere. Signing in again within 30 days cancels it. */
  async requestDeletion(id: string, password: string) {
    const u = await this.get(id);
    if (!(await argon2.verify(u.passwordHash, password).catch(() => false))) throw new UnauthorizedException({ code: 'BAD_PASSWORD', message: 'Password is incorrect' });
    await this.prisma.user.update({ where: { id }, data: { deletedAt: new Date() } });
    await this.sessions.destroyAllForUser(id);
    this.logger.info({ userId: id }, 'Account deletion requested');
  }

  /** Nightly: permanently remove accounts whose grace period has passed (cascades through every table). */
  @Cron('0 3 * * *')
  async purgeDeleted() {
    const cutoff = new Date(Date.now() - GRACE_DAYS * 86_400_000);
    const r = await this.prisma.user.deleteMany({ where: { deletedAt: { lt: cutoff } } });
    if (r.count) this.logger.info({ count: r.count }, 'Purged deleted accounts');
  }
}
