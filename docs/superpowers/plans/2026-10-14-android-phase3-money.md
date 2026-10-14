# Mitra Android — Phase 3 (Money) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **LIGHT PLAN (user decision for speed):** tasks are briefs, not code. Each brief fixes the files, the exact interfaces neighbouring tasks rely on, the behaviour to port (web file + function + copy), named tests with concrete expected values, and the verify command. Implement each task TDD-style (failing test → code → green → commit) following the Phase 2 code that already exists — when this plan says "as `TasksRepositoryImpl`", open that file and match it.

**Goal:** Ship the Money module on Android — dashboard (Summary / Analytics with month switcher and account filter), transactions (filter / search / sort / grouped list), add/edit transaction sheet with numeric keypad and live ₹ formatting, budgets, categories, recurring (+ Mark paid), subscriptions, a fully working Quick Add **Expense**, the real Money card + "Spent today" stat on Today, and a **Money home-screen widget** ("Safe to spend", with per-widget "Hide amounts") — all offline-first through the Phase 1/2 outbox.

**Architecture:** Same layering as Phases 1–2 (feature → domain ← data, MVVM, Route + stateless Screen). Five new SQLDelight tables (`accounts`, `categories`, `transactions`, `recurring`, `subscriptions`; schema **v3** via `migrations/2.sqm`) are the source of truth. `MoneyRepositoryImpl` is a copy of the `TasksRepositoryImpl` pattern (write counter, `applyEcho`, refresh-race guard, pending-id protection, sync handlers registered at DI time) plus local account-balance effects. Web selectors (`mitra-frontend/src/store/selectors.ts` money block, `screens/money.tsx`, `screens/today.tsx` `TodayMoney`) are ported 1:1 into `domain/money` operating on integer paise. Repository flows become hot and shared (`shareIn`) — Tasks retrofitted. The Money widget is Glance in `androidApp/widget/`, sharing `WidgetUpdater` and `MidnightReceiver` with the Tasks widget.

