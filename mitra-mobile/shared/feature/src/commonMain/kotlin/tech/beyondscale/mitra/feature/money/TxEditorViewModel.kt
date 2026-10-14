package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AmountInput
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.domain.money.findDuplicate
import tech.beyondscale.mitra.domain.money.recentMerchants
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

data class TxOption(val id: String, val label: String)

/** The add/edit transaction sheet (web `TxForm` in screens/money.tsx). */
data class TxEditorUiState(
    /** False until [TxEditorViewModel.start] has prefilled the form. */
    val ready: Boolean = false,
    val editId: String? = null,
    val title: String = "Add expense",
    val kind: TxKind = TxKind.Expense,
    val amountText: String = "",
    val amountDisplay: String = "0",
    /** The keypad is always shown when adding; when editing it opens on tapping the amount (never the system keyboard). */
    val showKeypad: Boolean = true,
    val error: String? = null,
    val duplicate: String? = null,
    /** False for a transfer: its category is the system `transfer` one. */
    val showCategories: Boolean = true,
    val categories: List<TxOption> = emptyList(),
    val categoryId: String? = null,
    val accountLabel: String = "Account",
    val accounts: List<TxOption> = emptyList(),
    val accountId: String? = null,
    val accountHint: String? = null,
    val toAccounts: List<TxOption> = emptyList(),
    val toAccountId: String? = null,
    val today: String = "",
    val date: String = "",
    val time: String = "",
    val timeLabel: String = "",
    val merchantLabel: String = "Merchant",
    val merchantPlaceholder: String = "e.g. Swiggy",
    val merchant: String = "",
    /** "Recent:" merchant chips for the chosen category, only while the merchant is empty. */
    val recentMerchants: List<String> = emptyList(),
    val note: String = "",
    val showRepeat: Boolean = true,
    val repeat: Boolean = false,
    val repeatLabel: String = "Repeat monthly",
    val showDelete: Boolean = false,
    val showSaveAgain: Boolean = true,
    val saveLabel: String = "Save expense",
    val saving: Boolean = false,
)

sealed interface TxEditorAction {
    data class Kind(val k: TxKind) : TxEditorAction
    data class Key(val c: Char) : TxEditorAction
    data object Backspace : TxEditorAction
    /** Replaces the amount (keypad long-press clear sends ""); pasted text is sanitised and held to the keypad's rules. */
    data class Amount(val text: String) : TxEditorAction
    data class Category(val id: String) : TxEditorAction
    data class NewCategory(val name: String, val emoji: String) : TxEditorAction
    data class Account(val id: String) : TxEditorAction
    data class ToAccount(val id: String) : TxEditorAction
    data class Date(val iso: String) : TxEditorAction
    data class Time(val hhmm: String) : TxEditorAction
    data class Merchant(val v: String) : TxEditorAction
    data class Note(val v: String) : TxEditorAction
    data object ToggleRepeat : TxEditorAction
    data class Save(val again: Boolean) : TxEditorAction
    data object Delete : TxEditorAction
    /** Undo for "Expense saved · ₹450". */
    data class UndoAdd(val id: String) : TxEditorAction
}

/** Shell-scoped like `TaskEditorViewModel`: the host calls [start] and shows `TxFormSheet`; Undo snackbars outlive the sheet. */
class TxEditorViewModel(private val money: MoneyRepository, private val clock: AppClock) : ViewModel() {
    private data class Form(
        val ready: Boolean = false,
        val editing: Transaction? = null,
        val kind: TxKind = TxKind.Expense,
        val amount: String = "",
        /** When true the category follows the kind's default (first chip, or the transfer category). */
        val autoCategory: Boolean = true,
        val categoryId: String? = null,
        val accountId: String? = null,
        val toAccountId: String? = null,
        val date: String = "",
        val time: String = "",
        val merchant: String = "",
        val note: String = "",
        val repeat: Boolean = false,
        val error: String? = null,
        val saving: Boolean = false,
    )

    private data class Data(val accounts: List<Account>, val categories: List<Category>, val transactions: List<Transaction>)

    /** What a save sends, resolved against the current lists. */
    private data class Resolved(val categoryId: String?, val accountId: String?, val toAccountId: String?)

    private val form = MutableStateFlow(Form())
    private val queue = EventQueue<TxEditorAction>()
    val events: Flow<UiEvent<TxEditorAction>> = queue.events

    private val data = combine(money.accounts, money.categories, money.transactions, ::Data)

    val state: StateFlow<TxEditorUiState> = combine(form, data, clock.dayFlow(), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxEditorUiState())

