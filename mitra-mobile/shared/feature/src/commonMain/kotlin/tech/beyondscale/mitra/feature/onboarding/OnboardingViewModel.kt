package tech.beyondscale.mitra.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.money.ExpenseParser
import tech.beyondscale.mitra.domain.repository.AccountRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.QuickAddParser

/** Web onboarding's six areas; [module] is the setting a pick turns on ("" = always on). */
enum class OnbArea(val title: String, val sub: String, val module: String) {
    Tasks("Tasks & projects", "Inbox, today, upcoming, focus mode", ""),
    Money("Money", "Expenses, budgets, bills, subscriptions", "money"),
    Habits("Habits", "Daily and flexible habits with gentle streaks", ""),
    Goals("Goals", "Milestones linked to habits and tasks", ""),
    Wellness("Wellness", "Sleep, movement, water, mood — no medical claims", "wellness"),
    Notes("Notes & journal", "Quick notes, daily journal, second brain", ""),
}

data class OnbNotif(val tasks: Boolean = true, val habits: Boolean = true, val bills: Boolean = true, val digest: Boolean = true) {
    val on: Int get() = listOf(tasks, habits, bills, digest).count { it }
}

/** What the first capture would save. */
sealed interface CapturePreview {
    data class Expense(val amountPaise: Long, val merchant: String, val categoryId: String?) : CapturePreview
    data class Task(val title: String, val due: String?) : CapturePreview
}

data class OnboardingUiState(
    val step: Int = 1,
    val picked: Set<OnbArea> = setOf(OnbArea.Tasks, OnbArea.Money, OnbArea.Habits),
    val capture: String = "Spent ₹450 on dinner",
    val preview: String? = null,
    val notif: OnbNotif = OnbNotif(),
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface OnboardingAction {
    data class Toggle(val area: OnbArea) : OnboardingAction
    data class Capture(val text: String) : OnboardingAction
    data class Notif(val notif: OnbNotif) : OnboardingAction
    data object Next : OnboardingAction
    data object Back : OnboardingAction
    data object Skip : OnboardingAction
}

private val MID_AMOUNT = Regex("""(?:₹|\brs\.?\s?)(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

/** Expense when Money is picked and the text has an amount; otherwise a task (Quick Add's parsers). */
fun capturePreview(text: String, moneyOn: Boolean, categories: List<Category>, projects: List<Project>, today: String): CapturePreview? {
    if (text.isBlank()) return null
    if (moneyOn) {
        val e = ExpenseParser.parse(text, categories)
        val amount = e.amountPaise
        if (amount != null) return CapturePreview.Expense(amount, e.merchant, e.category?.id)
        // "Spent ₹450 on dinner": a ₹ amount mid-sentence; the words after "on"/"at" (else the rest) name it.
        val m = MID_AMOUNT.find(text)
        val rupees = m?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        if (m != null && rupees != null && rupees > 0) {
            val rest = text.removeRange(m.range).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val after = rest.indexOfFirst { it.lowercase() in setOf("on", "at", "for") }
            val words = (if (after >= 0) rest.drop(after + 1) else rest.filterNot { it.lowercase() in setOf("spent", "paid") })
            val name = words.joinToString(" ")
            val cat = ExpenseParser.parse(name, categories).category
            return CapturePreview.Expense(kotlin.math.round(rupees * 100).toLong(), name.replaceFirstChar { it.uppercase() }, cat?.id)
        }
    }
    val t = QuickAddParser.parse(text, today, projects)
    return if (t.title.isBlank()) null else CapturePreview.Task(t.title, t.due)
}

/** Web `OnboardingScreen` (4 steps), shown once per account until `users/me/onboarded` is recorded. */
class OnboardingViewModel(
    private val account: AccountRepository,
    private val tasks: TasksRepository,
    private val money: MoneyRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val local = MutableStateFlow(OnboardingUiState())

    val state: StateFlow<OnboardingUiState> = combine(local, money.categories, tasks.projects) { s, cats, projects ->
        val today = clock.today()
        val p = capturePreview(s.capture, OnbArea.Money in s.picked, cats, projects, today)
        s.copy(preview = when (p) {
            is CapturePreview.Expense -> "Expense ${inrPaise(p.amountPaise)}" + if (p.merchant.isNotBlank()) " · ${p.merchant}" else ""
            is CapturePreview.Task -> "Task · ${p.title}" + (p.due?.let { " · ${fmtDay(it, today)}" } ?: "")
            null -> null
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun onAction(a: OnboardingAction) {
        when (a) {
            is OnboardingAction.Toggle -> local.update { s -> s.copy(picked = if (a.area in s.picked) s.picked - a.area else s.picked + a.area) }
            is OnboardingAction.Capture -> local.update { it.copy(capture = a.text) }
            is OnboardingAction.Notif -> local.update { it.copy(notif = a.notif) }
            OnboardingAction.Back -> local.update { it.copy(step = (it.step - 1).coerceAtLeast(1), error = null) }
            OnboardingAction.Next, OnboardingAction.Skip -> if (local.value.step < 4) local.update { it.copy(step = it.step + 1) } else finish()
        }
    }

    private fun finish() {
        val s = local.value
        if (s.saving) return
        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val moneyOn = OnbArea.Money in s.picked
            val modules = Modules(money = moneyOn, wellness = OnbArea.Wellness in s.picked, ai = true)
            // The capture first: once onboarded the account's user flips and this screen leaves composition.
            runCatching { saveCapture(s.capture, moneyOn) }
            when (val r = account.markOnboarded(modules, s.notif.digest)) {
                is Outcome.Ok -> Unit // the session's user now has onboardedAt: the shell takes over
                is Outcome.Err -> local.update { it.copy(saving = false, error = r.error.message) }
            }
        }
    }

    private var captured = false

    private suspend fun saveCapture(text: String, moneyOn: Boolean) {
        if (captured) return
        val today = clock.today()
        when (val p = capturePreview(text, moneyOn, money.categories.first(), tasks.projects.first(), today)) {
            is CapturePreview.Expense -> {
                val acc = money.accounts.first().firstOrNull { !it.archived } ?: return
                val now = clock.localNow()
                val time = now.hour.toString().padStart(2, '0') + ":" + now.minute.toString().padStart(2, '0')
                money.addTransaction(TxDraft(TxKind.Expense, p.amountPaise, p.categoryId, acc.id, null, p.merchant, null, today, time))
            }
            is CapturePreview.Task -> tasks.create(TaskDraft(p.title, due = p.due))
            null -> return
        }
        captured = true
    }
}