**Tech Stack:** Unchanged from Phase 2 (Kotlin 2.4.21, AGP 9.4.1, CMP 1.12.1, Koin 4.2.2, Ktor 3.6.0, SQLDelight 2.4.1, coroutines 1.11.0, datetime 0.8.0, Glance 1.2.0, Turbine 1.2.1). **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-10-13-android-app-design.md` — §13 item 3 (Money) with §6 (sync), §7 (ported selectors: `spent`, `spentByCategory`, `spentToday`, `thisMonth`, `totalBudget`, safe-to-spend, next bill/renewal, `inr`/`inrShort`), §9 (amount keypad with live ₹ formatting, month switcher, press-and-scrub charts, bottom-sheet forms, Quick Add Expense parsing), §10 (Money widget). Phase 2 plan (conventions): `docs/superpowers/plans/2026-10-14-android-phase2-tasks.md`.

## Global Constraints

- All code lives under `mitra-mobile/`; repo root is `/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai` — **the path contains a space; always quote it**. Never stage the repo-root `README.md`; `git add` only the files a task names.
- Branch: keep working on `feat/android-phase1`.
- Kotlin package root `tech.beyondscale.mitra`. compileSdk 37, targetSdk 36, minSdk 26, JDK 17, Gradle wrapper only. Versions exactly as in `gradle/libs.versions.toml`; add nothing.
- `shared/*/commonMain` must not import Android APIs. `domain` depends only on `core`. `feature` never depends on `data`. Only `app` and `androidApp` wire `data` in. ViewModels depend on domain interfaces (`MoneyRepository`, `TasksRepository`, `AppClock`), never on `data` classes.
- Screens are `XxxRoute` (collects state, owns `koinViewModel`) + stateless `XxxScreen(state, onAction, …)`; one-off effects go through `EventQueue` / `EventEffect` (`feature/common/UiEvent.kt`); snackbars with Undo via the shell's `SnackbarController`.
- **Money is integer paise (`Long`) on the device, end to end** — models, DB columns (`*Paise INTEGER`), selectors. The server speaks **rupees as JSON numbers** (`amount`, `balance`, `budget`; see `mitra-backend/src/money/mapper.ts`). Conversion lives only in `data/remote/MoneyDtos.kt`: in = `(rupees * 100).roundToLong()`, out = `paise / 100.0` (the backend `amount` refine is a tolerance check, so `435 / 100.0` = `4.35` is accepted). Never use `Float` for money.
- Formatting only through `core` `inr(rupees: Double)` / `inrShort(rupees: Double)` (already exact ports of the web) via the new paise helpers `inrPaise(Long)` / `inrShortPaise(Long)` (Task 2). Ratios (safe-to-spend per day, percentages) are computed in `Double` from paise and rounded exactly where the web rounds (`Math.round` → `jsRound`).
- API paths relative, no leading slash, base URL ends in `/api/`. POST/PATCH without payload send `{}`; DELETE sends no body. Server contract (from `mitra-backend/src/money/*`):
  `GET money/bootstrap → { accounts, categories, transactions, recurring, subscriptions }` (transactions: last 12 months, server-UTC cut-off; accounts/categories exclude archived);
  `POST|PATCH|DELETE money/transactions[/:id]`, `money/categories[/:id]`, `money/recurring[/:id]`, `money/subscriptions[/:id]` (client UUID `id` on POST);
  `POST money/recurring/:id/pay { id?, accountId?, date?, amount? } → { transaction, recurring }` (creates the transaction and sets `lastPaidOn`).
  Wire enums: tx `kind` = `expense|income|transfer`, `source` = `user|import|ai|recurring`; category `kind` = `expense|income`; account `kind` = `bank|credit|cash`; recurring `kind` = `bill|income|transfer`; subscription `cycle` = `Monthly|Yearly`. Nullable refs are sent as JSON `null` (as the web store does: `categoryId: null`, `toAccountId: null`, `note: null`, `budget: null`).
- **Mark paid always sends a client transaction `id` and the device's local `date`** (`clock.today()`), never relies on the server's UTC `todayISO()`; it is an action row (`op` POST, `entityId = recurringId`), never coalesced.
- Every outbox write carries `entityId`; PATCHes coalesce with `coalesceKey = "transaction:<id>" | "category:<id>" | "recurring:<id>" | "subscription:<id>"` (newest-row-only rule from Phase 2). Outbox entity names are added to `OutboxEntities`: `TRANSACTION = "transaction"`, `CATEGORY = "category"`, `RECURRING = "recurring"`, `SUBSCRIPTION = "subscription"`, `RECURRING_PAY = "recurring-pay"`.
- "Today" / "this month" always come from `clock.today()` (device local date) and roll over with `clock.dayFlow()`; never UTC.
- UI copy, ordering and thresholds follow `mitra-frontend/src/screens/money.tsx` and `screens/today.tsx` (`TodayMoney`) unless this plan says otherwise. Icons from `MitraIcons`; colours from `Mitra.colors`.
- Tests: `./gradlew testAndroidHostTest` from `mitra-mobile/` (all shared `commonTest` + `androidHostTest` on JVM). Harnesses: `MockServer` + `testDriver()` + `FixedClock` + `ViewModelTest` + Turbine. Fakes live in `shared/testing`.
- Commit after every task; message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `local.properties`.
- *(Deviations flagged for the user)*: (a) Accounts are read-only on Android this phase (created/edited on the web / Profile in Phase 6) — pickers list them, the dashboard shows balances; (b) Analytics renders as mobile cards/lists (no tables) — same numbers; (c) budget bar tone uses the spec §10 thresholds everywhere — warning at **≥ 90 %**, danger at **> 100 %** (the web dashboard uses rounded `p > 90`); (d) Receipt upload and the "Mitra noticed Apollo Pharmacy" detected-recurring banner are not ported (both are web placeholders); (e) "Hide amounts" masks the widget's amounts whenever it is on (the app itself never masks); (f) bill/renewal notifications stay in Phase 6.

## Review Focus

1. **Paise / ₹ formatting drifting from the web** — typing `4.35`, `0.1`, `1,200`, `₹99.999` on the keypad, editing `1234.10`, a budget of `₹2,000`: stored paise, wire rupees and every displayed string must equal what the web shows for the same data (`inr` rounds half-up on whole rupees: 123.50 → `₹124`; negatives show without a sign). Pinned in Task 2 (`MoneyFormatTest.inrPaiseMatchesWebRounding`), Task 3 (`AmountInputTest.rejectsThirdDecimalAndSecondDot`, `AmountInputTest.liveFormatsIndianGrouping`) and Task 5 (`MoneyDtosTest.paiseRoundTripsThroughRupees`).
2. **Month boundaries and IST midnight** — at 00:30 IST on 1 Nov the UTC date is still 31 Oct: "this month", "Spent today", days-left (`dim − day + 1`), safe-to-spend, paid-this-month and next-bill days must use the local date and recompute at local midnight; on the last day of the month safe-to-spend = everything left (÷ 1), never ÷ 0. Pinned in Task 2 (`MoneySelectorsTest.safeToSpendOnLastDayDividesByOne`, `MoneySelectorsTest.firstOfMonthStartsAFreshMonth`, `MoneySelectorsTest.nextBillWrapsAcrossMonthEnd`) and Task 12 (`TodayViewModelTest.moneyCardRollsOverAtLocalMidnight`).
3. **Offline create → edit → delete of one expense** — rows replay in order, the edit coalesces only into the newest queued PATCH, the delete never runs before the POST, local account balances move and move back exactly (no double-apply when the echo lands), and a refresh racing the queue neither resurrects the deleted row nor reverts the edit. Pinned in Task 5 (`MoneyRepositoryImplTest.offlineCreateEditDeleteReachesServerInOrder`, `MoneyRepositoryImplTest.balanceEffectsReverseExactly`, `MoneyRepositoryImplTest.refreshKeepsRowsWithPendingChanges`).
4. **Mark paid tapped twice / resent after a lost response** — exactly one transaction for that bill this month, dated the local day, and the queue never blocks. Pinned in Task 6 (`MoneyRepositoryImplTest.payTwiceQueuesOnce`, `MoneyRepositoryImplTest.paySendsClientIdAndLocalDate`, `MoneyRepositoryImplTest.payResentAfterLostResponseLeavesOneTransaction`).
5. **Widget leaking amounts** — with "Hide amounts" on, or after sign-out, or on a re-subscribed repository flow that still holds the previous account's replay value, the widget must render no digits of any amount. Pinned in Task 1 (`SharedFlowsTest.resubscribeAfterStopDoesNotReplayStaleValue`) and Task 14 (`MoneyWidgetModelTest.hiddenAmountsContainNoDigits`, `MoneyWidgetModelTest.signedOutShowsNoAmounts`).

---

## File Map (all paths relative to `mitra-mobile/`)

`…` = `src/commonMain/kotlin/tech/beyondscale/mitra` (tests: `src/commonTest/kotlin/tech/beyondscale/mitra`).

```
shared/core/          …/core/Dates.kt                         + monthKey, daysInMonth, dayOfMonth, fmtShort, MON, MON_LONG, monthTitle, addMonthsKey (T2)
                      …/core/Money.kt                          + inrPaise, inrShortPaise, jsRoundToLong (internal→public)                           (T2)
                      …/core/AmountInput.kt                    keypad / text amount editing + live format                                         (T3)
shared/domain/        …/domain/model/Money.kt                  Account, Category, Transaction, Recurring, Subscription, enums, drafts, changes      (T2)
                      …/domain/repository/MoneyRepository.kt   interface + PayResult                                                              (T2)
                      …/domain/money/MoneySelectors.kt         ported selectors (paise)                                                           (T2)
                      …/domain/money/TodayMoney.kt             safe-to-spend / next bill / alerts model                                           (T2)
                      …/domain/money/ExpenseParser.kt          Quick Add expense parser                                                           (T3)
                      …/domain/tasks/QuickAddParser.kt         ParsedField + Amount, Category                                                     (T3)
                      …/domain/money/MoneyWidgetModel.kt       widget model                                                                       (T14)
shared/testing/       …/testing/FakeMoneyRepository.kt                                                                                            (T2)
shared/data/          sqldelight/…/data/db/{Accounts,Categories,Transactions,Recurring,Subscriptions}.sq, sqldelight/migrations/2.sqm     (T4)
                      …/data/local/{LocalData.kt (+tables), MoneyRows.kt}                                                                 (T4)
                      …/data/local/SharedFlows.kt                                                                                         (T1)
                      …/data/repository/TasksRepositoryImpl.kt (shareIn)                                                                  (T1)
                      …/data/remote/MoneyDtos.kt · …/data/repository/{MoneyRepositoryImpl,MoneyPatches,BalanceEffects}.kt                 (T5, T6)
                      …/data/sync/PendingSettings.kt (OutboxEntities +5) · …/data/di/DataModule.kt                                       (T1, T5)
                      androidHostTest …/data/db/MigrationTest.kt (+v2→v3)                                                                 (T4)
shared/designsystem/  …/designsystem/components/{AmountKeypad,MonthSwitcher,ScrubBars,Donut,EmojiBox}.kt                                (T7)
shared/feature/       …/feature/money/{MoneyViewModel,MoneyScreen,TxRow}.kt                                                               (T8)
                      …/feature/money/{TxEditorViewModel,TxFormSheet,TransactionsViewModel,TransactionsScreen}.kt                         (T9)
                      …/feature/money/{BudgetsViewModel,BudgetsScreen,CategoriesViewModel,CategoriesScreen}.kt                            (T10)
                      …/feature/money/{RecurringViewModel,RecurringScreen,SubscriptionsViewModel,SubscriptionsScreen}.kt                  (T11)
                      …/feature/quickadd/{QuickAddViewModel,QuickAddSheet}.kt · …/feature/today/{TodayViewModel,TodayScreen}.kt           (T12)
                      …/feature/di/FeatureModule.kt                                                                                       (T13)
shared/app/           …/app/{Destinations,MainShell,LaunchTarget}.kt                                                                      (T13)
androidApp/           kotlin/…/android/widget/{MoneyWidget,MoneyWidgetReceiver,MoneyWidgetConfigActivity,WidgetUpdater,MidnightReceiver}.kt (T14)
                      kotlin/…/android/MitraApplication.kt · src/main/AndroidManifest.xml · res/xml/money_widget_info.xml · res/values/strings.xml (T14)
README.md (mitra-mobile/)                                                                                                         (T15)
```

## Parallelism

Dependency waves (tasks in the same wave touch disjoint files and may run in parallel):

- **Wave 1:** T1 (data: shared flows), T2 (core + domain + testing), T7 (designsystem).
- **Wave 2 (after T2):** T3 (parser + amount input), T4 (schema), T8, T10, T11 (feature VMs/screens against `FakeMoneyRepository`; screens also need T7).
- **Wave 3:** T5 (after T1, T4) → T6 (after T5, same files). In parallel: T9 (after T3, T7, T8 — reuses `TxRow`), T12 (after T3).
- **Wave 4:** T13 (after T8–T12 — it alone edits `FeatureModule.kt`, `MainShell.kt`, `Destinations.kt`, `LaunchTarget.kt`).
- **Wave 5:** T14 (after T5, T13 — needs DI + launch URIs). Then T15.

Shared-file owners (never edit outside the owning task): `DataModule.kt` T1→T5; `QuickAddParser.kt` T3; `FeatureModule.kt`/`MainShell.kt`/`Destinations.kt`/`LaunchTarget.kt` T13; `WidgetUpdater.kt`/`MidnightReceiver.kt`/`MitraApplication.kt`/manifest/`strings.xml` T14; `TodayViewModel.kt`/`TodayScreen.kt`/`QuickAdd*` T12.

---
### Task 1: Shared (hot) repository flows — helper + Tasks retrofit

Phase 2 deferred item: repository `Flow`s are cold, so every collector (Today, Tasks tab, Quick Add, widget, `WidgetUpdater`) runs its own SQL query and re-maps rows. Make them shared once, here, so Money (Task 5) starts that way.

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/SharedFlows.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImpl.kt` (constructor + `tasks` / `projects`)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/di/DataModule.kt` (pass the scope)
- Modify: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/TasksRepositoryImplTest.kt` (`Env` construction only)
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/local/SharedFlowsTest.kt`

**Interfaces:**
- Produces: `fun <T> Flow<T>.shareForRepository(scope: CoroutineScope): SharedFlow<T>` = `shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), replay = 1)`. `const val SHARE_STOP_TIMEOUT_MS = 5_000L`.
- Produces: `TasksRepositoryImpl(api, db, prefs, outbox, engine, clock, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO)` — new `scope` parameter **before** `io`; `tasks`/`projects` become `…mapToList(io).map{…}.shareForRepository(scope)`. Interface `TasksRepository` unchanged (`Flow<List<Task>>`).
- Consumed by: Task 5 (`MoneyRepositoryImpl` uses `shareForRepository` for all five flows).

**Requirements:**
- `replayExpirationMillis = 0` is mandatory: after the last collector stops (e.g. widget session ends, sign-out clears the DB) a new collector must not receive the previous account's cached list before the fresh query result (Review Focus 5).
- DI: `TasksRepositoryImpl(get(), get(), get(), get(), get(), get(), get<AppScope>().scope)`.
- Tests use `backgroundScope` from `runTest` as the sharing scope so nothing leaks.

**Tests** (`SharedFlowsTest`, using a counting upstream `flow { count++; emit(...) }`):
- `twoCollectorsShareOneUpstream` — two concurrent collectors → upstream started `1` time, both receive `[a]`.
- `resubscribeAfterStopDoesNotReplayStaleValue` — collect (gets `"A"`), cancel, advance 5 001 ms, change upstream to emit `"B"`, re-collect → first value received is `"B"` (never `"A"`).
- `resubscribeWithinTimeoutKeepsUpstream` — cancel, advance 4 000 ms, re-collect → upstream count still `1`.
- Existing `TasksRepositoryImplTest` stays green (only `Env` gains `backgroundScope`).

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---

### Task 2: Domain — money models, repository interface, ported selectors, Today money model, paise formatting, fake

**Files:**
- Modify: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/Dates.kt`, `…/core/Money.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/model/Money.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/repository/MoneyRepository.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/money/MoneySelectors.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/money/TodayMoney.kt`
- Create: `shared/testing/src/commonMain/kotlin/tech/beyondscale/mitra/testing/FakeMoneyRepository.kt`
- Test: `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/MoneyFormatTest.kt`, `…/core/DatesTest.kt` (extend), `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/money/MoneySelectorsTest.kt`, `…/domain/money/TodayMoneyTest.kt`

**Interfaces (Produces — every later task uses these names):**
- core `Dates.kt`: `val MON: List<String>` (`"Jan"…`), `val MON_LONG` (`"January"…`), `fun monthKey(iso: String): String` (`"2026-10"`), `fun daysInMonth(iso: String): Int`, `fun dayOfMonth(iso: String): Int`, `fun fmtShort(iso: String): String` (`"14 Oct"`), `fun monthTitle(monthKey: String): String` (`"October 2026"`), `fun addMonthsKey(monthKey: String, n: Int): String` (`"2026-01" -1 → "2025-12"`), `fun ordinal(n: Int): String` (`1→"1st"`, `2→"2nd"`, `3→"3rd"`, `11→"11th"`, `21→"21th"` — web's naive rule, ported as is).
- core `Money.kt`: `fun inrPaise(paise: Long): String = inr(paise / 100.0)`, `fun inrShortPaise(paise: Long): String`, `fun signedInrPaise(paise: Long): String` (`"−₹120"` / `"+₹120"`, U+2212 minus as the web), `fun jsRound(x: Double): Long` (make the existing private one public).
- `domain/model/Money.kt`:
  - `enum class AccountKind(val wire: String) { Bank("bank"), Credit("credit"), Cash("cash") }`; `data class Account(val id: String, val name: String, val mask: String, val kind: AccountKind, val balancePaise: Long, val archived: Boolean = false)`
  - `enum class CategoryKind(val wire: String) { Expense("expense"), Income("income") }`; `data class Category(val id: String, val name: String, val emoji: String, val kind: CategoryKind, val slug: String? = null, val budgetPaise: Long? = null, val archived: Boolean = false)`
  - `enum class TxKind(val wire: String) { Expense("expense"), Income("income"), Transfer("transfer") }`; `enum class TxSource(val wire: String) { User("user"), Import("import"), Ai("ai"), Recurring("recurring") }`
  - `data class Transaction(val id: String, val kind: TxKind, val amountPaise: Long, val categoryId: String?, val accountId: String, val toAccountId: String?, val merchant: String, val note: String?, val date: String, val time: String, val source: TxSource = TxSource.User, val recurringId: String? = null, val tags: List<String> = emptyList())`
  - `data class TxDraft(val kind: TxKind, val amountPaise: Long, val categoryId: String?, val accountId: String, val toAccountId: String?, val merchant: String, val note: String?, val date: String, val time: String, val source: TxSource = TxSource.User)`
  - `enum class RecurringKind(val wire: String) { Bill("bill"), Income("income"), Transfer("transfer") }`; `data class Recurring(val id: String, val name: String, val emoji: String, val amountPaise: Long, val day: Int, val method: String, val kind: RecurringKind, val reminder: Boolean, val categoryId: String?, val accountId: String?, val toAccountId: String?, val lastPaidOn: String?)`; `data class RecurringDraft(name, emoji, amountPaise, day, kind, reminder = true, categoryId = null, accountId = null, toAccountId = null)`
  - `enum class Cycle(val wire: String) { Monthly("Monthly"), Yearly("Yearly") }`; `data class Subscription(val id: String, val name: String, val amountPaise: Long, val cycle: Cycle, val next: String, val color: String, val letter: String, val lastUsed: String?, val categoryId: String?, val accountId: String?)`
  - `sealed interface CategoryChange { data class Name(val v: String); data class Emoji(val v: String); data class Budget(val paise: Long?) }` (`Budget(null)` = remove budget → wire `budget: null`)
- `domain/repository/MoneyRepository.kt`:
  ```
  interface MoneyRepository {
    val accounts: Flow<List<Account>>; val categories: Flow<List<Category>>; val transactions: Flow<List<Transaction>>
    val recurring: Flow<List<Recurring>>; val subscriptions: Flow<List<Subscription>>
    suspend fun refresh(): Outcome<Unit>
    suspend fun addTransaction(draft: TxDraft): Transaction
    suspend fun updateTransaction(id: String, draft: TxDraft)
    suspend fun deleteTransaction(id: String): Transaction?          // returned for Undo
    suspend fun restoreTransaction(tx: Transaction)                  // Undo: re-creates with the same id
    suspend fun addCategory(name: String, emoji: String, kind: CategoryKind, budgetPaise: Long? = null): Category
    suspend fun updateCategory(id: String, changes: List<CategoryChange>)
    suspend fun deleteCategory(id: String)
    suspend fun addRecurring(draft: RecurringDraft): Recurring
    suspend fun setRecurringReminder(id: String, on: Boolean)
    suspend fun deleteRecurring(id: String)
    suspend fun payRecurring(id: String): PayResult
    suspend fun addSubscription(name: String, amountPaise: Long, cycle: Cycle): Subscription
    suspend fun markSubscriptionUsed(id: String)
    suspend fun deleteSubscription(id: String)
    companion object { const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED }
  }
  sealed interface PayResult { data class Paid(val transaction: Transaction); data object AlreadyPaid; data object NoAccount; data object NoDestination; data object NotFound }
  ```
- `domain/money/MoneySelectors.kt` (ports of `selectors.ts` lines 55–64 + `money.tsx` helpers; all `Long` paise):
  `thisMonth(txs, month: String)`, `spent(txs)`, `income(txs)`, `transferred(txs)`, `spentToday(txs, today)`, `spentByCategory(txs): Map<String, Long>` (key `UNCATEGORISED = "uncategorised"`), `totalBudget(categories)` (expense kind with non-null budget, non-archived), `netWorth(accounts)`,
  `enum class BudgetTone { Normal, Warning, Danger }`, `budgetTone(spentPaise: Long, budgetPaise: Long): BudgetTone` (Danger if spent > budget; Warning if `spent * 10 >= budget * 9`; else Normal), `budgetPct(spent, budget): Int` (`jsRound(spent * 100.0 / budget)`, 0 when budget 0),
  `monthsWithData(txs, today): List<String>` (current month + every tx month, desc),
  `dailySpend(tx, month, today): List<DayPoint>` (`data class DayPoint(val date: String, val paise: Long, val label: String)`; current month → last 16 days ending today, labels every 3rd + last; past month → every day, labels every 5th + last — `MoneySummary` lines 129–132),
  `projectedMonthEnd(spent, month, today): Long`, `recentTransactions(tx, n = 5)` (sort by `date + time` desc),
  `categoryBreakdown(tx, categories): List<CatSpend>` (`data class CatSpend(val id: String, val name: String, val emoji: String, val paise: Long, val count: Int)`; missing → `"Uncategorised"`/`"💸"`, desc), `accountUtilisation(tx, accounts): List<AccountSpend>` (non-income outflows by `accountId`, n > 0, desc), `topOutflows(tx, n = 5)`, `savingsRatePct(income, spent): Double?`,
  `data class TxFilter(val month: String? = null /* null = all time */, val categoryId: String? = null, val accountId: String? = null, val kind: TxKind? = null, val query: String = "")`, `enum class TxSort { Date, Amount }`, `filterTransactions(txs, filter, sort)`, `groupTransactions(list, today): List<TxGroup>` (`data class TxGroup(val title: String, val items: List<Transaction>, val netPaise: Long)`; titles `Today`, `Yesterday`, `Earlier`, `Upcoming`, empty groups dropped — `TransactionsScreen` line 212),
  `findDuplicate(txs, merchant, amountPaise, date): Transaction?` (case-insensitive trimmed merchant, non-blank), `recentMerchants(txs, categoryId): List<String>` (distinct, max 3),
  `daysToDay(day: Int, today: String): Int` (`(day − dayOfMonth(today) + dim) % dim`), `paidThisMonth(r, today)`, `recurringMonthTotal(recurring, subs)` (non-income recurring + Monthly subs), `dueSoon(recurring, today)` (bills with `daysToDay <= 7`, asc),
  `data class Upcoming(val id: String, val emoji: String, val name: String, val amountPaise: Long, val days: Int, val isSubscription: Boolean)`, `upcoming(recurring, subs, today, n = 3)` (dashboard: bills + subs, not excluding paid — `MoneySummary` line 135),
  `subscriptionsMonthlyPaise(subs): Double` (Yearly / 12), `unusedSubscriptions(subs, today)` (`lastUsed` > 30 days ago), `renewalsWithin30(subs, today)`.
- `domain/money/TodayMoney.kt`: `data class BudgetAlert(val emoji: String, val name: String, val over: Boolean, val text: String)`, `data class NextBill(val emoji: String, val name: String, val amountPaise: Long, val days: Int)`, `data class TodayMoney(val budgetPaise: Long, val spentPaise: Long, val remainingPaise: Long, val safePerDayRupees: Double?, val daysLeft: Int, val monthShort: String, val alert: BudgetAlert?, val next: NextBill?)`, `fun todayMoney(categories, txs, recurring, subs, today): TodayMoney`, `fun nextBill(recurring, subs, today): NextBill?` (excludes bills paid this month — `today.tsx` line 43), and label helpers `safeLabel(m): String` (`"₹1,240"` or `"—"`), `daysLeftLabel(m)` (`"18 days left in Oct"` / `"1 day left in Oct"` / `"Set budgets to see this"`), `remainingTitle(m)` (`"Left this month"` / `"Over budget"`), `nextLabel(n)` (`"today"` / `"tomorrow"` / `"in 3 days"`).
- `FakeMoneyRepository` (`shared/testing`): `MutableStateFlow`s for the five lists seeded via constructor; records `calls: MutableList<String>` (e.g. `"addTransaction"`, `"pay:<id>"`); `payResult` overridable; `addTransaction` applies balance effects like the real repo so VM tests see balances move; `refreshResult` overridable. Mirror `FakeTasksRepository`.

**Requirements:**
- Safe to spend (spec §10 / `today.tsx` lines 37–39): `daysLeft = daysInMonth(today) − dayOfMonth(today) + 1`; `safePerDayRupees = if (budget == 0) null else max(0.0, (budget − spent) / 100.0 / daysLeft)`; spent = `spent(thisMonth(txs, monthKey(today)))`.
- Alert: `over` = category with budget and spent > budget, largest overage first → text `"🍛 Food & dining over by ₹1,200"`; else `near` = ratio ≥ 0.9, highest ratio → `"🍛 Food & dining at 93% of its budget"`.
- Fixtures: copy categories/amounts from `mitra-backend/src/money/money-defaults.service.ts` (`food`, `transport`, `home`, …) so tests read like the web.

**Tests:**
- `MoneyFormatTest.inrPaiseMatchesWebRounding` — `inrPaise(12350) == "₹124"`, `inrPaise(12349) == "₹123"`, `inrPaise(-150000) == "₹1,500"`, `inrPaise(123456789) == "₹12,34,568"`, `inrShortPaise(15000000) == "₹1.50L"`, `inrShortPaise(250000) == "₹2.5k"`, `signedInrPaise(-12000) == "−₹120"`.
- `DatesTest.monthHelpers` — `daysInMonth("2028-02-10") == 29`, `daysInMonth("2026-02-01") == 28`, `monthTitle("2026-10") == "October 2026"`, `addMonthsKey("2026-01", -1) == "2025-12"`, `fmtShort("2026-10-04") == "4 Oct"`, `ordinal(3) == "3rd"`, `ordinal(22) == "22th"` (web only special-cases 1/2/3).
- `MoneySelectorsTest.spentIgnoresIncomeAndTransfers` — expense 45000 + income 100000 + transfer 20000 → `spent == 45000`, `income == 100000`, `transferred == 20000`.
- `MoneySelectorsTest.spentByCategoryKeysUncategorised` — expense with `categoryId = null` 5000 → map `{"uncategorised": 5000}`.
- `MoneySelectorsTest.totalBudgetSkipsIncomeAndNull` — food 600000, salary(income) 100000 budget, transport null → `600000`.
- `MoneySelectorsTest.budgetToneThresholds` — (89, 100)→Normal, (90, 100)→Warning, (100, 100)→Warning, (101, 100)→Danger.
- `MoneySelectorsTest.safeToSpendOnLastDayDividesByOne` — today `2026-10-31`, budget 3 000 000, spent 2 900 000 → `daysLeft == 1`, `safePerDayRupees == 1000.0`.
- `MoneySelectorsTest.firstOfMonthStartsAFreshMonth` — today `2026-11-01`, txs dated `2026-10-31` (900 000) and `2026-11-01` (10 000), budget 3 000 000 → spent `10000`, `daysLeft == 30`, `safePerDayRupees` ≈ `996.67` (`(30000 − 100) / 30`, ±0.01); `spentToday == 10000`.
- `MoneySelectorsTest.overspentFloorsAtZero` — budget 100 000, spent 150 000 → `safePerDayRupees == 0.0`, `remainingTitle == "Over budget"`, remaining shown `inrPaise(abs(-50000)) == "₹500"`.
- `MoneySelectorsTest.nextBillWrapsAcrossMonthEnd` — today `2026-10-30`, bill day 2 unpaid → `days == 3` (`(2 − 30 + 31) % 31`); bill day 30 → `0` / `"today"`; bill day 5 with `lastPaidOn = "2026-10-05"` → excluded; subscription `next = "2026-10-31"` (1 day) wins over the bill.
- `MoneySelectorsTest.groupsTodayYesterdayEarlierUpcoming` — today `2026-10-14`: dates 14, 13, 10, 20 → titles `["Today","Yesterday","Earlier","Upcoming"]`; Today group of −45000 expense and +100000 income → `netPaise == 55000`.
- `MoneySelectorsTest.filterBySearchMatchesMerchantOrNote` — query `"swig"` matches merchant `"Swiggy"`; query `"birthday"` matches note; `TxSort.Amount` sorts desc by amount.
- `MoneySelectorsTest.dailySpendCurrentMonthIsLast16Days` — today `2026-10-14` → 16 points, first `2026-09-29`, last `2026-10-14`; labels at indices 0,3,6,9,12,15 non-empty.
- `MoneySelectorsTest.findDuplicateIsCaseInsensitive` — existing `"Swiggy"` 45000 on `2026-10-14`; `findDuplicate(" swiggy ", 45000, "2026-10-14") != null`; different amount → null; blank merchant → null.
- `TodayMoneyTest.alertPrefersOverOverNear` — food 110 % and transport 95 % → alert `"🍛 Food & dining over by ₹…"`, `over == true`; with food at 50 % → `"🚇 Transport at 95% of its budget"`.
- `TodayMoneyTest.noBudgetShowsDash` — no budgets → `safeLabel == "—"`, `daysLeftLabel == "Set budgets to see this"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:core:testAndroidHostTest :shared:domain:testAndroidHostTest :shared:feature:testAndroidHostTest` (the feature tests compile `shared/testing`, so the fake is type-checked).

---

### Task 3: Quick Add Expense parser + amount input (keypad / live ₹ formatting)

**Files:**
- Create: `shared/core/src/commonMain/kotlin/tech/beyondscale/mitra/core/AmountInput.kt`
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/money/ExpenseParser.kt`
- Modify: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/tasks/QuickAddParser.kt` (only `enum class ParsedField` gains `Amount, Category`)
- Test: `shared/core/src/commonTest/kotlin/tech/beyondscale/mitra/core/AmountInputTest.kt`, `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/money/ExpenseParserTest.kt`

**Interfaces:**
- Consumes: `Category`, `CategoryKind` (Task 2), `ParsedField` (Phase 2).
- Produces (core): `object AmountInput { fun press(current: String, key: Char): String; fun backspace(current: String): String; fun sanitize(typed: String): String; fun toPaise(text: String): Long?; fun fromPaise(paise: Long): String; fun display(text: String): String }`. `const val MAX_AMOUNT_PAISE = 100_000_000_000L` (₹1e9, the server `max`).
- Produces (domain): `data class ParsedExpense(val amountPaise: Long?, val category: Category?, val merchant: String)`; `object ExpenseParser { fun parse(input: String, categories: List<Category>, ignore: Set<ParsedField> = emptySet()): ParsedExpense }`.

**Requirements:**
- `AmountInput` ports the web `Keypad` + `AmountField` (`mitra-frontend/src/components/ui/controls.tsx` lines 52–57): keys `0–9`, `.`, `⌫`; a second `.` is ignored; **a third decimal digit is ignored** (server rejects >2 decimals); leading zeros collapse (`"0" + "5" → "5"`, `"0" + "." → "0."`); a value over `MAX_AMOUNT_PAISE` is ignored; `sanitize` strips everything except digits and the first `.` (paste of `"₹1,200.50"` → `"1200.50"`).
- `toPaise`: exact decimal-string → paise without `Double` (`"4.35" → 435`, `"0.1" → 10`, `"12." → 1200`, `"" / "." / "0" → null`).
- `display`: live Indian grouping of the integer part, keeping the typed fraction (`"1234567.5" → "12,34,567.5"`, `"1200" → "1,200"`, `"" → "0"`). Shown after a `₹` glyph by the keypad component (Task 7).
- `fromPaise` (edit prefill): `123410 → "1234.1"`, `120000 → "1200"`, `435 → "4.35"` — matches the web `String(ex.amount)`.
- `ExpenseParser` (spec §9): amount = first token matching `^₹?\s?\d[\d,]*(\.\d{1,2})?$` at the **start or end** of the input (`₹240`, `240`, `₹1,200`, `240.50`; also `rs`/`Rs.` prefix tokens `rs 240`); category = first remaining word (≥ 3 letters, case-insensitive) that equals a category `slug`, or a whole word of an expense category's `name` (`"food"` → "Food & dining", `"groceries"` → "Groceries", `"uber"` matches nothing); income categories are never matched; `merchant` = remaining words joined by single spaces with the category word **kept** (web merchant text reads naturally: `"lunch food"`), trimmed. `ignore` drops a parsed field (chip ✕ in the sheet) and puts its text back into `merchant`.

**Tests:**
- `AmountInputTest.rejectsThirdDecimalAndSecondDot` — press sequence `1 2 . 3 4 5` → `"12.34"`; `"12.3" + '.'` → `"12.3"`.
- `AmountInputTest.liveFormatsIndianGrouping` — `display("1234567.5") == "12,34,567.5"`, `display("") == "0"`.
- `AmountInputTest.toPaiseIsExact` — `toPaise("4.35") == 435L`, `toPaise("0.1") == 10L`, `toPaise("1234.10") == 123410L`, `toPaise(".") == null`.
- `AmountInputTest.capsAtServerMaximum` — `"1000000000" + '0'` stays `"1000000000"`.
- `AmountInputTest.sanitizesPastedRupees` — `sanitize("₹1,200.50") == "1200.50"`.
- `ExpenseParserTest.leadingRupeeAmountAndCategory` — `"₹240 food lunch"` → `amountPaise 24000`, category slug `food`, merchant `"food lunch"`.
- `ExpenseParserTest.trailingPlainAmount` — `"uber to office 180"` → `18000`, category `null`, merchant `"uber to office"`.
- `ExpenseParserTest.groupedAndDecimalAmount` — `"₹1,200.50 groceries dmart"` → `120050`, slug `groceries`.
- `ExpenseParserTest.amountInTheMiddleIsNotParsed` — `"paid 240 to ravi"` → `amountPaise == null`.
- `ExpenseParserTest.ignoredCategoryFallsBackToText` — `ignore = {Category}` on `"240 food"` → category `null`, merchant `"food"`.
- `ExpenseParserTest.incomeCategoriesNeverMatch` — `"500 salary"` → category `null`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:core:testAndroidHostTest :shared:domain:testAndroidHostTest`

