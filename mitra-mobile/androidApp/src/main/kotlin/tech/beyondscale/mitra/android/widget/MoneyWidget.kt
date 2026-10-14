package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.android.R
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.money.BudgetTone
import tech.beyondscale.mitra.domain.money.HIDDEN_AMOUNT
import tech.beyondscale.mitra.domain.money.MoneyWidgetModel
import tech.beyondscale.mitra.domain.money.moneyWidgetModel
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository

/**
 * "Safe to spend" home-screen widget (spec §10): safe-to-spend per day, the month's budget bar, the next bill or
 * renewal and ＋ Expense, read from the local database. Each placed widget has its own "Hide amounts" option
 * ([HIDE_AMOUNTS] in its Glance state, set by [MoneyWidgetConfigActivity]).
 */
class MoneyWidget : GlanceAppWidget(), KoinComponent {
    /** Exact: [LocalSize] is the widget's real size, so the small layout is picked by width. */
    override val sizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = get<PreferencesRepository>()
        // Like TasksWidget: update() on a live session does not re-run provideGlance, so the content collects the
        // flows itself; the first() snapshot is only the initial frame.
        val inputs = moneyWidgetInputs(get(), get(), get())
        val initialInputs = inputs.first()
        val initialTheme = prefs.theme.first()
        provideContent {
            val input by inputs.collectAsState(initialInputs)
            val theme by prefs.theme.collectAsState(initialTheme)
            val hide = currentState(HIDE_AMOUNTS) ?: false
            val model = remember(input, hide) { input.model(hideAmounts = hide) }
            MoneyWidgetContent(model, hide, remember(theme) { WidgetPalette.of(theme) })
        }
    }

    companion object {
        /** Per-widget "Hide amounts" (Glance state of that widget). */
        val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts")

        /** Lower than this, the medium layout drops the next-bill line so the rest is not clipped. */
        internal val NEXT_MIN_HEIGHT = 140.dp
    }
}

/** Everything the widget model is computed from, except the per-widget "Hide amounts". */
internal data class MoneyWidgetInputs(
    val categories: List<Category>,
    val txs: List<Transaction>,
    val recurring: List<Recurring>,
    val subs: List<Subscription>,
    val today: String,
    val signedIn: Boolean,
    val moneyEnabled: Boolean,
) {
    fun model(hideAmounts: Boolean): MoneyWidgetModel =
        moneyWidgetModel(categories, txs, recurring, subs, today, signedIn, hideAmounts, moneyEnabled)
}

/** Shared by the widget and [WidgetUpdater]: money flows + the local account (signed in, Money module on) + the day. */
internal fun moneyWidgetInputs(money: MoneyRepository, auth: AuthRepository, clock: AppClock): Flow<MoneyWidgetInputs> {
    val data = combine(money.categories, money.transactions, money.recurring, money.subscriptions) { c, t, r, s ->
        MoneyWidgetInputs(c, t, r, s, today = "", signedIn = false, moneyEnabled = true)
    }
    return combine(data, auth.localUser, clock.dayFlow()) { d, user, today ->
        d.copy(today = today, signedIn = user != null, moneyEnabled = user?.settings?.modules?.money ?: true)
    }
}

private const val MONEY_URI = "mitra://money"
private const val ADD_EXPENSE_URI = "mitra://quick-add/expense"
private const val BUDGETS_URI = "mitra://money/budgets"

@Composable
private fun MoneyWidgetContent(model: MoneyWidgetModel, hidden: Boolean, p: WidgetPalette) {
    val context = LocalContext.current
    val size = LocalSize.current
    val small = size.width < TasksWidget.MEDIUM_MIN_WIDTH
    Box(modifier = GlanceModifier.fillMaxSize().background(p.surface).cornerRadius(16.dp).padding(12.dp)) {
        when (model) {
            MoneyWidgetModel.SignedOut -> Centered(context.getString(R.string.money_widget_sign_in), p, openApp(context))
            MoneyWidgetModel.MoneyOff -> Centered(context.getString(R.string.money_widget_off), p, openApp(context))
            is MoneyWidgetModel.NoBudget -> NoBudget(model, hidden, small, p)
            is MoneyWidgetModel.Budget -> Budget(model, hidden, small, size.height >= MoneyWidget.NEXT_MIN_HEIGHT, p)
        }
    }
}

