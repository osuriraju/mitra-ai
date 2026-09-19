import { BadGatewayException, Injectable, NotFoundException } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { PinoLogger } from 'nestjs-pino';
import { todayISO } from '../common/money';
import { PrismaService } from '../prisma/prisma.service';
import { RedisService } from '../redis/redis.service';
import { AiContextService } from './context.service';
import { OpenAiProvider } from './provider';
import { Ask, JournalReplySchema, LogAction, ModelReply, ModelReplySchema, ModelSuggestion, SuggestionsReplySchema } from './schemas';

const fmtInr = (n: number) => '₹' + Math.round(n).toLocaleString('en-IN');
const dayLabel = (iso: string) => { const t = todayISO(); const d = Math.round((Date.parse(iso) - Date.parse(t)) / 86_400_000); if (d === 0) return 'Today'; if (d === 1) return 'Tomorrow'; if (d === -1) return 'Yesterday'; const x = new Date(iso + 'T00:00:00Z'); return `${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][x.getUTCDay()]} ${x.getUTCDate()} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][x.getUTCMonth()]}`; };
const fmtTime = (t: string) => { const [h, m] = t.split(':').map(Number); return `${h % 12 || 12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };

const SYSTEM = `You are Mitra, the assistant inside a personal life-OS app (tasks, habits, goals, money in INR, notes, wellness). You are calm, concise and concrete. Use the CONTEXT JSON as the only source of truth about the user; never invent records or numbers. Use Indian number formatting with the ₹ sign when you mention money.

You must answer with ONE JSON object: {"text": string, "proposal": Proposal|null, "chart": number[]|null}.
- "text": your reply in plain prose (you may bold key figures with **double asterisks**). Keep it under 80 words unless the user asks for a plan or analysis.
- "chart": optional, up to 12 numbers when a small bar chart helps (e.g. spend by category); otherwise null.
- "proposal": ONLY when the user's message is asking you to record or change something. Nothing is applied until the user confirms, so propose confidently. Otherwise null. Kinds:
  • {"kind":"expense","amount":number,"categoryId":id,"accountId":id,"merchant":string,"note":string|null} — for "spent/paid/bought …". Pick categoryId from CONTEXT.categories (kind "expense") and accountId from CONTEXT.accounts (prefer the account recently used for that category; else the first). Guess a clean merchant name (e.g. "Swiggy", "Metro recharge").
  • {"kind":"task","taskTitle":string,"due":"YYYY-MM-DD"|null,"time":"HH:MM"|null,"projectId":id|null} — for reminders / to-dos. Resolve relative dates against CONTEXT.today. Only use projectIds from CONTEXT.projects.
  • {"kind":"plan","goalName":string,"target":number,"months":number,"monthly":number} — for saving-goal planning ("plan to save ₹1 lakh in 6 months").
  • {"kind":"reschedule","moves":[{"id":taskId,"to":"YYYY-MM-DD"}]} — when the user is overloaded and asks to lighten today; move low-priority open tasks (ids from CONTEXT.openTasks) to later days, keep high-priority ones.
Questions ("what did I spend on food", "how productive was I", "what goals are slipping") get text (+chart) and proposal null. If a capture is missing the amount, ask for it (proposal null). Never include wellness data unless CONTEXT.wellness is present.`;

const TOOL_SYSTEM = `You are Mitra, the assistant inside a personal life-OS app (INR money, tasks, habits, goals, wellness). Use the CONTEXT JSON as the only source of truth; never invent records. Reply with exactly the JSON object the instruction asks for and nothing else.`;

@Injectable()
export class AiService {
  constructor(private readonly ai: OpenAiProvider, private readonly ctx: AiContextService, private readonly prisma: PrismaService, private readonly redis: RedisService, private readonly logger: PinoLogger) { logger.setContext('ai'); }

  enabled() { return this.ai.enabled(); }
  private async shareWellness(userId: string) { const u = await this.prisma.user.findUnique({ where: { id: userId }, select: { settings: true } }); const ws = await this.prisma.wellnessSettings.findUnique({ where: { userId } }); return !!((u?.settings as { shareWellness?: boolean })?.shareWellness || ws?.shareAI); }

  private parse<T>(raw: string, schema: { safeParse: (v: unknown) => { success: boolean; data?: T; error?: unknown } }): T {
    let json: unknown; try { json = JSON.parse(raw.replace(/^```(?:json)?\s*|\s*```$/g, '')); } catch { this.logger.warn({ raw: raw.slice(0, 300) }, 'AI returned non-JSON'); throw new BadGatewayException({ code: 'AI_BAD_REPLY', message: 'The assistant gave an unreadable answer — try rephrasing' }); }
    const r = schema.safeParse(json); if (!r.success) { this.logger.warn({ raw: raw.slice(0, 500), error: r.error }, 'AI reply failed validation'); throw new BadGatewayException({ code: 'AI_BAD_REPLY', message: 'The assistant gave an unexpected answer — try rephrasing' }); }
    return r.data as T;
  }

  /* ---------- chat / capture ---------- */
  async ask(userId: string, input: Ask) {
    const context = await this.ctx.build(userId, { wellness: await this.shareWellness(userId) });
    const messages = [
      { role: 'system' as const, content: SYSTEM }, { role: 'system' as const, content: `CONTEXT:\n${JSON.stringify(context)}` },
      ...input.history.slice(-10).map((h) => ({ role: h.who === 'user' ? ('user' as const) : ('assistant' as const), content: h.text })),
      { role: 'user' as const, content: input.text },
    ];
    const c = await this.ai.completeJson(messages, { userId, reasoning: 'low' });
    const reply = this.parse<ModelReply>(c.text, ModelReplySchema);
    return { text: reply.text, chart: reply.chart ?? undefined, proposal: this.enrich(reply.proposal ?? null, context) };
  }

  /** Turns the model's bare proposal into the preview the UI shows, dropping anything that points at ids we don't have. */
  private enrich(p: ModelReply['proposal'], ctx: Awaited<ReturnType<AiContextService['build']>>) {
    if (!p) return undefined;
    if (p.kind === 'expense') {
      const cat = ctx.categories.find((c) => c.id === p.categoryId) ?? ctx.categories.find((c) => c.kind === 'expense'); const acc = ctx.accounts.find((a) => a.id === p.accountId) ?? ctx.accounts[0];
      if (!cat || !acc) return undefined;
      return { kind: 'expense' as const, title: `${p.merchant || cat.name} · ${fmtInr(p.amount)}`, sub: `${cat.name} · Today · ${acc.name}`, amount: p.amount, categoryId: cat.id, accountId: acc.id, merchant: p.merchant || '', note: p.note ?? undefined };
    }
    if (p.kind === 'task') {
      const proj = p.projectId ? ctx.projects.find((x) => x.id === p.projectId) : undefined;
      return { kind: 'task' as const, title: p.taskTitle, sub: [p.due ? dayLabel(p.due) : 'Inbox', p.time ? fmtTime(p.time) : null, proj?.name].filter(Boolean).join(' · '), taskTitle: p.taskTitle, due: p.due ?? undefined, time: p.time ?? undefined, projectId: proj?.id };
    }
    if (p.kind === 'plan') return { kind: 'plan' as const, title: p.goalName, sub: `4 milestones · ${fmtInr(p.monthly)}/month · recurring transfer task on the 2nd`, goalName: p.goalName, target: p.target, months: p.months, monthly: p.monthly };
    const moves = p.moves.map((m) => { const t = ctx.openTasks.find((x) => x.id === m.id); return t ? { id: t.id, title: t.title, to: m.to, label: dayLabel(m.to) } : null; }).filter((m): m is NonNullable<typeof m> => !!m);
    if (!moves.length) return undefined;
    const keep = ctx.openTasks.filter((t) => t.due === ctx.today && !moves.some((m) => m.id === t.id)).map((t) => t.title).slice(0, 3).join(', ');
    return { kind: 'reschedule' as const, title: `Keep today: ${keep || '—'}`, sub: `Move ${moves.length} task${moves.length > 1 ? 's' : ''}`, moves };
  }

  /* ---------- suggestions (cached 6h per user; refresh on demand) ---------- */
  async suggestions(userId: string, refresh = false) {
    const key = `ai:suggestions:${userId}`;
    if (!refresh) { const cached = await this.redis.get(key); if (cached) return JSON.parse(cached) as ReturnType<AiService['toSuggestion']>[]; }
    const context = await this.ctx.build(userId, { wellness: await this.shareWellness(userId) });
    const prompt = `Look at CONTEXT and propose up to 5 specific, useful actions the user could take now. Only propose an action when the data supports it (e.g. a merchant recurring monthly, an inbox with unsorted tasks, a goal with no progress in 3+ weeks, a big task with an estimate but no subtasks, a category over budget, a habit slipping with no reminder). Every id must come from CONTEXT. Return {"suggestions":[{"title","why","source","icon","tone","preview","action"}]} where icon is one of bell|repeat|check-square|folder|target|wallet|sparkles, tone is ""|accent|warning, "source" names the data area (e.g. "Money · pattern detection"), "preview" states exactly what will change, and "action" is one of:
{"kind":"set_habit_reminder","habitId","time":"HH:MM"} · {"kind":"add_recurring","name","amount","day","categoryId"} · {"kind":"add_subtasks","taskId","titles":[...]} · {"kind":"triage_inbox","moves":[{"taskId","projectId"|null,"due"|null}]} · {"kind":"pause_goal","goalId"} · {"kind":"set_budget","categoryId","amount"} · {"kind":"add_task","title","due"|null}. Return an empty list if nothing is warranted.`;
    const c = await this.ai.completeJson([{ role: 'system', content: TOOL_SYSTEM }, { role: 'system', content: `CONTEXT:\n${JSON.stringify(context)}` }, { role: 'user', content: prompt }], { userId, maxTokens: 6000, reasoning: 'medium' });
    const out = this.parse<{ suggestions: ModelSuggestion[] }>(c.text, SuggestionsReplySchema).suggestions.map((s) => this.toSuggestion(s));
    await this.redis.set(key, JSON.stringify(out), 'EX', 6 * 3600);
    return out;
  }
  private toSuggestion(s: ModelSuggestion) { const id = 'sg-' + createHash('sha1').update(JSON.stringify(s.action)).digest('hex').slice(0, 10); return { id, ic: s.icon, t: s.title, p: s.why, src: s.source, tone: s.tone, preview: s.preview, action: s.action }; }

  /* ---------- journal draft ---------- */
  async journalDraft(userId: string, date: string) {
    const context = await this.ctx.build(userId, { wellness: await this.shareWellness(userId) });
    const [done, activity] = await Promise.all([
      this.prisma.task.findMany({ where: { userId, completedAt: new Date(`${date}T00:00:00Z`) }, select: { title: true } }),
      this.prisma.activity.findMany({ where: { userId, createdAt: { gte: new Date(`${date}T00:00:00`), lt: new Date(new Date(`${date}T00:00:00`).getTime() + 86_400_000) } }, select: { text: true, module: true }, take: 40 }),
    ]);
    const prompt = `Draft a short first-person journal entry (3–5 sentences, warm, factual, no advice) for ${date} from what actually happened: completed tasks ${JSON.stringify(done.map((t) => t.title))}, activity ${JSON.stringify(activity.map((a) => a.text))}${'wellness' in context ? `, wellness ${JSON.stringify((context as { wellness: unknown }).wellness)}` : ''}. If there is little data, keep it to two honest sentences. Return {"text": string, "sources": string} where sources lists the data areas used (e.g. "tasks, money, sleep").`;
    const c = await this.ai.completeJson([{ role: 'system', content: TOOL_SYSTEM }, { role: 'system', content: `CONTEXT:\n${JSON.stringify(context)}` }, { role: 'user', content: prompt }], { userId, maxTokens: 3000, reasoning: 'low' });
    return this.parse<{ text: string; sources: string }>(c.text, JournalReplySchema);
  }

  /* ---------- audit log ---------- */
  async actions(userId: string) { return (await this.prisma.aiAction.findMany({ where: { userId }, orderBy: { createdAt: 'desc' }, take: 300 })).map(this.actionOut); }
  async logAction(userId: string, a: LogAction) { return this.actionOut(await this.prisma.aiAction.create({ data: { userId, action: a.action, detail: a.detail, state: a.state, tone: a.tone, revertible: a.revertible, revert: a.revert as object[] | undefined } })); }
  /** Append-only: the original row is marked, and a new "Reverted" row is added. Returns both. */
  async revert(userId: string, id: string) {
    const a = await this.prisma.aiAction.findFirst({ where: { id, userId } }); if (!a) throw new NotFoundException({ code: 'NOT_FOUND', message: 'Action not found' });
    if (a.revertedAt) return { original: this.actionOut(a), reverted: null, revert: [] };
    const [orig, rev] = await this.prisma.$transaction([
      this.prisma.aiAction.update({ where: { id }, data: { revertedAt: new Date(), state: `${a.state} · reverted` } }),
      this.prisma.aiAction.create({ data: { userId, action: 'Reverted', detail: a.detail, state: 'By you', tone: 'warning', revertible: false, revertOfId: id } }),
    ]);
    return { original: this.actionOut(orig), reverted: this.actionOut(rev), revert: (a.revert as object[] | null) ?? [] };
  }
  private actionOut = (a: { id: string; action: string; detail: string; state: string; tone: string; revertible: boolean; revertedAt: Date | null; createdAt: Date; revert: unknown }) => {
    const d = a.createdAt; const t = todayISO(); const iso = d.toISOString().slice(0, 10); const hh = d.getHours(); const time = `${hh % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${hh < 12 ? 'AM' : 'PM'}`;
    return { id: a.id, when: `${iso === t ? 'Today' : dayLabel(iso)} ${time}`, at: d.toISOString(), action: a.action, detail: a.detail, state: a.state, tone: a.tone, revertible: a.revertible, reverted: !!a.revertedAt, revert: (a.revert as object[] | null) ?? undefined };
  };
}