---

### Task 4: Data — schema v3 (money tables) with an in-place v2→v3 migration

**Files:**
- Create: `shared/data/src/commonMain/sqldelight/tech/beyondscale/mitra/data/db/Accounts.sq`, `Categories.sq`, `Transactions.sq`, `Recurring.sq`, `Subscriptions.sq`
- Create: `shared/data/src/commonMain/sqldelight/migrations/2.sqm`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/MoneyRows.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/local/LocalData.kt`
- Test: `shared/data/src/androidHostTest/kotlin/tech/beyondscale/mitra/data/db/MigrationTest.kt` (extend), `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/local/MoneyRowsTest.kt`, `…/data/local/LocalStoreTest.kt` (extend)

**Interfaces:**
- Produces tables (columns mirror `mapper.ts`; money as `…Paise INTEGER`; booleans `INTEGER 0/1`; `tags` as JSON text):
  - `accounts(id PK, name, mask, kind, balancePaise INTEGER NOT NULL, archived INTEGER NOT NULL, position INTEGER NOT NULL)` — `position` = server order index (bootstrap order = createdAt asc), local creates get `MAX+1`.
  - `categories(id PK, name, emoji, kind, slug, budgetPaise INTEGER, archived INTEGER NOT NULL, position INTEGER NOT NULL)`
  - `transactions(id PK, kind, amountPaise INTEGER NOT NULL, categoryId, accountId TEXT NOT NULL, toAccountId, merchant TEXT NOT NULL, note, date TEXT NOT NULL, time TEXT NOT NULL, source TEXT NOT NULL, recurringId, tags TEXT NOT NULL)` + `CREATE INDEX transactions_date ON transactions(date)`
  - `recurring(id PK, name, emoji, amountPaise INTEGER NOT NULL, day INTEGER NOT NULL, method, kind, reminder INTEGER NOT NULL, categoryId, accountId, toAccountId, lastPaidOn)`
  - `subscriptions(id PK, name, amountPaise INTEGER NOT NULL, cycle, next TEXT NOT NULL, color, letter, lastUsed, categoryId, accountId)`
- Queries per table (names used by Task 5/6): `selectAll`, `selectById`, `selectIds`, `upsert` (INSERT OR REPLACE of all columns), `delete`, `deleteAll`; plus `accounts.adjustBalance(delta, id)`, `accounts.nextPosition`, `categories.nextPosition`, `transactions.clearCategory(categoryId)` (SET NULL, as the server FK), `transactions.selectIdsOnOrAfter(date)`, `recurring.setLastPaidOn(date, id)`. Ordering: accounts/categories `ORDER BY position, id`; transactions `ORDER BY date DESC, time DESC, id`; recurring `ORDER BY day, id`; subscriptions `ORDER BY next, id`.
- `MoneyRows.kt`: `fun X.toDomain()` / `fun Domain.toRow()` for all five (enum `wire` ↔ text; unknown wire value → first enum constant, never throw); `tags` via `Json.encodeToString(List<String>)`.
- `LocalData.clearUserData()` also `deleteAll()`s the five tables (sign-out / account switch — spec §6 "Logout clears the DB").

**Requirements:**
- `2.sqm` header comment `-- v2 (Phase 2 Tasks) → v3 (Phase 3 Money)`; creates the five tables + index exactly as the `.sq` files (SQLDelight verifies migrations against the schema; keep them identical). No data changes to existing tables.
- Database version becomes 3 automatically (two migration files); confirm `MitraDb.Schema.version == 3L`.

**Tests:**
- `MigrationTest.phase2DatabaseUpgradesInPlace` — create a v2 DB (run `1.sqm` on a v1 schema as the existing test does, or `Schema.create` then drop money tables… use the existing v1 fixture + `MitraDb.Schema.migrate(driver, 1, 3)`); a v2 task row and an outbox row survive; `SELECT count(*) FROM transactions == 0`; `MitraDb.Schema.version == 3L`.
- `MoneyRowsTest.roundTripsEveryField` — a `Transaction` with tags `["work","team"]`, `note = null`, `source = Ai` → `toRow().toDomain()` equals the original; same for `Recurring(lastPaidOn = null)` and `Category(budgetPaise = null)`.
- `MoneyRowsTest.unknownWireEnumFallsBack` — row with `kind = "loan"` → `TxKind.Expense`.
- `LocalStoreTest.clearUserDataRemovesMoney` — insert one row in each money table → `clearUserData()` → all counts `0`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---
### Task 5: Data — MoneyRepositoryImpl part 1: DTOs, refresh, transactions (local-first + balance effects), DI

**Files:**
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/remote/MoneyDtos.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/BalanceEffects.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/MoneyPatches.kt`
- Create: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/MoneyRepositoryImpl.kt`
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/sync/PendingSettings.kt` (`OutboxEntities` + `TRANSACTION`, `CATEGORY`, `RECURRING`, `SUBSCRIPTION`, `RECURRING_PAY`)
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/di/DataModule.kt`
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/remote/MoneyDtosTest.kt`, `…/data/repository/BalanceEffectsTest.kt`, `…/data/repository/MoneyRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `MoneyRepository`, models (Task 2); tables/queries + `MoneyRows` (Task 4); `shareForRepository` (Task 1); `OutboxWriter.enqueue(entity, method, path, body, coalesceKey, entityId)`, `SyncEngine.register/requestSync/acknowledged`, `PrefStore` `PrefKeys.LAST_USER_ID` (Phase 2).
- Produces: `class MoneyRepositoryImpl(api: ApiClient, db: MitraDb, prefs: PrefStore, outbox: OutboxWriter, engine: SyncEngine, clock: AppClock, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) : MoneyRepository`.
- Produces DTOs (`@Serializable`, `ignoreUnknownKeys` already on `api.json`): `MoneyBootstrapDto(accounts, categories, transactions, recurring, subscriptions)`, `AccountDto`, `CategoryDto`, `TransactionDto`, `RecurringDto`, `SubscriptionDto`, `PayResultDto(transaction: TransactionDto, recurring: RecurringDto)` with `toDomain()` and body builders `Transaction.createBody(): JsonObject`, `Transaction.patchBody(): JsonObject`.
- Produces `BalanceEffects.kt`: `fun balanceEffects(tx: Transaction): List<Pair<String, Long>>` (port of `effects` in `transactions.service.ts`: expense `−amt` on `accountId`; income `+amt`; transfer `−amt` from `accountId`, `+amt` to `toAccountId`) and `fun MitraDb.applyEffects(fx, sign: Int)`.
- DI (in `DataModule.kt`): `single<MoneyRepository>(createdAtStart = true) { MoneyRepositoryImpl(get(), get(), get(), get(), get(), get(), get<AppScope>().scope) }` and `"money" to { money.refresh() }` in the `ModuleRefresherImpl` modules map.

**Requirements:**
- Copy the `TasksRepositoryImpl` skeleton exactly: `writes` counter, `write { }` (bump → `db.transaction` → `engine.requestSync()`), `applyEcho`, `pendingIds(entity)`, `decode`, refresh with `REFRESH_ATTEMPTS = 3`, owner check on `PrefKeys.LAST_USER_ID`, `(writes, engine.acknowledged)` race check, `REFRESH_SUPERSEDED` error.
- `applyBootstrap` per table: delete local rows absent from the server **unless pending**; upsert server rows **unless pending**; assign `position` = index in server list. Pending ids: transactions → `TRANSACTION` entityIds; recurring → `RECURRING` ∪ `RECURRING_PAY` entityIds; categories → `CATEGORY`; subscriptions → `SUBSCRIPTION`.
  - **Transactions window:** the server only returns the last 12 months (UTC cut-off). Local transactions dated before `addMonthsJs(clock.today(), -12)` plus a 2-day margin are neither deleted nor updated by a refresh.
  - **Account balances:** while any `TRANSACTION` or `RECURRING_PAY` row is pending, keep each local `balancePaise` (the server's number does not include the queued effects yet); upsert every other account field. With nothing pending, take the server balance.
  - A category whose DELETE is queued: transactions in the bootstrap still referencing it get `categoryId = null` locally (mirrors Tasks' project-delete handling).
- Writes (each inside one `write { }`):
  - `addTransaction(draft)`: `id = newId()` (core `Ids.kt`), upsert row, apply effects (+1), `enqueue(TRANSACTION, "POST", "money/transactions", tx.createBody(), entityId = id)`. Create body as web `addTransaction` remote call: `{ id, kind, amount, categoryId: x ?: null, accountId, toAccountId: (transfer ? toAccountId : null), merchant, note: note?.ifBlank{null} ?: null, date, time, source, recurringId: null, tags: [] }`.
  - `updateTransaction(id, draft)`: reverse old effects, upsert merged, apply new effects; `enqueue(TRANSACTION, "PATCH", "money/transactions/$id", patchBody, coalesceKey = "transaction:$id", entityId = id)`. Patch body is the web's full body (`kind, amount, categoryId|null, accountId, toAccountId|null, merchant, note|null, date, time`). A non-transfer always sends `toAccountId: null`.
  - `deleteTransaction(id)`: reverse effects, delete row, `enqueue(TRANSACTION, "DELETE", "money/transactions/$id", null, entityId = id)`; return the removed row. `restoreTransaction(tx)`: upsert + apply effects + POST create body (same id).
- Sync handler `TRANSACTION`: `onSuccess` → decode `TransactionDto` (DELETE answers `{ok:true}` → ignore) → `applyEcho` upsert **only if the row still exists locally** (never resurrect) and never touch balances; `onRejected` → `refresh()`.
- Flows: each of the five `selectAll().asFlow().mapToList(io).map { … }.shareForRepository(scope)`; `accounts` and `categories` exclude `archived`.

**Tests** (`MockServer` + `testDriver()` + `FixedClock("2026-10-14T10:00:00+05:30")`, as `TasksRepositoryImplTest`):
- `MoneyDtosTest.paiseRoundTripsThroughRupees` — DTO `amount = 4.35` → `amountPaise == 435`; `1234.1` → `123410`; `0.07` → `7`; body for `435` paise encodes `"amount":4.35`; `budget = null` → `budgetPaise == null`; DTO without optional keys (`categoryId`, `note`, `recurringId`, `lastPaidOn` absent) decodes.
- `BalanceEffectsTest.transferMovesBetweenAccounts` — transfer 50000 A→B → `[(A,-50000),(B,50000)]`; income 100000 on A → `[(A,100000)]`.
- `MoneyRepositoryImplTest.refreshReplacesLocalData` — bootstrap fixture (Cash account balance 1500.5 → 150050, 12 default categories, 2 transactions) → flows emit those; second refresh without one transaction removes it locally.
- `MoneyRepositoryImplTest.refreshKeepsRowsWithPendingChanges` — local edit queued for tx `t1` (amount 50000), bootstrap returns `t1` with 45000 → local stays 50000; tx created offline (`t2`) absent from bootstrap survives.
- `MoneyRepositoryImplTest.refreshKeepsOldTransactionsOutsideWindow` — local tx dated `2025-09-01`, not in bootstrap → still present; local tx dated `2026-10-01` not in bootstrap → deleted.
- `MoneyRepositoryImplTest.refreshKeepsLocalBalanceWhilePending` — account local 100000 after an offline expense of 20000 (server says 120000) → local stays 100000; after queue drains and refresh → 100000 from server.
- `MoneyRepositoryImplTest.offlineCreateEditDeleteReachesServerInOrder` — offline: add (₹450), update (₹500), delete → outbox order `POST money/transactions`, `PATCH money/transactions/<id>` (body `"amount":500.0`), `DELETE money/transactions/<id>`; online drain hits the MockServer in that exact order; local list empty.
- `MoneyRepositoryImplTest.balanceEffectsReverseExactly` — Cash 0: add expense 45000 → −45000; edit to income 10000 → +10000; delete → 0; POST echo arriving afterwards leaves balance 0 and does not re-insert the row.
- `MoneyRepositoryImplTest.refreshRacingAWriteIsDiscarded` — bootstrap response is held while `addTransaction` runs → refresh result not applied (new row present), returns `Ok` on a later attempt or `REFRESH_SUPERSEDED`.
- `MoneyRepositoryImplTest.refreshAfterSignOutWritesNothing` — `LAST_USER_ID` cleared while the GET is in flight → tables stay empty.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest :shared:app:testAndroidHostTest` (the `AppModulesTest` Koin graph check must stay green).