@Composable
private fun Header(eyebrow: String, small: Boolean, p: WidgetPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            eyebrow,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        if (!small) ExpenseButton(p)
    }
}

@Composable
private fun ExpenseButton(p: WidgetPalette, compact: Boolean = false) {
    val context = LocalContext.current
    val label = context.getString(R.string.money_widget_add_expense_cd)
    Box(
        modifier = GlanceModifier.height(30.dp).cornerRadius(15.dp).background(p.accent).padding(horizontal = if (compact) 10.dp else 12.dp)
            .clickable(openUri(context, ADD_EXPENSE_URI)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (compact) "＋" else context.getString(R.string.money_widget_add_expense),
            maxLines = 1,
            style = TextStyle(color = p.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Hero(text: String, description: String, p: WidgetPalette, small: Boolean) {
    Text(
        text,
        maxLines = 1,
        modifier = GlanceModifier.semantics { contentDescription = description },
        style = TextStyle(color = p.text, fontSize = if (small) 22.sp else 26.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun Budget(model: MoneyWidgetModel.Budget, hidden: Boolean, small: Boolean, showNext: Boolean, p: WidgetPalette) {
    val context = LocalContext.current
    val description = if (hidden) {
        context.getString(R.string.money_widget_safe_hidden_cd)
    } else {
        context.getString(R.string.money_widget_safe_cd, model.safe)
    }
    Column(modifier = GlanceModifier.fillMaxSize().clickable(openUri(context, MONEY_URI))) {
        Header(context.getString(R.string.money_widget_safe_to_spend), small, p)
        Spacer(GlanceModifier.height(4.dp))
        Hero(context.getString(R.string.money_widget_per_day, model.safe), description, p, small)
        if (small) {
            Spacer(GlanceModifier.defaultWeight())
            ExpenseButton(p)
            return@Column
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            model.line,
            maxLines = 1,
            modifier = if (hidden) GlanceModifier.semantics { contentDescription = context.getString(R.string.money_widget_line_hidden_cd) } else GlanceModifier,
            style = TextStyle(color = p.muted, fontSize = 12.sp),
        )
        // Hidden amounts: no bar — the ratio is as private as the numbers.
        model.progress?.let { progress ->
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                color = when (model.tone) {
                    BudgetTone.Normal -> p.accent
                    BudgetTone.Warning -> p.warning
                    BudgetTone.Danger -> p.danger
                },
                backgroundColor = p.track,
            )
        }
        val next = model.next
        if (showNext && next != null) {
            Spacer(GlanceModifier.height(6.dp))
            val nextDescription = if (hidden) next.replace(HIDDEN_AMOUNT, context.getString(R.string.money_widget_amount_hidden)) else next
            Text(
                next,
                maxLines = 1,
                modifier = GlanceModifier.semantics { contentDescription = nextDescription },
                style = TextStyle(color = p.muted, fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun NoBudget(model: MoneyWidgetModel.NoBudget, hidden: Boolean, small: Boolean, p: WidgetPalette) {
    val context = LocalContext.current
    val description = if (hidden) {
        context.getString(R.string.money_widget_spent_hidden_cd)
    } else {
        context.getString(R.string.money_widget_spent_cd, model.spent)
    }
    val setBudget = context.getString(R.string.money_widget_set_budget)
    Column(modifier = GlanceModifier.fillMaxSize().clickable(openUri(context, MONEY_URI))) {
        Header(context.getString(R.string.money_widget_spent_this_month), small, p)
        Spacer(GlanceModifier.height(4.dp))
        Hero(model.spent, description, p, small)
        Spacer(GlanceModifier.defaultWeight())
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                setBudget,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openUri(context, BUDGETS_URI))
                    .semantics { contentDescription = context.getString(R.string.money_widget_set_budget_cd) },
                style = TextStyle(color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            if (small) ExpenseButton(p, compact = true)
        }
    }
}