    /** Opens the form: a fresh expense for `null`, else prefilled from that transaction (closes if it no longer exists). */
    fun start(editId: String?) {
        form.value = Form()
        viewModelScope.launch {
            val today = clock.today()
            val now = clock.localNow()
            val hhmm = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
            if (editId == null) {
                form.value = Form(ready = true, date = today, time = hhmm)
                return@launch
            }
            val tx = money.transactions.first().firstOrNull { it.id == editId }
            if (tx == null) {
                queue.send(UiEvent.Close)
                return@launch
            }
            form.value = Form(
                ready = true, editing = tx, kind = tx.kind, amount = AmountInput.fromPaise(tx.amountPaise),
                autoCategory = false, categoryId = tx.categoryId, accountId = tx.accountId, toAccountId = tx.toAccountId,
                date = tx.date, time = tx.time, merchant = tx.merchant, note = tx.note ?: "",
            )
        }
    }

    fun onAction(a: TxEditorAction) {
        when (a) {
            // A chosen category survives a kind switch only while that kind still offers it (see [resolve]).
            is TxEditorAction.Kind -> form.update { f ->
                f.copy(kind = a.k, autoCategory = f.autoCategory || f.categoryId == null, error = null)
            }
            is TxEditorAction.Key -> editAmount { AmountInput.press(it, a.c) }
            TxEditorAction.Backspace -> editAmount { AmountInput.backspace(it) }
            is TxEditorAction.Amount -> editAmount { AmountInput.sanitize(a.text).fold("") { acc, c -> AmountInput.press(acc, c) } }
            is TxEditorAction.Category -> form.update { it.copy(autoCategory = false, categoryId = a.id, error = null) }
            is TxEditorAction.NewCategory -> addCategory(a.name, a.emoji)
            is TxEditorAction.Account -> form.update { it.copy(accountId = a.id, error = null) }
            is TxEditorAction.ToAccount -> form.update { it.copy(toAccountId = a.id, error = null) }
            is TxEditorAction.Date -> form.update { it.copy(date = a.iso) }
            is TxEditorAction.Time -> form.update { it.copy(time = a.hhmm) }
            is TxEditorAction.Merchant -> form.update { it.copy(merchant = a.v) }
            is TxEditorAction.Note -> form.update { it.copy(note = a.v) }
            TxEditorAction.ToggleRepeat -> form.update { it.copy(repeat = !it.repeat) }
            is TxEditorAction.Save -> save(a.again)
            TxEditorAction.Delete -> delete()
            is TxEditorAction.UndoAdd -> viewModelScope.launch { money.deleteTransaction(a.id) }
        }
    }

    private fun editAmount(f: (String) -> String) = form.update { it.copy(amount = f(it.amount), error = null) }

    private fun addCategory(name: String, emoji: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val kind = if (form.value.kind == TxKind.Income) CategoryKind.Income else CategoryKind.Expense
        viewModelScope.launch {
            val c = money.addCategory(n, emoji.ifBlank { "🎁" }, kind)
            form.update { it.copy(autoCategory = false, categoryId = c.id, error = null) }
            queue.send(UiEvent.Snack("Category added · ${c.emoji} ${c.name}"))
        }
    }