---

### Task 6: Data — MoneyRepositoryImpl part 2: categories/budgets, recurring + idempotent Mark paid, subscriptions

**Files:**
- Modify: `shared/data/src/commonMain/kotlin/tech/beyondscale/mitra/data/repository/MoneyRepositoryImpl.kt`, `…/data/repository/MoneyPatches.kt`, `…/data/remote/MoneyDtos.kt` (body builders only)
- Test: `shared/data/src/commonTest/kotlin/tech/beyondscale/mitra/data/repository/MoneyRepositoryImplTest.kt` (append), `…/data/repository/MoneyPatchesTest.kt`

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: the remaining `MoneyRepository` methods; `fun categoryPatch(changes: List<CategoryChange>): JsonObject` (only changed keys; `Budget(null)` → `"budget": null`, as web `updateCategory`).

**Requirements:**
- Categories: `addCategory` → POST `money/categories` `{ id, name, emoji, kind, budget: rupees|null }`, `position = nextPosition`. `updateCategory` → PATCH with `coalesceKey = "category:$id"`. `deleteCategory` → local delete + `transactions.clearCategory(id)` (server FK `SET NULL`) + DELETE. Slug `transfer` categories: the server archives instead of deleting — locally just remove (archived rows are excluded anyway). Handler: echo upsert unless pending / deleted; reject → refresh.
- Recurring: `addRecurring` → POST `money/recurring` `{ id, name, emoji, amount, day (clamped 1..28), method: "UPI", kind, reminder, categoryId|null, accountId|null, toAccountId|null }`. `setRecurringReminder` → PATCH `{ reminder }` (`coalesceKey = "recurring:$id"`). `deleteRecurring` → DELETE; past transactions keep existing locally (server sets their `recurringId` null — set it null locally too).
- **`payRecurring(id)`** (Review Focus 4):
  1. Not found → `NotFound`. `paidThisMonth(r, today)` **or** a pending `RECURRING_PAY` row with `entityId == id` → `AlreadyPaid` (double tap is a no-op).
  2. Account = `r.accountId` ?: first non-archived account by `position` → none → `NoAccount`. Kind: income→Income, transfer→Transfer, else Expense; transfer without `toAccountId` → `NoDestination`.
  3. Category = `r.categoryId` ?: category with slug `transfer` / `salary` / `home` (bill) as `recurring.service.ts` `pay`.
  4. In one `write { }`: insert `Transaction(id = newId(), source = Recurring, recurringId = r.id, merchant = r.name, amountPaise = r.amountPaise, date = clock.today(), time = local "HH:mm")`, apply balance effects, `recurring.setLastPaidOn(today)`, `enqueue(RECURRING_PAY, "POST", "money/recurring/${r.id}/pay", { id: txId, accountId, date: today }, entityId = r.id)` — no coalesce key.
  5. Handler `RECURRING_PAY.onSuccess`: decode `PayResultDto`, echo-upsert the transaction (if still local) and the recurring row (if not pending). `onRejected` (e.g. `400 NO_ACCOUNT`, or the third `500 INTERNAL` when a resend hits the already-created transaction id) → `refresh()`.
  6. Return `Paid(tx)`; the VM shows `"Paid · Rent · ₹15,000"` (web copy).
