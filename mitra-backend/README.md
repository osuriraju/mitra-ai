# mitra-backend

NestJS (Fastify) API for Mitra AI. Covers **auth, profile, Money, Tasks, Habits, Goals, Notes/Journal and Wellness**. AI is the remaining module.

## Run it

```bash
# once
cp .env.example .env            # edit DATABASE_URL / REDIS_URL / SESSION_SECRET
createdb mitra                  # or any Postgres you like
pnpm prisma:migrate             # creates the schema
pnpm db:seed                    # optional: demo user aarav@example.com / password123

# every day
pnpm dev                        # http://localhost:4000/api  (health: /api/health)
```

The web app proxies `/api/*` to this server (see `mitra-frontend/next.config.ts`), so the session cookie is same-origin and no CORS is needed in the browser.

## Layout

```
src/
  auth/      signup · login · logout · me · forgot/reset password · session guard (Redis sessions, httpOnly cookie)
  users/     profile · settings (JSONB) · change password · export · delete (30-day grace, nightly purge)
  money/     accounts · categories · transactions · recurring (+ /pay) · subscriptions · /money/bootstrap
  tasks/     tasks (subtasks, recurring, /toggle spawns the next occurrence) · projects · /tasks/bootstrap
  habits/    habits + per-day logs (PUT /habits/:id/logs)
  goals/     goals · milestones · progress log (server owns the progress maths)
  notes/     notes + journal entries (journalDate, mood)
  wellness/  daily check-ins (merge-saved per day) + settings
  activity/  event log written by every important action (GET /activity)
  common/    Zod pipe, decorators (@Public, @CurrentUser), error filter, money helpers
prisma/      schema + migrations + dev seed
logs/        api.<date>.<n>.log (info+) · dev.<date>.<n>.log (debug, dev only) · rotated daily, 14 kept
```

## Conventions

- **Validation** — Zod schemas per module (`schemas.ts`), applied with `@Body(zod(Schema))`. Errors come back as `{ error: { code, message, issues? } }`.
- **Money** — INR only. Stored as integer paise (`amountMinor BigInt`), exposed as rupees (`amount: number`). Account balances are adjusted inside the same DB transaction as the change (create / update / delete all reconcile).
- **Client ids** — create endpoints accept an optional UUID `id` so the PWA can write optimistically and reconcile.
- **Sessions** — `mitra_sid` cookie → Redis `sess:<id>`; sliding expiry (1 day, or 30 days with "keep me signed in"). Password change / reset / delete revoke sessions.
- **Ownership** — every query is scoped by `userId` from the session; cross-user ids 404.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | /auth/signup · /auth/login · /auth/logout | login accepts email **or** username |
| GET | /auth/me | |
| POST | /auth/forgot-password · /auth/reset-password | reset link logged when SMTP is unset |
| GET/PATCH | /users/me | name, email, phone, username |
| PATCH | /users/me/settings · /users/me/password | |
| POST | /users/me/onboarded | |
| GET | /users/me/export | full JSON export |
| DELETE | /users/me | `{ password }` — 30-day grace |
| GET | /money/bootstrap | accounts + categories + 12 months of transactions + recurring + subscriptions |
| CRUD | /money/accounts · /money/categories · /money/transactions · /money/recurring · /money/subscriptions | |
| GET | /money/transactions?month=&from=&to=&categoryId=&accountId=&kind=&q= | |
| POST | /money/recurring/:id/pay | records this month's occurrence as a transaction |
| GET | /tasks/bootstrap | tasks (open + 90 days of completed) + projects |
| CRUD | /tasks · /projects | subtasks cascade; deleting a project sends its tasks to Inbox |
| POST | /tasks/:id/toggle | complete ⇄ reopen; recurring tasks return `spawned` |
| CRUD | /habits | logs come back as `{ 'YYYY-MM-DD': value }` |
| PUT | /habits/:id/logs | `{ date, value \| null }` — idempotent per day |
| CRUD | /goals · /goals/:id/milestones/:mid | milestone-type goals derive progress from milestones |
| POST | /goals/:id/progress | numeric goals add the value; auto-ticks milestones, completes at target |
| CRUD | /notes | journal entries are notes with `journalDate` |
| GET | /wellness/bootstrap | entries keyed by day (120 days) + settings |
| PUT | /wellness/entries/:date | merge-save one day; `null` clears a field |
| GET/PATCH | /wellness/settings | |
| GET | /activity?date=&limit= | |
| GET | /health | db + redis |
