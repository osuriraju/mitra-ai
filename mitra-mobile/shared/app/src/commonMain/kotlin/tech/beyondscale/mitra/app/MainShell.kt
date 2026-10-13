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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.Avatar
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SyncPill
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.feature.common.ComingSoonScreen
import tech.beyondscale.mitra.feature.settings.SettingsRoute
import tech.beyondscale.mitra.feature.today.TodayRoute

private data class Dest(val label: String, val icon: ImageVector, val route: Any)

private val TODAY = Dest("Today", MitraIcons.Sun, TodayDest)
private val TASKS = Dest("Tasks", MitraIcons.CheckSquare, TasksDest)
private val MONEY = Dest("Money", MitraIcons.Wallet, MoneyDest)
private val HABITS = Dest("Habits", MitraIcons.Repeat, HabitsDest)
private val GOALS = Dest("Goals", MitraIcons.Target, GoalsDest)
private val WELLNESS = Dest("Wellness", MitraIcons.Heart, WellnessDest)
private val NOTES = Dest("Notes", MitraIcons.Note, NotesDest)
private val AI = Dest("AI", MitraIcons.Sparkles, AiDest)
private val SETTINGS = Dest("Settings", MitraIcons.Settings, SettingsDest)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(user: User, sync: SyncStatus, messages: Flow<String>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }

    // Bottom bar: Today · Tasks · ＋ · Money (Habits if Money is off) · More
    val modules = user.settings.modules
    val tabs = listOf(TODAY, TASKS, if (modules.money) MONEY else HABITS)
    val more = buildList {
        if (modules.money) add(HABITS)
        add(GOALS)
        if (modules.wellness) add(WELLNESS)
        add(NOTES)
        if (modules.ai) add(AI)
        add(SETTINGS)
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = (tabs + more).firstOrNull { d -> entry?.destination?.hierarchy?.any { it.hasRoute(d.route::class) } == true }

    fun go(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        containerColor = Mitra.colors.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MitraTopBar(title = current?.label ?: "Mitra", modifier = Modifier.statusBarsPadding()) {
                syncLabel(sync)?.let { (text, tone) -> SyncPill(text, tone) }
                IconButton(onClick = { go(SettingsDest) }) { Avatar(user.initials) }
            }
        },
        bottomBar = {
            BottomBar(
                tabs = tabs, current = current, moreSelected = current != null && current in more,
                onSelect = { go(it.route) }, onMore = { moreOpen = true },
                onAdd = { scope.launch { snackbar.showSnackbar("Quick add arrives with Tasks") } },
            )
        },
    ) { padding ->
        NavHost(nav, startDestination = TodayDest, modifier = Modifier.padding(padding)) {
            composable<TodayDest> { TodayRoute() }
            composable<TasksDest> { ComingSoonScreen("Tasks", MitraIcons.CheckSquare) }
            composable<MoneyDest> { ComingSoonScreen("Money", MitraIcons.Wallet) }
            composable<HabitsDest> { ComingSoonScreen("Habits", MitraIcons.Repeat) }
            composable<GoalsDest> { ComingSoonScreen("Goals", MitraIcons.Target) }
            composable<WellnessDest> { ComingSoonScreen("Wellness", MitraIcons.Heart) }
            composable<NotesDest> { ComingSoonScreen("Notes", MitraIcons.Note) }
            composable<AiDest> { ComingSoonScreen("AI", MitraIcons.Sparkles) }
            composable<SettingsDest> { SettingsRoute() }
        }
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

@Composable
private fun RowScope.MoreTile(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val c = Mitra.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.surface2)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp),
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
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
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
            TabItem(Dest("More", MitraIcons.Grid, Unit), moreSelected, onMore)
        }
    }
}

@Composable
private fun RowScope.TabItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Mitra.colors.accent else Mitra.colors.muted
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(24.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Mitra.colors.accent else Color.Transparent))
        Spacer(Modifier.height(6.dp))
        Icon(d.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(d.label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp), color = color)
    }
}