- Subscriptions: `addSubscription` → local `next = addDays(today, if (Yearly) 365 else 30)`, `color = "#6366F1"`, `letter = name.first().uppercase()`; POST `money/subscriptions` with those fields (the web sends them too). `markSubscriptionUsed` → PATCH `{ lastUsed: today }` (`coalesceKey = "subscription:$id"`). `deleteSubscription` → DELETE.

**Tests:**
- `MoneyPatchesTest.budgetClearSendsNull` — `[Budget(null)]` → `{"budget":null}`; `[Name("Food"), Budget(200000)]` → `{"name":"Food","budget":2000.0}`; `[]` → `{}`.
- `MoneyRepositoryImplTest.deleteCategoryUncategorisesTransactions` — tx in `food` → after `deleteCategory(food)` its `categoryId == null`; outbox has `DELETE money/categories/<food>`.
- `MoneyRepositoryImplTest.paySendsClientIdAndLocalDate` — clock `2026-11-01T00:30:00+05:30` (UTC `2026-10-31T19:00Z`) → body `date == "2026-11-01"` and `id` equals the locally inserted transaction id; `lastPaidOn == "2026-11-01"`.
- `MoneyRepositoryImplTest.payTwiceQueuesOnce` — two `payRecurring(rent)` calls back-to-back offline → second returns `AlreadyPaid`; one local transaction; one `RECURRING_PAY` outbox row.
- `MoneyRepositoryImplTest.payResentAfterLostResponseLeavesOneTransaction` — MockServer: first `POST …/pay` throws a network error after "recording", next three answer `500 {code: INTERNAL}`, bootstrap returns the transaction once → after drains: outbox empty, exactly one transaction with that id locally, `lastPaidOn` set.
- `MoneyRepositoryImplTest.payWithoutAccountReturnsNoAccount` — no accounts → `NoAccount`, nothing queued.
- `MoneyRepositoryImplTest.payFallsBackToHomeCategoryForBills` — bill without category → transaction `categoryId` = slug `home` id.
- `MoneyRepositoryImplTest.addSubscriptionDefaultsNextAndLetter` — `addSubscription("netflix", 64900, Monthly)` on `2026-10-14` → `next == "2026-11-13"`, `letter == "N"`, body contains `"color":"#6366F1"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:data:testAndroidHostTest`

---

### Task 7: Design system — amount keypad, month switcher, press-and-scrub bars, donut, emoji box

**Files:**
- Create: `shared/designsystem/src/commonMain/kotlin/tech/beyondscale/mitra/designsystem/components/AmountKeypad.kt`, `MonthSwitcher.kt`, `ScrubBars.kt`, `Donut.kt`, `EmojiBox.kt`
- Test: `shared/designsystem/src/commonTest/kotlin/tech/beyondscale/mitra/designsystem/components/ScrubIndexTest.kt`, `DonutSweepTest.kt`

**Interfaces (Produces):**
- `@Composable fun AmountDisplay(text: String, modifier: Modifier = Modifier)` — `₹` + already-formatted text (caller passes `AmountInput.display(raw)`), large tabular numerals (`Mitra.type` mono numerals as `StatCard`).
- `@Composable fun AmountKeypad(onKey: (Char) -> Unit, onBackspace: () -> Unit, onClear: (() -> Unit)? = null, modifier: Modifier = Modifier)` — 4×3 grid `1 2 3 / 4 5 6 / 7 8 9 / . 0 ⌫` (web order); light `TextHandleMove` haptic per key; long-press `⌫` calls `onClear` when given.
- `@Composable fun MonthSwitcher(title: String, canNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier)` — `‹ October 2026 ›`; content description `"Previous month"` / `"Next month"`.
- `@Composable fun ScrubBars(values: List<Long>, labels: List<String>, highlight: Int = -1, format: (Long) -> String, modifier: Modifier = Modifier.height(150.dp))` — press-and-drag shows a tooltip `"<label or date> · ₹450"` for the bar under the finger (spec §9); `fun scrubIndex(x: Float, width: Float, count: Int): Int` (pure, clamped `0..count-1`) is public for tests.
- `@Composable fun Donut(segments: List<DonutSegment>, size: Dp, stroke: Dp, centerLabel: String? = null)`; `data class DonutSegment(val value: Long, val color: Color, val label: String)`; `fun donutSweeps(values: List<Long>): List<Float>` (degrees, sum 360 when total > 0, empty when total 0).
- `@Composable fun EmojiBox(emoji: String, size: Dp = 36.dp)` — rounded soft box with the emoji (web `EmojiBox`).
- `val SeriesColors: List<Color>` — 8 categorical colours from the web `--s1…--s8` tokens (dark + light via `Mitra.colors`; add tokens to `Colors.kt` **only if missing** — check `theme/Colors.kt` first) and `OtherColor` = faint.

**Requirements:** Stateless, previewable, no domain imports. Follow `Progress.kt`/`Chips.kt` styling. Keypad keys ≥ 56 dp tall.

