package tech.beyondscale.mitra.android.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.android.MainActivity
import tech.beyondscale.mitra.android.R
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.designsystem.theme.MitraColors
import tech.beyondscale.mitra.designsystem.theme.NoirDark
import tech.beyondscale.mitra.designsystem.theme.NoirLight
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.TasksWidgetLayout
import tech.beyondscale.mitra.domain.tasks.TasksWidgetMetrics
import tech.beyondscale.mitra.domain.tasks.TasksWidgetModel
import tech.beyondscale.mitra.domain.tasks.WidgetTaskRow
import tech.beyondscale.mitra.domain.tasks.tasksWidgetLayout
import tech.beyondscale.mitra.domain.tasks.tasksWidgetModel
import androidx.glance.color.ColorProvider as DayNightColorProvider

/** "Today" home-screen widget (spec §10): today's open tasks, completed in place, read from the local database. */
class TasksWidget : GlanceAppWidget(), KoinComponent {
    /** Exact: [LocalSize] is the widget's real size, so as many rows as fit are drawn (spec §10). */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = get<TasksRepository>()
        val auth = get<AuthRepository>()
        val clock = get<AppClock>()
        val prefs = get<PreferencesRepository>()
        // update()/updateAll() on a live session does not re-run provideGlance, so the content collects the flows
        // itself; the first() snapshot is only the initial frame.
        val initialModel = tasksWidgetModel(tasks.tasks.first(), clock.today(), auth.hasLocalAccount.first())
        val initialTheme = prefs.theme.first()
        val models = combine(tasks.tasks, auth.hasLocalAccount, clock.dayFlow()) { list, signedIn, today ->
            tasksWidgetModel(list, today, signedIn)
        }
        provideContent {
            val model by models.collectAsState(initialModel)
            val theme by prefs.theme.collectAsState(initialTheme)
            TasksWidgetContent(model, remember(theme) { WidgetPalette.of(theme) })
        }
    }

    companion object {
        /** Narrower than this draws the small (2×2) layout. */
        val MEDIUM_MIN_WIDTH = 250.dp
    }
}

/** Widget colours. Auto follows the launcher's day/night setting directly, so it flips without a widget update. */
internal class WidgetPalette(
    val surface: ColorProvider, val text: ColorProvider, val muted: ColorProvider,
    val accent: ColorProvider, val danger: ColorProvider, val track: ColorProvider,
    val warning: ColorProvider, val onAccent: ColorProvider,
) {
    companion object {
        fun of(mode: ThemeMode): WidgetPalette = when (mode) {
            ThemeMode.Dark -> fixed(NoirDark)
            ThemeMode.Light -> fixed(NoirLight)
            ThemeMode.Auto -> {
                fun dn(pick: (MitraColors) -> androidx.compose.ui.graphics.Color) = DayNightColorProvider(day = pick(NoirLight), night = pick(NoirDark))
                WidgetPalette(
                    dn { it.surface }, dn { it.text }, dn { it.muted }, dn { it.accent }, dn { it.danger }, dn { it.track },
                    dn { it.warning }, dn { it.onAccent },
                )
            }
        }

        private fun fixed(c: MitraColors) = WidgetPalette(
            ColorProvider(c.surface), ColorProvider(c.text), ColorProvider(c.muted),
            ColorProvider(c.accent), ColorProvider(c.danger), ColorProvider(c.track),
            ColorProvider(c.warning), ColorProvider(c.onAccent),
        )
    }
}

/** Opens [uri] (a `mitra://` launch URI) in MainActivity; distinct data URIs keep the PendingIntents distinct. */
internal fun openUri(context: Context, uri: String): Action =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun TasksWidgetContent(model: TasksWidgetModel, p: WidgetPalette) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier.fillMaxSize().background(p.surface).cornerRadius(16.dp).padding(TasksWidgetMetrics.PADDING.dp),
    ) {
        when {
            !model.signedIn -> Centered(context.getString(R.string.tasks_widget_sign_in), p, openApp(context))
            size.width < TasksWidget.MEDIUM_MIN_WIDTH -> Small(model, p)
            else -> Full(model, p, tasksWidgetLayout(size.height.value, model.rows.size))
        }
    }
}

