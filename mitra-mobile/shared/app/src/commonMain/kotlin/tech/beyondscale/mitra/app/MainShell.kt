package tech.beyondscale.mitra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.LocalSnackbar
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SnackbarController
import tech.beyondscale.mitra.designsystem.components.SyncPill
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.feature.common.ComingSoonScreen
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.money.BudgetsRoute
import tech.beyondscale.mitra.feature.money.CategoriesRoute
import tech.beyondscale.mitra.feature.money.MoneyLink
import tech.beyondscale.mitra.feature.money.MoneyRoute
import tech.beyondscale.mitra.feature.money.RecurringRoute
import tech.beyondscale.mitra.feature.money.SubscriptionsRoute
import tech.beyondscale.mitra.feature.money.TransactionsRoute
import tech.beyondscale.mitra.feature.money.TxEditorViewModel
import tech.beyondscale.mitra.feature.money.TxFormSheet
import tech.beyondscale.mitra.feature.quickadd.QuickAddAction
import tech.beyondscale.mitra.feature.quickadd.QuickAddSheet
import tech.beyondscale.mitra.feature.quickadd.QuickAddViewModel
import tech.beyondscale.mitra.feature.quickadd.QuickType
import tech.beyondscale.mitra.feature.settings.SettingsRoute
import tech.beyondscale.mitra.feature.tasks.FocusRoute
import tech.beyondscale.mitra.feature.tasks.ProjectDetailRoute
import tech.beyondscale.mitra.feature.tasks.TaskDetailRoute
import tech.beyondscale.mitra.feature.tasks.TaskEditorAction
import tech.beyondscale.mitra.feature.tasks.TaskEditorViewModel
import tech.beyondscale.mitra.feature.tasks.TaskFormSheet
import tech.beyondscale.mitra.feature.tasks.TasksRoute
import tech.beyondscale.mitra.feature.today.TodayRoute

internal data class Dest(val label: String, val icon: ImageVector, val route: Any)

private val TODAY = Dest("Today", MitraIcons.Sun, TodayDest)
private val TASKS = Dest("Tasks", MitraIcons.CheckSquare, TasksDest)
private val MONEY = Dest("Money", MitraIcons.Wallet, MoneyDest)
private val HABITS = Dest("Habits", MitraIcons.Repeat, HabitsDest)
private val GOALS = Dest("Goals", MitraIcons.Target, GoalsDest)
private val WELLNESS = Dest("Wellness", MitraIcons.Heart, WellnessDest)
private val NOTES = Dest("Notes", MitraIcons.Note, NotesDest)
private val AI = Dest("AI", MitraIcons.Sparkles, AiDest)
private val SETTINGS = Dest("Settings", MitraIcons.Settings, SettingsDest)
private val MORE = Dest("More", MitraIcons.Grid, Unit)