**Tests:**
- `ScrubIndexTest.clampsAndMaps` — `scrubIndex(-5f, 160f, 16) == 0`, `scrubIndex(159.9f, 160f, 16) == 15`, `scrubIndex(85f, 160f, 16) == 8`, `count 0 → -1`.
- `DonutSweepTest.sweepsSumTo360` — `[1,1,2]` → `[90,90,180]`; `[0,0]` → `[]`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:designsystem:testAndroidHostTest`

---
### Task 8: Money dashboard — Summary / Analytics, month switcher, account filter, transaction row

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/TxRow.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/MoneyViewModel.kt`
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/MoneyScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/money/TxRowUiTest.kt`, `…/feature/money/MoneyViewModelTest.kt`

**Interfaces:**
- Consumes: `MoneyRepository`, selectors (Task 2), `inrPaise`/`signedInrPaise`/`monthTitle`/`addMonthsKey` (Task 2), `MonthSwitcher`, `ScrubBars`, `Donut`, `EmojiBox` (Task 7), `FakeMoneyRepository`.
- Produces (`TxRow.kt`, reused by Tasks 9, 10, 12): `data class TxRowUi(val id: String, val emoji: String, val title: String, val sub: String, val amount: String, val positive: Boolean, val accountLine: String, val recurring: Boolean, val ai: Boolean)`; `fun txRowUi(tx: Transaction, categories: List<Category>, accounts: List<Account>, today: String): TxRowUi`; `@Composable fun TxRow(row: TxRowUi, onClick: (() -> Unit)?, modifier: Modifier = Modifier)`.
- Produces: `class MoneyViewModel(money: MoneyRepository, clock: AppClock) : ViewModel()` with `state: StateFlow<MoneyUiState>`, `events: Flow<UiEvent<MoneyAction>>`, `fun onAction(a: MoneyAction)`; `sealed interface MoneyAction { data object PrevMonth; data object NextMonth; data class SelectAccount(val id: String?); data class SelectTab(val index: Int); data object Refresh }`; `enum class MoneyLink { AddExpense, Transactions, Budgets, Categories, Recurring, Subscriptions }`; `@Composable fun MoneyRoute(onEditTransaction: (String) -> Unit, onOpen: (MoneyLink) -> Unit)`.

**Requirements (port `MoneyScreen` / `MoneySummary` / `MoneyAnalytics`, `money.tsx` lines 103–203):**
- `TxRowUi`: title = merchant or category name; sub = `"<category name> · <fmtDay(date, today)>"` + `" · <fmtTime(time)>"` when the date is today or yesterday; amount `"+₹1,00,000"` for income else `"−₹450"` (U+2212); `accountLine = "<name> <mask>"` trimmed; emoji = category emoji or `💸`.
- Header: `MonthSwitcher` (title `monthTitle(month)`; Next disabled at the current month; Prev bounded by the earliest month in `monthsWithData`), account filter chip row (`All accounts` + each `"<name> <mask>"`), tabs `Summary` / `Analytics` (`MitraSegmented`), quick links row: Transactions · Budgets · Categories · Recurring · Subscriptions, top-bar ＋ = `AddExpense`. Account filter keeps tx with `accountId == id || toAccountId == id`.
- Summary: KPI card `Spent` (`"of ₹30,000 budget"` or `""`), `Income` (`"2 credits"` / `"1 credit"` / `"none yet"`), `Saved` (`"−"` prefix when negative; sub `"<n>% of income"` + `" · ₹X moved between accounts"` or `"income − expenses"`); budget bar (`budgetTone`); footer `"61% of budget · 17 days left"` (current month; days left here = `dim − day`, web line 125) or `"61% of budget · September 2026"`, and `"Projected month end: ₹…"` (current month only). "Cash flow · daily spend" (`ScrubBars`, today highlighted; empty `"Nothing spent in October 2026 yet."`). "Recent transactions" (5, tap → `onEditTransaction`; empty `"No expenses yet this month"` + body `"Try typing “Spent ₹450 on dinner” in Quick Add — it takes two seconds."`). "Budgets" top 4 by ratio with bars. "Accounts" (balance, danger colour when negative). "Upcoming" (`upcoming(…, 3)`: `"Today"` / `"Tomorrow"` / `"in N days"`).
- Analytics: 4 stat tiles (`Total income` "Primary monthly inflow"; `Total expenses` "<n> debits & charges"; `Net savings`; `Savings rate` `"12.5%"` / `"—"` "% of income retained"), Donut of top 8 categories + `"Other (n)"`, category ranking list (amount, `% share` with one decimal, txn count), account utilisation list, top 5 outflows. Empty month → `"No transactions in October 2026"` / `"Pick another month, or add an expense to see the breakdown."` + `Add expense`.
- Pull-to-refresh → `money.refresh()`; `REFRESH_SUPERSEDED` / offline errors → snackbar text from `AppError` as `TasksViewModel` does.
- Recomputes on `clock.dayFlow()` (current month rolls at local midnight).

**Tests:**
- `TxRowUiTest.todayShowsTimeAndSignedAmount` — expense ₹450 today 13:05 in Food → `sub == "Food & dining · Today · 1:05 PM"`, `amount == "−₹450"`; income `+₹1,00,000`, positive true; tx dated 3 days ago → no time.
- `MoneyViewModelTest.summaryKpisForCurrentMonth` — fixture month spend 1 840 000, budget 3 000 000, income 0 → `spent == "₹18,400"`, `spentSub == "of ₹30,000 budget"`, `incomeSub == "none yet"`, `budgetFooter == "61% of budget · 17 days left"` on `2026-10-14`.
- `MoneyViewModelTest.prevMonthShowsPastMonthFooterWithoutProjection` — PrevMonth → title `"September 2026"`, footer ends `" · September 2026"`, `projected == null`, `canNext == true`.
- `MoneyViewModelTest.nextDisabledAtCurrentMonth` — NextMonth at current month leaves month unchanged.
- `MoneyViewModelTest.accountFilterIncludesIncomingTransfers` — transfer A→B, filter B → included in list (spent unaffected: transfers aren't spent).
- `MoneyViewModelTest.analyticsFoldsNinthCategoryIntoOther` — expenses in 9 categories → 9 donut segments: 8 coloured + last labelled `"Other (1)"`; ranking list still has 9 rows.
- `MoneyViewModelTest.monthRollsOverAtMidnight` — `FixedClock` advanced from `2026-10-31T23:59` to `2026-11-01T00:00:30` (IST) → title `"November 2026"`, spent `"₹0"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 9: Transaction form sheet (add / edit, keypad, live ₹) + Transactions list (filters, search, sort, swipe-delete)

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/TxEditorViewModel.kt`, `TxFormSheet.kt`, `TransactionsViewModel.kt`, `TransactionsScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/money/TxEditorViewModelTest.kt`, `TransactionsViewModelTest.kt`

**Interfaces:**
- Consumes: `AmountInput` (Task 3), `findDuplicate`, `recentMerchants`, `filterTransactions`, `groupTransactions` (Task 2), `AmountKeypad`, `AmountDisplay` (Task 7), `TxRow`/`txRowUi` (Task 8), `DatePickers.kt` quick-date chips (Phase 2), `SwipeRow` (Phase 2).
- Produces: `class TxEditorViewModel(money: MoneyRepository, clock: AppClock) : ViewModel()` with `fun start(editId: String?)`, `state: StateFlow<TxEditorUiState>`, `events`, `onAction(TxEditorAction)`; `@Composable fun TxFormSheet(vm: TxEditorViewModel, onDismiss: () -> Unit)` (ModalBottomSheet, host-owned like `TaskFormSheet`).
  `sealed interface TxEditorAction { data class Kind(val k: TxKind); data class Key(val c: Char); data object Backspace; data class Amount(val text: String); data class Category(val id: String); data class NewCategory(val name: String, val emoji: String); data class Account(val id: String); data class ToAccount(val id: String); data class Date(val iso: String); data class Time(val hhmm: String); data class Merchant(val v: String); data class Note(val v: String); data object ToggleRepeat; data class Save(val again: Boolean); data object Delete; data class UndoAdd(val id: String) }`.
- Produces: `class TransactionsViewModel(money: MoneyRepository, clock: AppClock) : ViewModel()`; `@Composable fun TransactionsRoute(initialCategoryId: String?, onBack: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit)`; `sealed interface TransactionsAction { data class Month(val key: String?); data class Category(val id: String?); data class Account(val id: String?); data class Kind(val k: TxKind?); data class Query(val q: String); data object ToggleSort; data object ClearFilters; data class Delete(val id: String); data class UndoDelete(val tx: Transaction); data object Refresh }`.

**Requirements (port `TxForm`, `money.tsx` lines 49–93, and `TransactionsScreen` lines 208–236):**
- Sheet title `Add expense` / `Edit transaction`; segmented `Expense · Income · Transfer`; `AmountDisplay(AmountInput.display(text))` + `AmountKeypad` (create only, as web `!ex`); the system keyboard is never shown for the amount.
- Category chips filtered by kind (`income` → income categories; expense/transfer → expense; transfer auto-selects slug `transfer` and hides chips); default = first; `＋ New category` inline row (emoji + name) → `addCategory` → selected, snackbar `"Category added · 🎁 Gifts"`; `Manage` link → Categories.
- Account picker labels `"HDFC ••1234 · ₹12,400"` (`" · −₹…"` when negative); To-account (transfer) excludes the from-account. Default account = first; default to-account = first other.
- Date (quick chips Today / Yesterday + picker), Time (picker, default now `HH:mm` from clock), Merchant (`Source` label for income, placeholders `e.g. Swiggy` / `e.g. Salary — Beyond Labs`), recent merchant chips `Recent:` (only when merchant empty), Note (`Optional`), `Repeat monthly` / `Repeats monthly ✓` toggle (create only), `Delete` (edit only, confirm `"Delete this transaction?"` / `"The account balance will be adjusted back."` → `"Transaction deleted"`).
- Duplicate banner: `"Looks like Swiggy ₹450 was already added at 1:05 PM."` (create only).
- Validation copy, in this order: `"Enter an amount"`, `"Pick a category"` (not for transfer), `"Pick an account"`, `"Pick a different account to transfer to"`.
- Save: create → `addTransaction`; if repeat → `addRecurring(RecurringDraft(name = merchant or category name or "Recurring", amountPaise, day = min(28, dayOfMonth(date)), emoji = category emoji ?: "🔁", kind = bill|income|transfer, categoryId, accountId, toAccountId))`; snackbar `"Expense saved · ₹450"` (`Income`/`Transfer`; `" · repeats monthly"` suffix) with Undo → `deleteTransaction`. `Save & add another` keeps the sheet open and clears amount/merchant/note. Edit → `updateTransaction` → `"Transaction updated"` + dismiss. Footer labels `Save expense` / `Save income` / `Save transfer` / `Save changes`.
- Edit prefill uses `AmountInput.fromPaise`.
- Transactions screen: filter chips (Month: `All time` + months with data — default current month; Category; Account; Type `Expense/Income/Transfer`), `Clear` chip when any filter/query set, search field `"Search merchant or note"`, summary `"12 transactions · spent ₹4,500 · income ₹0"` (`"1 transaction"`), `Sort by amount` / `Sort by date` link, grouped sections with signed net (`"−₹450"` / `"+₹…"`), rows tap → edit; swipe left → delete + snackbar `"Transaction deleted"` with Undo → `restoreTransaction`; empty `"No transactions match these filters"` / `"Try a different month or clear the filters."` + `Clear filters` (sets month to all time); pull-to-refresh. `initialCategoryId` pre-sets the category filter (from Categories → "Transactions").

**Tests:**
- `TxEditorViewModelTest.keypadBuildsAmountAndFormatsLive` — keys `1 2 3 4 5 . 5` → `amountDisplay == "12,345.5"`; save → repo got `amountPaise == 1234550`.
- `TxEditorViewModelTest.validationOrder` — empty amount → `"Enter an amount"`; amount but no categories → `"Pick a category"`; transfer with same accounts → `"Pick a different account to transfer to"`.
- `TxEditorViewModelTest.saveWithRepeatAddsRecurring` — date `2026-10-30`, merchant `"Rent"`, repeat → `addRecurring` with `day == 28`, kind `Bill`; snackbar `"Expense saved · ₹15,000 · repeats monthly"`.
- `TxEditorViewModelTest.saveAndAddAnotherClearsFields` — keeps sheet (no Dismiss event), amount/merchant/note empty, category kept.
- `TxEditorViewModelTest.editPrefillsAndSendsUpdate` — tx 123410 paise → `amountText == "1234.1"`; save → `updateTransaction` called, snackbar `"Transaction updated"`.
- `TxEditorViewModelTest.duplicateWarning` — existing Swiggy ₹450 today 13:05; typing merchant `"swiggy"` + 450 → banner `"Looks like Swiggy ₹450 was already added at 1:05 PM."`.
- `TxEditorViewModelTest.transferSelectsTransferCategory` — Kind(Transfer) → `categoryId` = slug `transfer` id, chips hidden.
- `TransactionsViewModelTest.filtersCombineAndSummaryCounts` — month Oct + Food + query `"swi"` → 1 row, summary `"1 transaction · spent ₹450 · income ₹0"`.
- `TransactionsViewModelTest.deleteThenUndoRestoresSameId` — Delete(t1) → `restoreTransaction` called with `id == "t1"` on Undo.
- `TransactionsViewModelTest.clearFiltersShowsAllTime` — `ClearFilters` from the empty state → month `null` (`"All time"`).

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 10: Budgets + Categories screens

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/BudgetsViewModel.kt`, `BudgetsScreen.kt`, `CategoriesViewModel.kt`, `CategoriesScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/money/BudgetsViewModelTest.kt`, `CategoriesViewModelTest.kt`

**Interfaces:**
- Consumes: `MoneyRepository.updateCategory/addCategory/deleteCategory`, `CategoryChange`, selectors + `budgetTone`/`budgetPct` (Task 2), `Donut`, `EmojiBox` (Task 7), `MitraProgressBar`, `MitraChip`.
- Produces: `class BudgetsViewModel(money, clock)`, `@Composable fun BudgetsRoute(onBack: () -> Unit)`; `class CategoriesViewModel(money, clock)`, `@Composable fun CategoriesRoute(onBack: () -> Unit, onOpenTransactions: (categoryId: String) -> Unit)`.

**Requirements (port `BudgetsScreen` lines 261–276, `CategoriesScreen` lines 239–258):**
- Budgets header: `Total budget`, `Spent` (sum over budgeted categories only), `Left` (`max(0, total − spent)`, sub `"18 days"` where days = `dim − day + 1`), bar, `"Safe to spend: ₹1,240 / day until 31 Oct"`.
- Per budgeted category card: emoji + name, `"Monthly · resets 1 Nov"`, chip `"Over by ₹1,200"` (danger) / `"Near limit"` (warning, `budgetTone == Warning`) / `"On track"`, thick bar, `"₹X of ₹Y"` + `"₹Z left"` (or `"—"` when over), `Edit` → inline whole-rupee field (digits only) with `Save` (`"Budget updated"`) and `Remove` (`CategoryChange.Budget(null)`, `"Budget removed"`).
- `＋ New budget` → sheet: pick an unbudgeted expense category (not slug `transfer`) + monthly limit → `"Budget set"`; when none: `"Every category has a budget — create a new category first."`.
- Categories: segmented `Expense` / `Income` + current month label (`"October"`); create card (emoji field default `🎁`, name `"Category name"`, `Monthly budget` for expense) → `"Category created · 🎁 Gifts"`; donut of top 5 + Other (accent shades); rows `"<n> tx · budget ₹X"` and this month's amount, sorted desc; overflow menu `Transactions` (→ `onOpenTransactions`), `Rename / budget` (inline, `"Category updated"`), `Delete` (confirm `"Delete this category?"` / `"Existing transactions keep their history but show as uncategorised."` → `"Category deleted"`).
- Budget amounts entered as whole rupees → `× 100` paise.

**Tests:**
- `BudgetsViewModelTest.headerOnLastDay` — today `2026-10-31`, total 3 000 000, spent 2 900 000 → left `"₹1,000"`, days sub `"1 days"` (web copy `${left} days`, ported verbatim); safe line `"Safe to spend: ₹1,000 / day until 31 Oct"`.
- `BudgetsViewModelTest.chipStates` — food 101 % → `"Over by ₹…"`; transport 90 % → `"Near limit"`; health 50 % → `"On track"`.
- `BudgetsViewModelTest.removeSendsNullBudget` — Remove → `updateCategory(id, [Budget(null)])`.
- `BudgetsViewModelTest.unbudgetedExcludesTransferAndIncome`.
- `CategoriesViewModelTest.createExpenseWithBudget` — name `"Gifts"`, budget `"2000"` → `addCategory("Gifts", "🎁", Expense, 200000)`, snackbar `"Category created · 🎁 Gifts"`.
- `CategoriesViewModelTest.incomeTabUsesIncomeTotals` — salary income 100 000 this month → row value `"₹1,000"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 11: Recurring (+ Mark paid) + Subscriptions screens

**Files:**
- Create: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/money/RecurringViewModel.kt`, `RecurringScreen.kt`, `SubscriptionsViewModel.kt`, `SubscriptionsScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/money/RecurringViewModelTest.kt`, `SubscriptionsViewModelTest.kt`