internal fun openApp(context: Context): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
internal fun Centered(text: String, p: WidgetPalette, onClick: Action) {
    Box(modifier = GlanceModifier.fillMaxSize().clickable(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AddButton(p: WidgetPalette) {
    val context = LocalContext.current
    val label = context.getString(R.string.tasks_widget_add)
    Box(
        modifier = GlanceModifier.size(32.dp).cornerRadius(16.dp).clickable(openUri(context, "mitra://quick-add/task"))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("＋", style = TextStyle(color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Small(model: TasksWidgetModel, p: WidgetPalette) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.tasks_widget_left, model.left),
                modifier = GlanceModifier.defaultWeight().clickable(openUri(context, "mitra://tasks")),
                style = TextStyle(color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            AddButton(p)
        }
        Spacer(GlanceModifier.height(8.dp))
        val next = model.rows.firstOrNull()
        if (next == null) {
            Text(context.getString(R.string.tasks_widget_all_clear), style = TextStyle(color = p.muted, fontSize = 13.sp))
        } else {
            Text(
                next.title,
                maxLines = 2,
                modifier = GlanceModifier.clickable(openUri(context, "mitra://task/${next.id}")),
                style = TextStyle(color = if (next.overdue) p.danger else p.muted, fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun Full(model: TasksWidgetModel, p: WidgetPalette, layout: TasksWidgetLayout) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(TasksWidgetMetrics.HEADER.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.tasks_widget_header, model.done, model.total),
                modifier = GlanceModifier.defaultWeight().clickable(openUri(context, "mitra://tasks")),
                style = TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            AddButton(p)
        }
        Spacer(GlanceModifier.height(TasksWidgetMetrics.BAR_GAP_TOP.dp))
        LinearProgressIndicator(
            progress = if (model.total == 0) 0f else model.done.toFloat() / model.total,
            modifier = GlanceModifier.fillMaxWidth().height(TasksWidgetMetrics.BAR.dp),
            color = p.accent,
            backgroundColor = p.track,
        )
        Spacer(GlanceModifier.height(TasksWidgetMetrics.BAR_GAP_BOTTOM.dp))
        if (model.rows.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                Text(context.getString(R.string.tasks_widget_all_clear), style = TextStyle(color = p.muted, fontSize = 14.sp))
            }
            return@Column
        }
        model.rows.take(layout.rows).forEach { TaskRow(it, p) }
        if (layout.more > 0) {
            Box(
                modifier = GlanceModifier.fillMaxWidth().height(TasksWidgetMetrics.FOOTER.dp).clickable(openUri(context, "mitra://tasks")),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    context.getString(R.string.tasks_widget_more, layout.more),
                    maxLines = 1,
                    style = TextStyle(color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun TaskRow(row: WidgetTaskRow, p: WidgetPalette) {
    val context = LocalContext.current
    val complete = context.getString(R.string.tasks_widget_complete, row.title)
    val open = context.getString(if (row.overdue) R.string.tasks_widget_open_overdue else R.string.tasks_widget_open, row.title)
    Row(modifier = GlanceModifier.fillMaxWidth().height(TasksWidgetMetrics.ROW.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<ToggleTaskAction>(actionParametersOf(ToggleTaskAction.TaskId to row.id)),
            colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.muted),
            modifier = GlanceModifier.semantics { contentDescription = complete },
        )
        if (row.overdue) {
            Box(modifier = GlanceModifier.size(6.dp).cornerRadius(3.dp).background(p.danger)) {}
            Spacer(GlanceModifier.width(6.dp))
        }
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(openUri(context, "mitra://task/${row.id}"))
                .semantics { contentDescription = open },
        ) {
            Text(row.title, maxLines = 1, style = TextStyle(color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            row.meta?.let { Text(it, maxLines = 1, style = TextStyle(color = if (row.overdue) p.danger else p.muted, fontSize = 11.sp)) }
        }
    }
}
