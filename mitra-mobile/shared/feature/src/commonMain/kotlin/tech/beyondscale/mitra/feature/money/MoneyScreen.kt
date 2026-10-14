package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.Donut
import tech.beyondscale.mitra.designsystem.components.DonutSegment
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.MonthSwitcher
import tech.beyondscale.mitra.designsystem.components.OtherColor
import tech.beyondscale.mitra.designsystem.components.ScrubBars
import tech.beyondscale.mitra.designsystem.components.SeriesColors
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.money.BudgetTone
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun MoneyRoute(
    onEditTransaction: (String) -> Unit,
    onOpen: (MoneyLink) -> Unit,
    /** Extra top-bar actions after Add (the shell passes its sync pill and avatar). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: MoneyViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    MoneyScreen(state, vm::onAction, onEditTransaction, onOpen, topBarActions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyScreen(
    state: MoneyUiState,
    onAction: (MoneyAction) -> Unit,
    onEditTransaction: (String) -> Unit,
    onOpen: (MoneyLink) -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Money") {
            IconButton(onClick = { onOpen(MoneyLink.AddExpense) }) {
                Icon(MitraIcons.Plus, contentDescription = "Add expense", tint = Mitra.colors.accent)
            }
            topBarActions()
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(MoneyAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") { Header(state, onAction, onOpen) }
                if (state.tab == 0) summary(state, onEditTransaction, onOpen) else analytics(state, onOpen)
            }
        }
    }
}

/* ---------------- header: month · accounts · tabs · links ---------------- */

@Composable
private fun Header(state: MoneyUiState, onAction: (MoneyAction) -> Unit, onOpen: (MoneyLink) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = MitraDimens.gap), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonthSwitcher(
            title = state.monthTitle,
            canNext = state.canNext,
            onPrev = { if (state.canPrev) onAction(MoneyAction.PrevMonth) },
            onNext = { onAction(MoneyAction.NextMonth) },
            modifier = Modifier.padding(horizontal = MitraDimens.pad - 8.dp),
        )
        ChipRow {
            state.accountFilters.forEach { f ->
                MitraChip(
                    f.label,
                    icon = if (f.id == null) MitraIcons.Wallet else null,
                    selected = f.id == state.selectedAccount,
                    onClick = { onAction(MoneyAction.SelectAccount(f.id)) },
                )
            }
        }
        MitraSegmented(
            options = listOf("Summary", "Analytics"),
            selectedIndex = state.tab,
            onSelect = { onAction(MoneyAction.SelectTab(it)) },
            modifier = Modifier.padding(horizontal = MitraDimens.pad),
        )
        ChipRow {
            listOf(
                MoneyLink.Transactions to "Transactions",
                MoneyLink.Budgets to "Budgets",
                MoneyLink.Categories to "Categories",
                MoneyLink.Recurring to "Recurring",
                MoneyLink.Subscriptions to "Subscriptions",
            ).forEach { (link, label) ->
                MitraChip(label, tone = ChipTone.Outline, onClick = { onOpen(link) })
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/* ---------------- Summary ---------------- */

private fun LazyListScope.summary(state: MoneyUiState, onEditTransaction: (String) -> Unit, onOpen: (MoneyLink) -> Unit) {
    val s = state.summary
    item(key = "kpis") {
        Card {
            MitraCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi("Spent", s.spent, s.spentSub, Modifier.weight(1f))
                    Kpi("Income", s.income, s.incomeSub, Modifier.weight(1f))
                    Kpi(
                        "Saved", s.saved, s.savedSub, Modifier.weight(1f),
                        valueColor = when (s.savedDirection) { 1 -> Mitra.colors.success; -1 -> Mitra.colors.danger; else -> Mitra.colors.text },
                    )
                }
                MitraProgressBar(s.budgetFraction, color = toneColor(s.budgetTone))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.budgetFooter, Modifier.weight(1f), style = Mitra.type.xs, color = Mitra.colors.muted)
                    s.projected?.let { Text(it, style = Mitra.type.xs, color = Mitra.colors.muted) }
                }
            }
        }
    }
    gap("g:kpis")
    item(key = "h:cash") { SectionTitle("Cash flow · daily spend", s.spent) }
    item(key = "cash") {
        Card {
            MitraCard {
                if (s.cashFlowEmpty != null) {
                    Text(s.cashFlowEmpty, style = Mitra.type.sm, color = Mitra.colors.muted)
                } else {
                    ScrubBars(values = s.daily, labels = s.dailyLabels, highlight = s.dailyHighlight, format = ::inrPaise)
                    Text(s.cashFlowCaption, style = Mitra.type.xs, color = Mitra.colors.muted)
                }
            }
        }
    }
    gap("g:cash")
    item(key = "h:recent") { SectionTitle("Recent transactions", "All") { onOpen(MoneyLink.Transactions) } }
    item(key = "recent") {
        Card {
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                if (s.recent.isEmpty()) {
                    EmptyState(icon = MitraIcons.Wallet, title = s.recentEmptyTitle, body = s.recentEmptyBody)
                } else {
                    s.recent.forEachIndexed { i, r ->
                        if (i > 0) RowDivider()
                        TxRow(r, onClick = { onEditTransaction(r.id) })
                    }
                }
            }
        }
    }
    if (s.budgets.isNotEmpty()) {
        gap("g:recent")
        item(key = "h:budgets") { SectionTitle("Budgets", "All budgets") { onOpen(MoneyLink.Budgets) } }
        item(key = "budgets") {
            Card {
                MitraCard {
                    s.budgets.forEach { b ->
                        Column(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${b.emoji} ${b.name}", Modifier.weight(1f), style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(b.spent, style = Mitra.type.num, color = Mitra.colors.text)
                                Text(" ${b.budget}", style = Mitra.type.num, color = Mitra.colors.muted)
                            }
                            MitraProgressBar(b.fraction, color = toneColor(b.tone))
                        }
                    }
                }
            }
        }
    }
    if (s.accounts.isNotEmpty()) {
        gap("g:budgets")
        item(key = "h:accounts") { SectionTitle("Accounts") }
        item(key = "accounts") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    s.accounts.forEachIndexed { i, a ->
                        if (i > 0) RowDivider()
                        LinkRow(onClick = { onOpen(MoneyLink.Transactions) }, label = "Open transactions") {
                            IconTile(if (a.credit) MitraIcons.Columns else MitraIcons.Wallet)
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = Mitra.type.body.copy(fontWeight = FontWeight.Medium), color = Mitra.colors.text)
                                if (a.mask.isNotEmpty()) Text(a.mask, style = Mitra.type.xs, color = Mitra.colors.muted)
                            }
                            Text(a.balance, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = if (a.negative) Mitra.colors.danger else Mitra.colors.text)
                        }
                    }
                }
            }
        }
    }
    if (s.upcoming.isNotEmpty()) {
        gap("g:accounts")
        item(key = "h:upcoming") { SectionTitle("Upcoming", "Recurring") { onOpen(MoneyLink.Recurring) } }
        item(key = "upcoming") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    s.upcoming.forEachIndexed { i, u ->
                        if (i > 0) RowDivider()
                        val link = if (u.isSubscription) MoneyLink.Subscriptions else MoneyLink.Recurring
                        LinkRow(onClick = { onOpen(link) }, label = if (u.isSubscription) "Open subscriptions" else "Open recurring") {
                            EmojiBox(u.emoji)
                            Column(Modifier.weight(1f)) {
                                Text(u.name, style = Mitra.type.body.copy(fontWeight = FontWeight.Medium), color = Mitra.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(u.whenLabel, style = Mitra.type.xs, color = Mitra.colors.muted)
                            }
                            Text(u.amount, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text)
                            Icon(MitraIcons.ChevronRight, contentDescription = null, tint = Mitra.colors.faint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- Analytics ---------------- */

private fun LazyListScope.analytics(state: MoneyUiState, onOpen: (MoneyLink) -> Unit) {
    val a = state.analytics
    if (a.empty) {
        item(key = "a:empty") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    EmptyState(
                        icon = MitraIcons.Chart,
                        title = a.emptyTitle,
                        body = "Pick another month, or add an expense to see the breakdown.",
                    ) { MitraButton("Add expense", onClick = { onOpen(MoneyLink.AddExpense) }, icon = MitraIcons.Plus) }
                }
            }
        }
        return
    }
    item(key = "a:tiles") {
        val c = Mitra.colors
        Column(Modifier.padding(horizontal = MitraDimens.pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Total income", a.totalIncome, "Primary monthly inflow", c.success, Modifier.weight(1f))
                Tile("Total expenses", a.totalExpenses, a.expensesSub, c.danger, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Net savings", a.net, a.netSub, c.accent, Modifier.weight(1f))
                Tile("Savings rate", a.savingsRate, "% of income retained", c.info, Modifier.weight(1f))
            }
        }
    }
    gap("g:tiles")
    item(key = "h:donut") { SectionTitle("Expense distribution by category") }
    item(key = "donut") {
        Card {
            MitraCard {
                if (a.donut.isEmpty()) {
                    Text("Nothing spent in ${state.monthTitle} yet.", style = Mitra.type.sm, color = Mitra.colors.muted)
                } else {
                    val segments = a.donut.map { DonutSegment(it.paise, sliceColor(it.colorIndex), it.label) }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Donut(segments, size = 170.dp, stroke = 26.dp, centerLabel = inrPaise(a.spentPaise))
                    }
                    Column(Modifier.clearAndSetSemantics { }) {
                        a.donut.forEach { g ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Dot(sliceColor(g.colorIndex))
                                Spacer(Modifier.width(8.dp))
                                Text(g.label, Modifier.weight(1f), style = Mitra.type.sm, color = Mitra.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.share, style = Mitra.type.num, color = Mitra.colors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
    gap("g:donut")
    item(key = "h:rank") { SectionTitle("Spending by category ranking") }
    item(key = "rank") {
        Card {
            MitraCard {
                if (a.ranking.isEmpty()) Text("No expenses to rank.", style = Mitra.type.sm, color = Mitra.colors.muted)
                a.ranking.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            contentDescription = "${r.name}, ${r.amount}, ${r.share} of spend, ${r.count} transaction${if (r.count == 1) "" else "s"}"
                        },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Dot(sliceColor(r.colorIndex))
                            Spacer(Modifier.width(8.dp))
                            Text("${r.emoji} ${r.name}", Modifier.weight(1f), style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.amount, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text)
                        }
                        MitraProgressBar(r.fraction, color = sliceColor(r.colorIndex), height = 8.dp)
                        Text("${r.share} share · ${r.count} txn${if (r.count == 1) "" else "s"}", style = Mitra.type.xs, color = Mitra.colors.muted)
                    }
                }
                Text(a.rankingCaption, style = Mitra.type.xs, color = Mitra.colors.muted)
            }
        }
    }
    gap("g:rank")
    item(key = "h:acc") { SectionTitle("Account utilisation") }
    item(key = "acc") {
        Card {
            MitraCard {
                if (a.accountUse.isEmpty()) Text("No outflows this month.", style = Mitra.type.sm, color = Mitra.colors.muted)
                a.accountUse.forEach { u ->
                    val color = if (u.credit) Mitra.colors.warning else Mitra.colors.accent
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (u.credit) MitraIcons.Columns else MitraIcons.Wallet, contentDescription = null, tint = Mitra.colors.muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(u.name, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text)
                            Text(" ${u.mask}", Modifier.weight(1f), style = Mitra.type.sm, color = Mitra.colors.muted, maxLines = 1)
                            Text(u.amount, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text)
                        }
                        MitraProgressBar(u.fraction, color = color)
                        Text("${u.share} share · ${u.count} txn${if (u.count == 1) "" else "s"}", style = Mitra.type.xs, color = Mitra.colors.muted)
                    }
                }
            }
        }
    }
    gap("g:acc")
    item(key = "h:top") { SectionTitle("Top individual outflows") }
    item(key = "top") {
        Card {
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                if (a.topOutflows.isEmpty()) {
                    Text("No expenses this month.", Modifier.padding(MitraDimens.pad), style = Mitra.type.sm, color = Mitra.colors.muted)
                }
                a.topOutflows.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("#${t.rank}", style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = Mitra.type.body.copy(fontWeight = FontWeight.Medium), color = Mitra.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(t.date, t.category, t.account).filter { it.isNotEmpty() }.joinToString(" · "), style = Mitra.type.xs, color = Mitra.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(t.amount, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.text)
                    }
                }
            }
        }
    }
}

/* ---------------- pieces ---------------- */

@Composable
private fun sliceColor(index: Int?): Color = if (index == null) OtherColor else SeriesColors[index % SeriesColors.size]

@Composable
private fun toneColor(tone: BudgetTone): Color = when (tone) {
    BudgetTone.Normal -> Mitra.colors.accent
    BudgetTone.Warning -> Mitra.colors.warning
    BudgetTone.Danger -> Mitra.colors.danger
}

private fun LazyListScope.gap(key: String) = item(key = key) { Spacer(Modifier.height(MitraDimens.gap)) }

@Composable
private fun Card(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = MitraDimens.pad).fillMaxWidth()) { content() }

