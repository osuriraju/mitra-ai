package tech.beyondscale.mitra.android.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.android.MainActivity
import tech.beyondscale.mitra.android.R
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.designsystem.theme.MitraColors
import tech.beyondscale.mitra.designsystem.theme.NoirDark
import tech.beyondscale.mitra.designsystem.theme.NoirLight
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.TasksWidgetModel
import tech.beyondscale.mitra.domain.tasks.WidgetTaskRow
import tech.beyondscale.mitra.domain.tasks.tasksWidgetModel
import androidx.glance.color.ColorProvider as DayNightColorProvider

/** "Today" home-screen widget (spec §10): today's open tasks, completed in place, read from the local database. */
class TasksWidget : GlanceAppWidget(), KoinComponent {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = tasksWidgetModel(get<TasksRepository>().tasks.first(), get<AppClock>().today(), get<AuthRepository>().hasLocalAccount.first())
        val palette = WidgetPalette.of(get<PreferencesRepository>().theme.first())
        provideContent { TasksWidgetContent(model, palette) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

/** Task rows that fit under the header (64 dp) at 36 dp each; always at least one. */
internal fun widgetRowsThatFit(heightDp: Float): Int = (((heightDp - 64f) / 36f).toInt()).coerceAtLeast(1)

/** Widget colours. Auto follows the launcher's day/night setting directly, so it flips without a widget update. */
internal class WidgetPalette(
    val surface: ColorProvider, val text: ColorProvider, val muted: ColorProvider,
    val accent: ColorProvider, val danger: ColorProvider, val track: ColorProvider,
) {
    companion object {
        fun of(mode: ThemeMode): WidgetPalette = when (mode) {
            ThemeMode.Dark -> fixed(NoirDark)
            ThemeMode.Light -> fixed(NoirLight)
            ThemeMode.Auto -> {
                fun dn(pick: (MitraColors) -> androidx.compose.ui.graphics.Color) = DayNightColorProvider(day = pick(NoirLight), night = pick(NoirDark))
                WidgetPalette(dn { it.surface }, dn { it.text }, dn { it.muted }, dn { it.accent }, dn { it.danger }, dn { it.track })
            }
        }

        private fun fixed(c: MitraColors) = WidgetPalette(
            ColorProvider(c.surface), ColorProvider(c.text), ColorProvider(c.muted),
            ColorProvider(c.accent), ColorProvider(c.danger), ColorProvider(c.track),
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
        modifier = GlanceModifier.fillMaxSize().background(p.surface).cornerRadius(16.dp).padding(12.dp),
    ) {
        when {
            !model.signedIn -> Centered(context.getString(R.string.tasks_widget_sign_in), p, openApp(context))
            size.width < TasksWidget.MEDIUM.width -> Small(model, p)
            else -> Full(model, p, widgetRowsThatFit(size.height.value))
        }
    }
}

private fun openApp(context: Context): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun Centered(text: String, p: WidgetPalette, onClick: Action) {
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
private fun Full(model: TasksWidgetModel, p: WidgetPalette, fit: Int) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.tasks_widget_header, model.done, model.total),
                modifier = GlanceModifier.defaultWeight().clickable(openUri(context, "mitra://tasks")),
                style = TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            AddButton(p)
        }
        Spacer(GlanceModifier.height(4.dp))
        LinearProgressIndicator(
            progress = if (model.total == 0) 0f else model.done.toFloat() / model.total,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp),
            color = p.accent,
            backgroundColor = p.track,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (model.rows.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                Text(context.getString(R.string.tasks_widget_all_clear), style = TextStyle(color = p.muted, fontSize = 14.sp))
            }
            return@Column
        }
        model.rows.take(fit).forEach { TaskRow(it, p) }
        val more = model.rows.size - fit
        if (more > 0) {
            Text(
                context.getString(R.string.tasks_widget_more, more),
                modifier = GlanceModifier.padding(top = 2.dp).clickable(openUri(context, "mitra://tasks")),
                style = TextStyle(color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun TaskRow(row: WidgetTaskRow, p: WidgetPalette) {
    val context = LocalContext.current
    val complete = context.getString(R.string.tasks_widget_complete, row.title)
    val open = context.getString(R.string.tasks_widget_open, row.title)
    Row(modifier = GlanceModifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
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