    private fun save(again: Boolean) {
        val f = form.value
        // Double-tap guard: claimed synchronously, released on a validation error or after "Save & add another".
        if (!f.ready || f.saving) return
        form.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val d = data.first()
            val r = resolve(f, d)
            val paise = AmountInput.toPaise(f.amount)
            val accountId = r.accountId
            val error = when {
                paise == null -> "Enter an amount"
                r.categoryId == null && f.kind != TxKind.Transfer -> "Pick a category"
                accountId == null -> "Pick an account"
                f.kind == TxKind.Transfer && (r.toAccountId == null || r.toAccountId == accountId) -> "Pick a different account to transfer to"
                else -> null
            }
            if (error != null || paise == null || accountId == null) {
                form.update { it.copy(error = error, saving = false) }
                return@launch
            }
            val existing = f.editing
            val draft = TxDraft(
                kind = f.kind, amountPaise = paise, categoryId = r.categoryId, accountId = accountId,
                toAccountId = if (f.kind == TxKind.Transfer) r.toAccountId else null,
                merchant = f.merchant.trim(), note = f.note.trim().ifEmpty { null }, date = f.date, time = f.time,
                source = existing?.source ?: TxSource.User,
            )
            if (existing != null) {
                money.updateTransaction(existing.id, draft)
                queue.send(UiEvent.Snack("Transaction updated"))
                queue.send(UiEvent.Close)
                return@launch
            }
            val tx = money.addTransaction(draft)
            if (f.repeat) money.addRecurring(recurringFor(draft, d.categories.firstOrNull { it.id == draft.categoryId }))
            val text = "${kindName(draft.kind)} saved · ${inrPaise(paise)}" + if (f.repeat) " · repeats monthly" else ""
            queue.send(UiEvent.Snack(text, TxEditorAction.UndoAdd(tx.id)))
            if (again) {
                form.update { it.copy(amount = "", merchant = "", note = "", saving = false) }
            } else {
                queue.send(UiEvent.Close)
            }
        }
    }

    /** Web: `addRecurring({ name: merchant || category name || 'Recurring', day: min(28, day of date), … })`. */
    private fun recurringFor(draft: TxDraft, cat: Category?) = RecurringDraft(
        name = draft.merchant.ifEmpty { cat?.name ?: "Recurring" },
        emoji = cat?.emoji ?: "🔁",
        amountPaise = draft.amountPaise,
        day = minOf(28, dayOfMonth(draft.date)),
        kind = when (draft.kind) {
            TxKind.Income -> RecurringKind.Income
            TxKind.Transfer -> RecurringKind.Transfer
            TxKind.Expense -> RecurringKind.Bill
        },
        categoryId = draft.categoryId,
        accountId = draft.accountId,
        toAccountId = draft.toAccountId,
    )

    private fun delete() {
        val f = form.value
        val existing = f.editing ?: return
        if (f.saving) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            money.deleteTransaction(existing.id)
            queue.send(UiEvent.Snack("Transaction deleted"))
            queue.send(UiEvent.Close)
        }
    }

    private fun build(f: Form, d: Data, today: String): TxEditorUiState {
        val edit = f.editing != null
        val cats = categoriesFor(f.kind, d.categories)
        val r = resolve(f, d)
        val paise = AmountInput.toPaise(f.amount)
        val dup = if (edit || paise == null) null else findDuplicate(d.transactions, f.merchant, paise, f.date)
        val income = f.kind == TxKind.Income
        return TxEditorUiState(
            ready = f.ready,
            editId = f.editing?.id,
            title = if (edit) "Edit transaction" else "Add expense",
            kind = f.kind,
            amountText = f.amount,
            amountDisplay = AmountInput.display(f.amount),
            showKeypad = !edit,
            error = f.error,
            duplicate = dup?.let { "Looks like ${it.merchant} ${inrPaise(it.amountPaise)} was already added at ${fmtTime(it.time)}." },
            showCategories = f.kind != TxKind.Transfer,
            categories = cats.map { TxOption(it.id, "${it.emoji} ${it.name}") },
            categoryId = r.categoryId,
            accountLabel = if (f.kind == TxKind.Transfer) "From account" else "Account",
            accounts = d.accounts.map { a ->
                TxOption(a.id, "${a.name} ${a.mask}" + (if (a.balancePaise < 0) " · −" else " · ") + inrPaise(a.balancePaise))
            },
            accountId = r.accountId,
            accountHint = if (d.accounts.isEmpty()) "No accounts yet — add one in Profile" else null,
            toAccounts = d.accounts.filter { it.id != r.accountId }.map { TxOption(it.id, "${it.name} ${it.mask}") },
            toAccountId = r.toAccountId,
            today = today,
            date = f.date,
            time = f.time,
            timeLabel = fmtTime(f.time),
            merchantLabel = if (income) "Source" else "Merchant",
            merchantPlaceholder = if (income) "e.g. Salary — Beyond Labs" else "e.g. Swiggy",
            merchant = f.merchant,
            recentMerchants = if (f.merchant.isEmpty()) recentMerchants(d.transactions, r.categoryId) else emptyList(),
            note = f.note,
            showRepeat = !edit,
            repeat = f.repeat,
            repeatLabel = if (f.repeat) "Repeats monthly ✓" else "Repeat monthly",
            showDelete = edit,
            showSaveAgain = !edit,
            saveLabel = if (edit) "Save changes" else "Save ${f.kind.wire}",
            saving = f.saving,
        )
    }

    private fun resolve(f: Form, d: Data): Resolved {
        val cats = categoriesFor(f.kind, d.categories)
        val chosen = f.categoryId
        val categoryId = when {
            // An uncategorised transaction being edited stays so until a category is picked (web: "Pick a category").
            !f.autoCategory && chosen == null && f.kind != TxKind.Transfer -> null
            !f.autoCategory && chosen != null && cats.any { it.id == chosen } -> chosen
            f.kind == TxKind.Transfer -> d.categories.firstOrNull { it.slug == TRANSFER_SLUG }?.id
            else -> cats.firstOrNull()?.id
        }
        val accountId = f.accountId?.takeIf { id -> d.accounts.any { it.id == id } } ?: d.accounts.firstOrNull()?.id
        val toAccountId = f.toAccountId?.takeIf { id -> d.accounts.any { it.id == id } }
            ?: d.accounts.firstOrNull { it.id != accountId }?.id
        return Resolved(categoryId, accountId, toAccountId)
    }

    private fun categoriesFor(kind: TxKind, all: List<Category>): List<Category> = when (kind) {
        TxKind.Income -> all.filter { it.kind == CategoryKind.Income }
        TxKind.Expense -> all.filter { it.kind == CategoryKind.Expense }
        TxKind.Transfer -> all.filter { it.kind == CategoryKind.Expense && it.slug == TRANSFER_SLUG }
    }

    private companion object {
        const val TRANSFER_SLUG = "transfer"
    }
}