@Composable
private fun RowDivider() = HorizontalDivider(color = Mitra.colors.border, thickness = 1.dp, modifier = Modifier.padding(start = 62.dp))

@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(bottom = 4.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Mitra.type.h3, color = Mitra.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) {
            val base = Modifier.heightIn(min = 48.dp)
            Box(
                if (onAction != null) base.clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onAction).padding(horizontal = 6.dp) else base,
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = Mitra.type.sm.copy(fontWeight = if (onAction != null) FontWeight.SemiBold else null), color = if (onAction != null) Mitra.colors.accent else Mitra.colors.muted)
            }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier = Modifier, valueColor: Color = Mitra.colors.text) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        Text(value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = valueColor, maxLines = 1)
        if (sub.isNotEmpty()) Text(sub, style = Mitra.type.xs, color = Mitra.colors.faint)
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, dot: Color, modifier: Modifier = Modifier) {
    MitraCard(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot)
            Spacer(Modifier.width(8.dp))
            Eyebrow(label)
        }
        Text(value, style = Mitra.type.numLg, color = Mitra.colors.text, maxLines = 1)
        Text(sub, style = Mitra.type.xs, color = Mitra.colors.muted)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mitra.colors.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Mitra.colors.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LinkRow(onClick: () -> Unit, label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
