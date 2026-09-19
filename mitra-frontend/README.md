# mitra-frontend

Next.js (App Router) PWA for Mitra AI — the **Noir · Cockpit** design, ported 1:1 from `../design/mitra-ai-design-screens.html`.
Fully interactive on a client-side store (Zustand, persisted to `localStorage`) seeded with realistic sample data — no backend needed yet.

## Run

```bash
pnpm install          # from the monorepo root
pnpm dev              # → http://localhost:3000  (or: pnpm --filter mitra-frontend dev)
pnpm --filter mitra-frontend build
pnpm --filter mitra-frontend typecheck
```

## Layout

```
src/app/            one folder per URL (47 screens) — thin pages that render a screen component
src/screens/        screen components grouped by module: global · today · tasks · habits · goals · money · wellness · notes · ai
src/components/ui   the design system (Btn, Chip, Card, Section, TaskRow, HabitRow, charts, Sheet, …)
src/components/shell/Screen.tsx   Cockpit shell: top nav (priority+ overflow → "More ▾"), menus, mobile bottom bar + sections sheet
src/components/theme            light/dark provider (persisted in localStorage)
src/store/          Zustand store: types.ts (domain model) · seed.ts (sample data, relative to today) · index.ts (actions) · selectors.ts (derived data)
src/lib/ai.ts       local natural-language "assistant" (capture / ask / plan / reschedule) — replaced by the Claude-backed API later
src/lib/dates.ts    date + ₹ helpers · src/lib/routes.ts  screen-id → URL map + primary nav · src/lib/icons.ts  icon set
src/app/globals.css design tokens + all component styles (container-query responsive: <768 mobile · 768–1199 tablet · ≥1200 desktop)
```

## What works (client-side)

Tasks (create/edit/complete, subtasks as first-class tasks with `parentId` — own status/priority/due, visible on the board, inbox triage, filters, list/board with drag-and-drop, focus timer), Projects (create/edit/delete),
Goals (create, log progress, milestones auto-tick, review flow, pause/complete), Habits (check-off, measurable logging, editable history calendar),
Money (add/edit expenses with keypad, budgets from data, categories, recurring, subscriptions), Wellness (check-in → trends, BMI), Notes (editor with
checklists + [[links]], journal with AI draft, live search), AI (capture → preview → apply, suggestions, revertable log), ⌘K palette, notifications,
settings (persisted), profile export/reset. Every overlay closes on Esc or clicking outside; destructive actions confirm and toasts offer Undo.

`Profile → Reset demo data` restores the seed.

## Conventions

- Screen ids from the prototype are kept (`goto="task-detail"`), resolved through `href()` in `src/lib/routes.ts`.
- Overlay screens (quick add, task detail, expense entry, …) render their base screen plus a `<Sheet>` and have their own URL.
- Layout responds to the `.frame` container width, not the viewport — resize `.frame` to preview mobile inside a wide window.
