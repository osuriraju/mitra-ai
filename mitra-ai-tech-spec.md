# Mitra AI — Tech Stack (short version)

*17 Sep 2026 · based on the product plan PDF and the Noir · Cockpit screens*

## What we're building

One responsive **PWA** (installable on phone, tablet, desktop — no separate apps) that puts **Today · Tasks · Goals · Habits · Money · Wellness · Notes · AI** in one place. Everything is linked (a task can belong to a goal, an expense to a budget), and the AI can capture and answer questions but must **preview → confirm → log** before changing anything.

## The stack — one language, TypeScript

| Part | Pick | One-line reason |
|---|---|---|
| Frontend | **Next.js (App Router) + React** as a PWA | File-based routing for 47 screens, Serwist/`next-pwa` for the service worker; login/Today can be server-rendered for a fast first paint |
| Styling | **Plain CSS + design tokens + container queries** | Exactly how the prototype is built — copy it over |
| UI bits | **Radix UI** (menus, dialogs) · **Vaul** (bottom sheets) · **Lucide** icons · **Recharts** | Accessible, unstyled — skinned with our tokens |
| Data on client | **TanStack Query** + **Dexie (IndexedDB)** | Works offline, syncs when back online |
| Backend | **Node.js + NestJS** (Fastify adapter) | One Nest module per product module; DI, validation, OpenAPI, queues built in |
| Validation | **Zod** — same schemas on client and server | Write once, use everywhere |
| Database | **PostgreSQL** + **Prisma** | See "Why Postgres" below; Prisma Migrate + Studio for schema and data browsing |
| Queue / cache | **Redis + BullMQ** | Reminders, recurring items, nightly rollups, AI jobs |
| Files | **S3-compatible storage** (AWS S3, or self-hosted MinIO on the same server) | Receipts, attachments, exports |
| Auth | **Email/username + password** only — `@nestjs/passport` (local strategy), Argon2 hashing, sessions in Redis, password reset by email | Simple, fully owned; no social logins |
| AI | **OpenAI `gpt-5.6-luna`** behind an `AiProvider` interface (`apps/api/src/ai/provider.ts`) | Swap providers with one class if ever needed |
| Push / email | **Web Push (VAPID)** · **Nodemailer** over SMTP | Reminders, password reset, digests |
| Logging | **In-app file logs** — `logs/api.log` and `logs/dev.log` via **nestjs-pino** with daily rotation; no cloud logging services | Everything stays on our server; grep-able |
| Repo | **pnpm monorepo**: `apps/web` (Next.js), `apps/api` (Nest), `apps/worker`, `packages/ui`, `packages/domain`, `packages/ai` | Shared code, one CI |
| Hosting | One VPS or **Railway/Fly.io** running Next.js, API, worker, Postgres, Redis in Docker | Simple, containerised, three envs: dev / staging / prod |
| Tests | **Vitest** · **Playwright** at 390 / 834 / 1320 px · **Storybook** | Prove it works at phone, tablet, desktop |

## Why Postgres and not a NoSQL database?

Yes, we have logs (activity, expenses) — but logs are the *easy* part; Postgres handles append-only tables of millions of rows fine.
What actually decides the database is the rest of the app:

- **Everything links to everything** — task → goal, expense → budget, habit → goal, note → anything. Relational databases do this natively; document DBs make you duplicate data or join in code.
- **Money must add up** — budgets, totals, transfers need transactions and `SUM`s. Postgres gives ACID by default.
- **Weekly reviews / insights** cross six modules — that's SQL's home turf; in Mongo it's long aggregation pipelines.
- **Search** for the ⌘K palette is built in (`tsvector`) — no extra service.
- **Flexible bits** (note bodies, Today layout, extra metadata) go in **JSONB** columns — so we still get document-style storage where it helps.
- **Immutable AI audit log** can be enforced by the DB itself (no UPDATE/DELETE), not just by app discipline.

**Short version:** it's a relational app with a few document-shaped corners. Postgres + JSONB covers both. NoSQL would make the 10 % (logs) marginally easier and the 90 % harder.

## How the backend is organised

```
apps/api (NestJS)
  auth · users · tasks · habits · goals · money · wellness · notes · today
  activity (event log) · notifications · search · ai · sync
apps/worker (NestJS + BullMQ)
  recurring items · reminders · rollups & insights · AI briefs · exports
```

- Modules talk through **events** (`TaskCompleted` → activity log + goal progress), never by reaching into each other's tables.
- Every important action writes one row to `activity` — this feeds the Today "Activity" panel, timeline, reviews and AI context.
- All tables carry `user_id`, `created_at`, `updated_at`, `version`, `source (user | import | ai)`.
- Money is **INR only**: amounts stored as integer paise (`amountMinor BigInt`), formatted lakh/crore style on the client. No currency column.

**Logging:** `nestjs-pino` writes JSON lines to `logs/api.log` (requests, errors, AI calls with token usage) and `logs/dev.log` (debug level, dev only). Rotated daily, kept 14 days, `logs/` is git-ignored. Every line carries a request id and `user_id` so a bug can be traced end to end.

## How the AI works

| Job | Model | Notes |
|---|---|---|
| Quick capture ("Spent ₹450 on dinner", "Call John tomorrow 6pm") | `claude-sonnet-5`, low effort | Cheap, fast, returns structured JSON |
| Questions, planning, weekly review | `claude-opus-5` | Uses read-only tools (`get_transactions`, `get_tasks`…) so answers cite real data |
| Nightly daily brief | `claude-sonnet-5` via Batches | Half price, not time-sensitive |

Rules: always show a preview and ask to confirm; tag records as `source = ai`; log every action with a revert; send only the data the request needs; the app must work fully with AI switched off.
Rough cost: **$2–5 per active user per month**.

## Must-haves (non-negotiable)

- Installable PWA, works offline for reading + queued writes.
- 44 px touch targets, keyboard shortcuts on desktop, dark/light, reduced motion.
- Export all my data; delete my account.
- Financial and wellness records are never auto-merged or silently changed.

## Build order

1. **Foundation** (4–6 wks) — monorepo, tokens + Cockpit shell, auth, DB schema, activity log, CI, staging.
2. **Core** (6–8 wks) — Tasks, Habits, Goals, Notes, Quick add, Today, search.
3. **Money** (4–5 wks) · 4. **Time/Calendar** · 5. **Wellness** · 6. **Reviews/Insights** · 7. **AI** · 8. **Integrations**.

Do one thin slice end-to-end first: *Quick add → task → Today → reminder → activity row.*

## What you need to get started

Node 22 · pnpm · Docker (Postgres, Redis, MinIO) · Nest CLI · Prisma CLI · an Anthropic API key · an SMTP account for reset/digest emails · a domain.

## Decisions

Decided: **Prisma** · **email/username + password auth** (no social login) · **INR only** · **Next.js** frontend · **file-based logs** in `logs/`, no cloud logging.

Decided (18 Sep 2026): **session cookies** (httpOnly `mitra_sid` → Redis, sliding expiry; the web app proxies `/api/*` so the cookie is same-origin). Still open: single VPS vs Railway/Fly for hosting?