**Interfaces:**
- Consumes: `MoneyRepository.addRecurring/setRecurringReminder/deleteRecurring/payRecurring/addSubscription/markSubscriptionUsed/deleteSubscription`, `PayResult`, `daysToDay`, `paidThisMonth`, `dueSoon`, `recurringMonthTotal`, `subscriptionsMonthlyPaise`, `unusedSubscriptions`, `renewalsWithin30`, `ordinal` (Task 2); `ScrubBars`, `EmojiBox` (Task 7).
- Produces: `class RecurringViewModel(money, clock)`, `@Composable fun RecurringRoute(onBack: () -> Unit)`; `class SubscriptionsViewModel(money, clock)`, `@Composable fun SubscriptionsRoute(onBack: () -> Unit)`.

**Requirements (port `RecurringScreen` lines 279–298, `SubscriptionsScreen` lines 301–315):**
- Recurring: tabs `Bills · Income · Transfers`; KPIs `This month` (`recurringMonthTotal`, sub `"<recurring+subs> items"`) and `Next 7 days` (unpaid due-soon bills sum, `"<n> due"`); add sheet (emoji default `💡`, name placeholder `e.g. Electricity` / `e.g. Salary`, amount, `Day` of month clamped 1–28) → `addRecurring(reminder = true, accountId = first account, toAccountId = second account for transfers)` → `"Recurring bill added"` (`income`/`transfer`).
- "Due soon" (Bills tab): sub `"Today · UPI"` / `"Tomorrow · UPI"` / `"in 3 days · UPI"`, right `"Pay reminder set"` / `"Auto"`; paid this month → chip `"Paid <fmtDay(lastPaidOn)>"`; else `Mark paid` → `PayResult`: `Paid` → `"Paid · Rent · ₹15,000"` (+ success haptic); `AlreadyPaid` → `"Already paid this month"`; `NoAccount` → `"Add an account first"`; `NoDestination` → `"Set a destination account on this recurring transfer first"`. The button is disabled while the call runs (no double submit).
- "All bills" list: sub `"5th · UPI"`, right `"reminder on"`; menu `Turn reminder on/off`, `Delete` (confirm `"Remove this recurring item?"` / `"Past transactions are kept."` → `"Removed"`); empty `"No recurring bills yet"` + `Add one`.
- Subscriptions: KPIs `Monthly` (`"<n> active"`), `Yearly equivalent`, `Unused 30d` (count; sub = first words joined `", "` or `"none"`); add sheet (`Service name`, amount, `Monthly/Yearly`) → `"Subscription added · Netflix"`; rows: letter logo on `color`, `"Monthly · next <fmtDay(next)>"` + `" · last used <fmtDay>"`, amount, chip `"Not used 45d"`; menu `Mark as used today` (`"Netflix · used today"`), `Cancel guidance` (`"Mitra never cancels anything itself — open the service to cancel"`), `Remove` (confirm `"Remove this subscription?"` / `"This only removes it from tracking."` → `"Removed"`); `Renewals · next 30 days` → `ScrubBars` (labels `fmtShort`) or `"Nothing renews in the next 30 days."`.

**Tests:**
- `RecurringViewModelTest.dueSoonWrapsMonthEnd` — today `2026-10-30`, bill day 2 → sub starts `"in 3 days"`; day 30 → `"Today"`.
- `RecurringViewModelTest.markPaidShowsPaidSnack` — fake `payResult = Paid(tx ₹15,000)` → snackbar `"Paid · Rent · ₹15,000"`.
- `RecurringViewModelTest.markPaidTwiceCallsRepoOnce` — two rapid `MarkPaid(rent)` → fake `calls` contains `"pay:rent"` once.
- `RecurringViewModelTest.noAccountMessage` — `NoAccount` → `"Add an account first"`.
- `RecurringViewModelTest.next7DaysExcludesPaid` — two bills due in 3 days, one paid this month → `next7 == "₹X"`, `"1 due"`.
- `SubscriptionsViewModelTest.monthlyFoldsYearly` — Monthly ₹649 + Yearly ₹1,499 → Monthly `"₹774"` (649 + 124.92 → `inr` rounds), Yearly equivalent `"₹9,287"`.
- `SubscriptionsViewModelTest.unusedAfter30Days` — `lastUsed` 31 days ago → count `"1"`, chip `"Not used 31d"`; 30 days → not unused.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---

### Task 12: Quick Add Expense (fully working) + Today Money card and "Spent today" stat

**Files:**
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/quickadd/QuickAddViewModel.kt`, `QuickAddSheet.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/today/TodayViewModel.kt`, `TodayScreen.kt`
- Test: `shared/feature/src/commonTest/kotlin/tech/beyondscale/mitra/feature/quickadd/QuickAddViewModelTest.kt`, `…/feature/today/TodayViewModelTest.kt` (extend; constructors gain the fake)

**Interfaces:**
- Consumes: `ExpenseParser`, `ParsedField.Amount/Category` (Task 3), `MoneyRepository`, `todayMoney`, `spentToday`, `budgetPct`, label helpers (Task 2), `FakeMoneyRepository`.
- Produces: `QuickAddViewModel(tasks: TasksRepository, money: MoneyRepository, clock: AppClock)`; `QuickType.Expense("Expense", null)`; `QuickAddUiState` + `amountLabel: String?`, `categoryLabel: String?`, `merchant: String`; `QuickAddAction` + `ClearAmount`, `ClearCategory`, `UndoExpense(val id: String)`.
- Produces: `TodayViewModel(auth, tasks, money: MoneyRepository, clock)`; `enum class StatTarget { Tasks, Money }`; `TodayUiState` + `money: MoneyCardUi?` (null when the `money` section is hidden or `modules.money` is off); `data class MoneyCardUi(val safe: String, val perDay: Boolean, val daysLeft: String, val remainingTitle: String, val remaining: String, val remainingDanger: Boolean, val budgetSub: String, val next: String?, val alert: String?, val alertDanger: Boolean)`; `TodayAction` + `AddExpense`, `OpenBudgets` (emitted as navigation events to the shell — Task 13 wires them).

**Requirements:**
- Quick Add with the Expense chip: parse with `ExpenseParser.parse(text, categories, ignore)`; chips show `"₹240"` and `"🍛 Food & dining"` (✕ → `ClearAmount`/`ClearCategory`); `canSave` iff amount parsed. Save → `addTransaction(TxDraft(Expense, amountPaise, category?.id, accountId = first account, toAccountId = null, merchant = parsed.merchant, note = null, date = today, time = local HH:mm))`; snackbar `"Expense saved · ₹240"` + Undo (`deleteTransaction`); no account → inline error `"Add an account first"`. Habit / Note / Mood keep the `"… arrives with …"` notice. Text field uses the default keyboard with `KeyboardType.Text` (users type `₹240 food`); when opened from the widget/Today with Expense preselected the field is focused.
- Today stat `Spent today`: value `inrPaise(spentToday(txs, today))`, sub `"<pct>% of month budget used"` (`budgetPct(monthSpent, totalBudget)`, `0` when no budget), target `StatTarget.Money`.
- Today Money section (port `TodayMoney`, `today.tsx` lines 36–51) with title `Money`, action `Overview`: KPI `Safe to spend` → `"₹1,240"` + `" / day"` (or `"—"`), sub `daysLeftLabel`; right KPI `remainingTitle` + `inrPaise(abs(remaining))` (danger when negative), sub `"of ₹30k budget"` (`inrShortPaise`) or `""`; next line `"🏠 Next up: Rent · ₹15,000 · in 3 days"`; alert line; buttons `Add expense` (→ `AddExpense`) and `Budgets` (→ `OpenBudgets`). Remove the Phase 2 placeholder copy that says money "will appear here".
- Everything recomputes on `clock.dayFlow()`.

**Tests:**
- `QuickAddViewModelTest.expenseChipParsesAmountAndCategory` — Type(Expense), Text(`"₹240 food lunch"`) → `amountLabel == "₹240"`, `categoryLabel == "🍛 Food & dining"`, `canSave`.
- `QuickAddViewModelTest.saveExpenseUsesDefaultAccountAndLocalDate` — clock `2026-11-01T00:30+05:30` → draft `date == "2026-11-01"`, `accountId` = first account; snackbar `"Expense saved · ₹240"`.
- `QuickAddViewModelTest.clearAmountDisablesSave` — ClearAmount → `canSave == false`.
- `QuickAddViewModelTest.noAccountShowsError` — `"Add an account first"`.
- `QuickAddViewModelTest.otherTypesStillArriveLater` — Type(Habit) → notice `"Habit check arrives with Habits — add it on the web for now."`.
- `TodayViewModelTest.spentTodayStat` — today spend 45000, month 1 840 000 of 3 000 000 → stat `"₹450"`, `"61% of month budget used"`.
- `TodayViewModelTest.moneyCardRollsOverAtLocalMidnight` — `2026-10-31T23:59+05:30` card `daysLeft == "1 day left in Oct"`; advance to `2026-11-01T00:00:30+05:30` → `"30 days left in Nov"`, Spent today `"₹0"`.
- `TodayViewModelTest.moneyHiddenWhenModuleOff` — `modules.money = false` → `money == null`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:feature:testAndroidHostTest`

---
### Task 13: App shell — Money routes, transaction sheet host, Today/Quick Add wiring, launch targets, Koin

**Files:**
- Modify: `shared/app/src/commonMain/kotlin/tech/beyondscale/mitra/app/Destinations.kt`, `MainShell.kt`, `LaunchTarget.kt`
- Modify: `shared/feature/src/commonMain/kotlin/tech/beyondscale/mitra/feature/di/FeatureModule.kt`
- Test: `shared/app/src/commonTest/kotlin/tech/beyondscale/mitra/app/ChromeTest.kt`, `LaunchTargetTest.kt`, `AppModulesTest.kt` (extend)

**Interfaces:**
- Consumes: `MoneyRoute`, `TransactionsRoute`, `BudgetsRoute`, `CategoriesRoute`, `RecurringRoute`, `SubscriptionsRoute`, `TxEditorViewModel` + `TxFormSheet`, `MoneyLink`, Today `AddExpense`/`OpenBudgets`/`StatTarget.Money`, `QuickAddAction.Type(QuickType.Expense)` (Tasks 8–12).
- Produces: `@Serializable data class TransactionsDest(val categoryId: String = "")`, `data object BudgetsDest`, `CategoriesDest`, `RecurringDest`, `SubscriptionsDest`; `LaunchTarget.OpenMoney`, `LaunchTarget.QuickAddExpense`, `LaunchTarget.OpenBudgets`; URIs (used by Task 14): `mitra://money`, `mitra://quick-add/expense`, `mitra://money/budgets`.

**Requirements:**
- `NON_TAB_ROUTES` + `chromeFor`: `MoneyDest` → `Chrome.TabOwnBar` (MoneyScreen draws its own top bar with ＋, like Tasks); the five sub-routes → `Chrome.Detail`.
- Replace `ComingSoonScreen("Money", …)` with `MoneyRoute`; register the five sub-routes. `MoneyLink` → navigate; `AddExpense` → open the transaction sheet.
- The transaction sheet is shell-hosted like `TaskFormSheet`: one `TxEditorViewModel` keyed `shellVmKey("tx-editor", user.id)`; `openTxForm(editId: String?)` calls `vm.start(editId)` and shows `TxFormSheet`; edit opens from Money dashboard rows, Transactions rows; add from Money ＋, Transactions ＋, Today `Add expense`. Its snackbar events (with Undo) go through the shell `SnackbarController` so Undo survives the sheet closing.
- Today: `StatTarget.Money` → `MoneyDest`; `OpenBudgets` → `BudgetsDest`. Categories `onOpenTransactions(id)` → `TransactionsDest(id)`.
- `parseLaunchUri`: add the three URIs (exact match); `mitra://money/` + anything else → null. Shell handling: `OpenMoney` → select Money tab; `QuickAddExpense` → open Quick Add then `Type(Expense)`; `OpenBudgets` → `BudgetsDest`. When `modules.money` is off, all three fall back to Today (no crash, no hidden route).
- `FeatureModule.kt`: `viewModel { MoneyViewModel(get(), get()) }`, `TxEditorViewModel`, `TransactionsViewModel`, `BudgetsViewModel`, `CategoriesViewModel`, `RecurringViewModel`, `SubscriptionsViewModel`; update `QuickAddViewModel(get(), get(), get())` and `TodayViewModel(get(), get(), get(), get())`.