/** Bottom-bar tabs and More-sheet entries allowed for the enabled [modules]. */
internal fun destinationsFor(modules: Modules): Pair<List<Dest>, List<Dest>> {
    val tabs = listOf(TODAY, TASKS, if (modules.money) MONEY else HABITS)
    val more = buildList {
        if (modules.money) add(HABITS)
        add(GOALS)
        if (modules.wellness) add(WELLNESS)
        add(NOTES)
        if (modules.ai) add(AI)
        add(SETTINGS)
    }
    return tabs to more
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(user: User, sync: SyncStatus, messages: Flow<String>) {
    val nav = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    // The shell's scope hosts every snackbar, so an Undo outlives the screen (or sheet) that raised it.
    val shellScope = rememberCoroutineScope()
    val snackbar = remember(snackbarHost, shellScope) { SnackbarController(snackbarHost, shellScope) }
    // Shell-scoped sheets: obtained outside any nav entry so their Undo snackbars survive navigation.
    // Keyed per account: a sheet left open at sign-out must not carry its draft (or edit target) into the next account.
    val quickAdd: QuickAddViewModel = koinViewModel(key = shellVmKey("quickAdd", user.id))
    val editor: TaskEditorViewModel = koinViewModel(key = shellVmKey("taskEditor", user.id))
    val txEditor: TxEditorViewModel = koinViewModel(key = shellVmKey("tx-editor", user.id))
    val launches: LaunchRequests = koinInject()
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var quickAddOpen by rememberSaveable { mutableStateOf(false) }
    var txFormOpen by rememberSaveable { mutableStateOf(false) }
    // Restored open (after process death, or for a new account's editor) with nothing started: don't show an empty sheet.
    LaunchedEffect(txEditor) { if (txFormOpen && !txEditor.state.value.ready) txFormOpen = false }
    // The one collector of sync messages; showSnackbar queues behind (rather than dismissing) an Undo snackbar.
    LaunchedEffect(messages) { messages.collect { snackbarHost.showSnackbar(it) } }

    // Bottom bar: Today · Tasks · ＋ · Money (Habits if Money is off) · More
    val modules = user.settings.modules
    val (tabs, more) = remember(modules) { destinationsFor(modules) }
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val chrome = chromeForDestination { k -> destination?.hasRoute(k) == true }
    val moneyDetail = MONEY_DETAIL_ROUTES.any { destination?.hasRoute(it) == true }
    // Money's sub-screens live under Money (none when the module is off); task and project detail and Focus under Tasks.
    val current = when {
        moneyDetail -> MONEY.takeIf { it in tabs }
        isDetail(chrome) -> TASKS
        else -> (tabs + more).firstOrNull { d -> destination?.hierarchy?.any { it.hasRoute(d.route::class) } == true }
    }

    fun go(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun openTask(id: String) = nav.navigate(TaskDetailDest(id))
    fun openTxForm(editId: String?) {
        txEditor.start(editId)
        txFormOpen = true
    }
    fun openMoney(link: MoneyLink) = when (link) {
        MoneyLink.AddExpense -> openTxForm(null)
        MoneyLink.Transactions -> nav.navigate(TransactionsDest())
        MoneyLink.Budgets -> nav.navigate(BudgetsDest)
        MoneyLink.Categories -> nav.navigate(CategoriesDest)
        MoneyLink.Recurring -> nav.navigate(RecurringDest)
        MoneyLink.Subscriptions -> nav.navigate(SubscriptionsDest)
    }
    /** Money's root with its saved stack cleared (launch targets), or Today when the module is off. */
    fun goMoneyRoot(): Boolean {
        if (!modules.money) {
            go(TodayDest)
            return false
        }
        go(MoneyDest)
        nav.popBackStack<MoneyDest>(inclusive = false)
        return true
    }
    // Never pop the last entry (a double tap on Back would otherwise leave an empty NavHost).
    fun back() { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    fun select(d: Dest) {
        // From a detail screen, a tab already beneath it is popped back to, so the detail isn't saved into that tab's stack.
        if (isDetail(chrome) && nav.popBackStack(d.route, inclusive = false)) return
        go(d.route)
    }

    // A module switched off while its screen is open would strand the user: fall back to Today.
    LaunchedEffect(modules, current, entry, chrome) {
        if ((!isDetail(chrome) || moneyDetail) && entry != null && current == null && entry?.destination?.route != null) go(TodayDest)
    }

    // Widget / shortcut targets wait in LaunchRequests (also while signed out) until this shell handles them.
    val pending by launches.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first() // the graph is set
        when (target) {
            is LaunchTarget.OpenTask -> {
                go(TasksDest)
                // go() may restore a detail saved in the Tasks stack: return to the list so details don't pile up.
                nav.popBackStack<TasksDest>(inclusive = false)
                openTask(target.id)
            }
            LaunchTarget.OpenTasks -> go(TasksDest)
            LaunchTarget.QuickAddTask -> quickAddOpen = true
            LaunchTarget.OpenMoney -> goMoneyRoot()
            LaunchTarget.OpenBudgets -> if (goMoneyRoot()) nav.navigate(BudgetsDest)
            LaunchTarget.QuickAddExpense -> if (modules.money) {
                quickAddOpen = true
                quickAdd.onAction(QuickAddAction.Type(QuickType.Expense))
            } else {
                go(TodayDest)
            }
        }
        launches.consume()
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        EventEffect(quickAdd.events, quickAdd::onAction, onClose = { quickAddOpen = false })
        EventEffect(editor.events, editor::onAction)
        val addTask: (TaskDraft) -> Unit = { editor.onAction(TaskEditorAction.Open(it)) }

        val shellActions: @Composable RowScope.() -> Unit = {
            syncLabel(sync)?.let { (text, tone) -> SyncPill(text, tone) }
            IconButton(onClick = { go(SettingsDest) }) { Avatar(user.initials) }
        }

        Scaffold(
            containerColor = Mitra.colors.bg,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                when (chrome) {
                    Chrome.Tab -> MitraTopBar(title = current?.label ?: "Mitra", modifier = Modifier.statusBarsPadding(), actions = shellActions)
                    // These screens draw their own top bar; keep them clear of the status bar.
                    Chrome.TabOwnBar, Chrome.Detail -> Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                    Chrome.Fullscreen -> Unit
                }
            },
            bottomBar = {
                if (chrome != Chrome.Fullscreen) {
                    BottomBar(
                        tabs = tabs, current = current, moreSelected = current != null && current in more,
                        onSelect = ::select, onMore = { moreOpen = true },
                        onAdd = { quickAddOpen = true },
                    )
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = TodayDest, modifier = Modifier.padding(padding)) {
                composable<TodayDest> {
                    TodayRoute(
                        onOpenTasks = { go(TasksDest) },
                        onOpenTask = ::openTask,
                        onOpenMoney = { if (modules.money) go(MoneyDest) },
                        onAddExpense = { openTxForm(null) },
                        // Via Money's root, so Budgets sits in the Money stack its highlighted tab implies.
                        onOpenBudgets = { if (goMoneyRoot()) nav.navigate(BudgetsDest) },
                    )
                }
                composable<TasksDest> {
                    TasksRoute(
                        onOpenTask = ::openTask,
                        onOpenProject = { nav.navigate(ProjectDetailDest(it)) },
                        onAddTask = addTask,
                        onFocus = { nav.navigate(FocusDest()) },
                        topBarActions = shellActions,
                    )
                }
                composable<TaskDetailDest> { e ->
                    val id = e.toRoute<TaskDetailDest>().id
                    TaskDetailRoute(
                        id = id,
                        onBack = ::back,
                        onEdit = { editor.onAction(TaskEditorAction.Edit(id)) },
                        onAddTask = addTask,
                        onOpenTask = ::openTask,
                        onFocus = { nav.navigate(FocusDest(id)) },
                        onDeleted = { editor.onAction(TaskEditorAction.Deleted(it)) },
                    )
                }
                composable<ProjectDetailDest> { e ->
                    ProjectDetailRoute(e.toRoute<ProjectDetailDest>().id, onBack = ::back, onOpenTask = ::openTask, onAddTask = addTask)
                }
                composable<FocusDest> { e ->
                    FocusRoute(e.toRoute<FocusDest>().taskId, onClose = ::back, onOpenTask = ::openTask)
                }
                composable<MoneyDest> {
                    MoneyRoute(onEditTransaction = ::openTxForm, onOpen = { openMoney(it) }, topBarActions = shellActions)
                }
                composable<TransactionsDest> { e ->
                    TransactionsRoute(
                        initialCategoryId = e.toRoute<TransactionsDest>().categoryId.ifEmpty { null },
                        onBack = ::back,
                        onEdit = ::openTxForm,
                        onAdd = { openTxForm(null) },
                    )
                }
                composable<BudgetsDest> { BudgetsRoute(onBack = ::back) }
                composable<CategoriesDest> { CategoriesRoute(onBack = ::back, onOpenTransactions = { nav.navigate(TransactionsDest(it)) }) }
                composable<RecurringDest> { RecurringRoute(onBack = ::back) }
                composable<SubscriptionsDest> { SubscriptionsRoute(onBack = ::back) }
                composable<HabitsDest> { ComingSoonScreen("Habits", MitraIcons.Repeat) }
                composable<GoalsDest> { ComingSoonScreen("Goals", MitraIcons.Target) }
                composable<WellnessDest> { ComingSoonScreen("Wellness", MitraIcons.Heart) }
                composable<NotesDest> { ComingSoonScreen("Notes", MitraIcons.Note) }
                composable<AiDest> { ComingSoonScreen("AI", MitraIcons.Sparkles) }
                composable<SettingsDest> { SettingsRoute() }
            }
        }

        if (quickAddOpen) {
            val state by quickAdd.state.collectAsStateWithLifecycle()
            QuickAddSheet(state, quickAdd::onAction, onDismiss = {
                quickAddOpen = false
                quickAdd.onAction(QuickAddAction.Reset)
            })
        }

        val editorState by editor.state.collectAsStateWithLifecycle()
        TaskFormSheet(editorState, editor::onAction)

        // Its snackbars (Undo included) go through the shell's SnackbarController, so Undo outlives the sheet.
        if (txFormOpen) {
            TxFormSheet(txEditor, onDismiss = { txFormOpen = false }, onManageCategories = { nav.navigate(CategoriesDest) })
        }

        if (moreOpen) {
            ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = Mitra.colors.surface) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("All sections", style = Mitra.type.h3, color = Mitra.colors.text)
                    more.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { d -> MoreTile(d, selected = d == current) { moreOpen = false; go(d.route) } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Routes stacked over a tab (detail screens and Focus). */
private fun isDetail(chrome: Chrome) = chrome == Chrome.Detail || chrome == Chrome.Fullscreen

@Composable
private fun RowScope.MoreTile(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val c = Mitra.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.surface2)
            .selectable(selected = selected, role = Role.Button, onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(d.icon, contentDescription = null, tint = if (selected) c.accent else c.text)
        Text(d.label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = if (selected) c.accent else c.text)
    }
}

@Composable
private fun BottomBar(tabs: List<Dest>, current: Dest?, moreSelected: Boolean, onSelect: (Dest) -> Unit, onAdd: () -> Unit, onMore: () -> Unit) {
    val c = Mitra.colors
    Column(Modifier.fillMaxWidth().background(c.navBg)) {
        HorizontalDivider(color = c.border)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            TabItem(tabs[0], current == tabs[0]) { onSelect(tabs[0]) }
            TabItem(tabs[1], current == tabs[1]) { onSelect(tabs[1]) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.offset(y = (-10).dp).size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
                        .clickable(role = Role.Button, onClickLabel = "Quick add", onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(MitraIcons.Plus, contentDescription = "Quick add", tint = c.onAccent, modifier = Modifier.size(26.dp)) }
            }
            TabItem(tabs[2], current == tabs[2]) { onSelect(tabs[2]) }
            TabItem(MORE, moreSelected, onMore)
        }
    }
}

@Composable
private fun RowScope.TabItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Mitra.colors.accent else Mitra.colors.muted
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(24.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Mitra.colors.accent else Color.Transparent))
        Spacer(Modifier.height(6.dp))
        Icon(d.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(d.label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp), color = color)
    }
}