**Tests:**
- `ChromeTest.moneyRoutes` — `chromeFor(MoneyDest::class) == TabOwnBar`; `TransactionsDest`, `BudgetsDest`, `CategoriesDest`, `RecurringDest`, `SubscriptionsDest` → `Detail`.
- `LaunchTargetTest.moneyUris` — `"mitra://money"` → `OpenMoney`; `"mitra://quick-add/expense"` → `QuickAddExpense`; `"mitra://money/budgets"` → `OpenBudgets`; `"mitra://money/x"` → `null`.
- `AppModulesTest` — the Koin graph (with the real `dataModule` on the test driver) resolves every new ViewModel and `MoneyRepository`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:app:testAndroidHostTest :androidApp:assembleDebug`

---

### Task 14: Money home-screen widget ("Safe to spend") + Hide amounts config + shared widget infrastructure

**Files:**
- Create: `shared/domain/src/commonMain/kotlin/tech/beyondscale/mitra/domain/money/MoneyWidgetModel.kt`
- Create: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/widget/MoneyWidget.kt`, `MoneyWidgetReceiver.kt`, `MoneyWidgetConfigActivity.kt`
- Create: `androidApp/src/main/res/xml/money_widget_info.xml`
- Modify: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/widget/WidgetUpdater.kt`, `MidnightReceiver.kt`, `TasksWidgetReceiver.kt` (`onDisabled` → `scheduleIfPlaced`)
- Modify: `androidApp/src/main/kotlin/tech/beyondscale/mitra/android/MitraApplication.kt`, `androidApp/src/main/AndroidManifest.xml`, `androidApp/src/main/res/values/strings.xml`
- Test: `shared/domain/src/commonTest/kotlin/tech/beyondscale/mitra/domain/money/MoneyWidgetModelTest.kt`

**Interfaces:**
- Consumes: `todayMoney`, `budgetTone`, `BudgetTone`, `inrPaise` (Task 2); `MoneyRepository` (Task 5 DI); `AuthRepository.hasLocalAccount`, `PreferencesRepository.theme`, `WidgetPalette`, `openUri` (Phase 2 `TasksWidget.kt`); URIs from Task 13.
- Produces: `const val HIDDEN_AMOUNT = "₹••••"`; `sealed interface MoneyWidgetModel { data object SignedOut; data class NoBudget(val spent: String); data class Budget(val safe: String, val line: String, val progress: Float?, val tone: BudgetTone, val next: String?) }`; `fun moneyWidgetModel(categories: List<Category>, txs: List<Transaction>, recurring: List<Recurring>, subs: List<Subscription>, today: String, signedIn: Boolean, hideAmounts: Boolean): MoneyWidgetModel`; `class MoneyWidget : GlanceAppWidget()` with `companion object { val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts") }`.

**Requirements (spec §10 "Money widget"):**
- Model: `Budget.safe = "₹1,240"` (`/ day` suffix drawn by the layout), `line = "₹18,400 of ₹30,000 · 61%"`, `progress = min(1, spent/budget)`, `tone = budgetTone(spent, budget)` (accent < 90 %, warning ≥ 90 %, danger > 100 %), `next = "🏠 Rent · in 3 days · ₹15,000"` (`today` / `tomorrow` wording from `nextLabel`). No budget → `NoBudget("₹18,400")`. `signedIn == false` → `SignedOut` regardless of data. `hideAmounts` → every amount is `HIDDEN_AMOUNT`, `line = "₹•••• of ₹••••"`, `progress = null` (bar not drawn — the ratio is private too), next amount masked.
- `MoneyWidget`: `SizeMode.Exact`, `stateDefinition = PreferencesGlanceStateDefinition`; content collects `combine(money.categories, money.transactions, money.recurring, money.subscriptions, auth.hasLocalAccount, clock.dayFlow())` itself (same reason as `TasksWidget`), reads `currentState(HIDE_AMOUNTS) ?: false`. Medium (≥ `TasksWidget.MEDIUM_MIN_WIDTH`): eyebrow `Safe to spend`, hero `"₹1,240 / day"`, line + bar, next line, `＋ Expense` button → `mitra://quick-add/expense`; body tap → `mitra://money`. Small: hero + `＋ Expense`. NoBudget: `"Spent this month ₹18,400"` + `Set budget` → `mitra://money/budgets`. SignedOut: `"Sign in to Mitra"` → opens app. Colours from `WidgetPalette.of(theme)`.
- `MoneyWidgetConfigActivity` (exported, `APPWIDGET_CONFIGURE` intent filter): one `Hide amounts` switch (+ `"Shows ₹•••• instead of amounts on this widget."`) and `Done`; writes `HIDE_AMOUNTS` with `updateAppWidgetState(context, glanceId)`, calls `MoneyWidget().update(context, glanceId)`, returns `RESULT_OK` with `EXTRA_APPWIDGET_ID`; back/cancel returns `RESULT_CANCELED` only on first placement. Compose UI with `MitraTheme`. No Koin access to account data in this activity.
- `money_widget_info.xml`: like `tasks_widget_info.xml` plus `android:configure=".android.widget.MoneyWidgetConfigActivity"` (fully-qualified), `android:widgetFeatures="reconfigurable|configuration_optional"`, `targetCellWidth 4`, `targetCellHeight 2`, description `@string/money_widget_description` (`"Safe to spend today, budget and next bill"`), receiver label `"Mitra · Money"`.
- `WidgetUpdater(context, tasks, money, auth, prefs, clock, scope)`: add a second pipeline — `combine(money flows, hasLocalAccount, theme, dayFlow)` → `moneyWidgetModel(…, hideAmounts = false)` paired with theme → `distinctUntilChanged().drop(1).debounce(500)` → `MoneyWidget().updateAll(context)`.
- `MidnightReceiver.onReceive` updates both widgets; `scheduleIfPlaced` arms when **either** widget type has a glance id; both receivers' `onDisabled` call `scheduleIfPlaced` (not `cancel`), `onEnabled` call `schedule`.
- Sign-out: `LocalData.clearUserData()` + `hasLocalAccount = false` already redraw via `WidgetUpdater`; the shared-flow `replayExpirationMillis = 0` (Task 1) guarantees a re-created widget session never shows the old account's numbers.

**Tests (`MoneyWidgetModelTest`):**
- `budgetLineAndTone` — budget 3 000 000, spent 1 840 000 on `2026-10-14` (18 days left incl. today) → `safe == "₹644"` (`(30000 − 18400) / 18 = 644.44`), `line == "₹18,400 of ₹30,000 · 61%"`, `tone == Normal`, `progress == 0.6133f ± 0.001`.
- `toneAt90And101Percent` — spent 2 700 000 → `Warning`; 3 030 000 → `Danger`, `progress == 1f`.
- `noBudgetShowsSpent` — no budgets → `NoBudget("₹18,400")`.
- `signedOutShowsNoAmounts` — `signedIn = false` with data → `SignedOut`.
- `hiddenAmountsContainNoDigits` — `hideAmounts = true` → `safe`, `line`, `NoBudget.spent` contain no digit; `next` matches no `Regex("₹\\s?\\d")`; `progress == null`.
- `nextBillLine` — rent day 17, today 14 → `next == "🏠 Rent · in 3 days · ₹15,000"`.

**Verify:** `cd mitra-mobile && ./gradlew :shared:domain:testAndroidHostTest :androidApp:assembleDebug`

---

### Task 15: Full build verification + README

**Files:**
- Modify: `mitra-mobile/README.md`

**Requirements:**
- Run `cd "/Users/rajuosuri/Documents/Mine/ Full Stack/mitra-ai/mitra-mobile" && ./gradlew testAndroidHostTest :androidApp:assembleDebug` — both must pass with zero test failures. If anything fails, use superpowers:systematic-debugging before changing code.
- README: Layout gains `shared/domain/money`, `shared/feature/money`; "Widgets" section gains "Mitra · Money" (Safe to spend, ＋ Expense, long-press → reconfigure → Hide amounts); Money notes (amounts are stored in paise; accounts are managed on the web this phase); update the test count line with the real number from the run.
- Commit: `git add mitra-mobile/README.md && git commit -m "docs(mobile): Phase 3 money and widget notes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

---

## Deferred device checklist (Phase 3 — run on a device with the Phase 2 list)

Run against the local backend (`curl -s http://localhost:4000/api/health`, `./gradlew :androidApp:installDebug`), installing **over the Phase 2 build**:

1. Upgrade in place: the app opens into Today signed in; tasks are still there; Money fills in after the first refresh (schema v3 migration).
2. Money tab: Summary numbers (Spent / Income / Saved, budget %, projected month end) and Analytics equal the web for the same month and account filter; month switcher goes back to older months; press-and-drag on the daily bars reads the amount.
3. Add expense from Money ＋ with the keypad: `4.35` saves as ₹4.35 on the web; `1234.1` edits round-trip; Save & add another keeps the sheet open; Undo removes it on both sides.
4. Transfer between two accounts: both balances move on the dashboard; edit it to an expense and back — balances match the web after sync.
5. Airplane mode: add → edit → delete one expense, add another, Mark paid a bill → pill shows pending; airplane off → the web shows exactly the surviving expense and one paid-bill transaction dated today (IST).
6. Mark paid double-tap → one transaction; "Paid 14 Oct" chip appears.
7. Budgets: set, edit, remove; Near limit / Over chips at 90 % / >100 %; Categories create with budget, rename, delete → transactions show uncategorised.
8. Subscriptions: add, Mark as used today, remove; renewals chart.
9. Quick Add → Expense chip → "₹240 food lunch" → expense in Food & dining on the default account; Undo works.
10. Today: Spent today stat and Money card (safe to spend, left this month, next bill, near/over line) equal the web's Today; after 00:00 IST they roll to the new day/month without restarting.
11. Add "Mitra · Money" widget: shows safe-to-spend / budget bar / next bill; ＋ Expense opens Quick Add on Expense; with no budgets shows "Spent this month" + Set budget; reconfigure → Hide amounts → only ₹•••• visible; sign out → "Sign in to Mitra" and no amounts; next morning it shows the new day without opening the app.
12. Remove the Tasks widget while the Money widget stays: the Money widget still refreshes at midnight (alarm kept).
13. Money widget fit at 2×2 and 4×2: the safe-to-spend hero does not clip, including at the largest font scale; the 2×2 ＋ button stays tappable.
14. Widget configuration flow on Android < 12 (config activity opens on placement; cancel removes the widget) and ≥ 12 (reconfigure from long-press); Save survives rotation.
15. Hide amounts on one widget only: that widget shows ₹•••• with no budget bar or tone, and stays hidden after opening the app; a second widget without it shows amounts.
16. Widget after sign-out ("Sign in to Mitra", no amounts) and after a 401 session expiry (amounts hidden until signed in again; note: the session-ended flag is not persisted across process death — check what shows after force-stop).
17. Account switch (sign out → sign in as another user) with the widget placed: no frame of the previous user's amounts.
18. Midnight rollover: safe-to-spend/day and the month bar update after 00:00 IST without opening the app (also on the 1st of a month).
19. ＋ Expense from the widget opens Quick Add on Expense (also when the app was killed).
20. Launch links via `adb shell am start … -d`: `mitra://money`, `mitra://quick-add/expense`, `mitra://money/budgets`; unknown `mitra://money/x` just opens the app.
21. Quick Add expense parser on device: `₹240 food lunch`, `lunch 240`, `rs 1,250.50 groceries`; amount and category chips can be dismissed.
22. TxFormSheet keypad: live ₹ formatting while typing (grouping, two decimals max), backspace/clear, IME does not cover the keypad.
23. Swipe-delete a transaction → Undo restores it (also after leaving and returning to the list before the snackbar ends).
24. Mark paid offline then sync: exactly one paid transaction on the web; refresh (pull or foreground) while the Mark paid is still queued does not drop the local transaction.
25. Delete the transfer category on the web/app: existing transfers keep working (category archived locally, not deleted).
26. Edit a transaction whose account is archived on the web: picker shows "Choose account" (known cosmetic) and saving without changing it keeps the original account.
